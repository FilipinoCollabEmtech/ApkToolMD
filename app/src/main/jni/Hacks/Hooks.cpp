//
// Created by github.com/seedhollow on 17/10/25.
//

#include "Hooks.hpp"
#include "Includes/Logger.h"
#include "Includes/obfuscate.h"
#include "UnityResolve/UnityResolve.hpp"
#include "Includes/ObscuredTypes.hpp"

void (*Hooks::orig_PlayerUpdate)(void *pInstance) = nullptr;

void Hooks::PlayerUpdate(void *pInstance) {
    if (true) {
        uintptr_t healthOffset = 0x12;
        ObscuredTypes::SetObscuredFloat(reinterpret_cast<uintptr_t>(pInstance) + healthOffset, 1000.0f);
    }

    if (orig_PlayerUpdate) {
        orig_PlayerUpdate(pInstance);
    }
}

void Hooks::InitHooks() {
    // Hooks will be installed here via UnityResolve or DobbyHook
    LOGI(OBFUSCATE("Hooks initialized"));
}
