#include "Menu/Setup.hpp"
#include <sstream>
#include "../Includes/obfuscate.h"
#include "../Includes/Logger.h"
#include "../Includes/Utils.hpp"
#include "Menu/Menu.hpp"

void Init(JNIEnv *env, jobject thiz, jobject ctx, jobject title, jobject bottomTitle) {
    Menu::setText(env, title, OBFUSCATE("R3D NETWORK ID"));
    Menu::setText(env, bottomTitle, OBFUSCATE("Modded by rrosetta | Platinmods.com"));

    Menu::showToast(env, ctx, OBFUSCATE("R3D Network ID"), 1);
}

jstring Icon(JNIEnv *env, jobject thiz) {
    return env->NewStringUTF(OBFUSCATE(""));
}

int RegisterMenu(JNIEnv *env) {
    jclass clazz = env->FindClass(OBFUSCATE("com/fluxin/apktoolmd/support/Menu"));
    if (!clazz) {
        LOGE(OBFUSCATE("Menu class not found"));
        return JNI_ERR;
    }

    static const JNINativeMethod methods[] = {
            {OBFUSCATE("Init"), OBFUSCATE("(Landroid/content/Context;Landroid/widget/TextView;Landroid/widget/TextView;)V"), reinterpret_cast<void *>(Init)},
            {OBFUSCATE("Icon"), OBFUSCATE("()Ljava/lang/String;"), reinterpret_cast<void *>(Icon)},
    };

    jint ret = env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(JNINativeMethod));
    if (ret != JNI_OK) {
        return ret;
    }

    LOGI(OBFUSCATE("Menu registered"));
    return JNI_OK;
}
