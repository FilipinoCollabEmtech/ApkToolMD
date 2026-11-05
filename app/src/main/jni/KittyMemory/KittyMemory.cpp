#include "KittyMemory.h"
#include <android/log.h>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <sstream>

#define LOG_TAG "KittyMemory"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace KittyMemory {

static bool parseMapsLine(const char *line, MapInfo &info) {
    char perms[8] = {0};
    char dev[16] = {0};
    char path[1024] = {0};
    unsigned long long start = 0, end = 0, offset = 0, inode = 0;
    int fields = sscanf(line, "%llx-%llx %7s %llx %15s %llu %1023s",
                        &start, &end, perms, &offset, dev, &inode, path);
    if (fields < 7) return false;
    info.start = (uintptr_t)start;
    info.end = (uintptr_t)end;
    info.offset = (uintptr_t)offset;
    info.inode = inode;
    info.path = path;
    info.dev = dev;
    info.perms = perms;
    return true;
}

bool getLibraryMap(const char *libraryName, MapInfo &outInfo) {
    std::string mapsPath = "/proc/self/maps";
    std::ifstream maps(mapsPath);
    if (!maps.is_open()) return false;
    std::string line;
    while (std::getline(maps, line)) {
        if (line.find(libraryName) == std::string::npos) continue;
        MapInfo info{};
        if (!parseMapsLine(line.c_str(), info)) continue;
        outInfo = info;
        return true;
    }
    return false;
}

uintptr_t getLibraryBase(const char *libraryName) {
    MapInfo info{};
    if (!getLibraryMap(libraryName, info)) return 0;
    return info.start;
}

bool isPtrReadable(uintptr_t address, size_t len) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return false;
    char line[1024];
    bool result = false;
    while (fgets(line, sizeof(line), f)) {
        MapInfo info{};
        if (!parseMapsLine(line, info)) continue;
        if (address >= info.start && (address + len) <= info.end) {
            if (info.perms.find('r') != std::string::npos) {
                result = true;
            }
            break;
        }
    }
    fclose(f);
    return result;
}

bool isPtrWritable(uintptr_t address, size_t len) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return false;
    char line[1024];
    bool result = false;
    while (fgets(line, sizeof(line), f)) {
        MapInfo info{};
        if (!parseMapsLine(line, info)) continue;
        if (address >= info.start && (address + len) <= info.end) {
            if (info.perms.find('w') != std::string::npos) {
                result = true;
            }
            break;
        }
    }
    fclose(f);
    return result;
}

ssize_t readMemory(uintptr_t address, void *buffer, size_t len) {
    if (!isPtrReadable(address, len)) return -1;
    memcpy(buffer, (const void *)address, len);
    return (ssize_t)len;
}

ssize_t writeMemory(uintptr_t address, const void *buffer, size_t len) {
    if (!isPtrWritable(address, len)) return -1;
    memcpy((void *)address, buffer, len);
    return (ssize_t)len;
}

} // namespace KittyMemory
