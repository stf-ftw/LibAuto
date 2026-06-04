#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <csignal>
#include <cstring>
#include <dlfcn.h>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <time.h>
#include <unwind.h>
#include <unistd.h>
#include <vector>

#include <boost/asio.hpp>

#include <aasdk_proto/AuthCompleteIndicationMessage.pb.h>
#include <aasdk_proto/AudioFocusResponseMessage.pb.h>
#include <aasdk_proto/AVChannelData.pb.h>
#include <aasdk_proto/AVInputChannelData.pb.h>
#include <aasdk_proto/AVInputOpenResponseMessage.pb.h>
#include <aasdk_proto/AVChannelSetupResponseMessage.pb.h>
#include <aasdk_proto/AVChannelSetupStatusEnum.pb.h>
#include <aasdk_proto/AVMediaAckIndicationMessage.pb.h>
#include <aasdk_proto/AVStreamTypeEnum.pb.h>
#include <aasdk_proto/BindingResponseMessage.pb.h>
#include <aasdk_proto/BluetoothChannelData.pb.h>
#include <aasdk_proto/BluetoothPairingMethodEnum.pb.h>
#include <aasdk_proto/BluetoothPairingResponseMessage.pb.h>
#include <aasdk_proto/BluetoothPairingStatusEnum.pb.h>
#include <aasdk_proto/ButtonCodeEnum.pb.h>
#include <aasdk_proto/DrivingStatusEnum.pb.h>
#include <aasdk_proto/GearEnum.pb.h>
#include <aasdk_proto/ChannelDescriptorData.pb.h>
#include <aasdk_proto/ChannelOpenResponseMessage.pb.h>
#include <aasdk_proto/InputChannelData.pb.h>
#include <aasdk_proto/InputEventIndicationMessage.pb.h>
#include <aasdk_proto/SensorChannelData.pb.h>
#include <aasdk_proto/SensorEventIndicationMessage.pb.h>
#include <aasdk_proto/SensorStartResponseMessage.pb.h>
#include <aasdk_proto/NavigationFocusResponseMessage.pb.h>
#include <aasdk_proto/ServiceDiscoveryResponseMessage.pb.h>
#include <aasdk_proto/StatusEnum.pb.h>
#include <aasdk_proto/TouchActionEnum.pb.h>
#include <aasdk_proto/VideoFocusIndicationMessage.pb.h>
#include <aasdk_proto/VideoFocusModeEnum.pb.h>
#include <aasdk_proto/VideoFPSEnum.pb.h>
#include <aasdk_proto/VideoResolutionEnum.pb.h>
#include <f1x/aasdk/Channel/AV/AVInputServiceChannel.hpp>
#include <f1x/aasdk/Channel/AV/IAVInputServiceChannelEventHandler.hpp>
#include <f1x/aasdk/Channel/AV/AudioServiceChannel.hpp>
#include <f1x/aasdk/Channel/AV/IAudioServiceChannelEventHandler.hpp>
#include <f1x/aasdk/Channel/AV/MediaAudioServiceChannel.hpp>
#include <f1x/aasdk/Channel/AV/SpeechAudioServiceChannel.hpp>
#include <f1x/aasdk/Channel/AV/SystemAudioServiceChannel.hpp>
#include <f1x/aasdk/Channel/AV/IVideoServiceChannelEventHandler.hpp>
#include <f1x/aasdk/Channel/AV/VideoServiceChannel.hpp>
#include <f1x/aasdk/Channel/Bluetooth/BluetoothServiceChannel.hpp>
#include <f1x/aasdk/Channel/Bluetooth/IBluetoothServiceChannelEventHandler.hpp>
#include <f1x/aasdk/Channel/Control/ControlServiceChannel.hpp>
#include <f1x/aasdk/Channel/Control/IControlServiceChannelEventHandler.hpp>
#include <f1x/aasdk/Channel/Input/IInputServiceChannelEventHandler.hpp>
#include <f1x/aasdk/Channel/Input/InputServiceChannel.hpp>
#include <f1x/aasdk/Channel/Promise.hpp>
#include <f1x/aasdk/Channel/Sensor/ISensorServiceChannelEventHandler.hpp>
#include <f1x/aasdk/Channel/Sensor/SensorServiceChannel.hpp>
#include <f1x/aasdk/Error/Error.hpp>
#include <f1x/aasdk/Messenger/Cryptor.hpp>
#include <f1x/aasdk/Messenger/MessageInStream.hpp>
#include <f1x/aasdk/Messenger/MessageOutStream.hpp>
#include <f1x/aasdk/Messenger/Messenger.hpp>
#include <f1x/aasdk/TCP/TCPEndpoint.hpp>
#include <f1x/aasdk/TCP/TCPWrapper.hpp>
#include <f1x/aasdk/Transport/SSLWrapper.hpp>
#include <f1x/aasdk/Transport/TCPTransport.hpp>

#include <openssl/err.h>

#include "AndroidUsbTransport.h"
#include "NativeLog.h"
#include "UsbTransport.h"

#define LOG_TAG "AASDKJNI"

namespace {
using namespace f1x::aasdk;

constexpr int kDefaultVideoWidth = 1280;
constexpr int kDefaultVideoHeight = 720;
constexpr int kMediaAudioSampleRate = 48000;
constexpr int kMediaAudioChannels = 2;
constexpr int kSpeechAudioSampleRate = 16000;
constexpr int kSpeechAudioChannels = 1;
constexpr int kSystemAudioSampleRate = 16000;
constexpr int kSystemAudioChannels = 1;
constexpr int kAudioInputSampleRate = 16000;
constexpr int kAudioInputChannels = 1;
constexpr int kAudioSinkMedia = 0;
constexpr int kAudioSinkSpeech = 1;
constexpr int kAudioSinkSystem = 2;
constexpr int kAudioBitDepth = 16;
constexpr uint32_t kMaxUnacked = 1;
constexpr uint32_t kMediaAudioMaxUnacked = 4;
// Keep the AASDK strand clear for audio/video/control, but allow a short burst of
// MOVE indications so fast drags are not reduced to a low-rate latest-only stream.
constexpr int32_t kMaxTouchInFlight = 4;
constexpr int32_t kMaxTouchHardLimit = 10;
constexpr int64_t kMaxPendingTouchMoveAgeMs = 70;
constexpr std::array<uint32_t, 19> kSupportedButtonCodes = {
    static_cast<uint32_t>(proto::enums::ButtonCode::MENU),
    static_cast<uint32_t>(proto::enums::ButtonCode::HOME),
    static_cast<uint32_t>(proto::enums::ButtonCode::BACK),
    static_cast<uint32_t>(proto::enums::ButtonCode::PHONE),
    static_cast<uint32_t>(proto::enums::ButtonCode::CALL_END),
    static_cast<uint32_t>(proto::enums::ButtonCode::UP),
    static_cast<uint32_t>(proto::enums::ButtonCode::DOWN),
    static_cast<uint32_t>(proto::enums::ButtonCode::LEFT),
    static_cast<uint32_t>(proto::enums::ButtonCode::RIGHT),
    static_cast<uint32_t>(proto::enums::ButtonCode::ENTER),
    static_cast<uint32_t>(proto::enums::ButtonCode::MICROPHONE_1),
    static_cast<uint32_t>(proto::enums::ButtonCode::TOGGLE_PLAY),
    static_cast<uint32_t>(proto::enums::ButtonCode::NEXT),
    static_cast<uint32_t>(proto::enums::ButtonCode::PREV),
    86, // STOP
    89, // REWIND
    90, // FAST_FORWARD
    static_cast<uint32_t>(proto::enums::ButtonCode::PLAY),
    static_cast<uint32_t>(proto::enums::ButtonCode::PAUSE)
};

std::atomic<bool> g_running{false};
std::string g_last_error;
std::thread g_worker;
std::mutex g_session_mutex;
JavaVM* g_vm = nullptr;
jclass g_projection_sink_class = nullptr;
jmethodID g_configure_video = nullptr;
jmethodID g_stop_video = nullptr;
jmethodID g_push_video = nullptr;
jmethodID g_configure_audio = nullptr;
jmethodID g_stop_audio = nullptr;
jmethodID g_push_audio = nullptr;
jclass g_mic_bridge_class = nullptr;
jmethodID g_start_mic = nullptr;
jmethodID g_stop_mic = nullptr;
jclass g_bluetooth_bridge_class = nullptr;
jmethodID g_get_bluetooth_adapter_address = nullptr;
jmethodID g_is_phone_paired = nullptr;
std::mutex g_jni_mutex;
std::atomic<bool> g_microphone_permission_granted{false};
std::atomic<bool> g_car_speed_sensor_started{false};
std::atomic<bool> g_navigation_focus_active{false};
std::atomic<int32_t> g_latest_car_speed_mps{0};
std::atomic<uint64_t> g_touch_event_count{0};
std::atomic<uint64_t> g_touch_drop_count{0};
std::atomic<uint64_t> g_touch_coalesce_count{0};
std::atomic<uint64_t> g_button_event_count{0};
std::atomic<uint64_t> g_video_frame_count{0};
std::atomic<int32_t> g_video_width{kDefaultVideoWidth};
std::atomic<int32_t> g_video_height{kDefaultVideoHeight};
std::atomic<int32_t> g_video_frame_width{kDefaultVideoWidth};
std::atomic<int32_t> g_video_frame_height{kDefaultVideoHeight};
std::atomic<int32_t> g_video_margin_width{0};
std::atomic<int32_t> g_video_margin_height{0};
std::atomic<int32_t> g_video_resolution{
    static_cast<int32_t>(proto::enums::VideoResolution::_720p)
};
std::atomic<int32_t> g_video_fps{60};
std::atomic<int32_t> g_touch_in_flight{0};

struct VideoConfigInfo {
    uint32_t config_index;
    int width;
    int height;
    int frame_width;
    int frame_height;
    int margin_width;
    int margin_height;
    proto::enums::VideoResolution::Enum resolution;
};

struct AudioConfigInfo {
    uint32_t config_index;
    int sample_rate;
    int channel_count;
};

const AudioConfigInfo kMediaAudioConfig{1, kMediaAudioSampleRate, kMediaAudioChannels};
const AudioConfigInfo kSpeechAudioConfig{1, kSpeechAudioSampleRate, kSpeechAudioChannels};
const AudioConfigInfo kSystemAudioConfig{1, kSystemAudioSampleRate, kSystemAudioChannels};
const AudioConfigInfo kAudioInputConfig{1, kAudioInputSampleRate, kAudioInputChannels};

struct EnvHolder {
    JNIEnv* env;
    bool attached;
};

EnvHolder getEnv() {
    if (g_vm == nullptr) {
        return {nullptr, false};
    }
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        return {env, false};
    }
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return {nullptr, false};
    }
    return {env, true};
}

bool ensureProjectionSink(JNIEnv* env) {
    if (g_projection_sink_class != nullptr) {
        return true;
    }
    jclass local = env->FindClass("com/example/androidautodisplay/AaProjectionSink");
    if (local == nullptr) {
        native_log::Log(LOG_TAG, "E", "AaProjectionSink class not found");
        return false;
    }
    g_projection_sink_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_configure_video = env->GetStaticMethodID(g_projection_sink_class, "nativeConfigureVideo", "(II)V");
    g_stop_video = env->GetStaticMethodID(g_projection_sink_class, "nativeStopVideo", "()V");
    g_push_video = env->GetStaticMethodID(g_projection_sink_class, "nativePushVideo", "([BJ)V");
    g_configure_audio = env->GetStaticMethodID(g_projection_sink_class, "nativeConfigureAudio", "(III)V");
    g_stop_audio = env->GetStaticMethodID(g_projection_sink_class, "nativeStopAudio", "(I)V");
    g_push_audio = env->GetStaticMethodID(g_projection_sink_class, "nativePushAudio", "(I[BJ)V");
    return g_configure_video != nullptr &&
           g_stop_video != nullptr &&
           g_push_video != nullptr &&
           g_configure_audio != nullptr &&
           g_stop_audio != nullptr &&
           g_push_audio != nullptr;
}

