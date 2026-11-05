#include "UnityResolve.hpp"
#include <dlfcn.h>
#include <cstring>

std::unordered_map<std::string, Assembly *> UnityResolve::s_assemblies;

void UnityResolve::Init(void *imageHandle) {
    Assembly *asmObj = new Assembly();
    asmObj->name = "Assembly-CSharp.dll";
    s_assemblies["Assembly-CSharp.dll"] = asmObj;
}

Class &Assembly::Get(const std::string &className) {
    auto it = classes.find(className);
    if (it == classes.end()) {
        Class c;
        c.name = className;
        classes.emplace(className, c);
        return classes.at(className);
    }
    return it->second;
}

Method &Class::Get(const std::string &methodName) {
    auto it = methods.find(methodName);
    if (it == methods.end()) {
        Method m;
        methods.emplace(methodName, m);
        return methods.at(methodName);
    }
    return it->second;
}

Field &Class::GetField(const std::string &fieldName) {
    auto it = fields.find(fieldName);
    if (it == fields.end()) {
        Field f;
        fields.emplace(fieldName, f);
        return fields.at(fieldName);
    }
    return it->second;
}

void Class::SetValue(void *instance, const std::string &fieldName, int value) {
    (void)instance; (void)fieldName; (void)value;
}

void Class::SetValue(void *instance, const std::string &fieldName, float value) {
    (void)instance; (void)fieldName; (void)value;
}

void Class::SetValue(void *instance, const std::string &fieldName, bool value) {
    (void)instance; (void)fieldName; (void)value;
}

Assembly *UnityResolve::Get(const std::string &imageName) {
    auto it = s_assemblies.find(imageName);
    if (it != s_assemblies.end()) return it->second;
    return nullptr;
}

void UnityResolve::Hook(const std::string &className, const std::string &methodName,
                        const std::vector<std::string> &argTypes,
                        void *detour, void **original) {
    (void)className; (void)methodName; (void)argTypes;
    (void)detour; (void)original;
    // Hook registration handled by Dobby/inline hooks at runtime
}
