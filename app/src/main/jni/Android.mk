LOCAL_PATH := $(call my-dir)

# Application.mk settings (inlined since AGP 3.5.3 ndkBuild DSL
# does not support arguments() for NDK_APPLICATION_MK)
APP_ABI := arm64-v8a
APP_PLATFORM := android-21
APP_STL := c++_static

# ---------------------------------------------------------------------------
# libmodmenu - R3DNETWORK mod menu injector for Cocos2D games.
# ---------------------------------------------------------------------------
include $(CLEAR_VARS)
LOCAL_MODULE := modmenu
LOCAL_SRC_FILES := Main.cpp
LOCAL_CPPFLAGS := -std=c++17 -fexceptions -frtti -O2 -fvisibility=hidden
LOCAL_CFLAGS := -fPIE -frtti -fexceptions -fvisibility=hidden
LOCAL_LDLIBS := -llog -ldl
include $(BUILD_SHARED_LIBRARY)
