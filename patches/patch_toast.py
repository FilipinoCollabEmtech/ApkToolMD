p = "/tmp/uecn/AndUEDumper/src/library.cpp"
s = open(p).read()

if "#include <jni.h>" not in s:
    s = s.replace("#include <thread>",
                  "#include <thread>\n#include <jni.h>\n#include <dlfcn.h>\n"
                  "#include <fstream>\n#include <link.h>\n#include <elf.h>\n#include <cstring>", 1)

helper = r'''
static void write_status(const char* msg) {
    // primary: shared /sdcard path (needs storage permission)
    std::ofstream f("/sdcard/UEDump3r_status.txt", std::ofstream::out | std::ofstream::trunc);
    if (f.is_open()) { f << msg << "\n"; f.close(); }
    // fallback: our own app dir, always writable, no permission needed
    std::string dir = KittyUtils::getExternalStorage() + "/Android/data/" + std::string(getprogname()) + "/files";
    std::string p2 = dir + "/UEDump3r_status.txt";
    std::ofstream g(p2.c_str(), std::ofstream::out | std::ofstream::trunc);
    if (g.is_open()) { g << msg << "\n"; g.close(); }
}

// Resolve JNI_GetCreatedJavaVMs by scanning the already-loaded VM library's
// in-memory ELF dynsym. Needed because dlopen() of libart/libnativehelper is
// blocked by the app linker namespace on this device.
struct _FindLib { const char* needle; int found; uintptr_t base; uintptr_t dyn_vaddr; };
static int _find_lib_cb(struct dl_phdr_info* info, size_t size, void* data) {
    _FindLib* f = (_FindLib*)data;
    if (!info->dlpi_name || !info->dlpi_name[0]) return 0;
    if (strstr(info->dlpi_name, f->needle) == nullptr) return 0;
    f->found = 1; f->base = (uintptr_t)info->dlpi_addr; f->dyn_vaddr = 0;
    for (int i = 0; i < info->dlpi_phnum; i++)
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC)
            f->dyn_vaddr = (uintptr_t)info->dlpi_phdr[i].p_vaddr;
    return 1;
}
static void* _dyn_sym(const char* libsubstr, const char* symname) {
    _FindLib f; f.needle = libsubstr; f.found = 0; f.base = 0; f.dyn_vaddr = 0;
    dl_iterate_phdr(_find_lib_cb, &f);
    if (!f.found || f.dyn_vaddr == 0) return nullptr;
    uintptr_t base = f.base;
    Elf64_Dyn* dyn = (Elf64_Dyn*)(base + f.dyn_vaddr);
    const char* strtab = nullptr;
    Elf64_Sym* symtab = nullptr;
    uint32_t* hash = nullptr;
    uint32_t* gnu = nullptr;
    for (Elf64_Dyn* d = dyn; d->d_tag != DT_NULL; d++) {
        if (d->d_tag == DT_STRTAB) strtab = (const char*)(base + d->d_un.d_ptr);
        else if (d->d_tag == DT_SYMTAB) symtab = (Elf64_Sym*)(base + d->d_un.d_ptr);
        else if (d->d_tag == DT_HASH) hash = (uint32_t*)(base + d->d_un.d_ptr);
        else if (d->d_tag == DT_GNU_HASH) gnu = (uint32_t*)(base + d->d_un.d_ptr);
    }
    if (!strtab || !symtab) return nullptr;
    uint32_t nsyms = 0;
    if (hash) nsyms = hash[1];
    else if (gnu) {
        uint32_t nbuckets = gnu[0], symoffset = gnu[1], bloom_size = gnu[2];
        const uint32_t* buckets = gnu + 4 + bloom_size * 2;
        const uint32_t* chain = buckets + nbuckets;
        uint32_t maxidx = 0;
        for (uint32_t b = 0; b < nbuckets; b++) {
            uint32_t idx = buckets[b];
            if (idx < symoffset) continue;
            uint32_t i = idx - symoffset;
            while (!(chain[i] & 1u)) i++;
            uint32_t last = i + symoffset;
            if (last > maxidx) maxidx = last;
        }
        nsyms = maxidx + 1;
    }
    if (nsyms == 0) return nullptr;
    for (uint32_t i = 0; i < nsyms; i++) {
        if (symtab[i].st_name == 0) continue;
        if (strcmp(strtab + symtab[i].st_name, symname) == 0)
            return (void*)(base + symtab[i].st_value);
    }
    return nullptr;
}
static jint find_java_vms(JavaVM** vm, jsize* n) {
    typedef jint (*GetVMsFn)(JavaVM**, jsize, jsize*);
    GetVMsFn fn = (GetVMsFn)_dyn_sym("libnativehelper.so", "JNI_GetCreatedJavaVMs");
    if (!fn) fn = (GetVMsFn)_dyn_sym("libart.so", "JNI_GetCreatedJavaVMs");
    if (!fn) return JNI_ERR;
    return fn(vm, 1, n);
}

static void show_notification(const char* msg) {
    JavaVM* vm = nullptr; jsize n = 0;
    if (find_java_vms(&vm, &n) != JNI_OK || n == 0 || !vm) return;
    JNIEnv* env = nullptr; bool attached = false;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    if (!env) { if (attached) vm->DetachCurrentThread(); return; }
    jclass atCls = env->FindClass("android/app/ActivityThread");
    jobject app = nullptr;
    if (atCls) {
        jmethodID curApp = env->GetStaticMethodID(atCls, "currentApplication", "()Landroid/app/Application;");
        if (curApp) app = env->CallStaticObjectMethod(atCls, curApp);
    }
    if (app && !env->ExceptionCheck()) {
        jstring svcName = env->NewStringUTF("notification");
        jmethodID getSystemService = env->GetMethodID(env->GetObjectClass(app), "getSystemService", "(Ljava/lang/String;)Ljava/lang/Object;");
        jobject nm = env->CallObjectMethod(app, getSystemService, svcName);
        if (nm && !env->ExceptionCheck()) {
            jclass channelCls = env->FindClass("android/app/NotificationChannel");
            if (channelCls) {
                jmethodID channelCtor = env->GetMethodID(channelCls, "<init>", "(Ljava/lang/String;Ljava/lang/CharSequence;I)V");
                jstring cid = env->NewStringUTF("uedump3r");
                jstring cname = env->NewStringUTF("UEDump3r");
                jobject channel = env->NewObject(channelCls, channelCtor, cid, cname, 3);
                jmethodID createChannel = env->GetMethodID(env->GetObjectClass(nm), "createNotificationChannel", "(Landroid/app/NotificationChannel;)V");
                if (createChannel) env->CallVoidMethod(nm, createChannel, channel);
                env->DeleteLocalRef(cid); env->DeleteLocalRef(cname);
            }
            jclass resCls = env->FindClass("android/content/res/Resources");
            jmethodID getSystem = env->GetStaticMethodID(resCls, "getSystem", "()Landroid/content/res/Resources;");
            jobject res = env->CallStaticObjectMethod(resCls, getSystem);
            jmethodID getIdentifier = env->GetMethodID(resCls, "getIdentifier", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I");
            jstring iname = env->NewStringUTF("ic_dialog_info");
            jstring itype = env->NewStringUTF("drawable");
            jstring ipkg = env->NewStringUTF("android");
            jint iconId = env->CallIntMethod(res, getIdentifier, iname, itype, ipkg);
            jclass builderCls = env->FindClass("android/app/Notification$Builder");
            if (builderCls) {
                jmethodID builderCtor = env->GetMethodID(builderCls, "<init>", "(Landroid/content/Context;Ljava/lang/String;)V");
                jstring cid2 = env->NewStringUTF("uedump3r");
                jobject builder = env->NewObject(builderCls, builderCtor, app, cid2);
                if (builder && !env->ExceptionCheck()) {
                    jmethodID setTitle = env->GetMethodID(builderCls, "setContentTitle", "(Ljava/lang/CharSequence;)Landroid/app/Notification$Builder;");
                    jmethodID setText = env->GetMethodID(builderCls, "setContentText", "(Ljava/lang/CharSequence;)Landroid/app/Notification$Builder;");
                    jmethodID setIcon = env->GetMethodID(builderCls, "setSmallIcon", "(I)Landroid/app/Notification$Builder;");
                    jmethodID build = env->GetMethodID(builderCls, "build", "()Landroid/app/Notification;");
                    jstring title = env->NewStringUTF("UEDump3r");
                    jstring text = env->NewStringUTF(msg);
                    if (setTitle) env->CallObjectMethod(builder, setTitle, title);
                    if (setText) env->CallObjectMethod(builder, setText, text);
                    if (setIcon && iconId != 0) env->CallObjectMethod(builder, setIcon, iconId);
                    jobject notification = env->CallObjectMethod(builder, build);
                    if (notification && !env->ExceptionCheck()) {
                        jmethodID notify = env->GetMethodID(env->GetObjectClass(nm), "notify", "(ILandroid/app/Notification;)V");
                        if (notify) env->CallVoidMethod(nm, notify, 1, notification);
                    }
                    env->DeleteLocalRef(title); env->DeleteLocalRef(text); env->DeleteLocalRef(cid2);
                }
            }
        }
        env->DeleteLocalRef(svcName);
    }
    if (app) env->DeleteLocalRef(app);
    if (atCls) env->DeleteLocalRef(atCls);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) vm->DetachCurrentThread();
}

'''

