#ifndef MENU_HPP
#define MENU_HPP

#include <jni.h>

class Menu {
public:
    static void setText(JNIEnv *env, jobject obj, const char *text);
    static void showDialog(jobject ctx, JNIEnv *env, const char *title, const char *msg);
    static void showToast(JNIEnv *env, jobject thiz, const char *text, int length);
};

#endif
