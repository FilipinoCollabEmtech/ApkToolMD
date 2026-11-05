#ifndef UTILS_HPP
#define UTILS_HPP

#include <cstdint>
#include <cstddef>
#include <string>

class Utils {
public:
    static uintptr_t findLibraryBase(const char *libraryName);
    static bool isPtrReadable(uintptr_t address, size_t len);
    static bool isPtrWritable(uintptr_t address, size_t len);
    static std::string readString(uintptr_t address, size_t maxLen = 256);
};

#endif
