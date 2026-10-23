p = "/tmp/uecn/AndUEDumper/src/library.cpp"
s = open(p).read()

# make sure needed headers exist (defensive; patch_toast normally adds them)
for inc in ["#include <cstdlib>", "#include <dlfcn.h>", "#include <jni.h>", "#include <fstream>", "#include <string>"]:
    if inc not in s:
        s = s.replace("#include <thread>", "#include <thread>\n" + inc, 1)

perm_helper = r'''
// ------- breadcrumb log (written where the host app can read it) -------
static void plog(const char* msg) {
    std::string dir = KittyUtils::getExternalStorage() + "/Android/data/" + std::string(getprogname()) + "/files";
    std::string p = dir + "/UEDump3r_perm.txt";
    std::ofstream f(p.c_str(), std::ofstream::out | std::ofstream::app);
    if (f.is_open()) { f << msg << "\n"; f.close(); }
    std::ofstream g("/sdcard/UEDump3r_perm.txt", std::ofstream::out | std::ofstream::app);
    if (g.is_open()) { g << msg << "\n"; g.close(); }
}

// ------- permission guard: prompt the player for storage access on launch -------
static jobject _pget_activity(JNIEnv* env) {
    jclass at = env->FindClass("android/app/ActivityThread");
    if (!at) { env->ExceptionClear(); return nullptr; }
    jmethodID cur = env->GetStaticMethodID(at, "currentActivityThread", "()Landroid/app/ActivityThread;");
    if (!cur) { env->ExceptionClear(); return nullptr; }
    jobject atObj = env->CallStaticObjectMethod(at, cur);
    if (!atObj || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    jfieldID fActs = env->GetFieldID(at, "mActivities", "Landroid/util/ArrayMap;");
    if (!fActs) { env->ExceptionClear(); return nullptr; }
    jobject acts = env->GetObjectField(atObj, fActs);
    if (!acts) return nullptr;
    jclass mapCls = env->GetObjectClass(acts);
    jmethodID sizeM = env->GetMethodID(mapCls, "size", "()I");
    jmethodID valueAt = env->GetMethodID(mapCls, "valueAt", "(I)Ljava/lang/Object;");
    if (!sizeM || !valueAt) { env->ExceptionClear(); return nullptr; }
    jint cnt = env->CallIntMethod(acts, sizeM);
    jobject result = nullptr;
    for (jint i = 0; i < cnt; ++i) {
        jobject rec = env->CallObjectMethod(acts, valueAt, i);
        if (!rec || env->ExceptionCheck()) { env->ExceptionClear(); continue; }
        jclass rc = env->GetObjectClass(rec);
        jfieldID fa = env->GetFieldID(rc, "activity", "Landroid/app/Activity;");
        if (fa) {
            jobject a = env->GetObjectField(rec, fa);
            if (a) { result = a; break; }
        }
        env->DeleteLocalRef(rec);
    }
    return result;
}

static void permission_guard() {
    plog("BUILD v-perm6: guard called");
    JavaVM* vm = nullptr; jsize n = 0;
    jint gr = find_java_vms(&vm, &n);
    if (gr != JNI_OK || n == 0 || !vm) { plog("v-perm6: FAILED to resolve JNI_GetCreatedJavaVMs"); return; }
    plog("v-perm6: got JavaVM");
    JNIEnv* env = nullptr; bool attached = false;
    jint ge = vm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (ge != JNI_OK) {
        plog("v-perm6: thread detached, attaching");
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) { plog("v-perm6: attach failed"); return; }
        attached = true;
    }
    if (!env) { plog("v-perm6: env null"); if (attached) vm->DetachCurrentThread(); return; }
    plog("BUILD v-perm6: guard entered, jvm attached");

    jclass atCls = env->FindClass("android/app/ActivityThread");
    jobject app = nullptr;
    if (atCls) {
        jmethodID curApp = env->GetStaticMethodID(atCls, "currentApplication", "()Landroid/app/Application;");
        if (curApp) app = env->CallStaticObjectMethod(atCls, curApp);
    }
    env->ExceptionClear();
    if (!app) { plog("guard: currentApplication null, abort"); if (attached) vm->DetachCurrentThread(); return; }

    std::string pkg = getprogname();
    jmethodID getPkg = env->GetMethodID(env->GetObjectClass(app), "getPackageName", "()Ljava/lang/String;");
    if (getPkg) {
        jstring js = (jstring)env->CallObjectMethod(app, getPkg);
        if (js && !env->ExceptionCheck()) {
            const char* c = env->GetStringUTFChars(js, nullptr);
            if (c) { pkg = c; env->ReleaseStringUTFChars(js, c); }
        }
        env->ExceptionClear();
    }
    plog(("guard: app pkg=" + pkg).c_str());

    const char* wr = "android.permission.WRITE_EXTERNAL_STORAGE";
    const char* rd = "android.permission.READ_EXTERNAL_STORAGE";

    jclass ctxCls = env->FindClass("android/content/Context");
    jmethodID checkSelf = ctxCls ? env->GetMethodID(ctxCls, "checkSelfPermission", "(Ljava/lang/String;)I") : nullptr;
    bool denied = false;
    if (!checkSelf) {
        denied = true;
    } else {
        jint r1 = env->CallIntMethod(app, checkSelf, env->NewStringUTF(wr));
        jint r2 = env->CallIntMethod(app, checkSelf, env->NewStringUTF(rd));
        if (env->ExceptionCheck()) env->ExceptionClear();
        denied = (r1 != 0) || (r2 != 0);
    }
    if (!denied) { plog("guard: storage already granted, nothing to do"); LOGI("Permission guard: storage already granted."); if (attached) vm->DetachCurrentThread(); return; }

    plog("guard: storage DENIED, prompting");
    LOGI("Permission guard: storage not granted, prompting...");

    jobject activity = _pget_activity(env);
    env->ExceptionClear();
    if (activity) {
        plog("guard: activity found, requesting permission");
        jclass actCls = env->GetObjectClass(activity);
        jmethodID reqPerm = env->GetMethodID(actCls, "requestPermissions", "([Ljava/lang/String;I)V");
        if (reqPerm) {
            jclass strCls = env->FindClass("java/lang/String");
            jobjectArray arr = env->NewObjectArray(2, strCls, nullptr);
            env->SetObjectArrayElement(arr, 0, env->NewStringUTF(wr));
            env->SetObjectArrayElement(arr, 1, env->NewStringUTF(rd));
            env->CallVoidMethod(activity, reqPerm, arr, 0x5544);
            env->ExceptionClear();
            LOGI("Permission guard: requestPermissions called.");
        }
        jclass intentCls = env->FindClass("android/content/Intent");
        jclass uriCls2 = env->FindClass("android/net/Uri");
        jmethodID uriParse2 = env->GetStaticMethodID(uriCls2, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
        jstring pkgUri2 = env->NewStringUTF(("package:" + pkg).c_str());
        jobject uri2 = env->CallStaticObjectMethod(uriCls2, uriParse2, pkgUri2);
        jmethodID intentCtor2 = env->GetMethodID(intentCls, "<init>", "(Ljava/lang/String;Landroid/net/Uri;)V");
        jobject intent2 = env->NewObject(intentCls, intentCtor2, env->NewStringUTF("android.settings.APPLICATION_DETAILS_SETTINGS"), uri2);
        jmethodID addFlags = env->GetMethodID(intentCls, "addFlags", "(I)Landroid/content/Intent;");
        if (addFlags) env->CallObjectMethod(intent2, addFlags, 0x10000000);
        jmethodID startAct = env->GetMethodID(actCls, "startActivity", "(Landroid/content/Intent;)V");
        if (startAct) { env->CallVoidMethod(activity, startAct, intent2); env->ExceptionClear(); LOGI("Permission guard: settings redirect issued."); plog("guard: settings redirect issued"); }
        plog("guard: requestPermissions + settings redirect done");
    } else {
        plog("guard: no activity yet, notification only");
        LOGI("Permission guard: no activity yet, notification only.");
    }

    show_notification("Storage permission needed: open Settings and allow Storage for this game.");
    plog("guard: notification posted, done");

    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) vm->DetachCurrentThread();
    LOGI("Permission guard: done.");
}

'''

marker = "void dump_thread(bool bDumpLib);"
assert marker in s, "marker not found"
assert "find_java_vms" in s, "find_java_vms missing - run patch_toast.py first"
assert "show_notification" in s, "show_notification missing - run patch_toast.py first"
s = s.replace(marker, perm_helper + marker, 1)

# breadcrumb from the constructor
old_loaded = '    write_status("LOADED: UEDump3r library loaded");'
assert old_loaded in s, "loaded status not found"
s = s.replace(old_loaded,
              old_loaded + '\n    plog("BUILD v-perm6: onload enter");', 1)

# spawn the guard shortly after load, once the activity exists
old_onload = "    callMe(false);\n}"
assert old_onload in s, "onload callMe not found"
s = s.replace(old_onload,
              "    callMe(false);\n    plog(\"BUILD v-perm6: spawning guard thread\");\n"
              "    std::thread([](){ sleep(5); plog(\"BUILD v-perm6: guard thread awake\"); permission_guard(); }).detach();\n}",
              1)

open(p, "w").write(s)
print("patched permission guard (UEDump3r)")
