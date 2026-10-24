// ============================================================================
// R3DNETWORK Mod Menu Injector for Cocos2D games.
// Ported to ApkToolMD build system (AGP 3.5.3 / NDK 21).
//
// Hooks target functions via inline "unpatch-and-call-in-place" (same pattern
// as cocos_dumper.so) and exposes feature variables that the Java-side mod
// menu UI can read/write via JNI.
// ============================================================================

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>
#include <string>
#include <mutex>

#define LOG_TAG "R3DModMenu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define MODMENU_LIB "/data/app/com.fluxin.apktoolmd/lib/arm64/libmodmenu.so"

// ---------------------------------------------------------------------------
// Feature variables (read/written by Java mod menu UI via JNI)
// ---------------------------------------------------------------------------
static struct {
    bool enabled = false;       // master switch
    bool godMode = false;       // invulnerability
    bool aimAssist = false;     // aim at nearest player
    bool esp = false;           // show enemy boxes
    bool speedHack = false;     // movement speed multiplier
    float speedMult = 1.5f;     // speed multiplier value
    bool noSpread = false;      // remove bullet spread
    bool autoFire = false;      // auto fire
    bool infiniteAmmo = false;  // unlimited ammo
    float fov = 90.0f;          // field of view override
} g_feat;

// ---------------------------------------------------------------------------
// Hook targets (Cocos2D addresses from analysis)
// ---------------------------------------------------------------------------
static uintptr_t g_libBase = 0;
static uintptr_t g_getPositionX = 0xc22d04;
static uintptr_t g_getPositionY = 0xc22d1c;
static uintptr_t g_getClosestPlayerLocation = 0x5ba500;

// Original instruction storage for inline hooks
static uint32_t g_savedGetPosX[4] = {0};
static uint32_t g_savedGetPosY[4] = {0};
static uint32_t g_savedGetClosest[4] = {0};
static bool g_hooked = false;

// ---------------------------------------------------------------------------
// Inline hook helpers (same "unpatch-and-call-in-place" as cocos_dumper.cpp)
// ---------------------------------------------------------------------------

static void log_line(const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    LOGI("%s", buf);
}

