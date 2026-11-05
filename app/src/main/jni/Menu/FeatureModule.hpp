#ifndef FEATUREMODULE_HPP
#define FEATUREMODULE_HPP

#include <jni.h>
#include <string>

struct Widget;

class FeatureModule {
public:
    static void OnDraw(JNIEnv *env, jclass clazz, jobject draw_view, jobject canvas);
    static jstring GetFeatureList(JNIEnv *env, jobject context);
    static void RegisterFeatures();
    static void OnFeatureChanged(JNIEnv *env, jclass clazz, jobject obj, jint featNum,
                                 jstring featName, jint value, jfloat floatValue,
                                 jboolean boolean, jstring str);
};

#endif
