#ifndef HOOKS_HPP
#define HOOKS_HPP

#include <cstdint>
#include <vector>
#include <string>

class Hooks {
public:
    static void InitHooks();
    static void (*orig_PlayerUpdate)(void *pInstance);
    static void PlayerUpdate(void *pInstance);
};

#endif
