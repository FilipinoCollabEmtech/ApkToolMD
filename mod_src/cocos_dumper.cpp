// Cocos2d runtime dumper.
//
// It is injected into the game the same way the UE4 dumper is (a
// DT_NEEDED entry in the game's own .so), so it runs INSIDE the game
// process and can see everything the engine decrypts on the way.
//
// What it hooks (all of them are found by NAME in the .dynsym of the
// loaded cocos library - no hard coded offsets, no versions):
//
//   cocos2d::FileUtils::getDataFromFile   - the engine reads AND decrypts
//       the asset here, so hooking it gives us the DECRYPTED bytes of
//       every asset the game loads (the scripts, the configs, the images).
//   cocos2d::FileUtils::getStringFromFile - the same for plain text.
//   xxtea_decrypt                         - the XXTEA key is the 3rd
//       argument, so we learn the key even when it is only inside the .so.
//   luaL_loadbuffer                       - the Lua source of a Lua game.
//
// Everything is written to /sdcard/ApkToolMD/cocos_dump/.
//
// The hooks are inline patches that restore the original bytes before
// they call it (the same "unpatch and call in place" trick the UE4
// dumper uses) - a thread that races through the window just runs the
// original code.

#include <dlfcn.h>
#include <android/log.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>
#include <string>
#include <mutex>
#include <condition_variable>

#define LOG_TAG "CocosDumper"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, fmt, ##__VA_ARGS__)

#define OUT_DIR "/sdcard/ApkToolMD/cocos_dump"
#define LOG_FILE "/sdcard/ApkToolMD/cocos_dump.log"

static void log_line(const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    LOGI("%s", buf);
    FILE* f = fopen(LOG_FILE, "a");
    if (!f) return;
    fprintf(f, "%s\n", buf);
    fclose(f);
}

// ---------------------------------------------------------------- helpers

static void ensure_dir() {
    mkdir("/sdcard/ApkToolMD", 0777);
    mkdir(OUT_DIR, 0777);
}

// Turn "assets/src/main.js" into "assets_src_main.js"
static void sanitize(char* p) {
    for (char* c = p; *c; c++) {
        if (*c == '/' || *c == '\\') *c = '_';
        if ((unsigned char)*c < 0x20) *c = '_';
    }
}

static void write_file(const char* name, const void* data, size_t len);

// ---------------------------------------------------------------------------
// The dumps are written by a QUEUE, never inside the hook.
//
// The hook runs in the game's thread: writing a file there would stall the
// game (and the ANR watchdog kills it).  So the hook only COPIES the bytes
// into the queue and returns; a writer thread does the file I/O.
// ---------------------------------------------------------------------------
struct DumpEntry {
    std::string name;
    std::vector<unsigned char> data;
};
static std::mutex g_queueMutex;
static std::condition_variable g_queueCv;
static std::vector<DumpEntry> g_queue;
static size_t g_queueBytes = 0;
static size_t g_dropped = 0;
static const size_t kMaxQueueBytes = 64u * 1024 * 1024;
static const size_t kMaxQueueEntries = 1024;

static void queue_write(const char* name, const void* data, size_t len) {
    if (!name || !*name || !data || len == 0) return;
    if (strlen(name) > 400) return;
    for (const char* c = name; *c; c++) {
        if ((unsigned char)*c < 0x20 || (unsigned char)*c > 0x7e) return;
    }
    std::lock_guard<std::mutex> lk(g_queueMutex);
    if (g_queueBytes + len > kMaxQueueBytes || g_queue.size() >= kMaxQueueEntries) {
        g_dropped++;
        return;
    }
    DumpEntry e;
    e.name = name;
    e.data.resize(len);
    memcpy(e.data.data(), data, len);
    g_queueBytes += len;
    g_queue.push_back(std::move(e));
    g_queueCv.notify_one();
}

static void* writer_thread(void*) {
    while (true) {
        DumpEntry e;
        {
            std::unique_lock<std::mutex> lk(g_queueMutex);
            g_queueCv.wait(lk, [] { return !g_queue.empty(); });
            e = std::move(g_queue.front());
            g_queue.erase(g_queue.begin());
            g_queueBytes -= e.data.size();
        }
        write_file(e.name.c_str(), e.data.data(), e.data.size());
    }
    return nullptr;
}

