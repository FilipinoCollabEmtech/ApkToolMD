#!/bin/sh
# Load the cocos dumper into the running Mini Militia - the game, its APK
# and its libraries are not modified at all.
PKG=${1:-com.appsomniacs.mmc}
LIB=$(ls -d /data/app/$PKG*/lib/arm64 2>/dev/null | head -1)
if [ -z "$LIB" ]; then echo "the game is not installed (no $PKG)"; exit 1; fi
echo "lib dir: $LIB"
cp /sdcard/ApkToolMD/libcocos_dumper.so "$LIB/libcocos_dumper.so" || exit 2
chmod 755 "$LIB/libcocos_dumper.so"
chown --reference="$LIB/libcocos2dcpp.so" "$LIB/libcocos_dumper.so" 2>/dev/null
restorecon "$LIB/libcocos_dumper.so" 2>/dev/null
/sdcard/ApkToolMD/ptrace_injector "$LIB/libcocos_dumper.so" libcocos2dcpp.so
