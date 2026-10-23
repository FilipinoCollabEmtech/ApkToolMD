#!/bin/bash
# Build ApkToolMD on a Colab runtime (mirrors .github/workflows/android.yml).
#
# Everything is pulled from GitHub - nothing is uploaded to the runtime:
#   * the repo            -> the clone below
#   * the dumper sources  -> the clone below
#   * the signing key     -> the published release APK (it embeds
#                            assets/keystore/release.p12 + the passwords,
#                            because the app re-signs patched games)
#
# The script must run from the repo root (tools/colab_build.sh).
set -uo pipefail

T0=$(date +%s)
step() { echo "=== [$(($(date +%s)-T0))s] $* ==="; }
export DEBIAN_FRONTEND=noninteractive

SDK=/usr/local/lib/android/sdk
WORK=/content
REPO=$WORK/ApkToolMD
NDK="$SDK/ndk/21.4.7075529"
RELEASE_URL=https://github.com/FilipinoCollabEmtech/ApkToolMD/releases/download/latest/app-debug.apk

mkdir -p "$SDK"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

step "apt deps"
apt-get update -qq > /dev/null 2>&1
apt-get install -y -qq unzip zip autoconf automake libtool file curl git > /dev/null 2>&1

step "JDK 8 (AGP 3.5 / Gradle 6.1 needs it)"
if [ ! -x "$WORK/jdk8/bin/java" ]; then
  curl -L --retry 3 -o "$WORK/jdk8.tar.gz" "https://api.adoptium.net/v3/binary/latest/8/ga/linux/x64/jdk/hotspot/normal/eclipse" || exit 10
  mkdir -p "$WORK/jdk8" && tar xzf "$WORK/jdk8.tar.gz" -C "$WORK/jdk8" --strip-components=1
fi
"$WORK/jdk8/bin/java" -version 2>&1 | head -2

step "cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -L --retry 3 -o "$WORK/cmdline.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip || exit 11
  rm -rf "$WORK/cmdt" && mkdir -p "$WORK/cmdt"
  unzip -q "$WORK/cmdline.zip" -d "$WORK/cmdt"
  rm -rf "$SDK/cmdline-tools/latest"
  mkdir -p "$SDK/cmdline-tools"
  mv "$WORK/cmdt/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

step "sdk packages: platform-tools, platforms;android-29, build-tools;29.0.3, ndk;21.4.7075529"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-29" "build-tools;29.0.3" "ndk;21.4.7075529" || exit 12

step "the signing key (from the published release APK)"
if [ ! -f "$WORK/keystore/release.p12" ] || [ ! -f "$WORK/keystore/storepass" ] ||
   [ ! -f "$WORK/keystore/keypass" ] || [ ! -f "$WORK/keystore/alias" ]; then
  curl -L --retry 3 -o "$WORK/release.apk" "$RELEASE_URL" || exit 13
  rm -rf "$WORK/keyextract" && mkdir -p "$WORK/keyextract"
  unzip -o -q "$WORK/release.apk" 'assets/keystore/*' -d "$WORK/keyextract" || exit 14
  mkdir -p "$WORK/keystore"
  cp "$WORK/keyextract/assets/keystore/release.p12" "$WORK/keystore/release.p12"
  cp "$WORK/keyextract/assets/keystore/alias" "$WORK/keystore/alias"
  cp "$WORK/keyextract/assets/keystore/storepass" "$WORK/keystore/storepass"
  cp "$WORK/keyextract/assets/keystore/keypass" "$WORK/keystore/keypass"
fi
ALIAS="$(tr -d '\r\n' < "$WORK/keystore/alias")"
PASS="$(tr -d '\r\n' < "$WORK/keystore/storepass")"
if [ -z "$PASS" ] || [ -z "$ALIAS" ]; then
  echo "FATAL: no signing key/password (the release APK did not carry assets/keystore)"
  exit 9
fi
echo "alias: $ALIAS"

step "clone Android_UE_Dumper_CN"
rm -rf /tmp/uecn
git clone --recurse-submodules https://github.com/DreamFekk/Android_UE_Dumper_CN.git /tmp/uecn || exit 14

