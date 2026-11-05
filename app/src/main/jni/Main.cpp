#include <list>
#include <vector>
#include <cstring>
#include <pthread.h>
#include <thread>
#include <cstring>
#include <jni.h>
#include <unistd.h>
#include <fstream>
#include <iostream>
#include <dlfcn.h>

#include "Includes/Logger.h"
#include "Includes/obfuscate.h"
#include "dobby/dobby.h"
#include "Menu/FeatureModule.hpp"
#include "UnityResolve/UnityResolve.hpp"
#include "Includes/Utils.hpp"
#include "Includes/RemapTools.h"
#include "KittyMemory/MemoryPatch.h"
#include "Includes/ObscuredTypes.hpp"
#include "Includes/ESPManager.h"
#include "Menu/JNILoader.hpp"
#include "Menu/Setup.hpp"
#include "Hacks/Hooks.hpp"

#define IL2CPP_MODULE OBFUSCATE("libil2cpp.so")

void *hack_thread(void *) {
    LOGI(OBFUSCATE("pthread created"));

    ESPManager *espManager = new ESPManager();

    do {
        sleep(1);
    } while (!KittyMemory::getLibraryBase(IL2CPP_MODULE));

    LOGI(OBFUSCATE("%s has been loaded"), (const char *)IL2CPP_MODULE);
    LOGI(OBFUSCATE("Trying to hook in il2cpp now..."));

    sleep(5);

    auto p_handler = dlopen(IL2CPP_MODULE, RTLD_NOW);
    if (!p_handler) {
        LOGE(OBFUSCATE("Failed to dlopen %s: %s"), (const char *)IL2CPP_MODULE, dlerror());
        return NULL;
    }

    UnityResolve::Init(p_handler);
    Hooks::InitHooks();
    return NULL;
}

__attribute__((constructor))
void init() {
    pthread_t ptid;
    pthread_create(&ptid, NULL, hack_thread, NULL);
    RemapTools::RemapLibrary(OBFUSCATE("libLoader.so"));
}

extern "C"
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    vm->GetEnv((void **)&env, JNI_VERSION_1_6);

    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    if (JNILoader::RegisterAll(env) != JNI_OK)
        return JNI_ERR;

    if (RegisterMenu(env) != 0)
        return JNI_ERR;

    return JNI_VERSION_1_6;
}