bool ensureMicInputBridge(JNIEnv* env) {
    if (g_mic_bridge_class != nullptr) {
        return true;
    }
    jclass local = env->FindClass("com/example/androidautodisplay/MicInputBridge");
    if (local == nullptr) {
        native_log::Log(LOG_TAG, "E", "MicInputBridge class not found");
        return false;
    }
    g_mic_bridge_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_start_mic = env->GetStaticMethodID(g_mic_bridge_class, "nativeStart", "()Z");
    g_stop_mic = env->GetStaticMethodID(g_mic_bridge_class, "nativeStop", "()V");
    return g_start_mic != nullptr && g_stop_mic != nullptr;
}

bool ensureBluetoothBridge(JNIEnv* env) {
    if (g_bluetooth_bridge_class != nullptr) {
        return true;
    }
    jclass local = env->FindClass("com/example/androidautodisplay/BluetoothBridge");
    if (local == nullptr) {
        native_log::Log(LOG_TAG, "W", "BluetoothBridge class not found");
        return false;
    }
    g_bluetooth_bridge_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_get_bluetooth_adapter_address = env->GetStaticMethodID(
        g_bluetooth_bridge_class,
        "nativeGetAdapterAddress",
        "()Ljava/lang/String;"
    );
    g_is_phone_paired = env->GetStaticMethodID(
        g_bluetooth_bridge_class,
        "nativeIsPhonePaired",
        "(Ljava/lang/String;)Z"
    );
    return g_get_bluetooth_adapter_address != nullptr && g_is_phone_paired != nullptr;
}

bool warmJvmBindings(JNIEnv* env) {
    return ensureProjectionSink(env) && ensureMicInputBridge(env) && ensureBluetoothBridge(env);
}

void configureVideoSink(int width, int height) {
    std::lock_guard<std::mutex> lock(g_jni_mutex);
    auto holder = getEnv();
    if (holder.env == nullptr || !ensureProjectionSink(holder.env)) {
        return;
    }
    holder.env->CallStaticVoidMethod(g_projection_sink_class, g_configure_video, width, height);
    native_log::LogJniException(holder.env, "nativeConfigureVideo");
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
}

void stopVideoSink() {
    std::lock_guard<std::mutex> lock(g_jni_mutex);
    auto holder = getEnv();
    if (holder.env == nullptr || !ensureProjectionSink(holder.env)) {
        return;
    }
    holder.env->CallStaticVoidMethod(g_projection_sink_class, g_stop_video);
    native_log::LogJniException(holder.env, "nativeStopVideo");
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
}

void pushVideoFrame(const common::DataConstBuffer& payload, int64_t pts_us) {
    const auto count = g_video_frame_count.fetch_add(1) + 1;
    if (payload.cdata == nullptr || payload.size == 0) {
        native_log::Logf(LOG_TAG, "W",
                         "AA video frame skipped empty count=%llu",
                         static_cast<unsigned long long>(count));
        return;
    }
    if (count % 600 == 0) {
        native_log::Logf(LOG_TAG, "I",
                         "AA video frame bytes=%zu pts=%lld count=%llu",
                         payload.size,
                         static_cast<long long>(pts_us),
                         static_cast<unsigned long long>(count));
    }
    auto holder = getEnv();
    if (holder.env == nullptr || !ensureProjectionSink(holder.env)) {
        return;
    }
    std::vector<jbyte> copy(payload.size);
    std::memcpy(copy.data(), payload.cdata, payload.size);
    jbyteArray arr = holder.env->NewByteArray(static_cast<jsize>(payload.size));
    if (arr == nullptr) {
        native_log::Log(LOG_TAG, "E", "AA video frame NewByteArray failed");
        native_log::LogJniException(holder.env, "nativePushVideo NewByteArray");
        if (holder.attached) {
            g_vm->DetachCurrentThread();
        }
        return;
    }
    holder.env->SetByteArrayRegion(
        arr,
        0,
        static_cast<jsize>(payload.size),
        copy.data()
    );
    holder.env->CallStaticVoidMethod(g_projection_sink_class, g_push_video, arr, static_cast<jlong>(pts_us));
    holder.env->DeleteLocalRef(arr);
    native_log::LogJniException(holder.env, "nativePushVideo");
    if (count % 600 == 0) {
        native_log::Logf(LOG_TAG, "I",
                         "AA video frame delivered count=%llu",
                         static_cast<unsigned long long>(count));
    }
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
}

void configureAudioSink(int sink_id, int sample_rate, int channel_count) {
    std::lock_guard<std::mutex> lock(g_jni_mutex);
    auto holder = getEnv();
    if (holder.env == nullptr || !ensureProjectionSink(holder.env)) {
        return;
    }
    holder.env->CallStaticVoidMethod(
        g_projection_sink_class,
        g_configure_audio,
        sink_id,
        sample_rate,
        channel_count
    );
    native_log::LogJniException(holder.env, "nativeConfigureAudio");
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
}

void stopAudioSink(int sink_id) {
    std::lock_guard<std::mutex> lock(g_jni_mutex);
    auto holder = getEnv();
    if (holder.env == nullptr || !ensureProjectionSink(holder.env)) {
        return;
    }
    holder.env->CallStaticVoidMethod(g_projection_sink_class, g_stop_audio, sink_id);
    native_log::LogJniException(holder.env, "nativeStopAudio");
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
}

bool startMicInput() {
    std::lock_guard<std::mutex> lock(g_jni_mutex);
    auto holder = getEnv();
    if (holder.env == nullptr || !ensureMicInputBridge(holder.env)) {
        return false;
    }
    const jboolean started = holder.env->CallStaticBooleanMethod(g_mic_bridge_class, g_start_mic);
    native_log::LogJniException(holder.env, "MicInputBridge.nativeStart");
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
    return started == JNI_TRUE;
}

void stopMicInput() {
    std::lock_guard<std::mutex> lock(g_jni_mutex);
    auto holder = getEnv();
    if (holder.env == nullptr || !ensureMicInputBridge(holder.env)) {
        return;
    }
    holder.env->CallStaticVoidMethod(g_mic_bridge_class, g_stop_mic);
    native_log::LogJniException(holder.env, "MicInputBridge.nativeStop");
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
}

std::string getBluetoothAdapterAddress() {
    std::lock_guard<std::mutex> lock(g_jni_mutex);
    auto holder = getEnv();
    if (holder.env == nullptr || !ensureBluetoothBridge(holder.env)) {
        return "";
    }
    auto value = static_cast<jstring>(
        holder.env->CallStaticObjectMethod(g_bluetooth_bridge_class, g_get_bluetooth_adapter_address)
    );
    native_log::LogJniException(holder.env, "BluetoothBridge.nativeGetAdapterAddress");
    std::string address;
    if (value != nullptr) {
        const char* raw = holder.env->GetStringUTFChars(value, nullptr);
        if (raw != nullptr) {
            address = raw;
            holder.env->ReleaseStringUTFChars(value, raw);
        }
        holder.env->DeleteLocalRef(value);
    }
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
    return address;
}

bool isPhoneBluetoothPaired(const std::string& phone_address) {
    std::lock_guard<std::mutex> lock(g_jni_mutex);
    auto holder = getEnv();
    if (holder.env == nullptr || !ensureBluetoothBridge(holder.env)) {
        return false;
    }
    jstring value = holder.env->NewStringUTF(phone_address.c_str());
    if (value == nullptr) {
        native_log::LogJniException(holder.env, "BluetoothBridge.phoneAddress");
        if (holder.attached) {
            g_vm->DetachCurrentThread();
        }
        return false;
    }
    const jboolean paired = holder.env->CallStaticBooleanMethod(
        g_bluetooth_bridge_class,
        g_is_phone_paired,
        value
    );
    holder.env->DeleteLocalRef(value);
    native_log::LogJniException(holder.env, "BluetoothBridge.nativeIsPhonePaired");
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
    return paired == JNI_TRUE;
}

void pushAudioFrame(int sink_id, const common::DataConstBuffer& payload, int64_t pts_us) {
    auto holder = getEnv();
    if (holder.env == nullptr || !ensureProjectionSink(holder.env)) {
        return;
    }
    jbyteArray arr = holder.env->NewByteArray(static_cast<jsize>(payload.size));
    holder.env->SetByteArrayRegion(
        arr,
        0,
        static_cast<jsize>(payload.size),
        reinterpret_cast<const jbyte*>(payload.cdata)
    );
    holder.env->CallStaticVoidMethod(g_projection_sink_class, g_push_audio, sink_id, arr, static_cast<jlong>(pts_us));
    holder.env->DeleteLocalRef(arr);
    native_log::LogJniException(holder.env, "nativePushAudio");
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
}

std::string hexDump(const uint8_t* data, size_t length) {
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

void logOpenSslErrors(const char* context) {
    unsigned long err = 0;
    while ((err = ERR_get_error()) != 0) {
        char buffer[256];
        ERR_error_string_n(err, buffer, sizeof(buffer));
        native_log::Logf(LOG_TAG, "E", "OpenSSL %s: %s", context, buffer);
    }
}

void set_error(const std::string& msg) {
    g_last_error = msg;
    native_log::Log(LOG_TAG, "E", msg);
}

void crashHandler(int sig) {
    native_log::Logf(LOG_TAG, "E", "Native crash signal=%d", sig);
    struct BacktraceState {
        void* pcs[32];
        size_t count;
    } state{};
    auto cb = [](_Unwind_Context* ctx, void* arg) -> _Unwind_Reason_Code {
        auto* st = reinterpret_cast<BacktraceState*>(arg);
        if (st->count >= 32) {
            return _URC_END_OF_STACK;
        }
        void* pc = reinterpret_cast<void*>(_Unwind_GetIP(ctx));
        st->pcs[st->count++] = pc;
        return _URC_NO_REASON;
    };
    _Unwind_Backtrace(cb, &state);
    for (size_t i = 0; i < state.count; ++i) {
        Dl_info info {};
        if (dladdr(state.pcs[i], &info) != 0 && info.dli_fname != nullptr) {
            const auto offset = reinterpret_cast<uintptr_t>(state.pcs[i]) -
                reinterpret_cast<uintptr_t>(info.dli_fbase);
            native_log::Logf(LOG_TAG, "E",
                             "  #%zu pc=%p off=0x%zx obj=%s sym=%s",
                             i,
                             state.pcs[i],
                             static_cast<size_t>(offset),
                             info.dli_fname,
                             info.dli_sname != nullptr ? info.dli_sname : "?");
        } else {
            native_log::Logf(LOG_TAG, "E", "  #%zu pc=%p", i, state.pcs[i]);
        }
    }
    _Exit(128 + sig);
}

void installCrashHandlers() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = crashHandler;
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGFPE, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
}

proto::messages::ChannelOpenResponse okChannelOpenResponse() {
    proto::messages::ChannelOpenResponse response;
    response.set_status(proto::enums::Status::OK);
    return response;
}

proto::messages::AVChannelSetupResponse okAvSetupResponse() {
    proto::messages::AVChannelSetupResponse response;
    response.set_media_status(proto::enums::AVChannelSetupStatus::OK);
    response.set_max_unacked(kMaxUnacked);
    response.add_configs(0);
    return response;
}

proto::messages::AVChannelSetupResponse okAvSetupResponse(uint32_t max_unacked) {
    proto::messages::AVChannelSetupResponse response;
    response.set_media_status(proto::enums::AVChannelSetupStatus::OK);
    response.set_max_unacked(max_unacked);
    response.add_configs(0);
    return response;
}

