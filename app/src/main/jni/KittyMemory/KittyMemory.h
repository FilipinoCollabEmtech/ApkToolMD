#ifndef KITTYMEMORY_H
#define KITTYMEMORY_H

#include <cstdint>
#include <cstddef>
#include <string>
#include <vector>
#include <memory>

namespace KittyMemory {

class MapInfo {
public:
    uintptr_t start = 0;
    uintptr_t end = 0;
    uintptr_t offset = 0;
    uint64_t inode = 0;
    std::string path;
    std::string dev;
    std::string perms;
};

bool getLibraryMap(const char *libraryName, MapInfo &outInfo);
uintptr_t getLibraryBase(const char *libraryName);
bool isPtrReadable(uintptr_t address, size_t len);
bool isPtrWritable(uintptr_t address, size_t len);
ssize_t readMemory(uintptr_t address, void *buffer, size_t len);
ssize_t writeMemory(uintptr_t address, const void *buffer, size_t len);

template <typename T>
bool readPtr(uintptr_t address, T &out) {
    return readMemory(address, &out, sizeof(T)) == sizeof(T);
}

template <typename T>
bool writePtr(uintptr_t address, const T &value) {
    return writeMemory(address, &value, sizeof(T)) == sizeof(T);
}

} // namespace KittyMemory

#endif
