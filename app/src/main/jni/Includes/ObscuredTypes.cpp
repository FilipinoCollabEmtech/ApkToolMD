#include "ObscuredTypes.hpp"
#include <cstring>

void ObscuredTypes::SetObscuredFloat(uintptr_t address, float value) {
    uint32_t bits;
    memcpy(&bits, &value, sizeof(float));
    bits ^= 0x9E3779B9u;
    float obfuscated;
    memcpy(&obfuscated, &bits, sizeof(float));
    memcpy((void *)address, &obfuscated, sizeof(float));
}

float ObscuredTypes::GetObscuredFloat(uintptr_t address) {
    float obfuscated = 0.0f;
    memcpy(&obfuscated, (const void *)address, sizeof(float));
    uint32_t bits;
    memcpy(&bits, &obfuscated, sizeof(float));
    bits ^= 0x9E3779B9u;
    float result;
    memcpy(&result, &bits, sizeof(float));
    return result;
}
