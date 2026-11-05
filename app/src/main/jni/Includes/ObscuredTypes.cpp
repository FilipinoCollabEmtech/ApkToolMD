#include "ObscuredTypes.hpp"
#include <cstring>

void ObscuredTypes::SetObscuredFloat(uintptr_t address, float value) {
    float obfuscated = value ^ 3.1415926f;
    memcpy((void *)address, &obfuscated, sizeof(float));
}

float ObscuredTypes::GetObscuredFloat(uintptr_t address) {
    float obfuscated = 0.0f;
    memcpy(&obfuscated, (const void *)address, sizeof(float));
    return obfuscated ^ 3.1415926f;
}
