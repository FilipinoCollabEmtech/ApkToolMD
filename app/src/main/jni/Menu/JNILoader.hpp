#ifndef JNILOADER_HPP
#define JNILOADER_HPP

#include <jni.h>

class JNILoader {
public:
    static int RegisterAll(JNIEnv *env);

private:
    static int RegisterPreferences(JNIEnv *env);
    static int RegisterBaseMenu(JNIEnv *env);
    static int RegisterDrawView(JNIEnv *env);
    static jbyteArray LoadFontData(JNIEnv *env, jobject thiz, jobject ctx);
};

#endif
