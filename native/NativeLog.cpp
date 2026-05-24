#include "NativeLog.h"

#include <android/log.h>
#include <cstdarg>
#include <cstdio>
#include <ctime>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <sys/time.h>
#include <thread>
#include <sys/syscall.h>
#include <unistd.h>

namespace native_log {

namespace {
std::mutex g_log_mutex;
std::string g_log_path;
constexpr off_t kMaxNativeLogBytes = 512 * 1024;

std::string timestamp() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    std::time_t t = tv.tv_sec;
    struct tm tm_val;
    localtime_r(&t, &tm_val);
    char buffer[64];
    std::snprintf(buffer, sizeof(buffer), "%02d:%02d:%02d.%03ld",
                  tm_val.tm_hour, tm_val.tm_min, tm_val.tm_sec, tv.tv_usec / 1000);
    return std::string(buffer);
}

void write_line(const std::string& line) {
    if (g_log_path.empty()) {
        return;
    }
    struct stat st {};
    if (stat(g_log_path.c_str(), &st) == 0 && st.st_size > kMaxNativeLogBytes) {
        FILE* trim = std::fopen(g_log_path.c_str(), "w");
        if (trim != nullptr) {
            std::fputs("=== native log trimmed ===\n", trim);
            std::fclose(trim);
        }
    }
    FILE* fp = std::fopen(g_log_path.c_str(), "a");
    if (fp == nullptr) {
        return;
    }
    std::fputs(line.c_str(), fp);
    std::fputs("\n", fp);
    std::fclose(fp);
}
}

void SetLogFilePath(const std::string& path) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_log_path = path;
    if (!g_log_path.empty()) {
        FILE* fp = std::fopen(g_log_path.c_str(), "a");
        if (fp != nullptr) {
            std::fputs("\n=== native log session ===\n", fp);
            std::fclose(fp);
        }
    }
}

std::string GetLogFilePath() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return g_log_path;
}

void Log(const char* tag, const char* level, const std::string& message) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    std::string line = timestamp();
    line += " [";
    line += level;
    line += "] ";
    line += tag;
    line += " tid=";
    line += std::to_string(static_cast<long>(syscall(SYS_gettid)));
    line += " ";
    line += message;
    write_line(line);
    __android_log_print(ANDROID_LOG_INFO, tag, "%s", message.c_str());
}

void Logf(const char* tag, const char* level, const char* fmt, ...) {
    char buffer[1024];
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    Log(tag, level, buffer);
}

void LogJniException(JNIEnv* env, const char* context) {
    if (env == nullptr) {
        return;
    }
    if (env->ExceptionCheck() == JNI_FALSE) {
        return;
    }
    env->ExceptionDescribe();
    env->ExceptionClear();
    Logf("NativeLog", "E", "JNI exception cleared: %s", context);
}

}
