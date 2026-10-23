#include <jni.h>
#include <android/log.h>

#define LOG_TAG "AimMod"
#define LOGF(fmt, ...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, fmt, ##__VA_ARGS__)

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) __attribute__((visibility("default"))) {
    __android_log_print(ANDROID_LOG_INFO, "AimMod", "AimMod loaded (minimal)");
    return JNI_VERSION_1_6;
}
