// ptrace_injector - load a .so into a running game WITHOUT touching its APK.
//
// Usage: ptrace_injector <so_path> [lib_needle]
//   lib_needle: a library that has to be mapped in the target, e.g.
//                "libcocos2dcpp.so" or "libUE4.so".  When it is not given
//                the first process with a ".so" of the same name is used.
//
// How it works (the classic ARM64 remote dlopen):
//   1. find the target process (a process that has the library mapped)
//   2. PTRACE_ATTACH it
//   3. read its registers
//   4. work out the address of dlopen in the target: the offset inside
//      its libc/libdl is the same as ours (same device, same libraries)
//   5. write the .so path into the target's stack
//   6. point the registers at dlopen(path, RTLD_NOW) with the return
//      address set to the interrupted pc
//   7. let it run and detach
//
// The game is never modified: the APK, its signature and its libraries
// stay exactly as they were.

#include <android/log.h>
#include <asm/ptrace.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <unistd.h>

// The NDK does not always declare the register requests for arm64
#ifndef PTRACE_GETREGS
#define PTRACE_GETREGS 12
#endif
#ifndef PTRACE_SETREGS
#define PTRACE_SETREGS 13
#endif

#define LOG_TAG "PtraceInjector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define RTLD_NOW_MODE 2

// ---------------------------------------------------------------- helpers

// A mapping of a process
struct MapInfo {
    uintptr_t start;
    uintptr_t end;
    char path[256];
};

static bool read_maps(pid_t pid, MapInfo* out, int max, int* count) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE* f = fopen(path, "r");
    if (!f) return false;
    char line[512];
    int n = 0;
    while (fgets(line, sizeof(line), f) && n < max) {
        MapInfo m{};
        unsigned long long start = 0, end = 0, offset = 0, inode = 0;
        char perms[8] = {0}, dev[16] = {0}, p[256] = {0};
        int fields = sscanf(line, "%llx-%llx %7s %llx %15s %llu %255s",
                            &start, &end, perms, &offset, dev, &inode, p);
        if (fields < 7) continue;
        m.start = (uintptr_t)start;
        m.end = (uintptr_t)end;
        snprintf(m.path, sizeof(m.path), "%s", p);
        out[n++] = m;
    }
    fclose(f);
    *count = n;
    return n > 0;
}

// the pid of a process that has <needle> mapped
static pid_t find_pid_by_lib(const char* needle) {
    DIR* dir = opendir("/proc");
    if (!dir) return 0;
    struct dirent* de;
    pid_t found = 0;
    while ((de = readdir(dir)) != NULL) {
        if (de->d_type != DT_DIR) continue;
        pid_t pid = (pid_t)atoi(de->d_name);
        if (pid <= 0) continue;
        char path[64];
        snprintf(path, sizeof(path), "/proc/%d/maps", pid);
        FILE* f = fopen(path, "r");
        if (!f) continue;
        char line[512];
        while (fgets(line, sizeof(line), f)) {
            if (strstr(line, needle)) {
                found = pid;
                break;
            }
        }
        fclose(f);
        if (found) break;
    }
    closedir(dir);
    return found;
}

// the address of dlopen inside the library that has it
static uintptr_t local_dlopen_offset(uintptr_t* lib_base) {
    void* h = dlopen("libdl.so", RTLD_NOW);
    if (!h) h = dlopen("libc.so", RTLD_NOW);
    if (!h) return 0;
    void* sym = dlsym(h, "dlopen");
    if (!sym) return 0;
    uintptr_t addr = (uintptr_t)sym;
    // find the mapping that holds it
    MapInfo maps[512];
    int n = 0;
    if (!read_maps(getpid(), maps, 512, &n)) return 0;
    for (int i = 0; i < n; i++) {
        if (addr >= maps[i].start && addr < maps[i].end) {
            *lib_base = maps[i].start;
            return addr - maps[i].start;
        }
    }
    return 0;
}

static uintptr_t target_dlopen(pid_t pid, const char* libPath, uintptr_t offset) {
    MapInfo maps[1024];
    int n = 0;
    if (!read_maps(pid, maps, 1024, &n)) return 0;
    for (int i = 0; i < n; i++) {
        const char* base = strrchr(maps[i].path, '/');
        const char* name = base ? base + 1 : maps[i].path;
        if (strcmp(name, libPath) == 0) return maps[i].start + offset;
    }
    return 0;
}

static bool write_mem(pid_t pid, uintptr_t addr, const void* data, size_t len) {
    const char* p = (const char*)data;
    while (len > 0) {
        size_t chunk = len < sizeof(long) ? len : sizeof(long);
        long word = 0;
        memcpy(&word, p, chunk);
        if (ptrace(PTRACE_POKEDATA, pid, (void*)addr, (void*)word) != 0) {
            LOGE("PTRACE_POKEDATA failed at 0x%lx: %s", addr, strerror(errno));
            return false;
        }
        addr += chunk;
        p += chunk;
        len -= chunk;
    }
    return true;
}

// ---------------------------------------------------------------- the work