proto::messages::AVMediaAckIndication makeAck(int32_t session, uint32_t value) {
    proto::messages::AVMediaAckIndication ack;
    ack.set_session(session);
    ack.set_value(value);
    return ack;
}

struct AaSession {
    boost::asio::io_service io;
    boost::asio::io_service::strand strand;
    using WorkGuard = boost::asio::executor_work_guard<boost::asio::io_service::executor_type>;
    std::unique_ptr<WorkGuard> work_guard;
    std::thread io_thread;
    std::shared_ptr<transport::Transport> transport;
    std::shared_ptr<tcp::TCPWrapper> tcp_wrapper;
    tcp::ITCPEndpoint::SocketPointer tcp_socket;
    std::shared_ptr<transport::SSLWrapper> ssl_wrapper;
    std::shared_ptr<messenger::Cryptor> cryptor;
    std::shared_ptr<messenger::MessageInStream> in_stream;
    std::shared_ptr<messenger::MessageOutStream> out_stream;
    std::shared_ptr<messenger::Messenger> messenger;
    std::shared_ptr<channel::control::ControlServiceChannel> control;
    std::shared_ptr<channel::av::VideoServiceChannel> video;
    std::shared_ptr<channel::av::MediaAudioServiceChannel> media_audio;
    std::shared_ptr<channel::av::SpeechAudioServiceChannel> speech_audio;
    std::shared_ptr<channel::av::SystemAudioServiceChannel> system_audio;
    std::shared_ptr<channel::av::AVInputServiceChannel> av_input;
    std::shared_ptr<channel::input::InputServiceChannel> input;
    std::shared_ptr<channel::sensor::SensorServiceChannel> sensor;
    std::shared_ptr<channel::bluetooth::BluetoothServiceChannel> bluetooth;
    std::shared_ptr<channel::control::IControlServiceChannelEventHandler> control_handler;
    std::shared_ptr<channel::av::IVideoServiceChannelEventHandler> video_handler;
    std::shared_ptr<channel::av::IAudioServiceChannelEventHandler> media_audio_handler;
    std::shared_ptr<channel::av::IAudioServiceChannelEventHandler> speech_audio_handler;
    std::shared_ptr<channel::av::IAudioServiceChannelEventHandler> system_audio_handler;
    std::shared_ptr<channel::av::IAVInputServiceChannelEventHandler> av_input_handler;
    std::shared_ptr<channel::input::IInputServiceChannelEventHandler> input_handler;
    std::shared_ptr<channel::sensor::ISensorServiceChannelEventHandler> sensor_handler;
    std::shared_ptr<channel::bluetooth::IBluetoothServiceChannelEventHandler> bluetooth_handler;

    AaSession() : strand(io) {}
};

std::shared_ptr<AaSession> g_session;
std::thread g_tcp_accept_thread;
std::shared_ptr<boost::asio::ip::tcp::acceptor> g_tcp_acceptor;
std::atomic<bool> g_tcp_accepting{false};

VideoConfigInfo currentVideoConfig() {
    auto width = g_video_width.load();
    auto height = g_video_height.load();
    auto frame_width = g_video_frame_width.load();
    auto frame_height = g_video_frame_height.load();
    auto margin_width = g_video_margin_width.load();
    auto margin_height = g_video_margin_height.load();
    auto resolution = static_cast<proto::enums::VideoResolution::Enum>(
        g_video_resolution.load()
    );
    if (width <= 0 || height <= 0 || frame_width <= 0 || frame_height <= 0 ||
        margin_width < 0 || margin_height < 0 ||
        width + margin_width > frame_width || height + margin_height > frame_height ||
        !proto::enums::VideoResolution_Enum_IsValid(static_cast<int>(resolution)) ||
        resolution == proto::enums::VideoResolution::NONE) {
        width = kDefaultVideoWidth;
        height = kDefaultVideoHeight;
        frame_width = kDefaultVideoWidth;
        frame_height = kDefaultVideoHeight;
        margin_width = 0;
        margin_height = 0;
        resolution = proto::enums::VideoResolution::_720p;
    }
    return {0, width, height, frame_width, frame_height, margin_width, margin_height, resolution};
}

uint64_t monotonicNanos() {
    timespec ts{};
    if (clock_gettime(CLOCK_BOOTTIME, &ts) != 0 &&
        clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now().time_since_epoch()
            ).count()
        );
    }
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL +
           static_cast<uint64_t>(ts.tv_nsec);
}

proto::enums::TouchAction_Enum toTouchAction(int32_t action) {
    switch (action) {
        case 0:
        case 5:
            return proto::enums::TouchAction_Enum_PRESS;
        case 1:
        case 3:
        case 6:
            return proto::enums::TouchAction_Enum_RELEASE;
        case 2:
        default:
            return proto::enums::TouchAction_Enum_DRAG;
    }
}

struct NativeTouchPoint {
    int32_t x;
    int32_t y;
    int32_t pointer_id;
};

struct PendingNativeTouchMove {
    std::shared_ptr<AaSession> session;
    int32_t action = 2;
    int32_t action_index = 0;
    std::vector<NativeTouchPoint> points;
    std::chrono::steady_clock::time_point stored_at{};
    bool has_value = false;
};

std::mutex g_touch_pending_mutex;
PendingNativeTouchMove g_pending_touch_move;

void drainPendingTouchMove();

void resetTouchPipeline() {
    g_touch_in_flight.store(0);
    std::lock_guard<std::mutex> lock(g_touch_pending_mutex);
    g_pending_touch_move = PendingNativeTouchMove{};
}

void storePendingTouchMove(
    const std::shared_ptr<AaSession>& session,
    int32_t action,
    int32_t action_index,
    std::vector<NativeTouchPoint> points) {
    if (points.size() > 2) {
        points.resize(2);
    }
    {
        std::lock_guard<std::mutex> lock(g_touch_pending_mutex);
        g_pending_touch_move.session = session;
        g_pending_touch_move.action = action;
        g_pending_touch_move.action_index = action_index;
        g_pending_touch_move.points = std::move(points);
        g_pending_touch_move.stored_at = std::chrono::steady_clock::now();
        g_pending_touch_move.has_value = true;
    }
    const auto coalesced = g_touch_coalesce_count.fetch_add(1) + 1;
    if (coalesced % 500 == 0) {
        native_log::Logf(LOG_TAG, "I",
                         "AA touch native coalesce action=%d inFlight=%d coalesced=%llu",
                         action,
                         g_touch_in_flight.load(),
                         static_cast<unsigned long long>(coalesced));
    }
}

void clearPendingTouchMove() {
    std::lock_guard<std::mutex> lock(g_touch_pending_mutex);
    g_pending_touch_move = PendingNativeTouchMove{};
}

void postTouchEventDirect(
    const std::shared_ptr<AaSession>& session,
    int32_t action,
    int32_t action_index,
    std::vector<NativeTouchPoint> points) {
    const auto in_flight = g_touch_in_flight.load();
    if (in_flight >= kMaxTouchHardLimit) {
        const auto drops = g_touch_drop_count.fetch_add(1) + 1;
        native_log::Logf(LOG_TAG, "W",
                         "AA touch native hard drop action=%d inFlight=%d drops=%llu",
                         action,
                         in_flight,
                         static_cast<unsigned long long>(drops));
        return;
    }
    g_touch_in_flight.fetch_add(1);
    boost::asio::post(session->strand, [session, action, action_index, points = std::move(points)]() {
        if (session->input == nullptr) {
            g_touch_in_flight.fetch_sub(1);
            drainPendingTouchMove();
            return;
        }
        proto::messages::InputEventIndication indication;
        indication.set_timestamp(monotonicNanos());
        auto* touch = indication.mutable_touch_event();
        touch->set_action_index(static_cast<uint32_t>(
            std::clamp(action_index, 0, static_cast<int32_t>(points.size() - 1))
        ));
        // AA expects raw Android pointer actions for multi-touch gestures.
        touch->set_touch_action(static_cast<proto::enums::TouchAction_Enum>(action));
        const auto config = currentVideoConfig();
        for (const auto& point : points) {
            auto* location = touch->add_touch_location();
            location->set_x(static_cast<uint32_t>(std::clamp(point.x, 0, config.width - 1)));
            location->set_y(static_cast<uint32_t>(std::clamp(point.y, 0, config.height - 1)));
            location->set_pointer_id(static_cast<uint32_t>(std::clamp(point.pointer_id, 1, 2)));
        }

        const auto count = g_touch_event_count.fetch_add(1) + 1;
        if (count % 500 == 0) {
            const auto& first = points.front();
            native_log::Logf(LOG_TAG, "I",
                             "AA touch action=%d x=%d y=%d pointer=%d actionIndex=%d pointers=%zu count=%llu inFlight=%d",
                             action, first.x, first.y, first.pointer_id, action_index, points.size(),
                             static_cast<unsigned long long>(count), g_touch_in_flight.load());
        }

        auto promise = channel::SendPromise::defer(session->strand);
        promise->then(
            []() {
                g_touch_in_flight.fetch_sub(1);
                drainPendingTouchMove();
            },
            [](const error::Error& e) {
                g_touch_in_flight.fetch_sub(1);
                native_log::Logf(LOG_TAG, "E",
                                 "AA touch send failed code=%d native=%u",
                                 static_cast<int>(e.getCode()), e.getNativeCode());
                drainPendingTouchMove();
            }
        );
        session->input->sendInputEventIndication(indication, std::move(promise));
    });
}

void drainPendingTouchMove() {
    PendingNativeTouchMove pending;
    {
        std::lock_guard<std::mutex> lock(g_touch_pending_mutex);
        if (!g_pending_touch_move.has_value ||
            g_touch_in_flight.load() >= kMaxTouchInFlight) {
            return;
        }
        pending = std::move(g_pending_touch_move);
        g_pending_touch_move = PendingNativeTouchMove{};
    }
    if (pending.session == nullptr || pending.session->input == nullptr || pending.points.empty()) {
        return;
    }
    const auto age_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - pending.stored_at
    ).count();
    if (pending.action == 2 && age_ms > kMaxPendingTouchMoveAgeMs) {
        const auto drops = g_touch_drop_count.fetch_add(1) + 1;
        if (drops <= 8 || drops % 100 == 0) {
            native_log::Logf(LOG_TAG, "W",
                             "AA touch native stale drop ageMs=%lld limitMs=%lld drops=%llu",
                             static_cast<long long>(age_ms),
                             static_cast<long long>(kMaxPendingTouchMoveAgeMs),
                             static_cast<unsigned long long>(drops));
        }
        return;
    }
    postTouchEventDirect(
        pending.session,
        pending.action,
        pending.action_index,
        std::move(pending.points)
    );
}

bool sendTouchEventMulti(
    const std::shared_ptr<AaSession>& session,
    int32_t action,
    int32_t action_index,
    std::vector<NativeTouchPoint> points) {
    if (session == nullptr || session->input == nullptr) {
        return false;
    }
    if (points.empty()) {
        return false;
    }
    if (points.size() > 2) {
        points.resize(2);
    }
    if (action == 2 && g_touch_in_flight.load() >= kMaxTouchInFlight) {
        storePendingTouchMove(session, action, action_index, std::move(points));
        return true;
    }
    if (action != 2) {
        clearPendingTouchMove();
    }
    postTouchEventDirect(session, action, action_index, std::move(points));
    return true;
}

