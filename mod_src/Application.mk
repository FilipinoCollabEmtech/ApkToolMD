APP_ABI := arm64-v8a
APP_PLATFORM := android-21
# The game APK does NOT ship libc++_shared.so - a .so that needs it
# kills the process at load time (the linker aborts).  Static STL.
APP_STL := c++_static
APP_CPPFLAGS := -frtti -fexceptions -fvisibility=hidden
APP_OPTIM := release