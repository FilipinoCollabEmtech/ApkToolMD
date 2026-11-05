#ifndef REMAPTOOLS_H
#define REMAPTOOLS_H

#include <cstdint>
#include <string>

struct ProcMapInfo {
    uintptr_t start = 0;
    uintptr_t end = 0;
    uintptr_t offset = 0;
    uint64_t inode = 0;
    std::string path;
};

class RemapTools {
public:
    static void RemapLibrary(const char *libraryName);
};

#endif
