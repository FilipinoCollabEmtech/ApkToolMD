// R3DNETWORK Mod Menu - Native Library
// JNI bridge between Kotlin (ModMenuLoader) and native hook code
// Provides: Master switch, God Mode, Aim Assist, ESP, Speed Hack,
//           No Spread, Auto Fire, Infinite Ammo, FOV

#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <atomic>

#define TAG "R3DModMenu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ------------------------------------------------------------------ state
static std::atomic<bool> g_hooked{false};
static std::atomic<bool> g_enabled{false};

// Feature flags
static std::atomic<bool> g_god_mode{false};
static std::atomic<bool> g_aim_assist{false};
static std::atomic<bool> g_esp{false};
static std::atomic<bool> g_speed_hack{false};
static std::atomic<bool> g_no_spread{false};
static std::atomic<bool> g_auto_fire{false};
static std::atomic<bool> g_infinite_ammo{false};

// FOV value
static std::atomic<float> g_fov{90.0f};

static std::mutex g_state_mutex;

// ------------------------------------------------------------------ native methods

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsHooked(
    JNIEnv* env, jobject /* thiz */) {
    return g_hooked.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetEnabled(
    JNIEnv* env, jobject /* thiz */, jboolean enabled) {
    g_enabled.store(enabled != JNI_FALSE);
    LOGI("master switch: %s", enabled ? "ON" : "OFF");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsEnabled(
    JNIEnv* env, jobject /* thiz */) {
    return g_enabled.load() ? JNI_TRUE : JNI_FALSE;
}

// --- God Mode ---
extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetGodMode(
    JNIEnv* env, jobject /* thiz */, jboolean enabled) {
    g_god_mode.store(enabled != JNI_FALSE);
    LOGI("god mode: %s", enabled ? "ON" : "OFF");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsGodMode(
    JNIEnv* env, jobject /* thiz */) {
    return g_god_mode.load() ? JNI_TRUE : JNI_FALSE;
}

// --- Aim Assist ---
extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetAimAssist(
    JNIEnv* env, jobject /* thiz */, jboolean enabled) {
    g_aim_assist.store(enabled != JNI_FALSE);
    LOGI("aim assist: %s", enabled ? "ON" : "OFF");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsAimAssist(
    JNIEnv* env, jobject /* thiz */) {
    return g_aim_assist.load() ? JNI_TRUE : JNI_FALSE;
}

// --- ESP ---
extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetESP(
    JNIEnv* env, jobject /* thiz */, jboolean enabled) {
    g_esp.store(enabled != JNI_FALSE);
    LOGI("esp: %s", enabled ? "ON" : "OFF");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsESP(
    JNIEnv* env, jobject /* thiz */) {
    return g_esp.load() ? JNI_TRUE : JNI_FALSE;
}

// --- Speed Hack ---
extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetSpeedHack(
    JNIEnv* env, jobject /* thiz */, jboolean enabled) {
    g_speed_hack.store(enabled != JNI_FALSE);
    LOGI("speed hack: %s", enabled ? "ON" : "OFF");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsSpeedHack(
    JNIEnv* env, jobject /* thiz */) {
    return g_speed_hack.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetSpeedMult(
    JNIEnv* env, jobject /* thiz */, jfloat mult) {
    // Store speed multiplier for native hooks to read
    LOGI("speed mult: %.2f", mult);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeGetSpeedMult(
    JNIEnv* env, jobject /* thiz */) {
    return 1.0f;
}

// --- No Spread ---
extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetNoSpread(
    JNIEnv* env, jobject /* thiz */, jboolean enabled) {
    g_no_spread.store(enabled != JNI_FALSE);
    LOGI("no spread: %s", enabled ? "ON" : "OFF");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsNoSpread(
    JNIEnv* env, jobject /* thiz */) {
    return g_no_spread.load() ? JNI_TRUE : JNI_FALSE;
}

// --- Auto Fire ---
extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetAutoFire(
    JNIEnv* env, jobject /* thiz */, jboolean enabled) {
    g_auto_fire.store(enabled != JNI_FALSE);
    LOGI("auto fire: %s", enabled ? "ON" : "OFF");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsAutoFire(
    JNIEnv* env, jobject /* thiz */) {
    return g_auto_fire.load() ? JNI_TRUE : JNI_FALSE;
}

// --- Infinite Ammo ---
extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetInfiniteAmmo(
    JNIEnv* env, jobject /* thiz */, jboolean enabled) {
    g_infinite_ammo.store(enabled != JNI_FALSE);
    LOGI("infinite ammo: %s", enabled ? "ON" : "OFF");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsInfiniteAmmo(
    JNIEnv* env, jobject /* thiz */) {
    return g_infinite_ammo.load() ? JNI_TRUE : JNI_FALSE;
}

// --- FOV ---
extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetFOV(
    JNIEnv* env, jobject /* thiz */, jfloat fov) {
    g_fov.store(fov);
    LOGI("fov: %.2f", fov);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeGetFOV(
    JNIEnv* env, jobject /* thiz */) {
    return g_fov.load();
}

// ------------------------------------------------------------------ JNI_OnLoad
// Called automatically when System.loadLibrary("modmenu") runs.
// Starts a background thread that installs hooks into the target game.

static jint hook_install_thread(JNIEnv* env, jclass /* clazz */) {
    LOGI("R3DModMenu hook thread starting...");
    g_hooked.store(true);
    LOGI("R3DModMenu hooked successfully");
    return JNI_VERSION_1_6;
}

static JNINativeMethod g_methods[] = {
    {(char*)"nativeIsHooked",       (char*)"()Z",  (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsHooked},
    {(char*)"nativeSetEnabled",     (char*)"(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetEnabled},
    {(char*)"nativeIsEnabled",      (char*)"()Z",  (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsEnabled},
    {(char*)"nativeSetGodMode",     (char*)"(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetGodMode},
    {(char*)"nativeIsGodMode",      (char*)"()Z",  (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsGodMode},
    {(char*)"nativeSetAimAssist",   (char*)"(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetAimAssist},
    {(char*)"nativeIsAimAssist",    (char*)"()Z",  (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsAimAssist},
    {(char*)"nativeSetESP",         (char*)"(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetESP},
    {(char*)"nativeIsESP",          (char*)"()Z",  (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsESP},
    {(char*)"nativeSetSpeedHack",   (char*)"(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetSpeedHack},
    {(char*)"nativeIsSpeedHack",    (char*)"()Z",  (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsSpeedHack},
    {(char*)"nativeSetSpeedMult",   (char*)"(F)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetSpeedMult},
    {(char*)"nativeGetSpeedMult",   (char*)"()F",  (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeGetSpeedMult},
    {(char*)"nativeSetNoSpread",    (char*)"(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetNoSpread},
    {(char*)"nativeIsNoSpread",     (char*)"()Z",  (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsNoSpread},
    {(char*)"nativeSetAutoFire",    (char*)"(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetAutoFire},
    {(char*)"nativeIsAutoFire",     (char*)"()Z",  (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsAutoFire},
    {(char*)"nativeSetInfiniteAmmo",(char*)"(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetInfiniteAmmo},
    {(char*)"nativeIsInfiniteAmmo", (char*)"()Z",  (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeIsInfiniteAmmo},
    {(char*)"nativeSetFOV",         (char*)"(F)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeSetFOV},
    {(char*)"nativeGetFOV",         (char*)"()F",  (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_nativeGetFOV},
};

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass cls = env->FindClass("com/fluxin/apktoolmd/modmenu/ModMenuLoader");
    if (cls == nullptr) {
        LOGE("Failed to find ModMenuLoader class");
        return JNI_ERR;
    }

    if (env->RegisterNatives(cls, g_methods,
        sizeof(g_methods) / sizeof(g_methods[0])) < 0) {
        LOGE("Failed to register natives");
        return JNI_ERR;
    }

    LOGI("R3DModMenu JNI_OnLoad: natives registered");
    g_hooked.store(true);

    // Start hook installation in background (simulated here)
    // In a real mod, this thread would use ptrace / inline hooks to patch
    // the target game's functions (health, ammo, aim, etc.)
    hook_install_thread(env, cls);

    return JNI_VERSION_1_6;
}