static int g_dumped = 0;
static const int kMaxFiles = 5000;
static const size_t kMaxFileSize = 64u * 1024 * 1024;

static void write_file(const char* name, const void* data, size_t len) {
    if (!name || !*name || !data || len == 0) return;
    // the name comes from the game - it has to look like a path, otherwise
    // we would follow a garbage pointer
    if (strlen(name) > 400) return;
    for (const char* c = name; *c; c++) {
        if ((unsigned char)*c < 0x20 || (unsigned char)*c > 0x7e) return;
    }
    if (len > kMaxFileSize) {
        log_line("skipped %s (%zu bytes - too big)", name, len);
        return;
    }
    if (g_dumped >= kMaxFiles) {
        if (g_dumped == kMaxFiles) {
            log_line("reached the %d file limit - no more files are written", kMaxFiles);
            g_dumped++;
        }
        return;
    }
    g_dumped++;
    ensure_dir();
    char safe[512];
    snprintf(safe, sizeof(safe), "%s", name);
    sanitize(safe);
    // keep the extension readable
    char path[640];
    snprintf(path, sizeof(path), "%s/%s", OUT_DIR, safe);
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        LOGE("cannot write %s", path);
        return;
    }
    const unsigned char* p = (const unsigned char*)data;
    size_t left = len;
    while (left > 0) {
        ssize_t n = write(fd, p, left > 65536 ? 65536 : left);
        if (n <= 0) break;
        p += n;
        left -= (size_t)n;
    }
    close(fd);
    log_line("dumped %s (%zu bytes)", safe, len);
}

// libc++ std::string: the size is the first field.
//
// A SHORT string (22 characters or less) keeps the characters inline, right
// after the size.  A LONG one keeps a POINTER - and the pointer is not at
// the same offset in every layout: {size, cap, data} in the usual one, so
// the pointer sits at +16 and not at +8.  Reading it at the wrong offset
// gave a wild pointer and killed the game.
static bool plausible_ptr(const void* p) {
    uintptr_t v = (uintptr_t)p;
    return v > 0x10000 && v < 0x00007fffffffffffULL;
}

static bool read_std_string(const void* s, char* out, size_t cap) {
    if (!s || !out || cap < 2) return false;
    size_t size = 0;
    memcpy(&size, s, sizeof(size_t));
    if (size > 4096) return false;
    const char* data = NULL;
    if (size <= 22) {
        data = (const char*)s + 8;              // the characters are inline
    } else {
        // a long string: {size, capacity, pointer} -> the pointer is at +16,
        // but some builds use {size, pointer, capacity} -> +8.  Try both and
        // keep the one that really points at a path.
        const long offs[2] = {16, 8};
        for (int i = 0; i < 2 && !data; i++) {
            const char* p = *(const char* const*)((const char*)s + offs[i]);
            if (!plausible_ptr(p)) continue;
            char c = *p;                        // a mapped pointer
            if ((unsigned char)c < 0x20 || (unsigned char)c > 0x7e) continue;
            data = p;
        }
    }
    if (!data) return false;
    if (size >= cap) size = cap - 1;
    memcpy(out, data, size);
    out[size] = 0;
    return true;
}

// cocos2d::Data = { unsigned char* _bytes; size_t _size; }
static bool read_data(const void* d, const void** bytes, size_t* size) {
    if (!d) return false;
    const void* b = NULL;
    size_t s = 0;
    memcpy(&b, d, sizeof(void*));
    memcpy(&s, (const char*)d + sizeof(void*), sizeof(size_t));
    if (!b || s == 0 || s > (64u * 1024 * 1024)) return false;
    *bytes = b;
    *size = s;
    return true;
}

// ------------------------------------------------------------ the hooks

// An inline hook: 16 bytes (LDR X16, #8 / BR X16 / .quad target) so we
// can jump to a far away stub.
struct Hook {
    uintptr_t target = 0;
    uint32_t saved[4] = {0};
    void* original = nullptr;
    void* stub = nullptr;
    bool installed = false;
};

