LOCAL_PATH := $(call my-dir)

# ---------------------------------------------------------------------------
# libmodmenu - R3DNETWORK mod menu injector for Cocos2D games.
# JNI_OnLoad entry point. Spawns a hook thread that:
#   1) Waits for libcocos2dcpp.so to load
#   2) Installs inline hooks on target functions (CCNode::getPositionX/Y,
#      CoOpStage::getClosestPlayerLocation, etc.)
#   3) Launches a floating overlay mod menu bubble
# ---------------------------------------------------------------------------
include $(CLEAR_VARS)
LOCAL_MODULE := modmenu
LOCAL_SRC_FILES := Main.cpp
LOCAL_CPPFLAGS := -std=c++17 -fexceptions -frtti -O2 -fvisibility=hidden
LOCAL_CFLAGS := -fPIE
LOCAL_LDLIBS := -llog -ldl
include $(BUILD_SHARED_LIBRARY)
