#include <jni.h>
#include <android/log.h>

#define LOG_TAG "HelloTest"
#define LOGF(fmt, ...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, fmt, ##__VA_ARGS__)

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "HelloWorld .so loaded, returning JNI_ERR to test anti-tamper");
    return JNI_ERR; // Force load failure to see if game still launches
}