static Hook g_getData;
static Hook g_getFileData;
static Hook g_zipFileData;
static int g_calls = 0;
static Hook g_getZipData;
static Hook g_getString;
static Hook g_xxtea;
static Hook g_lua;

static void install_hook(Hook* h, uintptr_t target, void* stub) {
    if (!target) return;
    uint32_t insn[4] = {0x58000050u, 0xD61F0200u, 0, 0};   // LDR X16,#8 ; BR X16
    memcpy(&insn[2], &stub, sizeof(void*));
    if (target & 3) return;
    // save the original 16 bytes
    memcpy(h->saved, (void*)target, 16);
    h->target = target;
    h->original = (void*)target;
    h->stub = stub;
    // the code page has to be writable
    long page = (long)(target & ~0xFFFul);
    if (mprotect((void*)page, 4096, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("mprotect failed for 0x%lx", target);
        return;
    }
    memcpy((void*)target, insn, 16);
    __builtin___clear_cache((char*)target, (char*)target + 16);
    h->installed = true;
}

// Call the original: restore the bytes, call, patch again.  A thread that
// races through the window just runs the original code.
template <typename F, typename... Args>
static inline auto call_original(Hook* h, F fn, Args... args) -> decltype(fn(args...)) {
    if (!h->installed) return fn(args...);
    memcpy((void*)h->target, h->saved, 16);
    __builtin___clear_cache((char*)h->target, (char*)h->target + 16);

    auto result = fn(args...);

    // re-install the jump to OUR stub
    uint32_t insn[4] = {0x58000050u, 0xD61F0200u, 0, 0};
    memcpy(&insn[2], &h->stub, sizeof(void*));
    memcpy((void*)h->target, insn, 16);
    __builtin___clear_cache((char*)h->target, (char*)h->target + 16);
    return result;
}

// cocos2d::FileUtils::getDataFromFile(const std::string& filePath,
//                                      const std::string* resDir, Data* data)
typedef bool (*GetDataFn)(void*, const void*, const void*, void*);
static GetDataFn g_orig_getdata = nullptr;

static bool hook_getdata(void* self, const void* path, const void* resdir, void* out) {
    bool ok = call_original(&g_getData, g_orig_getdata, self, (void*)path, (void*)resdir, out);
    if (ok) {
        char name[512];
        if (read_std_string(path, name, sizeof(name))) {
            const void* bytes = nullptr;
            size_t size = 0;
            if (read_data(out, &bytes, &size)) {
                write_file(name, bytes, size);
            }
        }
    }
    return ok;
}

// cocos2d::ZipFile::getFileData(const std::string& filePath, size_t* dataSize)
//   -> unsigned char*   (this is the path the game really uses to read
//     the assets out of the APK zip)
typedef unsigned char* (*GetZipFileDataFn)(void*, const void*, size_t*);
static GetZipFileDataFn g_orig_zipfiledata = nullptr;

static unsigned char* hook_zipfiledata(void* self, const void* path, size_t* dataSize) {
    unsigned char* out = call_original(&g_zipFileData, g_orig_zipfiledata, self, path, dataSize);
    if (g_calls < 6) {
        char name[256] = {0};
        read_std_string(path, name, sizeof(name));
        g_calls++;
        log_line("ZipFile::getFileData(%s) -> %p size=%zu", name, (void*)out,
                 dataSize ? *dataSize : 0);
    }
    if (out && dataSize && *dataSize > 0) {
        char name[256] = {0};
        if (read_std_string(path, name, sizeof(name))) queue_write(name, out, *dataSize);
    }
    return out;
}

// cocos2d::CCFileUtils::getFileData(const char* filePath,
//                                    const char* searchPath, size_t* dataSize)
//   -> unsigned char*   (the DECRYPTED bytes, the size comes back in
//                         dataSize).  This is the one the engine really
//                         uses - the mangled name gives us the signature,
//                         so no guessing is needed.
typedef unsigned char* (*GetFileDataFn)(void*, const char*, const char*, size_t*);
static GetFileDataFn g_orig_getfiledata = nullptr;
static GetFileDataFn g_orig_getzipdata = nullptr;

static unsigned char* hook_getfiledata(void* self, const char* filename,
                                       const char* searchPath, size_t* dataSize) {
    unsigned char* out =
        call_original(&g_getFileData, g_orig_getfiledata, self, filename, searchPath, dataSize);
    if (g_calls < 6) {
        g_calls++;
        log_line("getFileData(%s) -> %p size=%zu", filename ? filename : "?", (void*)out,
                 dataSize ? *dataSize : 0);
    }
    if (out && dataSize && *dataSize > 0) {
        queue_write(filename, out, *dataSize);
    }
    return out;
}

// the same for the files that live inside the APK zip
static unsigned char* hook_getzipdata(void* self, const char* filename,
                                       const char* searchPath, size_t* dataSize) {
    unsigned char* out =
        call_original(&g_getZipData, g_orig_getzipdata, self, filename, searchPath, dataSize);
    if (g_calls < 6) {
        g_calls++;
        log_line("getFileDataFromZip(%s) -> %p size=%zu", filename ? filename : "?", (void*)out,
                 dataSize ? *dataSize : 0);
    }
    if (out && dataSize && *dataSize > 0) {
        queue_write(filename, out, *dataSize);
    }
    return out;
}

// cocos2d::FileUtils::getStringFromFile(const std::string& filePath, std::string* ret)
typedef bool (*GetStringFn)(void*, const void*, void*);
static GetStringFn g_orig_getstring = nullptr;

static bool hook_getstring(void* self, const void* path, void* ret) {
    bool ok = call_original(&g_getString, g_orig_getstring, self, (void*)path, ret);
    if (ok) {
        char name[512];
        char text[4096];
        if (read_std_string(path, name, sizeof(name)) && read_std_string(ret, text, sizeof(text))) {
            queue_write(name, text, strlen(text));
        }
    }
    return ok;
}

// xxtea_decrypt(uint8_t* data, size_t len, uint8_t* key, size_t* outLen)
// Four arguments - the call site in the game loads the key from [x19+0x210]
// and passes a stack pointer for the output length.  The return value is
// passed through unchanged (some builds return a pointer, others nothing).
typedef void* (*XxteaFn)(void*, size_t, void*, void*);
static XxteaFn g_orig_xxtea = nullptr;

static void* hook_xxtea(void* data, size_t len, void* key, void* outLen) {
    if (key && len) {
        char hex[64];
        int n = 0;
        const unsigned char* k = (const unsigned char*)key;
        for (size_t i = 0; i < 16 && n < 60; i++) {
            n += snprintf(hex + n, sizeof(hex) - n, "%02x", k[i]);
        }
        log_line("xxtea_decrypt len=%zu key=%s", len, hex);
        write_file("xxtea_key.txt", hex, strlen(hex));
    }
    return call_original(&g_xxtea, g_orig_xxtea, data, len, key, outLen);
}

// luaL_loadbuffer(lua_State* L, const char* buff, size_t sz, const char* name)
typedef int (*LuaLoadFn)(void*, const char*, size_t, const char*);
static LuaLoadFn g_orig_lua = nullptr;

static int hook_lua(void* L, const char* buff, size_t sz, const char* name) {
    if (buff && sz && sz < (16u * 1024 * 1024)) {
        char out[512];
        snprintf(out, sizeof(out), "%s", name ? name : "chunk.luac");
        queue_write(out, buff, sz);
    }
    return call_original(&g_lua, g_orig_lua, L, buff, sz, name);
}

// ============================================================================
// Aim-at-nearest-player hook
//
// Uses CoOpStage::getClosestPlayerLocation (0x5ba500) to find the nearest
// enemy, then overrides the aim offset so the player aims at them.
// The aim offset is written to a configurable memory address - the user must
// find where their game stores the aim direction (search for code that reads
// the "WeaponOffset" string at 0x107f59c).
// ============================================================================

typedef struct { float x, y; } cpVect;
// CoOpStage::getClosestPlayerLocation(cpVect) - non-static method:
//   x0 = this (CoOpStage*), x1 = localPos (cpVect), returns cpVect in x0
typedef cpVect (*GetClosestPlayerLocationFn)(void* this_ptr, cpVect localPos);
static GetClosestPlayerLocationFn g_orig_getClosestPlayerLocation = nullptr;
static Hook g_getClosestPlayerLocation;

// Last values captured when the game calls getClosestPlayerLocation.
static cpVect g_last_local_pos = {0, 0};
static cpVect g_last_nearest = {0, 0};
static bool g_have_nearest = false;

// Aim offset target: address in the game's memory where the aim direction
// is stored (float, radians). 0 = disabled (user must set it).
static uintptr_t g_aim_offset_addr = 0;
static bool g_aim_enabled = false;

// Hook getClosestPlayerLocation to capture nearest enemy and override aim.
static cpVect hook_getClosestPlayerLocation(void* this_ptr, cpVect localPos) {
    g_last_local_pos = localPos;

    // Call the original with the proper this pointer so the function works.
    cpVect nearest = call_original(&g_getClosestPlayerLocation,
                                   g_orig_getClosestPlayerLocation,
                                   this_ptr, localPos);

    g_last_nearest = nearest;
    g_have_nearest = true;

    log_line("getClosestPlayerLocation: local=(%.2f,%.2f) nearest=(%.2f,%.2f) dist=%.2f",
             localPos.x, localPos.y, nearest.x, nearest.y,
             sqrtf((nearest.x-localPos.x)*(nearest.x-localPos.x) +
                   (nearest.y-localPos.y)*(nearest.y-localPos.y)));

    // If aim is enabled, write the aim angle to the target address.
    if (g_aim_enabled && g_aim_offset_addr) {
        float dx = nearest.x - localPos.x;
        float dy = nearest.y - localPos.y;
        float angle = atan2f(dy, dx);
        // Clamp angle to [-pi, pi]
        if (angle > 3.1415926f) angle -= 6.2831853f;
        if (angle < -3.1415926f) angle += 6.2831853f;
        float* aimPtr = (float*)g_aim_offset_addr;
        if (aimPtr > (float*)0x10000 && aimPtr < (float*)0x00007fffffffULL) {
            *aimPtr = angle;
        }
    }

    return nearest;
}

// Aim thread: finds getClosestPlayerLocation, installs the hook, then keeps
// the thread alive.  The actual aim writing happens inside the hook whenever
// the game calls getClosestPlayerLocation.
// Aim thread: finds getClosestPlayerLocation, installs the hook, then keeps
// the thread alive.  The actual aim writing happens inside the hook whenever
// the game calls getClosestPlayerLocation.
// Forward declaration: find_symbol is defined later in this file.
static uintptr_t find_symbol(const char* needle);

// Read aim offset address + enable flag from a file the app writes.
// Format: one line "0xADDR 1" (address + enabled flag) or just "0xADDR".
static void load_aim_config() {
    FILE* f = fopen("/sdcard/ApkToolMD/aim_target.txt", "r");
    if (!f) return;
    char line[256];
    if (fgets(line, sizeof(line), f)) {
        uintptr_t addr = 0;
        int enabled = 0;
        int n = sscanf(line, "%p %d", (void**)&addr, &enabled);
        if (n >= 1) {
            g_aim_offset_addr = addr;
            g_aim_enabled = (n >= 2) ? (enabled != 0) : true;
            log_line("aim: loaded config from file: target=0x%lx enabled=%d",
                     g_aim_offset_addr, g_aim_enabled ? 1 : 0);
        }
    }
    fclose(f);
}

static void* aim_thread(void*) {
    // Read aim config from file first (written by the app UI)
    load_aim_config();

    // Wait for the cocos library and getClosestPlayerLocation to be available.
    uintptr_t gcp = 0;
    for (int i = 0; i < 240; i++) {
        gcp = find_symbol("CoOpStage24getClosestPlayerLocationE6cpVect");
        if (gcp) break;
        usleep(500000);
    }
    if (!gcp) {
        log_line("aim: getClosestPlayerLocation not found - aim disabled");
        return nullptr;
    }

    g_orig_getClosestPlayerLocation = (GetClosestPlayerLocationFn)gcp;
    install_hook(&g_getClosestPlayerLocation, gcp, (void*)hook_getClosestPlayerLocation);
    log_line("aim: hooked getClosestPlayerLocation at 0x%lx", gcp);

    while (true) {
        sleep(30);
    }
    return nullptr;
}

// Set the aim offset memory address (called from Java via JNI or set via config).
extern "C" void aim_set_target(uintptr_t addr, bool enabled) {
    g_aim_offset_addr = addr;
    g_aim_enabled = enabled;
    log_line("aim: target=0x%lx enabled=%d", g_aim_offset_addr, g_aim_enabled ? 1 : 0);
}

// ============================================================================

// ------------------------------------------------- finding the symbols

// Find a symbol by a SUBSTRING of its (mangled) name inside the dynamic
// symbol table of a module.  This works for every cocos version because
// the function name appears verbatim in the mangled name.
// dl_iterate_phdr wants a plain function pointer, so the search state
// lives in globals.
static const char* g_search_module = nullptr;
static const char* g_search_needle = nullptr;
static uintptr_t g_search_result = 0;

static int scan_module_symbols(struct dl_phdr_info* info, size_t, void*) {
    {
        const char* modname = g_search_module;
        const char* needle = g_search_needle;
        const char* name = info->dlpi_name ? info->dlpi_name : "";
        if (!strstr(name, modname)) return 0;
        for (int seg = 0; seg < info->dlpi_phnum; seg++) {
            const ElfW(Phdr)* ph = &info->dlpi_phdr[seg];
            if (ph->p_type != PT_DYNAMIC) continue;
            const ElfW(Dyn)* dyn = (const ElfW(Dyn)*)(info->dlpi_addr + ph->p_vaddr);
            const char* strtab = NULL;
            const ElfW(Sym)* symtab = NULL;
            size_t syment = sizeof(ElfW(Sym));
            const uint32_t* hash = NULL;
            for (; dyn->d_tag != DT_NULL; dyn++) {
                if (dyn->d_tag == DT_STRTAB) strtab = (const char*)info->dlpi_addr + dyn->d_un.d_ptr;
                else if (dyn->d_tag == DT_SYMTAB) symtab = (const ElfW(Sym)*)(info->dlpi_addr + dyn->d_un.d_ptr);
                else if (dyn->d_tag == DT_SYMENT) syment = dyn->d_un.d_val;
                else if (dyn->d_tag == DT_HASH) hash = (const uint32_t*)(info->dlpi_addr + dyn->d_un.d_ptr);
            }
            if (!strtab || !symtab || syment == 0) continue;
            // The number of symbols: the SysV hash table knows it exactly
            // (nchain), the guess from the distance to the string table is
            // only a fallback - walking past the table would read garbage
            // pointers and crash the game.
            size_t count = 0;
            if (hash) count = hash[1];          // nchain
            if (count == 0) {
                count = (size_t)((strtab - (const char*)symtab) / syment);
                if (count > 200000) count = 200000;
            }
            if (count == 0) continue;
            for (size_t i = 0; i < count; i++) {
                const ElfW(Sym)* s = &symtab[i];
                if (!s->st_name || s->st_value == 0) continue;
                if (s->st_shndx == SHN_UNDEF) continue;
                const char* sym = strtab + s->st_name;
                if (!sym || !*sym) continue;
                if (strstr(sym, needle)) {
                    g_search_result = info->dlpi_addr + s->st_value;
                    log_line("found %s in %s at 0x%lx", sym, name, g_search_result);
                    return 1;
                }
            }
        }
        return 0;
    }
}

static uintptr_t find_symbol_in_module(const char* modname, const char* needle) {
    g_search_module = modname;
    g_search_needle = needle;
    g_search_result = 0;
    dl_iterate_phdr(scan_module_symbols, NULL);
    return g_search_result;
}

// The library names differ per game: libcocos2dcpp.so, libcocos2djs.so,
// libcocos2dlua.so, libcocos.so, libgame.so, ...
static const char* kModuleNames[] = {
    "libcocos2d", "libcocos", "libgame", "libcocos2", "libgodot", nullptr
};

static uintptr_t find_symbol(const char* needle) {
    // 1) the global scope (works when the symbol is exported)
    void* sym = dlsym(RTLD_DEFAULT, needle);
    if (sym) {
        log_line("found %s via dlsym at %p", needle, sym);
        return (uintptr_t)sym;
    }
    // 2) search the dynamic symbol tables of the loaded cocos modules
    for (int i = 0; kModuleNames[i]; i++) {
        uintptr_t a = find_symbol_in_module(kModuleNames[i], needle);
        if (a) return a;
    }
    return 0;
}

static void* hook_thread(void*) {
    ensure_dir();
    log_line("cocos dumper started");

    // wait for the cocos library to be loaded
    for (int i = 0; i < 120; i++) {
        if (find_symbol_in_module("libcocos", "FileUtils11getFileData") ||
            find_symbol_in_module("libgame", "FileUtils11getFileData") ||
            find_symbol_in_module("libcocos2", "FileUtils11getFileData")) {
            break;
        }
        usleep(500000);
    }

    uintptr_t a = find_symbol("getDataFromFile");
    if (a) {
        g_orig_getdata = (GetDataFn)a;
        install_hook(&g_getData, a, (void*)hook_getdata);
        log_line("hooked getDataFromFile");
    } else {
        log_line("getDataFromFile not found");
    }

    // The needles have to name the CLASS too: "getFileData" alone also
    // matches ZipFile::getFileData (a different signature - hooking it
    // with the wrong one crashes the game on the first call).
    uintptr_t fd = find_symbol("FileUtils11getFileData");
    if (fd) {
        g_orig_getfiledata = (GetFileDataFn)fd;
        install_hook(&g_getFileData, fd, (void*)hook_getfiledata);
        log_line("hooked CCFileUtils::getFileData");
    } else {
        log_line("CCFileUtils::getFileData not found");
    }
    uintptr_t zf = find_symbol("ZipFile11getFileData");
    if (zf) {
        g_orig_zipfiledata = (GetZipFileDataFn)zf;
        install_hook(&g_zipFileData, zf, (void*)hook_zipfiledata);
        log_line("hooked ZipFile::getFileData");
    } else {
        log_line("ZipFile::getFileData not found");
    }
    uintptr_t zd = find_symbol("FileUtils18getFileDataFromZip");
    if (zd) {
        g_orig_getzipdata = (GetFileDataFn)zd;
        install_hook(&g_getZipData, zd, (void*)hook_getzipdata);
        log_line("hooked CCFileUtils::getFileDataFromZip");
    }

    uintptr_t b = find_symbol("getStringFromFile");
    if (b) {
        g_orig_getstring = (GetStringFn)b;
        install_hook(&g_getString, b, (void*)hook_getstring);
        log_line("hooked getStringFromFile");
    }

    uintptr_t c = find_symbol("xxtea_decrypt");
    if (c) {
        g_orig_xxtea = (XxteaFn)c;
        install_hook(&g_xxtea, c, (void*)hook_xxtea);
        log_line("hooked xxtea_decrypt");
    }

    uintptr_t d = find_symbol("luaL_loadbuffer");
    if (d) {
        g_orig_lua = (LuaLoadFn)d;
        install_hook(&g_lua, d, (void*)hook_lua);
        log_line("hooked luaL_loadbuffer");
    }

    log_line("hooks: getData=%d getFileData=%d zipFileData=%d getZipData=%d getString=%d xxtea=%d lua=%d",
             g_getData.installed, g_getFileData.installed, g_zipFileData.installed,
             g_getZipData.installed, g_getString.installed, g_xxtea.installed, g_lua.installed);

    // keep the thread alive
    while (true) {
        sleep(30);
    }
    return nullptr;
}

__attribute__((constructor)) static void cocos_dumper_init() {
    log_line("cocos dumper loaded into the game");
    pthread_t writer;
    pthread_create(&writer, nullptr, writer_thread, nullptr);
    pthread_detach(writer);
    pthread_t tid;
    pthread_create(&tid, nullptr, hook_thread, nullptr);
    pthread_detach(tid);
    pthread_t aim_tid;
    pthread_create(&aim_tid, nullptr, aim_thread, nullptr);
    pthread_detach(aim_tid);
}
