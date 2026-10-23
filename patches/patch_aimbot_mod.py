import subprocess
import os
import shutil

# 1) Build aimbot_mod .so and ptrace_injector, stage into app assets
NDK = "/usr/local/lib/android/sdk/ndk/21.4.7075529"
mod_src = os.path.join(os.environ["GITHUB_WORKSPACE"], "mod_src")
assets_mod = os.path.join(os.environ["GITHUB_WORKSPACE"], "app/src/main/assets/mod")
assets_bin = os.path.join(os.environ["GITHUB_WORKSPACE"], "app/src/main/assets/bin")

os.makedirs(assets_mod, exist_ok=True)
os.makedirs(assets_bin, exist_ok=True)

result = subprocess.run([
    os.path.join(NDK, "ndk-build"), "-j4", "-B",
    "NDK_PROJECT_PATH=.",
    "NDK_APPLICATION_MK=./Application.mk",
    "APP_BUILD_SCRIPT=./Android.mk",
    "APP_ABI=arm64-v8a",
    "APP_OPTIM=release"
], cwd=mod_src, check=True)

print("=== built libs ===")
for root, dirs, files in os.walk(os.path.join(mod_src, "libs")):
    for f in files:
        print(os.path.join(root, f))

# Copy .so to assets/mod
for so_name in ["libaimbot_mod.so", "libcocos_dumper.so"]:
    src_so = os.path.join(mod_src, "libs/arm64-v8a", so_name)
    dst_so = os.path.join(assets_mod, so_name)
    if not os.path.exists(src_so):
        print(f"MISSING {src_so} - skipped")
        continue
    shutil.copy2(src_so, dst_so)
    print(f"Copied to {dst_so}")

# The injected .so must not need libc++_shared.so - the game APK does not
# ship it and the linker then kills the game at load time (it looks like a
# crash with no log at all).
import subprocess
for so_name in ["libcocos_dumper.so", "libaimbot_mod.so"]:
    src_so = os.path.join(mod_src, "libs/arm64-v8a", so_name)
    if not os.path.exists(src_so):
        continue
    out = subprocess.run(["/usr/local/lib/android/sdk/ndk/21.4.7075529/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf", "-d", src_so],
                         capture_output=True, text=True).stdout
    if "libc++_shared.so" in out:
        print(f"ERROR: {so_name} needs libc++_shared.so - the game would crash at load time")
        exit(1)
    print(f"{so_name}: no libc++_shared dependency")

# Copy ptrace_injector to assets/bin
src_injector = os.path.join(mod_src, "libs/arm64-v8a/ptrace_injector")
dst_injector = os.path.join(assets_bin, "ptrace_injector")
shutil.copy2(src_injector, dst_injector)
print(f"Copied to {dst_injector}")

# 2) Patch dumper to emit structured sdk_full.json from jsonFunctions
dumper_src = "/tmp/uecn/AndUEDumper/src/Dumper.cpp"
s = open(dumper_src).read()

# Find the anchor: script.json generation
anchor = '''    if (dumper_jf_ns::jsonFunctions.size())'''
if anchor not in s:
    print("Anchor not found")
    exit(1)

extra_dump = r'''
    // ---- Extra: dump structured sdk_full.json from jsonFunctions ----
    {
        json full_sdk;
        full_sdk["classes"] = json::object();

        uintptr_t baseAddr = _profile->GetUnrealELF().base();

        for (const auto& jf : dumper_jf_ns::jsonFunctions) {
            // jf.Name format: "ClassName$$FunctionName"
            std::string name = jf.Name;
            size_t delim = name.find("$$");
            std::string clsName = (delim != std::string::npos) ? name.substr(0, delim) : "Unknown";
            std::string funcName = (delim != std::string::npos) ? name.substr(delim + 2) : name;

            auto& jcls = full_sdk["classes"][clsName];
            if (jcls.is_null()) {
                jcls["functions"] = json::array();
            }
            json jf_obj;
            jf_obj["name"] = funcName;
            jf_obj["address"] = (uint64_t)jf.Address;
            jf_obj["rva"] = (uint64_t)(jf.Address - baseAddr);
            full_sdk["classes"][clsName]["functions"].push_back(jf_obj);
        }

        outBuffersMap->insert({"sdk_full.json", BufferFmt()});
        BufferFmt &fullBuf = outBuffersMap->at("sdk_full.json");
        fullBuf.append("{}", full_sdk.dump(4));
    }
'''

# Insert before script.json generation
s = s.replace(
    '    if (dumper_jf_ns::jsonFunctions.size())',
    extra_dump + '\n    if (dumper_jf_ns::jsonFunctions.size())',
    1
)
open(dumper_src, "w").write(s)
print("Dumper patched for sdk_full.json")