#include "Utils.hpp"
#include <android/log.h>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <sstream>

#define LOG_TAG "Utils"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

uintptr_t Utils::findLibraryBase(const char *libraryName) {
    std::string mapsPath = "/proc/self/maps";
    std::ifstream maps(mapsPath);
    if (!maps.is_open()) return 0;
    std::string line;
    while (std::getline(maps, line)) {
        if (line.find(libraryName) == std::string::npos) continue;
        unsigned long long start = 0;
        sscanf(line.c_str(), "%llx-%*x %*s %*x %*s %*x %*s", &start);
        return (uintptr_t)start;
    }
    return 0;
}

bool Utils::isPtrReadable(uintptr_t address, size_t len) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return false;
    char line[1024];
    bool result = false;
    while (fgets(line, sizeof(line), f)) {
        unsigned long long start = 0, end = 0;
        char perms[8] = {0};
        if (sscanf(line, "%llx-%llx %7s", &start, &end, perms) < 3) continue;
        if (address >= (uintptr_t)start && (address + len) <= (uintptr_t)end) {
            result = (strchr(perms, 'r') != nullptr);
            break;
        }
    }
    fclose(f);
    return result;
}

bool Utils::isPtrWritable(uintptr_t address, size_t len) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return false;
    char line[1024];
    bool result = false;
    while (fgets(line, sizeof(line), f)) {
        unsigned long long start = 0, end = 0;
        char perms[8] = {0};
        if (sscanf(line, "%llx-%llx %7s", &start, &end, perms) < 3) continue;
        if (address >= (uintptr_t)start && (address + len) <= (uintptr_t)end) {
            result = (strchr(perms, 'w') != nullptr);
            break;
        }
    }
    fclose(f);
    return result;
}

std::string Utils::readString(uintptr_t address, size_t maxLen) {
    std::string result;
    if (!isPtrReadable(address, maxLen)) return result;
    const char *ptr = reinterpret_cast<const char *>(address);
    for (size_t i = 0; i < maxLen; i++) {
        char c = ptr[i];
        if (c == '\0') break;
        result += c;
    }
    return result;
}
