#include "AndroidUsbTransport.h"

#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <cerrno>
#include <cstring>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <thread>
#include <unistd.h>

#include <f1x/aasdk/Error/Error.hpp>

#include "NativeLog.h"

namespace {
constexpr const char* kLogTag = "AndroidUsbTransport";

void boostCurrentThreadPriority(const char* label) {
    const auto tid = static_cast<int>(syscall(__NR_gettid));
    errno = 0;
    if (setpriority(PRIO_PROCESS, tid, -16) != 0) {
        native_log::Logf(kLogTag, "W",
                         "%s priority boost failed tid=%d errno=%d %s",
                         label, tid, errno, strerror(errno));
        return;
    }
    native_log::Logf(kLogTag, "I", "%s priority boosted tid=%d nice=-16", label, tid);
}
}

AndroidUsbTransport::AndroidUsbTransport(boost::asio::io_service& ioService)
    : f1x::aasdk::transport::Transport(ioService) {
    native_log::Log(kLogTag, "I", "AndroidUsbTransport created");
}

AndroidUsbTransport::~AndroidUsbTransport() {
    stop();
}

void AndroidUsbTransport::stop() {
    const bool wasRunning = running_.exchange(false);
    if (!wasRunning) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(readMutex_);
        pendingReceiveBuffer_.reset();
    }
    {
        std::lock_guard<std::mutex> lock(sendMutex_);
        sendPending_ = false;
    }
    readCv_.notify_all();
    sendCv_.notify_all();
    if (readThread_.joinable()) {
        readThread_.join();
    }
    if (sendThread_.joinable()) {
        sendThread_.join();
    }
}

void AndroidUsbTransport::enqueueReceive(f1x::aasdk::common::DataBuffer buffer) {
    {
        std::lock_guard<std::mutex> lock(readMutex_);
        pendingReceiveBuffer_ = std::move(buffer);
        if (!readThread_.joinable()) {
            readThread_ = std::thread(&AndroidUsbTransport::readLoop, this);
        }
    }
    readCv_.notify_one();
}

void AndroidUsbTransport::enqueueSend(SendQueue::iterator queueElement) {
    doSend(queueElement, 0);
}

void AndroidUsbTransport::doSend(SendQueue::iterator queueElement,
                                 f1x::aasdk::common::Data::size_type offset) {
    {
        std::lock_guard<std::mutex> lock(sendMutex_);
        pendingSend_ = queueElement;
        pendingSendOffset_ = offset;
        sendPending_ = true;
        if (!sendThread_.joinable()) {
            sendThread_ = std::thread(&AndroidUsbTransport::sendLoop, this);
        }
    }
    sendCv_.notify_one();
}

void AndroidUsbTransport::readLoop() {
    boostCurrentThreadPriority("USB read");
    while (running_.load()) {
        std::optional<f1x::aasdk::common::DataBuffer> buffer;
        {
            std::unique_lock<std::mutex> lock(readMutex_);
            readCv_.wait(lock, [this]() {
                return !running_.load() || pendingReceiveBuffer_.has_value();
            });
            if (!running_.load()) {
                return;
            }
            buffer = std::move(pendingReceiveBuffer_);
            pendingReceiveBuffer_.reset();
        }

        int timeoutCount = 0;
        int backoffMs = 50;
        std::chrono::steady_clock::time_point firstTimeoutAt;
        while (running_.load()) {
            const int result = usb_.Read(reinterpret_cast<uint8_t*>(buffer->data),
                                         static_cast<int>(buffer->size),
                                         kReadTimeoutMs);
            if (!running_.load()) {
                return;
            }
            if (result > 0) {
                readTimeoutCount_.store(0);
                firstTimeoutAt = std::chrono::steady_clock::time_point{};
                boost::asio::post(receiveStrand_, [this, self = shared_from_this(), result]() {
                    receiveHandler(static_cast<size_t>(result));
                });
                break;
            }
            if (result == -1 || result == 0) {
                timeoutCount++;
                readTimeoutCount_.store(timeoutCount);
                const auto now = std::chrono::steady_clock::now();
                if (firstTimeoutAt == std::chrono::steady_clock::time_point{}) {
                    firstTimeoutAt = now;
                }
                const auto idleMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                    now - firstTimeoutAt).count();
                if (idleMs >= kIdleDisconnectMs) {
                    native_log::Logf(kLogTag, "W",
                                     "AA USB idle for %lldms; ending stalled AA transport",
                                     static_cast<long long>(idleMs));
                    usb_.NotifyTransportStalled();
                    boost::asio::post(receiveStrand_, [this, self = shared_from_this(), result]() {
                        rejectReceivePromises(f1x::aasdk::error::Error(
                            f1x::aasdk::error::ErrorCode::USB_TRANSFER,
                            static_cast<uint32_t>(result)));
                    });
                    return;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(backoffMs));
                backoffMs = std::min(backoffMs * 2, 500);
                continue;
            }
            native_log::Logf(kLogTag, "E", "AA USB read error code=%d", result);
            boost::asio::post(receiveStrand_, [this, self = shared_from_this(), result]() {
                rejectReceivePromises(f1x::aasdk::error::Error(
                    f1x::aasdk::error::ErrorCode::USB_TRANSFER,
                    static_cast<uint32_t>(result)));
            });
            return;
        }
    }
}

