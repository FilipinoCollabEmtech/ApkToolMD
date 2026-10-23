# Patch the dumper so its SDK dump cannot kill the game.
#
# The dump runs on its own thread, but it is extremely CPU heavy: it walks
# all 58k objects, resolves every full name and then generates the whole
# AIOHeader.  On the phone that starves the game thread, the frame rate
# collapses to ~3 FPS and the ANR watchdog SIGKILLs the process (a SIGKILL
# cannot be caught, which is why there was never a crash report).
#
# Two changes:
#   1) the dump can be switched off (nodump=1 in /sdcard/aimbot_config.txt)
#   2) the heavy loops are throttled so the game keeps its frame rate
#
# Run AFTER patch_aimbot_into_dumper.py (it adds the includes) and it needs
# the dumper sources, so it patches Dumper.cpp and library.cpp in place.

import os

DUMPER = "/tmp/uecn/AndUEDumper/src/Dumper.cpp"
LIB = "/tmp/uecn/AndUEDumper/src/library.cpp"

# ---------------------------------------------------------------- library.cpp
if os.path.exists(LIB):
    s = open(LIB).read()

    guard = '''
    // ---- ApkToolMD: the SDK dump is very CPU heavy and used to get the
    // game killed by the ANR watchdog (a SIGKILL, so no crash report).
    // It can be switched off with "nodump=1" in /sdcard/aimbot_config.txt.
    {
        FILE* cfg = fopen("/sdcard/aimbot_config.txt", "r");
        if (cfg)
        {
            char cfgLine[160];
            while (fgets(cfgLine, sizeof(cfgLine), cfg))
            {
                if (strncmp(cfgLine, "nodump=", 7) == 0 && atoi(cfgLine + 7) != 0)
                {
                    fclose(cfg);
                    LOGI("AIMBOT: SDK dump disabled by config (nodump=1)");
                    return;
                }
            }
            fclose(cfg);
        }
    }
'''

    anchor = '''    LOGI("Dump will start after %d seconds.", WAIT_TIME_SEC);
    sleep(WAIT_TIME_SEC);'''
    if anchor in s and "SDK dump disabled by config" not in s:
        s = s.replace(anchor, anchor + "\n" + guard, 1)
        # make sure the headers we use are there
        if "#include <cstring>" not in s:
            s = s.replace("#include <cstdio>", "#include <cstdio>\n#include <cstring>\n#include <cstdlib>", 1)
        open(LIB, "w").write(s)
        print("library.cpp: dump guarded by nodump=1")
    elif "SDK dump disabled by config" in s:
        print("library.cpp: dump guard already present")
    else:
        print("library.cpp: ANCHOR NOT FOUND")

# ---------------------------------------------------------------- Dumper.cpp
if os.path.exists(DUMPER):
    s = open(DUMPER).read()

    if "ApkToolMD: throttle" not in s:
        # 1) the object gathering loop
        old = '''        objectsProgress++;
        if (progressCallback)
            progressCallback(objectsProgress);
    }

    logsBufferFmt.append("Gathered {} Objects (Packages {})\\n", objectsCount, packages.size());'''
        new = '''        objectsProgress++;
        if (progressCallback)
            progressCallback(objectsProgress);

        // ApkToolMD: throttle the dump - it used to starve the game thread
        // and the ANR watchdog killed the process (SIGKILL, no crash report).
        if ((i & 0x1FF) == 0x1FF)
        {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    }

    logsBufferFmt.append("Gathered {} Objects (Packages {})\\n", objectsCount, packages.size());'''
        if old in s:
            s = s.replace(old, new, 1)
        else:
            print("Dumper.cpp: gather anchor NOT FOUND")

        # 2) the AIOHeader generation (the other heavy phase)
        old2 = '''    logsBufferFmt.append("Generating AIOHeader...\\n");'''
        new2 = '''    logsBufferFmt.append("Generating AIOHeader...\\n");
    // ApkToolMD: throttle the dump (see the note in the gather loop)
    {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }'''
        if old2 in s:
            s = s.replace(old2, new2, 1)
        else:
            print("Dumper.cpp: aio anchor NOT FOUND")

        if "#include <thread>" not in s:
            s = s.replace('#include <fmt/format.h>', '#include <fmt/format.h>\n#include <thread>\n#include <chrono>', 1)

        open(DUMPER, "w").write(s)
        print("Dumper.cpp: dump throttled")
    else:
        print("Dumper.cpp: throttle already present")
else:
    print("Dumper.cpp not found")
