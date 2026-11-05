#ifndef UNITYRESOLVE_HPP
#define UNITYRESOLVE_HPP

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <unordered_map>
#include <dlfcn.h>
#include "dobby/dobby.h"

struct Method {
    void *function = nullptr;
};

struct Field {
    uintptr_t offset = 0;
};

struct Class {
    std::string name;
    std::unordered_map<std::string, Method> methods;
    std::unordered_map<std::string, Field> fields;

    Method &Get(const std::string &methodName);
    Field &GetField(const std::string &fieldName);
    template <typename T>
    T &Get() { return *reinterpret_cast<T *>(this); }
    void SetValue(void *instance, const std::string &fieldName, int value);
    void SetValue(void *instance, const std::string &fieldName, float value);
    void SetValue(void *instance, const std::string &fieldName, bool value);
};

struct Assembly {
    std::string name;
    std::unordered_map<std::string, Class> classes;

    Class &Get(const std::string &className);
};

class UnityResolve {
public:
    static void Init(void *imageHandle);
    static Assembly *Get(const std::string &imageName);
    static void Hook(const std::string &className, const std::string &methodName,
                     const std::vector<std::string> &argTypes,
                     void *detour, void **original);

private:
    static std::unordered_map<std::string, Assembly *> s_assemblies;
};

#endif
