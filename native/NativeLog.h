#pragma once

#include <jni.h>
#include <string>

namespace native_log {

void SetLogFilePath(const std::string& path);
void Log(const char* tag, const char* level, const std::string& message);
void Logf(const char* tag, const char* level, const char* fmt, ...);
bool LogJniException(JNIEnv* env, const char* context);
std::string GetLogFilePath();

}