void sendTouchEvent(
    const std::shared_ptr<AaSession>& session,
    int32_t action,
    int32_t x,
    int32_t y,
    int32_t pointer_id) {
    if (session == nullptr || session->input == nullptr) {
        return;
    }
    const auto in_flight = g_touch_in_flight.load();
    if ((action == 2 && in_flight >= kMaxTouchInFlight) ||
        in_flight >= kMaxTouchHardLimit) {
        return;
    }
    g_touch_in_flight.fetch_add(1);
    boost::asio::post(session->strand, [session, action, x, y, pointer_id]() {
        if (session->input == nullptr) {
            g_touch_in_flight.fetch_sub(1);
            return;
        }

        const auto config = currentVideoConfig();
        proto::messages::InputEventIndication indication;
        indication.set_timestamp(monotonicNanos());
        auto* touch = indication.mutable_touch_event();
        touch->set_touch_action(toTouchAction(action));
        auto* location = touch->add_touch_location();
        location->set_x(static_cast<uint32_t>(std::clamp(x, 0, config.width - 1)));
        location->set_y(static_cast<uint32_t>(std::clamp(y, 0, config.height - 1)));
        location->set_pointer_id(static_cast<uint32_t>(std::clamp(pointer_id, 1, 1)));

        const auto count = g_touch_event_count.fetch_add(1) + 1;
        if (count % 500 == 0) {
            native_log::Logf(LOG_TAG, "I",
                             "AA touch simple action=%d x=%d y=%d pointer=1 count=%llu",
                             action, x, y, static_cast<unsigned long long>(count));
        }

        auto promise = channel::SendPromise::defer(session->strand);
        promise->then(
            []() {
                g_touch_in_flight.fetch_sub(1);
            },
            [](const error::Error& e) {
                g_touch_in_flight.fetch_sub(1);
                native_log::Logf(LOG_TAG, "E",
                                 "AA touch send failed code=%d native=%u",
                                 static_cast<int>(e.getCode()), e.getNativeCode());
            }
        );
        session->input->sendInputEventIndication(indication, std::move(promise));
    });
}

void sendButtonEvent(
    const std::shared_ptr<AaSession>& session,
    int32_t scan_code,
    bool pressed) {
    if (session == nullptr || session->input == nullptr || scan_code <= 0) {
        return;
    }
    boost::asio::post(session->strand, [session, scan_code, pressed]() {
        if (session->input == nullptr) {
            return;
        }
        proto::messages::InputEventIndication indication;
        indication.set_timestamp(monotonicNanos());
        auto* button = indication.mutable_button_event()->add_button_events();
        button->set_scan_code(static_cast<uint32_t>(scan_code));
        button->set_is_pressed(pressed);
        button->set_meta(0);
        button->set_long_press(false);

        const auto count = g_button_event_count.fetch_add(1) + 1;
        if (count <= 12 || count % 100 == 0) {
            native_log::Logf(LOG_TAG, "I",
                             "AA button scan=%d pressed=%d count=%llu",
                             scan_code,
                             pressed ? 1 : 0,
                             static_cast<unsigned long long>(count));
        }

        auto promise = channel::SendPromise::defer(session->strand);
        promise->then(
            []() {},
            [](const error::Error& e) {
                native_log::Logf(LOG_TAG, "E",
                                 "AA button send failed code=%d native=%u",
                                 static_cast<int>(e.getCode()), e.getNativeCode());
            }
        );
        session->input->sendInputEventIndication(indication, std::move(promise));
    });
}

void sendCarSpeedEvent(const std::shared_ptr<AaSession>& session, int32_t speed_mps) {
    if (session == nullptr || session->sensor == nullptr) {
        return;
    }
    boost::asio::post(session->strand, [session, speed_mps]() {
        if (session->sensor == nullptr) {
            return;
        }
        proto::messages::SensorEventIndication indication;
        auto* speed = indication.add_speed();
        speed->set_speed(speed_mps);
        speed->set_cruise_engaged(false);
        speed->set_cruise_set_speed(false);
        auto promise = channel::SendPromise::defer(session->strand);
        promise->then(
            []() {},
            [](const error::Error& e) {
                native_log::Logf(LOG_TAG, "E",
                                 "AA car speed send failed code=%d native=%u",
                                 static_cast<int>(e.getCode()), e.getNativeCode());
            }
        );
        session->sensor->sendSensorEventIndication(indication, std::move(promise));
    });
}

class AndroidVideoHandler
    : public channel::av::IVideoServiceChannelEventHandler,
      public std::enable_shared_from_this<AndroidVideoHandler> {
public:
    AndroidVideoHandler(
        std::weak_ptr<channel::av::VideoServiceChannel> channel,
        boost::asio::io_service::strand& strand)
        : channel_(std::move(channel)), strand_(strand) {}

    void onChannelOpenRequest(const proto::messages::ChannelOpenRequest& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA video open request priority=%d channel=%d",
                         request.priority(), request.channel_id());
        if (auto channel = channel_.lock()) {
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendChannelOpenResponse(okChannelOpenResponse(), std::move(promise));
            receiveAgain();
        }
    }

    void onAVChannelSetupRequest(const proto::messages::AVChannelSetupRequest& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA video setup request config_index=%u",
                         request.config_index());
        const auto config = currentVideoConfig();
        active_session_ = -1;
        active_config_index_ = 0;
        configureVideoSink(config.frame_width, config.frame_height);
        if (auto channel = channel_.lock()) {
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                [self = shared_from_this()]() { self->sendVideoFocusIndication(); },
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendAVChannelSetupResponse(
                okAvSetupResponse(),
                std::move(promise)
            );
            receiveAgain();
        }
    }

    void onAVChannelStartIndication(const proto::messages::AVChannelStartIndication& indication) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA video start session=%d config=%u",
                         indication.session(), indication.config());
        active_session_ = indication.session();
        active_config_index_ = indication.config();
        const auto config = currentVideoConfig();
        configureVideoSink(config.frame_width, config.frame_height);
        receiveAgain();
    }

    void onAVMediaWithTimestampIndication(messenger::Timestamp::ValueType ts,
                                          const common::DataConstBuffer& buffer) override {
        pushVideoFrame(buffer, static_cast<int64_t>(ts));
        sendAck();
    }

    void onAVMediaIndication(const common::DataConstBuffer& buffer) override {
        pushVideoFrame(buffer, 0);
        sendAck();
    }

    void onVideoFocusRequest(const proto::messages::VideoFocusRequest& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA video focus request display=%d mode=%d reason=%d",
                         request.disp_index(),
                         static_cast<int>(request.focus_mode()),
                         static_cast<int>(request.focus_reason()));
        if (auto channel = channel_.lock()) {
            proto::messages::VideoFocusIndication indication;
            indication.set_focus_mode(proto::enums::VideoFocusMode::FOCUSED);
            indication.set_unrequested(false);
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendVideoFocusIndication(indication, std::move(promise));
            receiveAgain();
        }
    }

    void onChannelError(const error::Error& e) override {
        if (channel_failed_) {
            return;
        }
        channel_failed_ = true;
        native_log::Logf(LOG_TAG, "E",
                         "AA video channel error code=%d native=%u",
                         static_cast<int>(e.getCode()), e.getNativeCode());
        stopVideoSink();
    }

private:
    void sendVideoFocusIndication() {
        if (auto channel = channel_.lock()) {
            native_log::Log(LOG_TAG, "I", "AA video focus indication sent");
            proto::messages::VideoFocusIndication indication;
            indication.set_focus_mode(proto::enums::VideoFocusMode::FOCUSED);
            indication.set_unrequested(false);
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendVideoFocusIndication(indication, std::move(promise));
        }
    }

    void sendAck() {
        if (active_session_ < 0) {
            receiveAgain();
            return;
        }
        if (auto channel = channel_.lock()) {
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendAVMediaAckIndication(makeAck(active_session_, 1), std::move(promise));
            receiveAgain();
        }
    }

    void receiveAgain() {
        if (auto channel = channel_.lock()) {
            channel->receive(shared_from_this());
        }
    }

    std::weak_ptr<channel::av::VideoServiceChannel> channel_;
    boost::asio::io_service::strand& strand_;
    int32_t active_session_ = -1;
    uint32_t active_config_index_ = 0;
    bool channel_failed_ = false;
};

class AndroidAudioHandler
    : public channel::av::IAudioServiceChannelEventHandler,
      public std::enable_shared_from_this<AndroidAudioHandler> {
public:
    AndroidAudioHandler(
        std::weak_ptr<channel::av::AudioServiceChannel> channel,
        boost::asio::io_service::strand& strand,
        std::string label,
        int sink_id,
        bool render_to_sink,
        AudioConfigInfo config,
        uint32_t max_unacked = kMaxUnacked)
        : channel_(std::move(channel)),
          strand_(strand),
          label_(std::move(label)),
          sink_id_(sink_id),
          render_to_sink_(render_to_sink),
          config_(config),
          max_unacked_(max_unacked) {}

    void onChannelOpenRequest(const proto::messages::ChannelOpenRequest& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA %s open request priority=%d channel=%d",
                         label_.c_str(), request.priority(), request.channel_id());
        if (auto channel = channel_.lock()) {
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendChannelOpenResponse(okChannelOpenResponse(), std::move(promise));
            receiveAgain();
        }
    }

    void onAVChannelSetupRequest(const proto::messages::AVChannelSetupRequest& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA %s setup request config_index=%u",
                         label_.c_str(), request.config_index());
        active_session_ = -1;
        pending_ack_count_ = 0;
        if (render_to_sink_) {
            configureAudioSink(sink_id_, config_.sample_rate, config_.channel_count);
        }
        if (auto channel = channel_.lock()) {
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendAVChannelSetupResponse(
                okAvSetupResponse(max_unacked_),
                std::move(promise)
            );
            receiveAgain();
        }
    }

    void onAVChannelStartIndication(const proto::messages::AVChannelStartIndication& indication) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA %s start session=%d config=%u",
                         label_.c_str(), indication.session(), indication.config());
        active_session_ = indication.session();
        if (render_to_sink_) {
            configureAudioSink(sink_id_, config_.sample_rate, config_.channel_count);
        }
        receiveAgain();
    }

    void onAVChannelStopIndication(const proto::messages::AVChannelStopIndication&) override {
        native_log::Logf(LOG_TAG, "I", "AA %s stop", label_.c_str());
        flushPendingAck();
        active_session_ = -1;
        if (render_to_sink_) {
            stopAudioSink(sink_id_);
        }
        receiveAgain();
    }

    void onAVMediaWithTimestampIndication(messenger::Timestamp::ValueType ts,
                                          const common::DataConstBuffer& buffer) override {
        recordMediaPacket(buffer.size);
        if (render_to_sink_) {
            pushAudioFrame(sink_id_, buffer, static_cast<int64_t>(ts));
        }
        sendAck();
    }

    void onAVMediaIndication(const common::DataConstBuffer& buffer) override {
        recordMediaPacket(buffer.size);
        if (render_to_sink_) {
            pushAudioFrame(sink_id_, buffer, 0);
        }
        sendAck();
    }

    void onChannelError(const error::Error& e) override {
        if (channel_failed_) {
            return;
        }
        channel_failed_ = true;
        native_log::Logf(LOG_TAG, "E",
                         "AA %s channel error code=%d native=%u",
                         label_.c_str(), static_cast<int>(e.getCode()), e.getNativeCode());
        active_session_ = -1;
        if (render_to_sink_) {
            stopAudioSink(sink_id_);
        }
    }