static bool inject_so(pid_t pid, const char* so_path) {
    uintptr_t localBase = 0;
    uintptr_t offset = local_dlopen_offset(&localBase);
    if (!offset) {
        LOGE("could not find dlopen");
        return false;
    }
    // which library has it?
    char libPath[256] = {0};
    {
        MapInfo maps[512];
        int n = 0;
        if (!read_maps(getpid(), maps, 512, &n)) return false;
        void* h = dlopen("libdl.so", RTLD_NOW);
        if (!h) h = dlopen("libc.so", RTLD_NOW);
        void* sym = h ? dlsym(h, "dlopen") : NULL;
        if (!sym) return false;
        uintptr_t addr = (uintptr_t)sym;
        for (int i = 0; i < n; i++) {
            if (addr >= maps[i].start && addr < maps[i].end) {
                const char* base = strrchr(maps[i].path, '/');
                snprintf(libPath, sizeof(libPath), "%s", base ? base + 1 : maps[i].path);
                break;
            }
        }
    }
    LOGI("dlopen is at +0x%lx in %s", offset, libPath);

    uintptr_t dlopenAddr = target_dlopen(pid, libPath, offset);
    if (!dlopenAddr) {
        LOGE("could not work out dlopen in the target");
        return false;
    }
    LOGI("dlopen in the target: 0x%lx", dlopenAddr);

    if (ptrace(PTRACE_ATTACH, pid, NULL, NULL) != 0) {
        LOGE("PTRACE_ATTACH failed: %s", strerror(errno));
        return false;
    }
    int status = 0;
    waitpid(pid, &status, 0);

    struct user_pt_regs regs;
    if (ptrace(PTRACE_GETREGS, pid, NULL, &regs) != 0) {
        LOGE("PTRACE_GETREGS failed: %s", strerror(errno));
        ptrace(PTRACE_DETACH, pid, NULL, NULL);
        return false;
    }

    // the path goes to the stack, a bit below the current one
    uintptr_t pathAddr = regs.sp - 1024;
    pathAddr &= ~0xFul;
    size_t len = strlen(so_path) + 1;
    char* pathCopy = (char*)malloc(len + 8);
    memset(pathCopy, 0, len + 8);
    memcpy(pathCopy, so_path, len);
    if (!write_mem(pid, pathAddr, pathCopy, len + 8)) {
        free(pathCopy);
        ptrace(PTRACE_DETACH, pid, NULL, NULL);
        return false;
    }
    free(pathCopy);

    // x0 = the path, x1 = RTLD_NOW, pc = dlopen, lr = where we were
    uint64_t origPc = regs.pc;
    regs.regs[0] = pathAddr;
    regs.regs[1] = RTLD_NOW_MODE;
    regs.regs[30] = origPc;   // lr
    regs.pc = dlopenAddr;
    if (ptrace(PTRACE_SETREGS, pid, NULL, &regs) != 0) {
        LOGE("PTRACE_SETREGS failed: %s", strerror(errno));
        ptrace(PTRACE_DETACH, pid, NULL, NULL);
        return false;
    }

    LOGI("calling dlopen(\"%s\") in pid %d", so_path, pid);
    if (ptrace(PTRACE_CONT, pid, NULL, NULL) != 0) {
        LOGE("PTRACE_CONT failed: %s", strerror(errno));
        ptrace(PTRACE_DETACH, pid, NULL, NULL);
        return false;
    }

    // let it run for a moment, then detach
    usleep(400000);
    // if the process stopped, let it go
    pid_t w = waitpid(pid, &status, WNOHANG);
    if (w == pid) {
        LOGI("the target stopped (status 0x%x) - detaching", status);
        ptrace(PTRACE_DETACH, pid, NULL, NULL);
    } else {
        // it is running - we can not detach while it runs, so wait for the
        // next stop (the game will hit one soon) or just leave it attached
        // until it stops
        for (int i = 0; i < 20; i++) {
            w = waitpid(pid, &status, WNOHANG);
            if (w == pid) break;
            usleep(250000);
        }
        if (w == pid) {
            ptrace(PTRACE_DETACH, pid, NULL, NULL);
            LOGI("detached");
        } else {
            // still running: detach anyway (it is allowed while running)
            ptrace(PTRACE_DETACH, pid, NULL, NULL);
            LOGI("the target is running, detached anyway");
        }
    }
    return true;
}

int main(int argc, char** argv) {
    LOGI("ptrace injector starting");
    if (argc < 2) {
        fprintf(stderr, "usage: %s <so_path> [lib_needle]\n", argv[0]);
        return 1;
    }
    const char* so_path = argv[1];
    const char* needle = (argc > 2) ? argv[2] : NULL;

    if (access(so_path, R_OK) != 0) {
        LOGE("cannot read %s: %s", so_path, strerror(errno));
        return 1;
    }

    pid_t pid = 0;
    if (needle) {
        pid = find_pid_by_lib(needle);
        if (!pid) {
            LOGE("no process has %s mapped", needle);
            return 1;
        }
    } else {
        // the first process with a native library
        static const char* candidates[] = {
            "libcocos2dcpp.so", "libcocos2djs.so", "libcocos2dlua.so",
            "libcocos.so", "libgame.so", "libUE4.so", "libUnreal.so", NULL
        };
        for (int i = 0; candidates[i]; i++) {
            pid = find_pid_by_lib(candidates[i]);
            if (pid) {
                needle = candidates[i];
                break;
            }
        }
    }
    if (!pid) {
        LOGE("no target process found");
        return 1;
    }
    LOGI("target pid %d (%s)", pid, needle ? needle : "?");

    if (!inject_so(pid, so_path)) {
        LOGE("the injection failed");
        return 1;
    }
    LOGI("the injection is done - the library is loaded");
    return 0;
}
