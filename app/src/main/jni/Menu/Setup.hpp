#ifndef SETUP_HPP
#define SETUP_HPP

#include <jni.h>

void Init(JNIEnv *env, jobject thiz, jobject ctx, jobject title, jobject bottomTitle);
jstring Icon(JNIEnv *env, jobject thiz);
int RegisterMenu(JNIEnv *env);

#endif
