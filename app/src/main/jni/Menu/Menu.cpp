#include "Menu/Menu.hpp"
#include "Includes/obfuscate.h"

void Menu::setText(JNIEnv *env, jobject obj, const char *text) {
    jclass html = env->FindClass(OBFUSCATE("android/text/Html"));
    jmethodID fromHtml = env->GetStaticMethodID(html, OBFUSCATE("fromHtml"),
            OBFUSCATE("(Ljava/lang/String;)Landroid/text/Spanned;"));

    jclass textView = env->FindClass(OBFUSCATE("android/widget/TextView"));
    jmethodID setText = env->GetMethodID(textView, OBFUSCATE("setText"),
                                         OBFUSCATE("(Ljava/lang/CharSequence;)V"));

    jstring jstr = env->NewStringUTF(text);
    env->CallVoidMethod(obj, setText, env->CallStaticObjectMethod(html, fromHtml, jstr));
}

void Menu::showDialog(jobject ctx, JNIEnv *env, const char *title, const char *msg) {
    jclass alertBuilder = env->FindClass(OBFUSCATE("android/app/AlertDialog$Builder"));
    jmethodID alertCons = env->GetMethodID(alertBuilder, OBFUSCATE("<init>"),
                                           OBFUSCATE("(Landroid/content/Context;)V"));

    jobject mainAlert = env->NewObject(alertBuilder, alertCons, ctx);

    jmethodID setTitle = env->GetMethodID(alertBuilder, OBFUSCATE("setTitle"),
                                          OBFUSCATE("(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;"));
    env->CallObjectMethod(mainAlert, setTitle, env->NewStringUTF(title));

    jmethodID setMsg = env->GetMethodID(alertBuilder, OBFUSCATE("setMessage"),
                                        OBFUSCATE("(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;"));
    env->CallObjectMethod(mainAlert, setMsg, env->NewStringUTF(msg));

    jmethodID setCa = env->GetMethodID(alertBuilder, OBFUSCATE("setCancelable"),
                                       OBFUSCATE("(Z)Landroid/app/AlertDialog$Builder;"));
    env->CallObjectMethod(mainAlert, setCa, false);

    jmethodID setPB = env->GetMethodID(alertBuilder, OBFUSCATE("setPositiveButton"),
                                       OBFUSCATE("(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Landroid/app/AlertDialog$Builder;"));
    env->CallObjectMethod(mainAlert, setPB, env->NewStringUTF("Ok"), nullptr);

    jmethodID create = env->GetMethodID(alertBuilder, OBFUSCATE("create"),
                                        OBFUSCATE("()Landroid/app/AlertDialog;"));
    jobject alertObj = env->CallObjectMethod(mainAlert, create);

    jclass alertClass = env->FindClass(OBFUSCATE("android/app/AlertDialog"));
    jmethodID show = env->GetMethodID(alertClass, OBFUSCATE("show"), OBFUSCATE("()V"));
    env->CallVoidMethod(alertObj, show);
}

void Menu::showToast(JNIEnv *env, jobject thiz, const char *text, int length) {
    jstring jstr = env->NewStringUTF(text);
    jclass html = env->FindClass(OBFUSCATE("android/text/Html"));
    jmethodID fromHtml = env->GetStaticMethodID(html, OBFUSCATE("fromHtml"),
            OBFUSCATE("(Ljava/lang/String;)Landroid/text/Spanned;"));
    jclass toast = env->FindClass(OBFUSCATE("android/widget/Toast"));
    jmethodID methodMakeText = env->GetStaticMethodID(toast, OBFUSCATE("makeText"),
            OBFUSCATE("(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;"));
    jobject toastObj = env->CallStaticObjectMethod(toast, methodMakeText, thiz,
                                                   env->CallStaticObjectMethod(html, fromHtml, jstr), length);
    jmethodID methodShow = env->GetMethodID(toast, OBFUSCATE("show"), OBFUSCATE("()V"));
    env->CallVoidMethod(toastObj, methodShow);
}
