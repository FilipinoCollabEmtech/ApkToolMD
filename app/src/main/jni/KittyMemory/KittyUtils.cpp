#include "KittyUtils.h"

namespace KittyUtils {

uintptr_t calculateAbsoluteAddress(uintptr_t base, uintptr_t offset) {
    return base + offset;
}

void *alignPointer(void *ptr, size_t alignment) {
    uintptr_t addr = reinterpret_cast<uintptr_t>(ptr);
    uintptr_t mask = alignment - 1;
    return reinterpret_cast<void *>((addr + mask) & ~mask);
}

bool isAligned(uintptr_t address, size_t alignment) {
    return (address & (alignment - 1)) == 0;
}

} // namespace KittyUtils