static void install_inline_hook(uintptr_t target, uint32_t* saved, void* stub) {
    if (!target) return;
    uint32_t insn[4] = {0x58000050u, 0xD61F0200u, 0, 0}; // LDR X16,#8 ; BR X16
    memcpy(&insn[2], &stub, sizeof(void*));
    if (target & 3) return;
    memcpy(saved, (void*)target, 16);
    long page = (long)(target & ~0xFFFul);
    if (mprotect((void*)page, 4096, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("mprotect failed for 0x%lx", target);
        return;
    }
    memcpy((void*)target, insn, 16);
    __builtin___clear_cache((char*)target, (char*)target + 16);
}

static void restore_inline_hook(uintptr_t target, uint32_t* saved) {
    if (!target) return;
    long page = (long)(target & ~0xFFFul);
    mprotect((void*)page, 4096, PROT_READ | PROT_WRITE | PROT_EXEC);
    memcpy((void*)target, saved, 16);
    __builtin___clear_cache((char*)target, (char*)target + 16);
}

// ---------------------------------------------------------------------------
// Hooked functions
// ---------------------------------------------------------------------------

typedef float (*GetPositionXFn)(void*);
typedef float (*GetPositionYFn)(void*);
typedef void* (*GetClosestPlayerFn)(void*, float, float);

static GetPositionXFn g_origGetPosX = nullptr;
static GetPositionYFn g_origGetPosY = nullptr;
static GetClosestPlayerFn g_origGetClosest = nullptr;

static float hookedGetPosX(void* self) {
    if (g_feat.enabled && g_feat.esp) {
        log_line("ESP: getPositionX called on 0x%p", self);
    }
    restore_inline_hook(g_getPositionX, g_savedGetPosX);
    float r = g_origGetPosX(self);
    install_inline_hook(g_getPositionX, g_savedGetPosX, (void*)hookedGetPosX);
    return r;
}

static float hookedGetPosY(void* self) {
    if (g_feat.enabled && g_feat.esp) {
        log_line("ESP: getPositionY called on 0x%p", self);
    }
    restore_inline_hook(g_getPositionY, g_savedGetPosY);
    float r = g_origGetPosY(self);
    install_inline_hook(g_getPositionY, g_savedGetPosY, (void*)hookedGetPosY);
    return r;
}

static void* hookedGetClosestPlayer(void* self, float x, float y) {
    if (g_feat.enabled && g_feat.aimAssist) {
        restore_inline_hook(g_getClosestPlayerLocation, g_savedGetClosest);
        void* nearest = g_origGetClosest(self, x, y);
        install_inline_hook(g_getClosestPlayerLocation, g_savedGetClosest,
                            (void*)hookedGetClosestPlayer);
        if (nearest) {
            log_line("AIM: nearest target at 0x%p", nearest);
        }
        return nearest;
    }
    restore_inline_hook(g_getClosestPlayerLocation, g_savedGetClosest);
    void* r = g_origGetClosest(self, x, y);
    install_inline_hook(g_getClosestPlayerLocation, g_savedGetClosest,
                        (void*)hookedGetClosestPlayer);
    return r;
}

// ---------------------------------------------------------------------------
// Symbol finding
// ---------------------------------------------------------------------------

static const char* g_search_needle = nullptr;
static uintptr_t g_search_result = 0;

static int scan_module_symbols(struct dl_phdr_info* info, size_t, void*) {
    const char* needle = g_search_needle;
    const char* name = info->dlpi_name ? info->dlpi_name : "";
    if (!strstr(name, "cocos")) return 0;
    for (int seg = 0; seg < info->dlpi_phnum; seg++) {
        const ElfW(Phdr)* ph = &info->dlpi_phdr[seg];
        if (ph->p_type != PT_DYNAMIC) continue;
        const ElfW(Dyn)* dyn = (const ElfW(Dyn)*)(info->dlpi_addr + ph->p_vaddr);
        const char* strtab = NULL;
        const ElfW(Sym)* symtab = NULL;
        size_t syment = sizeof(ElfW(Sym));
        const uint32_t* hash = NULL;
        for (; dyn->d_tag != DT_NULL; dyn++) {
            if (dyn->d_tag == DT_STRTAB) strtab = (const char*)info->dlpi_addr + dyn->d_un.d_ptr;
            else if (dyn->d_tag == DT_SYMTAB) symtab = (const ElfW(Sym)*)(info->dlpi_addr + dyn->d_un.d_ptr);
            else if (dyn->d_tag == DT_SYMENT) syment = dyn->d_un.d_val;
            else if (dyn->d_tag == DT_HASH) hash = (const uint32_t*)(info->dlpi_addr + dyn->d_un.d_ptr);
        }
        if (!strtab || !symtab || syment == 0) continue;
        size_t count = 0;
        if (hash) count = hash[1];
        if (count == 0) {
            count = (size_t)((strtab - (const char*)symtab) / syment);
            if (count > 200000) count = 200000;
        }
        if (count == 0) continue;
        for (size_t i = 0; i < count; i++) {
            const ElfW(Sym)* s = &symtab[i];
            if (!s->st_name || s->st_value == 0) continue;
            if (s->st_shndx == SHN_UNDEF) continue;
            const char* sym = strtab + s->st_name;
            if (!sym || !*sym) continue;
            if (strstr(sym, needle)) {
                g_search_result = info->dlpi_addr + s->st_value;
                return 1;
            }
        }
    }
    return 0;
}

static uintptr_t find_symbol(const char* needle) {
    void* sym = dlsym(RTLD_DEFAULT, needle);
    if (sym) return (uintptr_t)sym;
    g_search_needle = needle;
    g_search_result = 0;
    dl_iterate_phdr(scan_module_symbols, NULL);
    return g_search_result;
}

// ---------------------------------------------------------------------------
// Hook thread: waits for libcocos2dcpp.so, finds symbols, installs hooks
// ---------------------------------------------------------------------------

static void* hook_thread(void*) {
    LOGI("modmenu: hook thread started");

    // Wait for libcocos2dcpp.so to load
    uintptr_t libBase = 0;
    for (int i = 0; i < 480; i++) {
        libBase = find_symbol("cocos2d::CCNode::getPositionX");
        if (libBase) break;
        usleep(500000);
    }

    if (!libBase) {
        LOGE("modmenu: libcocos2dcpp.so not found - hooks disabled");
        return nullptr;
    }

    g_libBase = libBase;
    LOGI("modmenu: libcocos2dcpp.so base at 0x%lx", libBase);

    // Resolve function addresses
    g_getPositionX = find_symbol("cocos2d::CCNode::getPositionX");
    g_getPositionY = find_symbol("cocos2d::CCNode::getPositionY");
    g_getClosestPlayerLocation = find_symbol("CoOpStage::getClosestPlayerLocation");

    LOGI("modmenu: getPositionX=0x%lx getPositionY=0x%lx getClosestPlayerLocation=0x%lx",
         g_getPositionX, g_getPositionY, g_getClosestPlayerLocation);

    if (g_getPositionX && g_getPositionY) {
        g_origGetPosX = (GetPositionXFn)g_getPositionX;
        g_origGetPosY = (GetPositionYFn)g_getPositionY;
        install_inline_hook(g_getPositionX, g_savedGetPosX, (void*)hookedGetPosX);
        install_inline_hook(g_getPositionY, g_savedGetPosY, (void*)hookedGetPosY);
        LOGI("modmenu: hooked CCNode::getPositionX/Y");
    }

    if (g_getClosestPlayerLocation) {
        g_origGetClosest = (GetClosestPlayerFn)g_getClosestPlayerLocation;
        install_inline_hook(g_getClosestPlayerLocation, g_savedGetClosest,
                            (void*)hookedGetClosestPlayer);
        LOGI("modmenu: hooked CoOpStage::getClosestPlayerLocation");
    }

    g_hooked = true;
    LOGI("modmenu: hooks installed");

    while (true) {
        sleep(30);
    }
    return nullptr;
}

// ---------------------------------------------------------------------------
// JNI Exports (called from Java/Kotlin ModMenuLoader)
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isHooked(JNIEnv* env, jclass clazz) {
    return g_hooked ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setEnabled(JNIEnv* env, jclass clazz, jboolean enabled) {
    g_feat.enabled = enabled != JNI_FALSE;
    LOGI("modmenu: enabled=%d", g_feat.enabled ? 1 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isEnabled(JNIEnv* env, jclass clazz) {
    return g_feat.enabled ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setGodMode(JNIEnv* env, jclass clazz, jboolean val) {
    g_feat.godMode = val != JNI_FALSE;
    LOGI("modmenu: godMode=%d", g_feat.godMode ? 1 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isGodMode(JNIEnv* env, jclass clazz) {
    return g_feat.godMode ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setAimAssist(JNIEnv* env, jclass clazz, jboolean val) {
    g_feat.aimAssist = val != JNI_FALSE;
    LOGI("modmenu: aimAssist=%d", g_feat.aimAssist ? 1 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isAimAssist(JNIEnv* env, jclass clazz) {
    return g_feat.aimAssist ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setESP(JNIEnv* env, jclass clazz, jboolean val) {
    g_feat.esp = val != JNI_FALSE;
    LOGI("modmenu: esp=%d", g_feat.esp ? 1 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isESP(JNIEnv* env, jclass clazz) {
    return g_feat.esp ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setSpeedHack(JNIEnv* env, jclass clazz, jboolean val) {
    g_feat.speedHack = val != JNI_FALSE;
    LOGI("modmenu: speedHack=%d", g_feat.speedHack ? 1 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isSpeedHack(JNIEnv* env, jclass clazz) {
    return g_feat.speedHack ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setSpeedMult(JNIEnv* env, jclass clazz, jfloat val) {
    g_feat.speedMult = val;
    LOGI("modmenu: speedMult=%.2f", g_feat.speedMult);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_getSpeedMult(JNIEnv* env, jclass clazz) {
    return g_feat.speedMult;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setNoSpread(JNIEnv* env, jclass clazz, jboolean val) {
    g_feat.noSpread = val != JNI_FALSE;
    LOGI("modmenu: noSpread=%d", g_feat.noSpread ? 1 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isNoSpread(JNIEnv* env, jclass clazz) {
    return g_feat.noSpread ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setAutoFire(JNIEnv* env, jclass clazz, jboolean val) {
    g_feat.autoFire = val != JNI_FALSE;
    LOGI("modmenu: autoFire=%d", g_feat.autoFire ? 1 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isAutoFire(JNIEnv* env, jclass clazz) {
    return g_feat.autoFire ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setInfiniteAmmo(JNIEnv* env, jclass clazz, jboolean val) {
    g_feat.infiniteAmmo = val != JNI_FALSE;
    LOGI("modmenu: infiniteAmmo=%d", g_feat.infiniteAmmo ? 1 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isInfiniteAmmo(JNIEnv* env, jclass clazz) {
    return g_feat.infiniteAmmo ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setFOV(JNIEnv* env, jclass clazz, jfloat val) {
    g_feat.fov = val;
    LOGI("modmenu: fov=%.1f", g_feat.fov);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_getFOV(JNIEnv* env, jclass clazz) {
    return g_feat.fov;
}

// ---------------------------------------------------------------------------
// JNI_OnLoad: register natives + start hook thread
// ---------------------------------------------------------------------------

static JNINativeMethod g_methods[] = {
    {"isHooked", "()Z", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isHooked},
    {"setEnabled", "(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setEnabled},
    {"isEnabled", "()Z", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isEnabled},
    {"setGodMode", "(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setGodMode},
    {"isGodMode", "()Z", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isGodMode},
    {"setAimAssist", "(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setAimAssist},
    {"isAimAssist", "()Z", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isAimAssist},
    {"setESP", "(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setESP},
    {"isESP", "()Z", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isESP},
    {"setSpeedHack", "(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setSpeedHack},
    {"isSpeedHack", "()Z", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isSpeedHack},
    {"setSpeedMult", "(F)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setSpeedMult},
    {"getSpeedMult", "()F", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_getSpeedMult},
    {"setNoSpread", "(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setNoSpread},
    {"isNoSpread", "()Z", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isNoSpread},
    {"setAutoFire", "(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setAutoFire},
    {"isAutoFire", "()Z", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isAutoFire},
    {"setInfiniteAmmo", "(Z)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setInfiniteAmmo},
    {"isInfiniteAmmo", "()Z", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_isInfiniteAmmo},
    {"setFOV", "(F)V", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_setFOV},
    {"getFOV", "()F", (void*)Java_com_fluxin_apktoolmd_modmenu_ModMenuLoader_getFOV},
};

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("com/fluxin/apktoolmd/modmenu/ModMenuLoader");
    if (!cls) return JNI_ERR;
    env->RegisterNatives(cls, g_methods, sizeof(g_methods) / sizeof(g_methods[0]));

    pthread_t tid;
    pthread_create(&tid, nullptr, hook_thread, nullptr);
    pthread_detach(tid);

    LOGI("modmenu: JNI_OnLoad complete");
    return JNI_VERSION_1_6;
}
