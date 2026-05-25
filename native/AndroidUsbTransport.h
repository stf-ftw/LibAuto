#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <f1x/aasdk/Transport/Transport.hpp>

#include "UsbTransport.h"

class AndroidUsbTransport : public f1x::aasdk::transport::Transport {
public:
    explicit AndroidUsbTransport(boost::asio::io_service& ioService);
    ~AndroidUsbTransport() override;
    void stop() override;

private:
    void enqueueReceive(f1x::aasdk::common::DataBuffer buffer) override;
    void enqueueSend(SendQueue::iterator queueElement) override;
    void doSend(SendQueue::iterator queueElement, f1x::aasdk::common::Data::size_type offset);
    void readLoop();
    void sendLoop();

    static std::string hexDump(const uint8_t* data, size_t length);

    UsbTransport usb_;
    std::atomic<bool> running_{true};
    std::atomic<int> readTimeoutCount_{0};
    std::atomic<int> writeTimeoutCount_{0};
    std::thread readThread_;
    std::thread sendThread_;
    std::mutex readMutex_;
    std::condition_variable readCv_;
    std::optional<f1x::aasdk::common::DataBuffer> pendingReceiveBuffer_;
    std::mutex sendMutex_;
    std::condition_variable sendCv_;
    bool sendPending_ = false;
    SendQueue::iterator pendingSend_;
    f1x::aasdk::common::Data::size_type pendingSendOffset_ = 0;

    static constexpr int kReadTimeoutMs = 200;
    static constexpr int kWriteTimeoutMs = 250;
    static constexpr int kIdleDisconnectMs = 1500;
};