marker = "void dump_thread(bool bDumpLib);"
assert marker in s, "dump_thread marker not found"
s = s.replace(marker, helper + marker, 1)

# onload: LOADED status
old = '    LOGI("Using UE Dumper %s", kUEDUMPER_VERSION);'
assert old in s, "onload log line not found"
s = s.replace(old, old + '\n    write_status("LOADED: UEDump3r library loaded");', 1)

# dump start
old = "    sleep(WAIT_TIME_SEC);\n"
assert old in s, "sleep marker not found"
s = s.replace(old,
              "    sleep(WAIT_TIME_SEC);\n"
              '    write_status("STARTED: dumping...");\n'
              '    show_notification("UEDump3r: starting dump...");\n', 1)

# game not supported
old = '        LOGE("Game is not supported. check AppID.");\n        return;'
assert old in s, "not-supported block not found"
s = s.replace(old,
              '        LOGE("Game is not supported. check AppID.");\n'
              '        write_status("ERROR: game not supported (AppID mismatch)");\n'
              '        show_notification("UEDump3r: game not supported");\n'
              '        return;', 1)

# dump failed (buffers empty)
old = ('        LOGE("Dump Failed, Error <Buffers empty>");\n'
       '        LOGE("Dump Status <%s>", uEDumper.GetLastError().c_str());\n'
       '        return;')
