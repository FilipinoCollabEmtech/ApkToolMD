#ifndef OBSCUREDTYPES_HPP
#define OBSCUREDTYPES_HPP

#include <cstdint>

class ObscuredTypes {
public:
    static void SetObscuredFloat(uintptr_t address, float value);
    static float GetObscuredFloat(uintptr_t address);
};

#endif