step "run the patch scripts"
export GITHUB_WORKSPACE="$REPO"
sed -i 's/^APP_ABI.*/APP_ABI := arm64-v8a/' /tmp/uecn/AndUEDumper/Application.mk
cp "$GITHUB_WORKSPACE/patches/SFG2.hpp" /tmp/uecn/AndUEDumper/src/UE/UEGameProfiles/SFG2.hpp
for p in patch_library patch_toast patch_perms patch_wrappers patch_aimbot_into_dumper patch_introspect patch_dump_guard patch_aimbot_mod; do
  echo "--- $p"
  python3 "$GITHUB_WORKSPACE/patches/$p.py" || exit 20
done

step "ndk-build the dumper .so (+ mod_src)"
cd /tmp/uecn/AndUEDumper
"$NDK/ndk-build" -j2 -B NDK_PROJECT_PATH=. NDK_APPLICATION_MK=./Application.mk APP_ABI=arm64-v8a APP_OPTIM=release || exit 21
find libs -type f | head

step "patchelf for arm64"
rm -rf /tmp/patchelf /tmp/patchelf-out
git clone --depth 1 https://github.com/NixOS/patchelf.git /tmp/patchelf || exit 30
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
export CC=$TC/bin/aarch64-linux-android21-clang
export CXX=$TC/bin/aarch64-linux-android21-clang++
export AR=$TC/bin/aarch64-linux-android-ar
export RANLIB=$TC/bin/aarch64-linux-android-ranlib
export LDFLAGS="-static"
cd /tmp/patchelf
autoreconf -fi > /dev/null 2>&1
./configure --host=aarch64-linux-android --prefix=/tmp/patchelf-out LDFLAGS="-static" > /dev/null 2>&1 || exit 31
make -j2 > /dev/null 2>&1 || exit 32
ls -la src/patchelf

step "stage the assets"
cd "$REPO"
mkdir -p app/src/main/assets/dumper app/src/main/jniLibs/arm64-v8a
cp /tmp/uecn/AndUEDumper/libs/arm64-v8a/libshared_UEDump3r_arm64.so app/src/main/assets/dumper/ 2>/dev/null
cp /tmp/uecn/AndUEDumper/libs/arm64-v8a/libshared_UEDump3r_arm64.so app/src/main/jniLibs/arm64-v8a/ 2>/dev/null
cp /tmp/uecn/AndUEDumper/libs/arm64-v8a/UEDump3r_arm64 app/src/main/assets/dumper/ 2>/dev/null
cp /tmp/patchelf/src/patchelf app/src/main/assets/dumper/ 2>/dev/null
ls -la app/src/main/assets/dumper/ app/src/main/jniLibs/arm64-v8a/

step "keystore (the APK is signed exactly like the CI one)"
mkdir -p "$REPO/keystore" "$REPO/app/src/main/assets/keystore"
cp "$WORK/keystore/release.p12" "$REPO/keystore/release.p12"
cp "$WORK/keystore/release.p12" "$REPO/app/src/main/assets/keystore/release.p12"
cp "$WORK/keystore/alias" "$REPO/app/src/main/assets/keystore/alias"
cp "$WORK/keystore/storepass" "$REPO/app/src/main/assets/keystore/storepass"
cp "$WORK/keystore/keypass" "$REPO/app/src/main/assets/keystore/keypass"
export KEYSTORE_FILE="$REPO/keystore/release.p12"
export KEYSTORE_PASSWORD="$PASS"
export KEY_ALIAS="$ALIAS"
export KEY_PASSWORD="$PASS"

step "gradle assembleDebug (JDK 8)"
export JAVA_HOME="$WORK/jdk8"
export PATH="$JAVA_HOME/bin:$PATH"
java -version 2>&1 | head -2
cd "$REPO"
chmod +x ./gradlew
./gradlew assembleDebug --no-daemon --stacktrace || exit 40

step "result"
APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"
ls -la "$APK"
APKSIGNER="$(find "$SDK/build-tools" -type f -name apksigner 2>/dev/null | head -1)"
"$APKSIGNER" verify --print-certs "$APK" 2>&1 | head -4

step "stage the artifacts"
rm -rf /content/out && mkdir -p /content/out
cp "$APK" /content/out/app-debug.apk
cp /tmp/uecn/AndUEDumper/libs/arm64-v8a/* /content/out/ 2>/dev/null
cp /tmp/patchelf/src/patchelf /content/out/ 2>/dev/null
ls -la /content/out/
step "DONE"
echo "=== DONE ==="
