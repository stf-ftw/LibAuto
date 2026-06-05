#include "UsbTransport.h"

#include <android/log.h>
#include <jni.h>
#include <mutex>
#include <shared_mutex>

#include "NativeLog.h"

#define USB_LOG_TAG "UsbTransport"

namespace {
JavaVM* g_vm = nullptr;
jclass g_bridge_class = nullptr;
jmethodID g_open_vid_pid = nullptr;
jmethodID g_open_name = nullptr;
jmethodID g_read = nullptr;
jmethodID g_write = nullptr;
jmethodID g_close = nullptr;
jmethodID g_transport_stalled = nullptr;
std::mutex g_bridge_mutex;
std::shared_mutex g_connection_mutex;

struct EnvHolder {
    JNIEnv* env;
    bool attached;
};

EnvHolder get_env() {
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

bool ensure_bridge(JNIEnv* env) {
    if (g_bridge_class != nullptr) {
        return true;
    }
    jclass local = env->FindClass("com/example/androidautodisplay/UsbJniBridge");
    if (local == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, USB_LOG_TAG, "UsbJniBridge class not found");
        return false;
    }
    g_bridge_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_open_vid_pid = env->GetStaticMethodID(g_bridge_class, "usbOpen", "(II)Z");
    g_open_name = env->GetStaticMethodID(g_bridge_class, "usbOpen", "(Ljava/lang/String;)Z");
    g_read = env->GetStaticMethodID(g_bridge_class, "usbRead", "([BI)I");
    g_write = env->GetStaticMethodID(g_bridge_class, "usbWrite", "([BII)I");
    g_close = env->GetStaticMethodID(g_bridge_class, "usbClose", "()V");
    g_transport_stalled = env->GetStaticMethodID(g_bridge_class, "usbTransportStalled", "()V");
    return g_open_vid_pid && g_open_name && g_read && g_write && g_close &&
        g_transport_stalled;
}
}

bool InitUsbJniBridge(void* env_ptr) {
    auto* env = reinterpret_cast<JNIEnv*>(env_ptr);
    if (env == nullptr) {
        return false;
    }
    if (env->GetJavaVM(&g_vm) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, USB_LOG_TAG, "Failed to get JavaVM");
        return false;
    }
    std::lock_guard<std::mutex> lock(g_bridge_mutex);
    return ensure_bridge(env);
}

bool UsbTransport::OpenByVidPid(int vid, int pid) {
    std::unique_lock<std::shared_mutex> connection_lock(g_connection_mutex);
    auto holder = get_env();
    JNIEnv* env = holder.env;
    if (env == nullptr) {
        return false;
    }
    {
        std::lock_guard<std::mutex> bridge_lock(g_bridge_mutex);
        if (!ensure_bridge(env)) {
            return false;
        }
    }
    jboolean ok = env->CallStaticBooleanMethod(g_bridge_class, g_open_vid_pid, vid, pid);
    native_log::Logf(USB_LOG_TAG, "I", "usbOpen vid=%d pid=%d => %d", vid, pid, ok);
    native_log::LogJniException(env, "usbOpen vid/pid");
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
    return ok == JNI_TRUE;
}

bool UsbTransport::OpenByDeviceName(const std::string& device_name) {
    std::unique_lock<std::shared_mutex> connection_lock(g_connection_mutex);
    auto holder = get_env();
    JNIEnv* env = holder.env;
    if (env == nullptr) {
        return false;
    }
    {
        std::lock_guard<std::mutex> bridge_lock(g_bridge_mutex);
        if (!ensure_bridge(env)) {
            return false;
        }
    }
    jstring name = env->NewStringUTF(device_name.c_str());
    jboolean ok = env->CallStaticBooleanMethod(g_bridge_class, g_open_name, name);
    env->DeleteLocalRef(name);
    native_log::Logf(USB_LOG_TAG, "I", "usbOpen name=%s => %d", device_name.c_str(), ok);
    native_log::LogJniException(env, "usbOpen name");
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
    return ok == JNI_TRUE;
}

int UsbTransport::Read(uint8_t* buffer, int length, int timeout_ms) {
    std::shared_lock<std::shared_mutex> connection_lock(g_connection_mutex);
    auto holder = get_env();
    JNIEnv* env = holder.env;
    if (env == nullptr) {
        return -1;
    }
    {
        std::lock_guard<std::mutex> bridge_lock(g_bridge_mutex);
        if (!ensure_bridge(env)) {
            return -1;
        }
    }
    jbyteArray arr = env->NewByteArray(length);
    jint result = env->CallStaticIntMethod(g_bridge_class, g_read, arr, timeout_ms);
    if (native_log::LogJniException(env, "usbRead")) {
        result = -99;
    } else if (result > 0) {
        env->GetByteArrayRegion(arr, 0, result, reinterpret_cast<jbyte*>(buffer));
    }
    env->DeleteLocalRef(arr);
    if (!first_read_logged_ && result > 0) {
        first_read_logged_ = true;
        native_log::Logf(USB_LOG_TAG, "I", "First IN read len=%d", result);
    }
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
    return result;
}

int UsbTransport::Write(const uint8_t* buffer, int length, int timeout_ms) {
    std::shared_lock<std::shared_mutex> connection_lock(g_connection_mutex);
    auto holder = get_env();
    JNIEnv* env = holder.env;
    if (env == nullptr) {
        return -1;
    }
    {
        std::lock_guard<std::mutex> bridge_lock(g_bridge_mutex);
        if (!ensure_bridge(env)) {
            return -1;
        }
    }
    jbyteArray arr = env->NewByteArray(length);
    env->SetByteArrayRegion(arr, 0, length, reinterpret_cast<const jbyte*>(buffer));
    jint result = env->CallStaticIntMethod(g_bridge_class, g_write, arr, length, timeout_ms);
    env->DeleteLocalRef(arr);
    if (native_log::LogJniException(env, "usbWrite")) {
        result = -99;
    }
    if (!first_write_logged_) {
        first_write_logged_ = true;
        native_log::Logf(USB_LOG_TAG, "I", "First OUT write len=%d result=%d", length, result);
    }
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
    return result;
}

void UsbTransport::Close() {
    std::unique_lock<std::shared_mutex> connection_lock(g_connection_mutex);
    auto holder = get_env();
    JNIEnv* env = holder.env;
    if (env == nullptr) {
        return;
    }
    {
        std::lock_guard<std::mutex> bridge_lock(g_bridge_mutex);
        if (!ensure_bridge(env)) {
            return;
        }
    }
    env->CallStaticVoidMethod(g_bridge_class, g_close);
    native_log::LogJniException(env, "usbClose");
    native_log::Log(USB_LOG_TAG, "I", "usbClose");
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
}

void UsbTransport::NotifyTransportStalled() {
    std::shared_lock<std::shared_mutex> connection_lock(g_connection_mutex);
    auto holder = get_env();
    JNIEnv* env = holder.env;
    if (env == nullptr) {
        return;
    }
    {
        std::lock_guard<std::mutex> bridge_lock(g_bridge_mutex);
        if (!ensure_bridge(env)) {
            return;
        }
    }
    env->CallStaticVoidMethod(g_bridge_class, g_transport_stalled);
    native_log::LogJniException(env, "usbTransportStalled");
    native_log::Log(USB_LOG_TAG, "I", "usbTransportStalled");
    if (holder.attached) {
        g_vm->DetachCurrentThread();
    }
}