void AndroidUsbTransport::sendLoop() {
    boostCurrentThreadPriority("USB send");
    while (running_.load()) {
        SendQueue::iterator queueElement;
        f1x::aasdk::common::Data::size_type currentOffset = 0;
        {
            std::unique_lock<std::mutex> lock(sendMutex_);
            sendCv_.wait(lock, [this]() {
                return !running_.load() || sendPending_;
            });
            if (!running_.load()) {
                return;
            }
            queueElement = pendingSend_;
            currentOffset = pendingSendOffset_;
            sendPending_ = false;
        }

        int consecutiveTimeouts = 0;
        while (running_.load()) {
            const auto& data = queueElement->first;
            if (currentOffset >= data.size()) {
                boost::asio::post(sendStrand_, [this, self = shared_from_this(), queueElement]() {
                    queueElement->second->resolve();
                    sendQueue_.erase(queueElement);
                    if (!sendQueue_.empty()) {
                        enqueueSend(sendQueue_.begin());
                    }
                });
                break;
            }

            const auto* ptr = reinterpret_cast<const uint8_t*>(&data[currentOffset]);
            const auto remaining = data.size() - currentOffset;
            const int result = usb_.Write(ptr, static_cast<int>(remaining), kWriteTimeoutMs);
            if (!running_.load()) {
                return;
            }

            if (result > 0) {
                writeTimeoutCount_.store(0);
                consecutiveTimeouts = 0;
                if (currentOffset == 0 && remaining == data.size() && data.size() <= 16) {
                    native_log::Logf(kLogTag, "I",
                                     "AA USB OUT first chunk %d bytes %s",
                                     result,
                                     hexDump(ptr, static_cast<size_t>(result)).c_str());
                }
                currentOffset += static_cast<size_t>(result);
                continue;
            }

            if (result == -1 || result == 0) {
                const int count = writeTimeoutCount_.fetch_add(1) + 1;
                consecutiveTimeouts++;
                if (count == 1 || count % 20 == 0) {
                    native_log::Logf(kLogTag, "W",
                                     "AA USB write timeout code=%d timeoutMs=%d count=%d",
                                     result, kWriteTimeoutMs, count);
                }
                if (consecutiveTimeouts >= 2) {
                    native_log::Logf(kLogTag, "W",
                                     "AA USB dropping stalled OUT message after %d consecutive timeouts",
                                     consecutiveTimeouts);
                    boost::asio::post(sendStrand_, [this, self = shared_from_this(), queueElement]() {
                        queueElement->second->resolve();
                        sendQueue_.erase(queueElement);
                        if (!sendQueue_.empty()) {
                            enqueueSend(sendQueue_.begin());
                        }
                    });
                    break;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(200));
                continue;
            }

            native_log::Logf(kLogTag, "E", "AA USB write error code=%d", result);
            boost::asio::post(sendStrand_, [this, self = shared_from_this(), queueElement, result]() {
                queueElement->second->reject(f1x::aasdk::error::Error(
                    f1x::aasdk::error::ErrorCode::USB_TRANSFER,
                    static_cast<uint32_t>(result)));
                sendQueue_.erase(queueElement);
                if (!sendQueue_.empty()) {
                    enqueueSend(sendQueue_.begin());
                }
            });
            return;
        }
    }
}

std::string AndroidUsbTransport::hexDump(const uint8_t* data, size_t length) {
    const size_t maxLen = std::min<size_t>(length, 64);
    std::string out;
    out.reserve(maxLen * 3 + 4);
    out.push_back('[');
    for (size_t i = 0; i < maxLen; ++i) {
        char buf[4];
        snprintf(buf, sizeof(buf), "%02X", data[i]);
        if (i > 0) {
            out.push_back(' ');
        }
        out.append(buf);
    }
    if (length > maxLen) {
        out.append(" ...");
    }
    out.push_back(']');
    return out;
}
