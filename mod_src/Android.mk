LOCAL_PATH := $(call my-dir)

# ---------------------------------------------------------------------------
# libaimbot_mod - standalone aimbot module (JNI_OnLoad entry point).
# Staged into app assets but NOT injected into UE4 (pglarmor blocks a 2nd
# .so); kept for reference / the Cocos2d path.
# ---------------------------------------------------------------------------
include $(CLEAR_VARS)
LOCAL_MODULE := aimbot_mod
LOCAL_SRC_FILES := aimbot_mod.cpp
LOCAL_CPPFLAGS := -std=c++17 -fexceptions -frtti -O2 -fvisibility=hidden
LOCAL_LDLIBS := -llog -ldl
include $(BUILD_SHARED_LIBRARY)

# ---------------------------------------------------------------------------
# libcocos_dumper - the Cocos2D runtime dumper (inline hooks into
# cocos2d::FileUtils, xxtea_decrypt, luaL_loadbuffer).
# ---------------------------------------------------------------------------
include $(CLEAR_VARS)
LOCAL_MODULE := cocos_dumper
LOCAL_SRC_FILES := cocos_dumper.cpp
LOCAL_CPPFLAGS := -std=c++17 -fexceptions -frtti -O2 -fvisibility=hidden
LOCAL_CFLAGS := -fPIE
LOCAL_LDLIBS := -llog -ldl
include $(BUILD_SHARED_LIBRARY)

# ---------------------------------------------------------------------------
# ptrace injector (the "remote dlopen" injection path)
# ---------------------------------------------------------------------------
include $(CLEAR_VARS)
LOCAL_MODULE := ptrace_injector
LOCAL_SRC_FILES := ptrace_injector.cpp
LOCAL_CPPFLAGS := -std=c++17 -fexceptions -O2
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -fPIE
LOCAL_LDFLAGS := -fPIE -pie
include $(BUILD_EXECUTABLE)
