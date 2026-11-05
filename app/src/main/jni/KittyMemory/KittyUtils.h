#ifndef KITTYUTILS_H
#define KITTYUTILS_H

#include <cstdint>
#include <cstddef>

namespace KittyUtils {

uintptr_t calculateAbsoluteAddress(uintptr_t base, uintptr_t offset);
void *alignPointer(void *ptr, size_t alignment);
bool isAligned(uintptr_t address, size_t alignment);

} // namespace KittyUtils

#endif