assert old in s, "buffers-empty block not found"
s = s.replace(old,
              '        LOGE("Dump Failed, Error <Buffers empty>");\n'
              '        LOGE("Dump Status <%s>", uEDumper.GetLastError().c_str());\n'
              '        { char _b[256]; snprintf(_b, sizeof(_b), "ERROR: dump failed: %s", uEDumper.GetLastError().c_str()); write_status(_b); }\n'
              '        show_notification("UEDump3r: dump failed");\n'
              '        return;', 1)

# success
old = '    LOGI("Dump Location: %s", sDumpGameDir.c_str());'
assert old in s, "dump location log not found"
s = s.replace(old,
              '    write_status("DONE: dump complete");\n'
              '    show_notification("UEDump3r: dump complete");\n'
              + old, 1)

# phase breadcrumbs inside the notify callbacks (so a hang/crash is locatable)
phase_subs = [
    ('            LOGI("Dumping Executable Info...");',
     '            LOGI("Dumping Executable Info...");\n            write_status("PHASE: exe info");'),
    ('            LOGI("Dumping Names Info...");',
     '            LOGI("Dumping Names Info...");\n            write_status("PHASE: names");'),
    ('            LOGI("Dumping Objects Info...");',
     '            LOGI("Dumping Objects Info...");\n            write_status("PHASE: objects");'),
    ('            LOGI("Dumping Offsets Info...");',
     '            LOGI("Dumping Offsets Info...");\n            write_status("PHASE: offsets");'),
]
for _a, _b in phase_subs:
    assert _a in s, "phase anchor not found: " + _a
    s = s.replace(_a, _b, 1)

