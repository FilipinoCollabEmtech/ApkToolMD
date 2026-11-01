LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := modmenu
LOCAL_SRC_FILES := modmenu.cpp

LOCAL_CFLAGS += -std=c++11 -frtti -fexceptions
LOCAL_LDLIBS    := -llog

include $(BUILD_SHARED_LIBRARY)