private:
    void recordMediaPacket(size_t bytes) {
        media_packet_count_++;
        media_byte_count_ += bytes;
        maybeLogStats();
    }

    void sendAck() {
        if (active_session_ < 0) {
            receiveAgain();
            return;
        }
        pending_ack_count_++;
        if (pending_ack_count_ < max_unacked_) {
            receiveAgain();
            return;
        }
        sendAckValue(pending_ack_count_);
        ack_message_count_++;
        ack_packet_count_ += pending_ack_count_;
        pending_ack_count_ = 0;
        maybeLogStats();
        receiveAgain();
    }

    void flushPendingAck() {
        if (active_session_ < 0 || pending_ack_count_ == 0) {
            return;
        }
        sendAckValue(pending_ack_count_);
        ack_message_count_++;
        ack_packet_count_ += pending_ack_count_;
        pending_ack_count_ = 0;
        maybeLogStats();
    }

    void sendAckValue(uint32_t value) {
        if (auto channel = channel_.lock()) {
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendAVMediaAckIndication(makeAck(active_session_, value), std::move(promise));
        }
    }

    void maybeLogStats() {
        if (!render_to_sink_) {
            return;
        }
        const auto now = std::chrono::steady_clock::now();
        if (last_stats_log_.time_since_epoch().count() != 0 &&
            now - last_stats_log_ < std::chrono::seconds(5)) {
            return;
        }
        last_stats_log_ = now;
        native_log::Logf(LOG_TAG, "I",
                         "AA %s stats packets=%llu bytes=%llu ackMsgs=%llu ackPackets=%llu pendingAck=%u maxUnacked=%u",
                         label_.c_str(),
                         static_cast<unsigned long long>(media_packet_count_),
                         static_cast<unsigned long long>(media_byte_count_),
                         static_cast<unsigned long long>(ack_message_count_),
                         static_cast<unsigned long long>(ack_packet_count_),
                         pending_ack_count_,
                         max_unacked_);
    }

    void receiveAgain() {
        if (auto channel = channel_.lock()) {
            channel->receive(shared_from_this());
        }
    }

    std::weak_ptr<channel::av::AudioServiceChannel> channel_;
    boost::asio::io_service::strand& strand_;
    std::string label_;
    int sink_id_ = kAudioSinkMedia;
    bool render_to_sink_ = false;
    AudioConfigInfo config_;
    uint32_t max_unacked_ = kMaxUnacked;
    uint32_t pending_ack_count_ = 0;
    uint64_t media_packet_count_ = 0;
    uint64_t media_byte_count_ = 0;
    uint64_t ack_message_count_ = 0;
    uint64_t ack_packet_count_ = 0;
    std::chrono::steady_clock::time_point last_stats_log_;
    int32_t active_session_ = -1;
    bool channel_failed_ = false;
};

class AndroidInputHandler
    : public channel::input::IInputServiceChannelEventHandler,
      public std::enable_shared_from_this<AndroidInputHandler> {
public:
    AndroidInputHandler(
        std::weak_ptr<channel::input::InputServiceChannel> channel,
        boost::asio::io_service::strand& strand)
        : channel_(std::move(channel)), strand_(strand) {}

    void onChannelOpenRequest(const proto::messages::ChannelOpenRequest& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA input open request priority=%d channel=%d",
                         request.priority(), request.channel_id());
        if (auto channel = channel_.lock()) {
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendChannelOpenResponse(okChannelOpenResponse(), std::move(promise));
            receiveAgain();
        }
    }

    void onBindingRequest(const proto::messages::BindingRequest& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA input binding request scan_codes=%d",
                         request.scan_codes_size());
        if (auto channel = channel_.lock()) {
            auto status = proto::enums::Status::OK;
            for (int i = 0; i < request.scan_codes_size(); ++i) {
                const auto scan_code = static_cast<uint32_t>(request.scan_codes(i));
                const auto supported = std::find(
                    kSupportedButtonCodes.begin(),
                    kSupportedButtonCodes.end(),
                    scan_code
                ) != kSupportedButtonCodes.end();
                if (!supported) {
                    native_log::Logf(LOG_TAG, "W",
                                     "AA input binding unsupported scan_code=%u",
                                     scan_code);
                    status = proto::enums::Status::FAIL;
                    break;
                }
            }
            proto::messages::BindingResponse response;
            response.set_status(status);
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendBindingResponse(response, std::move(promise));
            receiveAgain();
        }
    }

    void onChannelError(const error::Error& e) override {
        if (channel_failed_) {
            return;
        }
        channel_failed_ = true;
        native_log::Logf(LOG_TAG, "E",
                         "AA input channel error code=%d native=%u",
                         static_cast<int>(e.getCode()), e.getNativeCode());
    }

private:
    void receiveAgain() {
        if (auto channel = channel_.lock()) {
            channel->receive(shared_from_this());
        }
    }

    std::weak_ptr<channel::input::InputServiceChannel> channel_;
    boost::asio::io_service::strand& strand_;
    bool channel_failed_ = false;
};

class AndroidSensorHandler
    : public channel::sensor::ISensorServiceChannelEventHandler,
      public std::enable_shared_from_this<AndroidSensorHandler> {
public:
    AndroidSensorHandler(
        std::weak_ptr<channel::sensor::SensorServiceChannel> channel,
        boost::asio::io_service::strand& strand)
        : channel_(std::move(channel)), strand_(strand) {}

    void onChannelOpenRequest(const proto::messages::ChannelOpenRequest& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA sensor open request priority=%d channel=%d",
                         request.priority(), request.channel_id());
        if (auto channel = channel_.lock()) {
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendChannelOpenResponse(okChannelOpenResponse(), std::move(promise));
            receiveAgain();
        }
    }

    void onSensorStartRequest(const proto::messages::SensorStartRequestMessage& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA sensor start request type=%d",
                         static_cast<int>(request.sensor_type()));
        if (request.sensor_type() == proto::enums::SensorType_Enum_CAR_SPEED) {
            g_car_speed_sensor_started.store(true);
        }
        if (auto channel = channel_.lock()) {
            proto::messages::SensorStartResponseMessage response;
            response.set_status(proto::enums::Status::OK);
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                [self = shared_from_this(), sensor_type = request.sensor_type()]() {
                    self->sendInitialSensorEvent(sensor_type);
                },
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendSensorStartResponse(response, std::move(promise));
            receiveAgain();
        }
    }

    void onChannelError(const error::Error& e) override {
        if (channel_failed_) {
            return;
        }
        channel_failed_ = true;
        native_log::Logf(LOG_TAG, "E",
                         "AA sensor channel error code=%d native=%u",
                         static_cast<int>(e.getCode()), e.getNativeCode());
    }

private:
    void sendInitialSensorEvent(proto::enums::SensorType_Enum sensor_type) {
        auto channel = channel_.lock();
        if (channel == nullptr) {
            return;
        }
        proto::messages::SensorEventIndication indication;
        if (sensor_type == proto::enums::SensorType_Enum_DRIVING_STATUS) {
            indication.add_driving_status()->set_status(proto::enums::DrivingStatus_Enum_UNRESTRICTED);
        } else if (sensor_type == proto::enums::SensorType_Enum_NIGHT_DATA) {
            indication.add_night_mode()->set_is_night(false);
        } else if (sensor_type == proto::enums::SensorType_Enum_CAR_SPEED) {
            auto* speed = indication.add_speed();
            speed->set_speed(g_latest_car_speed_mps.load());
            speed->set_cruise_engaged(false);
            speed->set_cruise_set_speed(false);
        } else if (sensor_type == proto::enums::SensorType_Enum_PARKING_BRAKE) {
            indication.add_parking_brake()->set_parking_brake(false);
        } else if (sensor_type == proto::enums::SensorType_Enum_GEAR) {
            indication.add_gear()->set_gear(proto::enums::Gear_Enum_DRIVE);
        } else {
            return;
        }
        auto promise = channel::SendPromise::defer(strand_);
        promise->then(
            []() {},
            [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
        );
        channel->sendSensorEventIndication(indication, std::move(promise));
    }

    void receiveAgain() {
        if (auto channel = channel_.lock()) {
            channel->receive(shared_from_this());
        }
    }

    std::weak_ptr<channel::sensor::SensorServiceChannel> channel_;
    boost::asio::io_service::strand& strand_;
    bool channel_failed_ = false;
};

class AndroidAudioInputHandler
    : public channel::av::IAVInputServiceChannelEventHandler,
      public std::enable_shared_from_this<AndroidAudioInputHandler> {
public:
    AndroidAudioInputHandler(
        std::weak_ptr<channel::av::AVInputServiceChannel> channel,
        boost::asio::io_service::strand& strand)
        : channel_(std::move(channel)), strand_(strand) {}

    void onChannelOpenRequest(const proto::messages::ChannelOpenRequest& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA av input open request priority=%d channel=%d",
                         request.priority(), request.channel_id());
        if (auto channel = channel_.lock()) {
            proto::messages::ChannelOpenResponse response;
            response.set_status(
                g_microphone_permission_granted.load()
                    ? proto::enums::Status::OK
                    : proto::enums::Status::FAIL
            );
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendChannelOpenResponse(response, std::move(promise));
            receiveAgain();
        }
    }

    void onAVChannelSetupRequest(const proto::messages::AVChannelSetupRequest& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA av input setup request config_index=%u",
                         request.config_index());
        if (auto channel = channel_.lock()) {
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendAVChannelSetupResponse(
                okAvSetupResponse(),
                std::move(promise)
            );
            receiveAgain();
        }
    }

    void onAVInputOpenRequest(const proto::messages::AVInputOpenRequest& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA av input request open=%d anc=%d ec=%d max_unacked=%u",
                         request.open(), request.anc(), request.ec(), request.max_unacked());
        if (auto channel = channel_.lock()) {
            proto::messages::AVInputOpenResponse response;
            response.set_session(session_);
            if (request.open()) {
                const bool started = g_microphone_permission_granted.load() && startMicInput();
                response.set_value(started ? 0 : 1);
                microphone_active_ = started;
                native_log::Logf(LOG_TAG, "I",
                                 "AA av input microphone start result=%d",
                                 started ? 1 : 0);
            } else {
                stopMicInput();
                microphone_active_ = false;
                response.set_value(0);
            }
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendAVInputOpenResponse(response, std::move(promise));
            receiveAgain();
        }
    }

    void onAVMediaAckIndication(const proto::messages::AVMediaAckIndication& indication) override {
        receiveAgain();
    }

    void onChannelError(const error::Error& e) override {
        if (channel_failed_) {
            return;
        }
        channel_failed_ = true;
        native_log::Logf(LOG_TAG, "E",
                         "AA av input channel error code=%d native=%u",
                         static_cast<int>(e.getCode()), e.getNativeCode());
        microphone_active_ = false;
        stopMicInput();
    }

    void onMicrophoneFrame(common::Data data, int64_t pts_us) {
        if (!microphone_active_) {
            return;
        }
        if (auto channel = channel_.lock()) {
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendAVMediaWithTimestampIndication(pts_us, std::move(data), std::move(promise));
        }
    }

private:
    void receiveAgain() {
        if (auto channel = channel_.lock()) {
            channel->receive(shared_from_this());
        }
    }

    std::weak_ptr<channel::av::AVInputServiceChannel> channel_;
    boost::asio::io_service::strand& strand_;
    int32_t session_ = 0;
    bool microphone_active_ = false;
    bool channel_failed_ = false;
};

class AndroidBluetoothHandler
    : public channel::bluetooth::IBluetoothServiceChannelEventHandler,
      public std::enable_shared_from_this<AndroidBluetoothHandler> {
public:
    AndroidBluetoothHandler(
        std::weak_ptr<channel::bluetooth::BluetoothServiceChannel> channel,
        boost::asio::io_service::strand& strand)
        : channel_(std::move(channel)), strand_(strand) {}

    void onChannelOpenRequest(const proto::messages::ChannelOpenRequest& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA bluetooth open request priority=%d channel=%d",
                         request.priority(), request.channel_id());
        if (auto channel = channel_.lock()) {
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                [self = shared_from_this()]() { self->receiveAgain(); },
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendChannelOpenResponse(okChannelOpenResponse(), std::move(promise));
        }
    }

    void onBluetoothPairingRequest(const proto::messages::BluetoothPairingRequest& request) override {
        const auto paired = isPhoneBluetoothPaired(request.phone_address());
        native_log::Logf(LOG_TAG, "I",
                         "AA bluetooth pairing request phone=%s method=%d paired=%d",
                         request.phone_address().c_str(),
                         request.pairing_method(),
                         paired ? 1 : 0);
        proto::messages::BluetoothPairingResponse response;
        response.set_already_paired(paired);
        response.set_status(
            paired ? proto::enums::BluetoothPairingStatus::OK :
                proto::enums::BluetoothPairingStatus::FAIL
        );
        if (auto channel = channel_.lock()) {
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                [self = shared_from_this()]() { self->receiveAgain(); },
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            channel->sendBluetoothPairingResponse(response, std::move(promise));
        }
    }

    void onChannelError(const error::Error& e) override {
        if (channel_failed_) {
            return;
        }
        channel_failed_ = true;
        native_log::Logf(LOG_TAG, "E",
                         "AA bluetooth channel error code=%d native=%u",
                         static_cast<int>(e.getCode()), e.getNativeCode());
    }

    void receiveAgain() {
        if (auto channel = channel_.lock()) {
            channel->receive(shared_from_this());
        }
    }