# progress callbacks: report percentage (throttled to changes)
obj_cb_old = (
    '    uEDumper.setObjectsProgressCallback([](const SimpleProgressBar &progress)\n'
    '    {\n'
    '        static bool once = false;\n'
    '        if (!once)\n'
    '        {\n'
    '            once = true;\n'
    '            LOGI("Gathering UObjects....");\n'
    '        };\n'
    '    });'
)
assert obj_cb_old in s, "objects progress callback not found"
obj_cb_new = (
    '    uEDumper.setObjectsProgressCallback([](const SimpleProgressBar &progress)\n'
    '    {\n'
    '        static int _lastPct = -1;\n'
    '        int _pct = progress.getPercentage();\n'
    '        if (_pct != _lastPct)\n'
    '        {\n'
    '            _lastPct = _pct;\n'
    '            char _b[96];\n'
    '            snprintf(_b, sizeof(_b), "PHASE: gathering objects %d%% (%d/%d)", _pct, progress.getCurrent(), progress.getTotal());\n'
    '            write_status(_b);\n'
    '        }\n'
    '        else if ((progress.getCurrent() % 250) == 0)\n'
    '        {\n'
    '            char _b2[96];\n'
    '            snprintf(_b2, sizeof(_b2), "PHASE: gathering objects %d%% (%d/%d)", _pct, progress.getCurrent(), progress.getTotal());\n'
    '            write_status(_b2);\n'
    '        }\n'
    '    });'
)
s = s.replace(obj_cb_old, obj_cb_new, 1)

dump_cb_old = (
    '    uEDumper.setDumpProgressCallback([](const SimpleProgressBar &progress)\n'
    '    {\n'
    '        static bool once = false;\n'
    '        if (!once)\n'
    '        {\n'
    '            once = true;\n'
    '            LOGI("Dumping....");\n'
    '        };\n'
    '    });'
)
assert dump_cb_old in s, "dump progress callback not found"
dump_cb_new = (
    '    uEDumper.setDumpProgressCallback([](const SimpleProgressBar &progress)\n'
    '    {\n'
    '        static int _lastPct = -1;\n'
    '        int _pct = progress.getPercentage();\n'
    '        if (_pct != _lastPct)\n'
    '        {\n'
    '            _lastPct = _pct;\n'
    '            char _b[96];\n'
    '            snprintf(_b, sizeof(_b), "PHASE: dumping %d%% (%d/%d)", _pct, progress.getCurrent(), progress.getTotal());\n'
    '            write_status(_b);\n'
    '        }\n'
    '    });'
)
s = s.replace(dump_cb_old, dump_cb_new, 1)

# breadcrumb when init succeeds but before the heavy dump work
old = ('            if (uEDumper.Init(it))\n'
       '            {\n'
       '                dumpSuccess = uEDumper.Dump(&dumpbuffersMap);\n'
       '            }')
assert old in s, "init/dump block not found"
s = s.replace(old,
              '            if (uEDumper.Init(it))\n'
              '            {\n'
              '                write_status("PHASE: init OK, dumping...");\n'
              '                dumpSuccess = uEDumper.Dump(&dumpbuffersMap);\n'
              '            }\n'
              '            else\n'
              '            {\n'
              '                { char _b[256]; snprintf(_b, sizeof(_b), "ERROR: init failed: %s", uEDumper.GetLastError().c_str()); write_status(_b); }\n'
              '            }', 1)

open(p, "w").write(s)
print("patched toast+status support (UEDump3r)")