private:
    std::weak_ptr<channel::bluetooth::BluetoothServiceChannel> channel_;
    boost::asio::io_service::strand& strand_;
    bool channel_failed_ = false;
};

class AndroidControlHandler
    : public channel::control::IControlServiceChannelEventHandler,
      public std::enable_shared_from_this<AndroidControlHandler> {
public:
    AndroidControlHandler(
        std::weak_ptr<channel::control::ControlServiceChannel> control,
        std::weak_ptr<channel::av::VideoServiceChannel> video,
        std::weak_ptr<channel::av::MediaAudioServiceChannel> media_audio,
        std::weak_ptr<channel::av::SpeechAudioServiceChannel> speech_audio,
        std::weak_ptr<channel::av::SystemAudioServiceChannel> system_audio,
        std::weak_ptr<channel::av::AVInputServiceChannel> av_input,
        std::weak_ptr<channel::input::InputServiceChannel> input,
        std::weak_ptr<channel::sensor::SensorServiceChannel> sensor,
        std::weak_ptr<channel::bluetooth::BluetoothServiceChannel> bluetooth,
        std::shared_ptr<messenger::Cryptor> cryptor,
        boost::asio::io_service::strand& strand)
        : control_(std::move(control)),
          video_(std::move(video)),
          media_audio_(std::move(media_audio)),
          speech_audio_(std::move(speech_audio)),
          system_audio_(std::move(system_audio)),
          av_input_(std::move(av_input)),
          input_(std::move(input)),
          sensor_(std::move(sensor)),
          bluetooth_(std::move(bluetooth)),
          cryptor_(std::move(cryptor)),
          strand_(strand) {}

    void onVersionResponse(uint16_t majorCode,
                           uint16_t minorCode,
                           proto::enums::VersionResponseStatus::Enum status) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA version response major=%u minor=%u status=%d",
                         majorCode, minorCode, static_cast<int>(status));
        startHandshake();
        receiveAgain();
    }

    void onHandshake(const common::DataConstBuffer& payload) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA handshake payload %zu bytes %s",
                         payload.size,
                         hexDump(payload.cdata, payload.size).c_str());
        if (cryptor_ != nullptr) {
            try {
                cryptor_->writeHandshakeBuffer(payload);
                const bool done = cryptor_->doHandshake();
                native_log::Logf(LOG_TAG, "I",
                                 "AA TLS handshake progress active=%d",
                                 done ? 1 : 0);
                sendHandshakeData();
                maybeSendAuthComplete(done);
            } catch (const error::Error& e) {
                native_log::Logf(LOG_TAG, "E",
                                 "AA TLS handshake error code=%d native=%u",
                                 static_cast<int>(e.getCode()), e.getNativeCode());
                logOpenSslErrors("handshake");
            }
        }
        receiveAgain();
    }

    void onServiceDiscoveryRequest(const proto::messages::ServiceDiscoveryRequest& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA service discovery request device=%s brand=%s",
                         request.device_name().c_str(),
                         request.device_brand().c_str());
        sendServiceDiscoveryResponse();
    }

    void onAudioFocusRequest(const proto::messages::AudioFocusRequest& request) override {
        native_log::Logf(LOG_TAG, "I",
                         "AA audio focus request type=%d",
                         static_cast<int>(request.audio_focus_type()));
        if (auto control = control_.lock()) {
            proto::messages::AudioFocusResponse response;
            response.set_audio_focus_state(
                request.audio_focus_type() == proto::enums::AudioFocusType::RELEASE
                    ? proto::enums::AudioFocusState::LOSS
                    : proto::enums::AudioFocusState::GAIN
            );
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            control->sendAudioFocusResponse(response, std::move(promise));
            receiveAgain();
        }
    }

    void onShutdownRequest(const proto::messages::ShutdownRequest&) override {
        native_log::Log(LOG_TAG, "I", "AA shutdown request");
        receiveAgain();
    }

    void onShutdownResponse(const proto::messages::ShutdownResponse&) override {
        native_log::Log(LOG_TAG, "I", "AA shutdown response");
        receiveAgain();
    }

    void onNavigationFocusRequest(const proto::messages::NavigationFocusRequest& request) override {
        native_log::Logf(LOG_TAG, "I", "AA navigation focus request type=%u", request.type());
        g_navigation_focus_active.store(request.type() == 2);
        if (auto control = control_.lock()) {
            proto::messages::NavigationFocusResponse response;
            response.set_type(2);
            auto promise = channel::SendPromise::defer(strand_);
            promise->then(
                []() {},
                [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
            );
            control->sendNavigationFocusResponse(response, std::move(promise));
            receiveAgain();
        }
    }

    void onPingResponse(const proto::messages::PingResponse&) override {
        native_log::Log(LOG_TAG, "I", "AA ping response");
        receiveAgain();
    }

    void onChannelError(const error::Error& e) override {
        if (channel_failed_) {
            return;
        }
        channel_failed_ = true;
        native_log::Logf(LOG_TAG, "E",
                         "AA control channel error code=%d native=%u",
                         static_cast<int>(e.getCode()), e.getNativeCode());
    }

private:
    void startHandshake() {
        if (handshake_started_) {
            return;
        }
        handshake_started_ = true;
        if (cryptor_ == nullptr) {
            return;
        }
        try {
            cryptor_->init();
            native_log::Log(LOG_TAG, "I", "AA TLS init complete");
            const bool done = cryptor_->doHandshake();
            native_log::Logf(LOG_TAG, "I",
                             "AA TLS handshake start active=%d",
                             done ? 1 : 0);
            sendHandshakeData();
            maybeSendAuthComplete(done);
        } catch (const error::Error& e) {
            native_log::Logf(LOG_TAG, "E",
                             "AA TLS init error code=%d native=%u",
                             static_cast<int>(e.getCode()), e.getNativeCode());
            logOpenSslErrors("init");
        }
    }

    void maybeSendAuthComplete(bool handshake_done) {
        if (!handshake_done || auth_complete_sent_) {
            return;
        }
        auto control = control_.lock();
        if (control == nullptr) {
            return;
        }
        proto::messages::AuthCompleteIndication indication;
        indication.set_status(proto::enums::Status::OK);
        auto promise = channel::SendPromise::defer(strand_);
        promise->then(
            [self = shared_from_this()]() {
                self->auth_complete_sent_ = true;
                native_log::Log(LOG_TAG, "I", "AA auth complete sent");
            },
            [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
        );
        control->sendAuthComplete(indication, std::move(promise));
        receiveAgain();
    }

    void sendServiceDiscoveryResponse() {
        auto control = control_.lock();
        if (control == nullptr) {
            return;
        }
        proto::messages::ServiceDiscoveryResponse response;
        response.mutable_channels()->Reserve(256);

        auto* video_descriptor = response.add_channels();
        video_descriptor->set_channel_id(static_cast<uint32_t>(messenger::ChannelId::VIDEO));
        auto* video_channel = video_descriptor->mutable_av_channel();
        video_channel->set_stream_type(proto::enums::AVStreamType::VIDEO);
        video_channel->set_available_while_in_call(true);
        auto* video_config = video_channel->add_video_configs();
        const auto videoConfig = currentVideoConfig();
        const auto fps = g_video_fps.load() == 30 ? 30 : 60;
        video_config->set_video_resolution(videoConfig.resolution);
        video_config->set_video_fps(
            fps == 30 ? proto::enums::VideoFPS::_30 : proto::enums::VideoFPS::_60);
        video_config->set_margin_width(static_cast<uint32_t>(videoConfig.margin_width));
        video_config->set_margin_height(static_cast<uint32_t>(videoConfig.margin_height));
        video_config->set_dpi(160);
        video_config->set_additional_depth(0);
        native_log::Logf(LOG_TAG, "I",
                         "AA video config active=%dx%d frame=%dx%d margins=%dx%d fps=%d",
                         videoConfig.width,
                         videoConfig.height,
                         videoConfig.frame_width,
                         videoConfig.frame_height,
                         videoConfig.margin_width,
                         videoConfig.margin_height,
                         fps);

        auto* audio_descriptor = response.add_channels();
        audio_descriptor->set_channel_id(static_cast<uint32_t>(messenger::ChannelId::MEDIA_AUDIO));
        auto* audio_channel = audio_descriptor->mutable_av_channel();
        audio_channel->set_stream_type(proto::enums::AVStreamType::AUDIO);
        audio_channel->set_audio_type(proto::enums::AudioType::MEDIA);
        audio_channel->set_available_while_in_call(true);
        auto* audio_config = audio_channel->add_audio_configs();
        audio_config->set_sample_rate(kMediaAudioSampleRate);
        audio_config->set_bit_depth(kAudioBitDepth);
        audio_config->set_channel_count(kMediaAudioChannels);

        auto* speech_audio_descriptor = response.add_channels();
        speech_audio_descriptor->set_channel_id(static_cast<uint32_t>(messenger::ChannelId::SPEECH_AUDIO));
        auto* speech_audio_channel = speech_audio_descriptor->mutable_av_channel();
        speech_audio_channel->set_stream_type(proto::enums::AVStreamType::AUDIO);
        speech_audio_channel->set_audio_type(proto::enums::AudioType::SPEECH);
        speech_audio_channel->set_available_while_in_call(true);
        auto* speech_audio_config = speech_audio_channel->add_audio_configs();
        speech_audio_config->set_sample_rate(kSpeechAudioSampleRate);
        speech_audio_config->set_bit_depth(kAudioBitDepth);
        speech_audio_config->set_channel_count(kSpeechAudioChannels);

        auto* system_audio_descriptor = response.add_channels();
        system_audio_descriptor->set_channel_id(static_cast<uint32_t>(messenger::ChannelId::SYSTEM_AUDIO));
        auto* system_audio_channel = system_audio_descriptor->mutable_av_channel();
        system_audio_channel->set_stream_type(proto::enums::AVStreamType::AUDIO);
        system_audio_channel->set_audio_type(proto::enums::AudioType::SYSTEM);
        system_audio_channel->set_available_while_in_call(true);
        auto* system_audio_config = system_audio_channel->add_audio_configs();
        system_audio_config->set_sample_rate(kSystemAudioSampleRate);
        system_audio_config->set_bit_depth(kAudioBitDepth);
        system_audio_config->set_channel_count(kSystemAudioChannels);

        auto* av_input_descriptor = response.add_channels();
        av_input_descriptor->set_channel_id(static_cast<uint32_t>(messenger::ChannelId::AV_INPUT));
        auto* av_input_channel = av_input_descriptor->mutable_av_input_channel();
        av_input_channel->set_stream_type(proto::enums::AVStreamType::AUDIO);
        av_input_channel->set_available_while_in_call(true);
        auto* av_input_config = av_input_channel->mutable_audio_config();
        av_input_config->set_sample_rate(kAudioInputSampleRate);
        av_input_config->set_bit_depth(kAudioBitDepth);
        av_input_config->set_channel_count(kAudioInputChannels);

        auto* input_descriptor = response.add_channels();
        input_descriptor->set_channel_id(static_cast<uint32_t>(messenger::ChannelId::INPUT));
        auto* input_channel = input_descriptor->mutable_input_channel();
        for (const auto code : kSupportedButtonCodes) {
            input_channel->add_supported_keycodes(code);
        }
        auto* touch = input_channel->mutable_touch_screen_config();
        touch->set_width(videoConfig.width);
        touch->set_height(videoConfig.height);

        auto* sensor_descriptor = response.add_channels();
        sensor_descriptor->set_channel_id(static_cast<uint32_t>(messenger::ChannelId::SENSOR));
        auto* sensor_channel = sensor_descriptor->mutable_sensor_channel();
        sensor_channel->add_sensors()->set_type(proto::enums::SensorType_Enum_DRIVING_STATUS);
        sensor_channel->add_sensors()->set_type(proto::enums::SensorType_Enum_NIGHT_DATA);
        sensor_channel->add_sensors()->set_type(proto::enums::SensorType_Enum_CAR_SPEED);
        sensor_channel->add_sensors()->set_type(proto::enums::SensorType_Enum_PARKING_BRAKE);
        sensor_channel->add_sensors()->set_type(proto::enums::SensorType_Enum_GEAR);

        const auto bluetoothAddress = getBluetoothAdapterAddress();
        if (!bluetoothAddress.empty() && bluetooth_.lock() != nullptr) {
            auto* bluetooth_descriptor = response.add_channels();
            bluetooth_descriptor->set_channel_id(static_cast<uint32_t>(messenger::ChannelId::BLUETOOTH));
            auto* bluetooth_channel = bluetooth_descriptor->mutable_bluetooth_channel();
            bluetooth_channel->set_adapter_address(bluetoothAddress);
            bluetooth_channel->add_supported_pairing_methods(proto::enums::BluetoothPairingMethod::HFP);
            bluetooth_channel->add_supported_pairing_methods(proto::enums::BluetoothPairingMethod::A2DP);
            native_log::Logf(LOG_TAG, "I", "AA bluetooth adapter advertised %s", bluetoothAddress.c_str());
        } else {
            native_log::Log(LOG_TAG, "I", "AA bluetooth adapter not advertised");
        }

        response.set_head_unit_name("LibAuto");
        response.set_car_model("LibAuto");
        response.set_car_year("2018");
        response.set_car_serial("20180301");
        response.set_left_hand_drive_vehicle(true);
        response.set_headunit_manufacturer("stf_ftw");
        response.set_headunit_model("LibAuto");
        response.set_sw_build("1");
        response.set_sw_version("1.0");
        response.set_can_play_native_media_during_vr(false);
        response.set_hide_clock(false);

        auto promise = channel::SendPromise::defer(strand_);
        promise->then(
            [self = shared_from_this()]() {
                native_log::Log(LOG_TAG, "I", "AA service discovery response sent");
            },
            [self = shared_from_this()](const error::Error& e) { self->onChannelError(e); }
        );
        control->sendServiceDiscoveryResponse(response, std::move(promise));
        receiveAgain();
    }

    void sendHandshakeData() {
        if (cryptor_ == nullptr) {
            return;
        }
        auto control = control_.lock();
        if (control == nullptr) {
            return;
        }
        auto buffer = cryptor_->readHandshakeBuffer();
        if (buffer.empty()) {
            return;
        }
        native_log::Logf(LOG_TAG, "I",
                         "AA TLS handshake send %zu bytes %s",
                         buffer.size(),
                         hexDump(buffer.data(), buffer.size()).c_str());
        auto promise = channel::SendPromise::defer(strand_);
        promise->then(
            []() { native_log::Log(LOG_TAG, "I", "AA TLS handshake sent"); },
            [](const error::Error& e) {
                native_log::Logf(LOG_TAG, "E",
                                 "AA TLS handshake send failed code=%d native=%u",
                                 static_cast<int>(e.getCode()), e.getNativeCode());
            }
        );
        control->sendHandshake(std::move(buffer), std::move(promise));
    }

    void receiveAgain() {
        if (auto control = control_.lock()) {
            control->receive(shared_from_this());
        }
    }

    std::weak_ptr<channel::control::ControlServiceChannel> control_;
    std::weak_ptr<channel::av::VideoServiceChannel> video_;
    std::weak_ptr<channel::av::MediaAudioServiceChannel> media_audio_;
    std::weak_ptr<channel::av::SpeechAudioServiceChannel> speech_audio_;
    std::weak_ptr<channel::av::SystemAudioServiceChannel> system_audio_;
    std::weak_ptr<channel::av::AVInputServiceChannel> av_input_;
    std::weak_ptr<channel::input::InputServiceChannel> input_;
    std::weak_ptr<channel::sensor::SensorServiceChannel> sensor_;
    std::weak_ptr<channel::bluetooth::BluetoothServiceChannel> bluetooth_;
    std::shared_ptr<messenger::Cryptor> cryptor_;
    boost::asio::io_service::strand& strand_;
    bool handshake_started_ = false;
    bool auth_complete_sent_ = false;
    bool channel_failed_ = false;
};

bool startAaSessionWithTransport(
    std::shared_ptr<AaSession> session,
    std::shared_ptr<transport::Transport> transport,
    const char* label) {
    native_log::Logf(LOG_TAG, "I", "AA session starting transport=%s", label);
    g_video_frame_count.store(0);
    g_touch_event_count.store(0);
    g_touch_drop_count.store(0);
    g_touch_coalesce_count.store(0);
    resetTouchPipeline();
    g_button_event_count.store(0);
    session->transport = std::move(transport);
    session->ssl_wrapper = std::make_shared<transport::SSLWrapper>();
    session->cryptor = std::make_shared<messenger::Cryptor>(session->ssl_wrapper);
    session->in_stream = std::make_shared<messenger::MessageInStream>(
        session->io, session->transport, session->cryptor);
    session->out_stream = std::make_shared<messenger::MessageOutStream>(
        session->io, session->transport, session->cryptor);
    session->messenger = std::make_shared<messenger::Messenger>(
        session->io, session->in_stream, session->out_stream);
    session->control = std::make_shared<channel::control::ControlServiceChannel>(
        session->strand, session->messenger);
    session->video = std::make_shared<channel::av::VideoServiceChannel>(
        session->strand, session->messenger);
    session->media_audio = std::make_shared<channel::av::MediaAudioServiceChannel>(
        session->strand, session->messenger);
    session->speech_audio = std::make_shared<channel::av::SpeechAudioServiceChannel>(
        session->strand, session->messenger);
    session->system_audio = std::make_shared<channel::av::SystemAudioServiceChannel>(
        session->strand, session->messenger);
    session->av_input = std::make_shared<channel::av::AVInputServiceChannel>(
        session->strand, session->messenger);
    session->input = std::make_shared<channel::input::InputServiceChannel>(
        session->strand, session->messenger);
    session->sensor = std::make_shared<channel::sensor::SensorServiceChannel>(
        session->strand, session->messenger);
    session->bluetooth = std::make_shared<channel::bluetooth::BluetoothServiceChannel>(
        session->strand, session->messenger);

    session->video_handler = std::make_shared<AndroidVideoHandler>(session->video, session->strand);
    session->media_audio_handler = std::make_shared<AndroidAudioHandler>(
        session->media_audio, session->strand, "media audio", kAudioSinkMedia, true, kMediaAudioConfig, kMediaAudioMaxUnacked);
    session->speech_audio_handler = std::make_shared<AndroidAudioHandler>(
        session->speech_audio, session->strand, "speech audio", kAudioSinkSpeech, true, kSpeechAudioConfig);
    session->system_audio_handler = std::make_shared<AndroidAudioHandler>(
        session->system_audio, session->strand, "system audio", kAudioSinkSystem, true, kSystemAudioConfig);
    session->av_input_handler = std::make_shared<AndroidAudioInputHandler>(
        session->av_input, session->strand);
    session->input_handler = std::make_shared<AndroidInputHandler>(session->input, session->strand);
    session->sensor_handler = std::make_shared<AndroidSensorHandler>(session->sensor, session->strand);
    session->bluetooth_handler = std::make_shared<AndroidBluetoothHandler>(
        session->bluetooth, session->strand);
    session->control_handler = std::make_shared<AndroidControlHandler>(
        session->control,
        session->video,
        session->media_audio,
        session->speech_audio,
        session->system_audio,
        session->av_input,
        session->input,
        session->sensor,
        session->bluetooth,
        session->cryptor,
        session->strand
    );

    session->video->receive(session->video_handler);
    session->media_audio->receive(session->media_audio_handler);
    session->speech_audio->receive(session->speech_audio_handler);
    session->system_audio->receive(session->system_audio_handler);
    session->av_input->receive(session->av_input_handler);
    session->input->receive(session->input_handler);
    session->sensor->receive(session->sensor_handler);
    session->bluetooth->receive(session->bluetooth_handler);
    session->control->receive(session->control_handler);
    session->io_thread = std::thread([session]() {
        session->io.run();
    });

    auto promise = channel::SendPromise::defer(session->strand);
    promise->then(
        []() { native_log::Log(LOG_TAG, "I", "AA version request sent"); },
        [](const error::Error& e) {
            native_log::Logf(LOG_TAG, "E",
                             "AA version request failed code=%d native=%u",
                             static_cast<int>(e.getCode()), e.getNativeCode());
        }
    );
    session->control->sendVersionRequest(std::move(promise));

    g_session = std::move(session);
    return true;
}

bool startAaSession() {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    if (g_session != nullptr) {
        native_log::Log(LOG_TAG, "I", "AA session already running");
        return true;
    }

    auto session = std::make_shared<AaSession>();
    session->work_guard = std::make_unique<AaSession::WorkGuard>(session->io.get_executor());
    auto transport = std::make_shared<AndroidUsbTransport>(session->io);
    return startAaSessionWithTransport(std::move(session), std::move(transport), "usb");
}

bool startAaSessionOverTcp(uint16_t port) {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    if (g_session != nullptr) {
        native_log::Log(LOG_TAG, "I", "AA TCP start ignored: session already running");
        return true;
    }
    if (g_tcp_accepting.load()) {
        native_log::Log(LOG_TAG, "I", "AA TCP listener already running");
        return true;
    }
    if (g_tcp_accept_thread.joinable()) {
        g_tcp_accept_thread.join();
    }

    g_tcp_accepting.store(true);
    g_tcp_accept_thread = std::thread([port]() {
        auto session = std::make_shared<AaSession>();
        session->work_guard = std::make_unique<AaSession::WorkGuard>(session->io.get_executor());
        session->tcp_wrapper = std::make_shared<tcp::TCPWrapper>();
        session->tcp_socket = std::make_shared<boost::asio::ip::tcp::socket>(session->io);
        try {
            auto acceptor = std::make_shared<boost::asio::ip::tcp::acceptor>(session->io);
            {
                std::lock_guard<std::mutex> lock(g_session_mutex);
                g_tcp_acceptor = acceptor;
            }
            boost::asio::ip::tcp::endpoint endpoint(boost::asio::ip::tcp::v4(), port);
            boost::system::error_code ec;
            acceptor->open(endpoint.protocol(), ec);
            if (ec) {
                set_error("AA TCP listen open failed: " + ec.message());
                g_tcp_accepting.store(false);
                return;
            }
            acceptor->set_option(boost::asio::ip::tcp::acceptor::reuse_address(true), ec);
            acceptor->bind(endpoint, ec);
            if (ec) {
                set_error("AA TCP listen bind failed: " + ec.message());
                g_tcp_accepting.store(false);
                return;
            }
            acceptor->listen(boost::asio::socket_base::max_listen_connections, ec);
            if (ec) {
                set_error("AA TCP listen failed: " + ec.message());
                g_tcp_accepting.store(false);
                return;
            }
            native_log::Logf(LOG_TAG, "I", "AA TCP listening on 0.0.0.0:%u", port);
            acceptor->accept(*session->tcp_socket, ec);
            if (ec) {
                native_log::Logf(LOG_TAG, "W", "AA TCP accept stopped/failed: %s", ec.message().c_str());
                g_tcp_accepting.store(false);
                return;
            }
            native_log::Logf(LOG_TAG, "I",
                             "AA TCP accepted from %s",
                             session->tcp_socket->remote_endpoint().address().to_string().c_str());
            auto endpointPtr = std::make_shared<tcp::TCPEndpoint>(
                *session->tcp_wrapper,
                session->tcp_socket
            );
            auto transport = std::make_shared<transport::TCPTransport>(session->io, endpointPtr);
            {
                std::lock_guard<std::mutex> lock(g_session_mutex);
                if (g_session != nullptr) {
                    native_log::Log(LOG_TAG, "W", "AA TCP accepted but another session is active");
                    g_tcp_accepting.store(false);
                    return;
                }
                startAaSessionWithTransport(std::move(session), std::move(transport), "tcp");
            }
            g_tcp_accepting.store(false);
        } catch (const std::exception& e) {
            set_error(std::string("AA TCP listener exception: ") + e.what());
            g_tcp_accepting.store(false);
        }
        {
            std::lock_guard<std::mutex> lock(g_session_mutex);
            g_tcp_acceptor.reset();
        }
    });
    return true;
}

void stopAaSession() {
    std::shared_ptr<AaSession> session;
    std::shared_ptr<boost::asio::ip::tcp::acceptor> acceptor;
    {
        std::lock_guard<std::mutex> lock(g_session_mutex);
        session = g_session;
        g_session.reset();
        acceptor = g_tcp_acceptor;
    }
    if (acceptor != nullptr) {
        boost::system::error_code ec;
        acceptor->close(ec);
    }
    if (g_tcp_accept_thread.joinable() &&
        g_tcp_accept_thread.get_id() != std::this_thread::get_id()) {
        g_tcp_accept_thread.join();
    }
    g_tcp_accepting.store(false);
    if (session == nullptr) {
        return;
    }

    native_log::Log(LOG_TAG, "I", "AA session stopping");
    g_car_speed_sensor_started.store(false);
    g_navigation_focus_active.store(false);
    g_latest_car_speed_mps.store(0);
    g_touch_event_count.store(0);
    g_button_event_count.store(0);
    g_video_frame_count.store(0);
    g_touch_drop_count.store(0);
    g_touch_coalesce_count.store(0);
    resetTouchPipeline();
    stopVideoSink();
    stopAudioSink(kAudioSinkMedia);
    stopAudioSink(kAudioSinkSpeech);
    stopAudioSink(kAudioSinkSystem);
    stopMicInput();
    session->transport->stop();
    session->io.stop();
    session->work_guard.reset();
    if (session->io_thread.joinable()) {
        session->io_thread.join();
    }
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeInit(JNIEnv* env, jobject) {
    native_log::Log(LOG_TAG, "I", "nativeInit called");
    g_last_error.clear();
    if (env->GetJavaVM(&g_vm) != JNI_OK) {
        native_log::Log(LOG_TAG, "E", "Failed to acquire JavaVM");
        return JNI_FALSE;
    }
    if (!InitUsbJniBridge(env)) {
        native_log::Log(LOG_TAG, "W", "USB JNI bridge init skipped (env missing)");
    }
    {
        std::lock_guard<std::mutex> lock(g_jni_mutex);
        ensureProjectionSink(env);
    }
    installCrashHandlers();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeStart(JNIEnv*, jobject) {
    if (g_running.load()) {
        return JNI_TRUE;
    }
    g_running.store(true);
    native_log::Log(LOG_TAG, "I", "nativeStart called");
    g_worker = std::thread([]() {
        while (g_running.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1000));
        }
        native_log::Log(LOG_TAG, "I", "native loop stopped");
    });
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeSetLogPath(JNIEnv* env, jobject, jstring path) {
    if (path == nullptr) {
        return;
    }
    const char* chars = env->GetStringUTFChars(path, nullptr);
    native_log::SetLogFilePath(chars ? chars : "");
    env->ReleaseStringUTFChars(path, chars);
    native_log::Log(LOG_TAG, "I", "native log path set");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeSetVideoResolution(
    JNIEnv*,
    jobject,
    jint width,
    jint height,
    jint frameWidth,
    jint frameHeight,
    jint marginWidth,
    jint marginHeight,
    jint resolutionCode) {
    auto resolution = static_cast<proto::enums::VideoResolution::Enum>(resolutionCode);
    if (width <= 0 || height <= 0 || frameWidth <= 0 || frameHeight <= 0 ||
        marginWidth < 0 || marginHeight < 0 ||
        width + marginWidth > frameWidth || height + marginHeight > frameHeight ||
        !proto::enums::VideoResolution_Enum_IsValid(resolutionCode) ||
        resolution == proto::enums::VideoResolution::NONE) {
        width = kDefaultVideoWidth;
        height = kDefaultVideoHeight;
        frameWidth = kDefaultVideoWidth;
        frameHeight = kDefaultVideoHeight;
        marginWidth = 0;
        marginHeight = 0;
        resolution = proto::enums::VideoResolution::_720p;
    }
    g_video_width.store(width);
    g_video_height.store(height);
    g_video_frame_width.store(frameWidth);
    g_video_frame_height.store(frameHeight);
    g_video_margin_width.store(marginWidth);
    g_video_margin_height.store(marginHeight);
    g_video_resolution.store(static_cast<int32_t>(resolution));
    native_log::Logf(LOG_TAG, "I",
                     "AA video resolution set active=%dx%d frame=%dx%d margins=%dx%d resolution=%d",
                     static_cast<int>(width),
                     static_cast<int>(height),
                     static_cast<int>(frameWidth),
                     static_cast<int>(frameHeight),
                     static_cast<int>(marginWidth),
                     static_cast<int>(marginHeight),
                     static_cast<int>(resolution));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeSetVideoFps(
    JNIEnv*,
    jobject,
    jint fps) {
    const int32_t sanitized = fps == 30 ? 30 : 60;
    g_video_fps.store(sanitized);
    native_log::Logf(LOG_TAG, "I", "AA video fps set fps=%d", static_cast<int>(sanitized));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeSetMicrophonePermission(
    JNIEnv*,
    jobject,
    jboolean granted) {
    g_microphone_permission_granted.store(granted == JNI_TRUE);
    native_log::Logf(LOG_TAG, "I", "microphone permission granted=%d", granted == JNI_TRUE ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeReportProjectionStats(
    JNIEnv* env,
    jobject,
    jstring message) {
    if (message == nullptr) {
        return;
    }
    const char* chars = env->GetStringUTFChars(message, nullptr);
    native_log::Logf(LOG_TAG, "I", "ProjectionSink %s", chars ? chars : "");
    env->ReleaseStringUTFChars(message, chars);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeOnCarSpeed(
    JNIEnv*,
    jobject,
    jfloat speedMetersPerSecond) {
    const auto speed_mps = static_cast<int32_t>(std::lround(std::max(0.0f, speedMetersPerSecond)));
    g_latest_car_speed_mps.store(speed_mps);
    if (!g_car_speed_sensor_started.load() || !g_navigation_focus_active.load()) {
        return JNI_FALSE;
    }
    std::shared_ptr<AaSession> session;
    {
        std::lock_guard<std::mutex> lock(g_session_mutex);
        session = g_session;
    }
    if (session == nullptr) {
        return JNI_FALSE;
    }
    sendCarSpeedEvent(session, speed_mps);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeSendTouch(
    JNIEnv*,
    jobject,
    jint action,
    jint x,
    jint y,
    jint pointerId) {
    std::shared_ptr<AaSession> session;
    {
        std::lock_guard<std::mutex> lock(g_session_mutex);
        session = g_session;
    }
    if (session == nullptr || session->input == nullptr) {
        return JNI_FALSE;
    }
    sendTouchEvent(
        session,
        static_cast<int32_t>(action),
        static_cast<int32_t>(x),
        static_cast<int32_t>(y),
        static_cast<int32_t>(pointerId)
    );
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeSendButton(
    JNIEnv*,
    jobject,
    jint scanCode,
    jboolean pressed) {
    std::shared_ptr<AaSession> session;
    {
        std::lock_guard<std::mutex> lock(g_session_mutex);
        session = g_session;
    }
    if (session == nullptr || session->input == nullptr) {
        return JNI_FALSE;
    }
    sendButtonEvent(
        session,
        static_cast<int32_t>(scanCode),
        pressed == JNI_TRUE
    );
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeSendTouchMulti(
    JNIEnv*,
    jobject,
    jint action,
    jint actionIndex,
    jint pointerCount,
    jint x0,
    jint y0,
    jint pointerId0,
    jint x1,
    jint y1,
    jint pointerId1) {
    std::shared_ptr<AaSession> session;
    {
        std::lock_guard<std::mutex> lock(g_session_mutex);
        session = g_session;
    }
    if (session == nullptr || session->input == nullptr) {
        return JNI_FALSE;
    }
    std::vector<NativeTouchPoint> points;
    if (pointerCount >= 1) {
        points.push_back({
            static_cast<int32_t>(x0),
            static_cast<int32_t>(y0),
            static_cast<int32_t>(pointerId0)
        });
    }
    if (pointerCount >= 2) {
        points.push_back({
            static_cast<int32_t>(x1),
            static_cast<int32_t>(y1),
            static_cast<int32_t>(pointerId1)
        });
    }
    const bool queued = sendTouchEventMulti(
        session,
        static_cast<int32_t>(action),
        static_cast<int32_t>(actionIndex),
        std::move(points)
    );
    return queued ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeWarmJvmBindings(JNIEnv* env, jobject) {
    return warmJvmBindings(env) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeOnMicrophoneFrame(
    JNIEnv* env,
    jobject,
    jbyteArray data,
    jlong ptsUs) {
    if (data == nullptr) {
        return JNI_FALSE;
    }
    std::shared_ptr<AaSession> session;
    {
        std::lock_guard<std::mutex> lock(g_session_mutex);
        session = g_session;
    }
    if (session == nullptr || session->av_input_handler == nullptr) {
        return JNI_FALSE;
    }
    const auto length = env->GetArrayLength(data);
    if (length <= 0) {
        return JNI_FALSE;
    }
    common::Data payload(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(payload.data()));
    auto handler = std::dynamic_pointer_cast<AndroidAudioInputHandler>(session->av_input_handler);
    if (handler == nullptr) {
        return JNI_FALSE;
    }
    boost::asio::post(session->strand, [handler, payload = std::move(payload), ptsUs]() mutable {
        handler->onMicrophoneFrame(std::move(payload), static_cast<int64_t>(ptsUs));
    });
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeStartAaOverUsb(JNIEnv*, jobject) {
    native_log::Log(LOG_TAG, "I", "nativeStartAaOverUsb entered");
    g_last_error.clear();
    try {
        return startAaSession() ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        set_error(e.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeStartAaOverTcp(JNIEnv*, jobject, jint port) {
    native_log::Logf(LOG_TAG, "I", "nativeStartAaOverTcp entered port=%d", static_cast<int>(port));
    g_last_error.clear();
    try {
        if (port <= 0 || port > 65535) {
            set_error("Invalid TCP port for wireless Android Auto");
            return JNI_FALSE;
        }
        return startAaSessionOverTcp(static_cast<uint16_t>(port)) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        set_error(e.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeStopAaSession(JNIEnv*, jobject) {
    stopAaSession();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeStop(JNIEnv*, jobject) {
    stopAaSession();
    if (!g_running.load()) {
        return;
    }
    native_log::Log(LOG_TAG, "I", "nativeStop called");
    g_running.store(false);
    if (g_worker.joinable()) {
        g_worker.join();
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_androidautodisplay_AasdkNative_nativeGetLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}
