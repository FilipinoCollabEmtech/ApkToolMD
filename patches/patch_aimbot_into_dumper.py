# Patch library.cpp to add aimbot functionality into the dumper .so
# This merges aimbot into the whitelisted dumper .so.
#
# Two independent mechanisms:
#  1) ProcessEvent vtable scanner - finds the REAL UObject::ProcessEvent by
#     scoring vtable slots (the dump's "UObject$$ProcessInternal" entry is a
#     mislabelled blueprint function, NOT ProcessEvent).
#  2) Polling aimbot - finds MyPlayerController via the dumper's own
#     GUObjectArray walk and writes ControlRotation directly. Works even if
#     the ProcessEvent hook cannot be installed.
#
# IMPORTANT (2026-10-20): UEMemory::vm_rpm_ptr() is READ ONLY - it calls
# kMgr.readMem().  Every "write" in the old version of this patch was
# therefore silently a READ and the aimbot never changed a single byte of
# the game.  All writes now go through vm_wpm_ptr() (kMgr.writeMem()).

p = "/tmp/uecn/AndUEDumper/src/library.cpp"
s = open(p).read()

# 1. Add includes at top (after existing includes)
NEW_INCLUDES = (
    "#include <cmath>\n"
    "#include <cstdint>\n"
    "#include <cstdio>\n"
    "#include <cstdlib>\n"
    "#include <cctype>\n"
    "#include <cstring>\n"
    "#include <fstream>\n"
    "#include <string>\n"
    "#include <time.h>\n"
    "#include <signal.h>\n"
    "#include <fcntl.h>\n"
    "#include <unistd.h>\n"
    "#include <sys/mman.h>\n"
    "#include <dlfcn.h>\n"
    "#include <stdarg.h>\n"
    "#include <pthread.h>\n"
    "#include <atomic>\n"
    "#include <vector>\n"
    "#include <mutex>\n"
    "#include <KittyArm64.hpp>\n"
    "#include \"UE/UEWrappers.hpp\""
)
if "#include <cmath>" not in s:
    s = s.replace('#include <thread>', '#include <thread>\n' + NEW_INCLUDES)

# 2. Add aimbot-specific globals after the UE_Games definition
aimbot_globals = r'''
// ===== AIMBOT GLOBALS =====

// ---------------------------------------------------------------------------
// CRASH DIAGNOSTICS
//
// When the game dies we want to know WHERE.  Two things:
//
// 1) a breadcrumb: every risky operation sets it BEFORE it runs, so the
//    crash file says what we were doing at the moment of the crash
// 2) a signal handler: SIGSEGV/SIGBUS/SIGFPE/SIGILL/SIGABRT are caught and
//    written to /sdcard/apktoolmd_crash.txt (with a backtrace) before the
//    default handler is restored and the signal re-raised
// ---------------------------------------------------------------------------
static char g_lastOp[160] = "startup";
static char g_lastDetail[160] = "";
static char g_opRing[8][120];
static int g_opRingPos = 0;
static char g_crashLine[512] = "";    // pre-formatted, so the signal handler
                                      // only has to write() (snprintf in a
                                      // signal handler can deadlock)
static std::mutex g_lastOpMutex;

static void set_last_op(const char* op, const char* detail = "")
{
    std::lock_guard<std::mutex> lk(g_lastOpMutex);
    snprintf(g_lastOp, sizeof(g_lastOp), "%s", op ? op : "?");
    snprintf(g_lastDetail, sizeof(g_lastDetail), "%s", detail ? detail : "");
    snprintf(g_opRing[g_opRingPos % 8], sizeof(g_opRing[0]), "%s %s",
             op ? op : "?", detail ? detail : "");
    g_opRingPos++;
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    snprintf(g_crashLine, sizeof(g_crashLine),
             "\n=== CRASH signal ===\ntime=%02d:%02d:%02d.%03d\nlastOp=%s\nlastDetail=%s\n",
             (int)((ts.tv_sec / 3600) % 24), (int)((ts.tv_sec / 60) % 60),
             (int)(ts.tv_sec % 60), (int)(ts.tv_nsec / 1000000),
             g_lastOp, g_lastDetail);
}

static void crash_handler(int sig, siginfo_t* info, void*)
{
    // Async-signal-safe: only write() with pre-formatted buffers.
    int fd = open("/sdcard/apktoolmd_crash.txt", O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        write(fd, g_crashLine, strlen(g_crashLine));
        char extra[128];
        int m = snprintf(extra, sizeof(extra), "sig=%d addr=%p\n", sig,
                         info ? info->si_addr : nullptr);
        if (m > 0) write(fd, extra, m);
        for (int i = 0; i < 8; i++) {
            int idx = (g_opRingPos + i) % 8;
            if (!g_opRing[idx][0]) continue;
            char line[160];
            int lm = snprintf(line, sizeof(line), "  %d: %s\n", i, g_opRing[idx]);
            if (lm > 0) write(fd, line, lm);
        }
        close(fd);
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_crash_handler()
{
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sa.sa_sigaction = crash_handler;
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
    sigaction(SIGFPE, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
}

// The introspection module (patch_introspect.py) is inserted right after this
// block, so its entry points are declared here.
namespace Intro {
    void tick(int screenW, int screenH);
    void sample_bones();
    bool dump_full_info();
}

// Batched engine calls (one un-patch / re-patch for many calls).
static void call_engine_functions_batch(void* const* objs, void* const* funcs, void* const* params, int count);

namespace Offsets {
    constexpr uintptr_t GNames            = 0x46D2E90;
    constexpr uintptr_t GUObjectArray     = 0x4822620;
    constexpr uintptr_t UWorld            = 0x493F400;
    constexpr uintptr_t GEngine           = 0x493C1F8;
    constexpr uintptr_t SetControlRotation = 0x27F5BD8; // AController::SetControlRotation(FRotator&)

    // UE4.22 struct offsets (from dump Offsets.hpp)
    constexpr uintptr_t UObject_InternalIndex   = 0xC;
    constexpr uintptr_t FUObjectItem_Size       = 0x18;
    constexpr uintptr_t UStruct_PropertiesSize  = 0x50;
    constexpr uintptr_t UStruct_Children        = 0x48;
    constexpr uintptr_t UFunction_EFunctionFlags= 0x98;
    constexpr uintptr_t UFunction_ParamSize     = 0x9E;

    // AMyPlayerController (size 0x880)
    constexpr uintptr_t MyPC_EnemyAIM     = 0x740;
    constexpr uintptr_t MyPC_TimerAIM     = 0x728;
    constexpr uintptr_t MyPC_tAIM         = 0x72C;
    constexpr uintptr_t MyPC_Sensitivity  = 0x730;
    constexpr uintptr_t MyPC_AngleAIM     = 0x734;
    constexpr uintptr_t MyPC_AIMStep      = 0x738;
    constexpr uintptr_t MyPC_bAIM         = 0x820;
    constexpr uintptr_t MyPC_bAIMifZoom   = 0x821;
    constexpr uintptr_t MyPC_bAutoZoom    = 0x822;
    constexpr uintptr_t MyPC_bFireBut     = 0x830;   // the fire button
    constexpr uintptr_t MyPC_bFireDoubleTap = 0x831;
    constexpr uintptr_t MyPC_bAutoFire    = 0x832;
    // ---- bullet spread (removed so every shot goes where it points) ----
    constexpr uintptr_t AMan_ManSpread       = 0x870;  // float
    constexpr uintptr_t AMan_Weapons         = 0x758;  // AWeapon* Weapons[6]
    constexpr uintptr_t AWeapon_WeaponSpread = 0x4C8;  // float
    constexpr uintptr_t AWeapon_ZoomSpread   = 0x514;  // float
    constexpr uintptr_t AWeapon_CurrentRecoil = 0x3C4; // float
    constexpr uintptr_t AWeapon_CrosshairRecoil = 0x3C8;// float
    constexpr uintptr_t GameMode_PlayerSpread = 0x468; // float
    constexpr uintptr_t MyPC_PlayerTeam  = 0x80C; // int

    // AMan (SpecialForces.Man) - only valid on Man-derived actors
    constexpr uintptr_t AMan_Team        = 0x860; // int
    constexpr uintptr_t AMan_MaxHealth   = 0x864; // int
    constexpr uintptr_t AMan_Health      = 0x868; // int

    // AActor
    constexpr uintptr_t AActor_BeingDestroyedFlags = 0x84; // bit 0x4 = bActorIsBeingDestroyed

    constexpr uintptr_t AController_ControlRot = 0x398; // AController::ControlRotation (verified from dump)
    constexpr uintptr_t APlayerController_Player    = 0x3A8; // ULocalPlayer* (only set on the local PC)
    constexpr uintptr_t APlayerController_ACKPawn  = 0x3B0; // APawn*
    constexpr uintptr_t APlayerController_Pawn      = 0x360; // AController::Pawn
    constexpr uintptr_t APlayerController_CamMgr   = 0x3C8; // APlayerCameraManager*
    constexpr uintptr_t ACamMgr_CameraCache         = 0x3A0; // FCameraCacheEntry
    constexpr uintptr_t ACamMgr_CameraCachePrivate  = 0x1A20;
    constexpr uintptr_t ACamMgr_LastFrameCache      = 0x930;
    constexpr uintptr_t ACamMgr_ViewTarget          = 0xEC0; // FTViewTarget
    constexpr uintptr_t ACamMgr_AnimCamActor        = 0x2628; // ACameraActor* (animation camera)
    constexpr uintptr_t FViewTarget_Target          = 0x0;  // AActor*
    constexpr uintptr_t FViewTarget_POV             = 0x10; // FMinimalViewInfo
    constexpr uintptr_t FCameraCacheEntry_POV       = 0x10; // FMinimalViewInfo
    constexpr uintptr_t FMinimalViewInfo_Location   = 0x0;  // FVector
    constexpr uintptr_t FMinimalViewInfo_Rotation   = 0xC;  // FRotator
    constexpr uintptr_t AMan_Mesh         = 0x390;   // ACharacter::Mesh
    constexpr uintptr_t AChar_Capsule     = 0x3A0;   // ACharacter::CapsuleComponent
    constexpr uintptr_t UCapsule_HalfHeight = 0x590; // UCapsuleComponent::CapsuleHalfHeight
    constexpr uintptr_t UCapsule_Radius     = 0x594; // UCapsuleComponent::CapsuleRadius
    constexpr uintptr_t USkelMesh_CachedCompXforms = 0x7A8; // TArray<FTransform> (Data,Count)
    constexpr uintptr_t USkelMesh_CachedBoneXforms  = 0x798; // TArray<FTransform> (Data,Count)
    constexpr uintptr_t USkinnedMesh_SkeletalMesh   = 0x5A0; // USkeletalMesh*
    constexpr uintptr_t USkeletalMesh_ImportedBounds = 0x50; // FBoxSphereBounds (Origin, BoxExtent)
    constexpr uintptr_t USkeletalMesh_Sockets        = 0x330; // TArray<USkeletalMeshSocket*>
    constexpr uintptr_t USkelSocket_SocketName       = 0x28; // FName
    constexpr uintptr_t USceneComp_RelRot      = 0x170; // FRotator
    constexpr uintptr_t AActor_RootComp   = 0x158;   // AActor::RootComponent
    constexpr uintptr_t USceneComp_RelLoc = 0x164;   // USceneComponent::RelativeLocation
    constexpr uintptr_t FNameEntry_Name   = 0xC;     // FNameEntry::Name (ANSI)

    // ---- the GAME's own player list (from the SDK dump) ----
    // AMyPlayerController::MyGM            0x718 -> AMyGameMode
    // AMyGameMode::MyGameState             0x508 -> AMyGameState
    // AMyGameState::CurrentPlayersInfo     0x398 -> TArray<FPlayerInfo>
    // AMyGameState::AllPlayers             0x3A8 -> TArray<AMan*>
    // FPlayerInfo: MyMan 0x18, PlayerName 0x0 (FString), Team 0x3C,
    //              bOut 0x7F, bInfected 0x80
    constexpr uintptr_t MyPC_MyGM             = 0x718;
    constexpr uintptr_t GameMode_MyGameState  = 0x508;
    constexpr uintptr_t GameState_PlayersInfo = 0x398;
    constexpr uintptr_t GameState_AllPlayers = 0x3A8;
    constexpr uintptr_t PlayerInfo_MyMan     = 0x18;
    constexpr uintptr_t PlayerInfo_Team      = 0x3C;
    constexpr uintptr_t PlayerInfo_bOut      = 0x7F;
    constexpr uintptr_t PlayerInfo_bInfected = 0x80;
}

struct FVector {
    float X, Y, Z;
    FVector operator-(const FVector& o) const { return FVector{X - o.X, Y - o.Y, Z - o.Z}; }
    FVector operator+(const FVector& o) const { return FVector{X + o.X, Y + o.Y, Z + o.Z}; }
    FVector operator*(float f) const { return FVector{X * f, Y * f, Z * f}; }
    FVector& operator+=(const FVector& o) { X += o.X; Y += o.Y; Z += o.Z; return *this; }
};
struct FRotator { float Pitch, Yaw, Roll; };

// The introspection module (defined after this block) can also give us the
// real head bone of a target.
namespace Intro {
    bool head_bone_position(uint8_t* actor, FVector& out);
    void find_bone_functions();
    void remove_bullet_spread();   // zeroes the spread (offsets from the
                                   // game's own reflection data)
}

static uintptr_t g_libUE4_base = 0;
static uintptr_t g_GNames = 0;
static uintptr_t g_GUObjectArray = 0;
static uintptr_t g_UWorld = 0;
static uintptr_t g_ProcessEvent = 0;
static uintptr_t g_ProcessEventIndex = 0;

// ---------------------------------------------------------------------------
// The GAME thread.
//
// The game calls UObject::ProcessEvent from its own thread - that is the only
// thread that may call into the engine.  Calling a UFunction from one of our
// own threads touches the world / the net driver from the wrong thread and
// crashes the game (it did).
//
// The thread id is captured the first time the ProcessEvent hook is entered.
// ---------------------------------------------------------------------------
static std::atomic<uint64_t> g_gameThreadTid{0};

static inline bool on_game_thread()
{
    uint64_t tid = g_gameThreadTid.load(std::memory_order_relaxed);
    return tid != 0 && (uint64_t)pthread_self() == tid;
}

static bool g_aimbotEnabled = true;
static bool g_headOnly = true;
static bool g_instantAim = true;
static bool g_autoFire = true;
static bool g_keepGameAssist = true;   // 1 = use the game's own aim assist, 0 = force it OFF
static bool g_espEnabled = true;        // on-screen distance label
static bool g_skipDead = true;          // do not target dead bodies
static bool g_skipTeammates = true;      // do not target teammates
static bool g_useEngineAim = true;      // 1 = we write ControlRotation + the POV caches
static bool g_useGameAssist = true;     // 1 = we also drive the game's own assist
static bool g_useEngineCall = true;     // 1 = also call AController::SetControlRotation
                                        //     through ProcessEvent (the game thread)
static int  g_pitchSign = 1;            // +1 = UE convention (positive pitch = look UP)
static float g_headZ = 0.60f;           // head height = capsule centre + headZ * halfHeight
static int g_screenW = 0;                // viewport size (written by the app)
static int g_screenH = 0;

static float g_aimStrength = 0.50f;     // 0.05..1.0 - how hard we pull the view
                                        // back to the target (1.0 = hard lock)
static bool g_fullDumpRequested = false; // fulldump=1 -> write the full dump
static bool g_noSpread = true;          // nospread=0 -> keep the bullet spread
static bool g_bonesEnabled = true;       // bones=0 -> never call the engine bone functions
static bool g_boneFuncsLookedUp = false; // the bone UFunction lookup runs once, on the aim thread

// The Overlay tails /sdcard/UEDump3r_trace.txt, so writing per-frame logs
// there spams the user's console.  Aimbot logs go to their OWN file instead;
// only a few rare, one-off events are mirrored into the trace file.
static void aimbot_log(const char* fmt, ...) {
    // Truncate the log once per process start so it cannot grow forever.
    static bool truncated = false;
    FILE* fp = fopen("/sdcard/aimbot_debug.log", truncated ? "a" : "w");
    if (!fp) return;
    truncated = true;
    // a timestamp so the timing of the log lines is visible
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    fprintf(fp, "[%02d:%02d:%02d.%03d] ", (int)((ts.tv_sec / 3600) % 24),
            (int)((ts.tv_sec / 60) % 60), (int)(ts.tv_sec % 60), (int)(ts.tv_nsec / 1000000));
    va_list args;
    va_start(args, fmt);
    vfprintf(fp, fmt, args);
    fputc('\n', fp);
    fclose(fp);
    va_end(args);
}

// Rare, important events only (goes to the overlay's console).
static void aimbot_write_status(const char* fmt, ...) {
    FILE* fp = fopen("/sdcard/UEDump3r_status.txt", "a");
    if (!fp) return;
    va_list args;
    va_start(args, fmt);
    fprintf(fp, "[status] ");
    vfprintf(fp, fmt, args);
    fputc('\n', fp);
    fclose(fp);
    va_end(args);
}

static void aimbot_write_trace(const char* fmt, ...) {
    // Format ONCE - the old code called aimbot_log(fmt) without the varargs,
    // which re-formatted the format string with garbage.
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    aimbot_log("%s", buf);
    static int mirror = 0;
    if (mirror++ > 12) return;   // only mirror the first few into the console
    FILE* fp = fopen("/sdcard/UEDump3r_trace.txt", "a");
    if (!fp) return;
    fprintf(fp, "[trace] %s\n", buf);
    fclose(fp);
}

static uint64_t now_us()
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000ull + (uint64_t)ts.tv_nsec / 1000ull;
}

// The tool writes "true"/"false" - atoi() would turn "false" into 0 and
// "true" into 0 as well, so parse explicitly.
static bool parse_bool(const char* v)
{
    while (*v == ' ' || *v == '\t') v++;
    if (strncmp(v, "true", 4) == 0 || strncmp(v, "TRUE", 4) == 0 || strncmp(v, "True", 4) == 0) return true;
    if (strncmp(v, "false", 5) == 0 || strncmp(v, "FALSE", 5) == 0 || strncmp(v, "False", 5) == 0) return false;
    return (atoi(v) != 0);
}

static void read_config() {
    FILE* fp = fopen("/sdcard/aimbot_config.txt", "r");
    if (!fp) return;
    char line[128];
    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, "enabled=", 8) == 0) g_aimbotEnabled = parse_bool(line + 8);
        else if (strncmp(line, "head=", 5) == 0) g_headOnly = parse_bool(line + 5);
        else if (strncmp(line, "instant=", 8) == 0) g_instantAim = parse_bool(line + 8);
        else if (strncmp(line, "autofire=", 9) == 0) g_autoFire = parse_bool(line + 9);
        else if (strncmp(line, "gameassist=", 10) == 0) g_keepGameAssist = parse_bool(line + 10);
        else if (strncmp(line, "esp=", 4) == 0) g_espEnabled = parse_bool(line + 4);
        else if (strncmp(line, "skipdead=", 9) == 0) g_skipDead = parse_bool(line + 9);
        else if (strncmp(line, "skipteam=", 9) == 0) g_skipTeammates = parse_bool(line + 9);
        else if (strncmp(line, "engineaim=", 10) == 0) g_useEngineAim = parse_bool(line + 10);
        else if (strncmp(line, "gameaim=", 8) == 0) g_useGameAssist = parse_bool(line + 8);
        else if (strncmp(line, "enginecall=", 11) == 0) g_useEngineCall = parse_bool(line + 11);
        else if (strncmp(line, "nospread=", 9) == 0) g_noSpread = parse_bool(line + 9);
        else if (strncmp(line, "headz=", 6) == 0) {
            float v = (float)atof(line + 6);
            if (v > 0.05f && v < 2.0f) g_headZ = v;
        }
        else if (strncmp(line, "screenw=", 8) == 0) g_screenW = atoi(line + 8);
        else if (strncmp(line, "screenh=", 8) == 0) g_screenH = atoi(line + 8);
        else if (strncmp(line, "aimstr=", 7) == 0) {
            float v = (float)atof(line + 7);
            if (v >= 0.05f && v <= 1.0f) g_aimStrength = v;
        }
        else if (strncmp(line, "fulldump=", 9) == 0) {
            if (atoi(line + 9) != 0) g_fullDumpRequested = true;
        }
        else if (strncmp(line, "bones=", 6) == 0) {
            g_bonesEnabled = (atoi(line + 6) != 0);
        }
    }
    fclose(fp);
}

// Reading the config file on every ProcessEvent call (the game calls it
// thousands of times per second) was a huge waste - throttle it.
static uint64_t g_lastConfigRead = 0;
static void read_config_throttled()
{
    uint64_t now = now_us();
    if (g_lastConfigRead && (now - g_lastConfigRead) < 100000) return;   // 100 ms
    g_lastConfigRead = now;
    read_config();
}

// ---------------------------------------------------------------------------
// Memory WRITE helper.
//
// UEMemory::vm_rpm_ptr() is READ ONLY (it calls kMgr.readMem()), so all the
// old "writes" were silently reads and the aimbot never touched the game.
// kMgr.writeMem() is the real write path (the dumper initialises the memory
// manager for the game's own pid, so this is a write into ourselves).
// ---------------------------------------------------------------------------
static bool vm_wpm_ptr(void* address, const void* src, size_t len)
{
    if (!address || !src || !len) return false;
    // The memory manager is initialised for the game's own pid, so this is a
    // write into ourselves (process_vm_writev).
    if (UEMemory::kMgr.writeMem((uintptr_t)address, (void*)src, len) == len) return true;
    // Fallback: we ARE the game process, so a direct write works too.  Only
    // do it when the address really is in a writable region (the validator
    // can have a stale map, but a bogus address would kill the game).
    if (!UEMemory::kPtrValidator.isPtrWritable((uintptr_t)address, len)) {
        static int warned = 0;
        if (warned++ < 3) {
            aimbot_log("AIM: write to %p rejected (not writable)", (void*)address);
        }
        return false;
    }
    memcpy(address, src, len);
    return true;
}

// ---------------------------------------------------------------------------
// FName -> string.
//
// UE4.22 uses a TNameEntryArray (NOT FNamePool):
//   GNames global -> pointer to the chunk array
//   chunkArray[chunkIndex] -> chunk
//   chunk[withinIndex]      -> FNameEntry
//   FNameEntry + 0xC         -> the ANSI name
// ---------------------------------------------------------------------------
static std::string resolve_fname(int32_t id)
{
    if (id < 0 || id > (1 << 22)) return std::string();
    uintptr_t chunks = 0;
    if (!UEMemory::vm_rpm_ptr((void*)g_GNames, &chunks, sizeof(chunks)) || !chunks) return std::string();

    const int32_t kPerChunk = 16384;
    uintptr_t chunk = 0;
    if (!UEMemory::vm_rpm_ptr((void*)(chunks + (uintptr_t)(id / kPerChunk) * sizeof(uintptr_t)),
                              &chunk, sizeof(chunk)) || !chunk) return std::string();
    uintptr_t entry = 0;
    if (!UEMemory::vm_rpm_ptr((void*)(chunk + (uintptr_t)(id % kPerChunk) * sizeof(uintptr_t)),
                              &entry, sizeof(entry)) || !entry) return std::string();

    char buf[192];
    if (!UEMemory::vm_rpm_ptr((void*)(entry + Offsets::FNameEntry_Name), buf, sizeof(buf) - 1)) return std::string();
    buf[sizeof(buf) - 1] = '\0';
    for (size_t i = 0; buf[i]; i++) {
        unsigned char c = (unsigned char)buf[i];
        if (c < 0x20 || c > 0x7E) return std::string();   // wide / garbage
    }
    return std::string(buf);
}

// The profile that was initialised for this game (it owns the UE offsets).
static UE_Offsets* active_offsets()
{
    for (auto &it : UE_Games) {
        auto vars = it->GetUEVars();
        if (vars && vars->GetBaseAddress() && vars->GetBaseAddress() == g_libUE4_base)
            return it->GetOffsets();
    }
    for (auto &it : UE_Games) {
        auto off = it->GetOffsets();
        if (off) return off;
    }
    return nullptr;
}

// Byte offset of a parameter inside a function's parms buffer.
// UE4.22 keeps the properties in the UStruct::Children linked list and the
// offset is stored in UProperty::Offset_Internal.
static int find_prop_offset(void* func, UE_Offsets* o, const char* name)
{
    if (!func || !o) return -1;
    uintptr_t child = 0;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)func + o->UStruct.Children), &child, sizeof(child))) {
        aimbot_log("AIM: find_prop_offset(%s): cannot read Children at +0x%lx",
                   name, (unsigned long)o->UStruct.Children);
        return -1;
    }
    if (!child) {
        aimbot_log("AIM: find_prop_offset(%s): Children is null (+0x%lx)",
                   name, (unsigned long)o->UStruct.Children);
        return -1;
    }

    int guard = 0;
    while (child && guard++ < 64) {
        uintptr_t namePtr = 0;
        if (UEMemory::vm_rpm_ptr((void*)(child + o->UObject.NamePrivate), &namePtr, sizeof(namePtr))) {
            int32_t idx = -1;
            if (UEMemory::vm_rpm_ptr((void*)namePtr, &idx, sizeof(idx))) {
                std::string nm = resolve_fname(idx);
                if (nm == name) {
                    int32_t poff = -1;
                    if (!UEMemory::vm_rpm_ptr((void*)(child + o->UProperty.Offset_Internal), &poff, sizeof(poff)))
                        return -1;
                    return poff;
                }
                if (guard <= 6) {
                    aimbot_log("AIM: prop list: idx=%d name='%s' (want '%s')",
                               idx, nm.c_str(), name);
                }
            }
        }
        uintptr_t next = 0;
        if (!UEMemory::vm_rpm_ptr((void*)(child + o->UField.Next), &next, sizeof(next))) break;
        child = next;
    }
    return -1;
}

static int func_param_size(void* func)
{
    uint16_t sz = 0;
    if (!func) return 0;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)func + Offsets::UFunction_ParamSize), &sz, sizeof(sz)))
        return 0;
    return (int)sz;
}

// ---------------------------------------------------------------------------
// ProcessEvent vtable scanner
//
// Scans the vtable of a live UObject (GWorld) and scores each slot by
// looking for the instruction signature of UObject::ProcessEvent:
//   - ADRP referencing GUObjectArray / ObjObjects
//   - LDR with immediate == UObject::InternalIndex (0xC)
//   - LDR with immediate == FUObjectItem::Size (0x18)
//   - LDR with immediate == UStruct::PropertiesSize (0x50)
//   - LDR with immediate == UFunction::ParamSize (0x9E)
//   - LDR with immediate == UStruct::Children (0x48)
// ---------------------------------------------------------------------------
static uintptr_t scan_vtable_for_process_event(uint8_t* uObject)
{
    if (!uObject) return 0;

    // Safe read of the vtable pointer (readability validated).
    uintptr_t vftAddr = 0;
    if (!UEMemory::vm_rpm_ptr((void*)uObject, &vftAddr, sizeof(vftAddr)) || !vftAddr) {
        LOGE("AIMBOT: cannot read vtable ptr at %p", uObject);
        return 0;
    }

    uintptr_t objArrayPtr = g_GUObjectArray;
    uintptr_t objObjectsPtr = 0;
    if (objArrayPtr) {
        UEMemory::vm_rpm_ptr((void*)(objArrayPtr + 0x10), &objObjectsPtr, sizeof(objObjectsPtr));
    }

    int bestScore = 0;
    int bestIdx = -1;
    uintptr_t bestAddr = 0;

    const int kMaxSlots = 120;
    for (int i = 0; i < kMaxSlots; i++) {
        uintptr_t fn = 0;
        if (!UEMemory::vm_rpm_ptr((void*)(vftAddr + (uintptr_t)i * sizeof(uintptr_t)), &fn, sizeof(fn))) break;
        if (!fn) break;

        int score = 0;
        bool ok[9] = {false,false,false,false,false,false,false,false,false};

        // Read up to 0x200 bytes of the function (safe read)
        uint32_t instrs[0x200 / 4];
        if (!UEMemory::vm_rpm_ptr((void*)fn, instrs, sizeof(instrs))) continue;

        for (size_t j = 0; j < (0x200 / 4); j++) {
            uint32_t insn = instrs[j];

            // --- ADRP / ADR : does it point at GUObjectArray / ObjObjects ? ---
            if (!ok[0] && (KittyArm64::is_insn_adrp(insn) || KittyArm64::is_insn_adr(insn))) {
                int64_t imm = 0;
                if (KittyArm64::decode_adr_imm(insn, &imm)) {
                    uintptr_t page = fn + (j * 4);
                    uintptr_t target = KittyArm64::is_insn_adrp(insn)
                        ? (page & ~(uintptr_t)4095) + (uintptr_t)imm
                        : page + (uintptr_t)imm;
                    uintptr_t deref = 0;
                    if (target > 0x1000 && (target & 7) == 0) {
                        UEMemory::vm_rpm_ptr((void*)target, &deref, sizeof(deref));
                    }
                    if ((objArrayPtr && (target == objArrayPtr || deref == objArrayPtr)) ||
                        (objObjectsPtr && (target == objObjectsPtr || deref == objObjectsPtr))) {
                        ok[0] = true;
                    }
                }
            }

            // --- LDR immediate offsets ---
            int32_t off = 0;
            if ((KittyArm64::is_insn_ld(insn) || KittyArm64::is_insn_ldst(insn)) &&
                KittyArm64::decode_ldrstr_uimm(insn, &off)) {
                if (off == (int32_t)Offsets::UObject_InternalIndex)    ok[1] = true;
                if (off == (int32_t)Offsets::FUObjectItem_Size)       ok[2] = true;
                if (off == (int32_t)Offsets::UStruct_PropertiesSize)  ok[3] = true;
                if (off == (int32_t)Offsets::UFunction_ParamSize)     ok[4] = true;
                if (off == (int32_t)Offsets::UStruct_Children)        ok[5] = true;
            }

            // --- ADD/SUB immediate (constant loads) ---
            int32_t addImm = KittyArm64::decode_addsub_imm(insn);
            if (addImm == (int32_t)Offsets::FUObjectItem_Size)       ok[6] = true;
            if (addImm == (int32_t)Offsets::UObject_InternalIndex)    ok[7] = true;
            if (addImm == (int32_t)Offsets::UFunction_ParamSize)     ok[8] = true;
        }

        for (int k = 0; k < 9; k++) if (ok[k]) score++;
        if (score > bestScore) { bestScore = score; bestIdx = i; bestAddr = fn; }
    }

    if (bestIdx >= 0 && bestScore >= 2) {
        LOGI("AIMBOT: ProcessEvent vtable scan -> index=%d score=%d addr=0x%lx rva=0x%lx",
             bestIdx, bestScore, bestAddr, bestAddr - g_libUE4_base);
        aimbot_write_trace("AIM: ProcessEvent FOUND index=%d score=%d rva=0x%lx",
                            bestIdx, bestScore, bestAddr - g_libUE4_base);
        g_ProcessEventIndex = (uintptr_t)bestIdx;
        return bestAddr;
    }

    LOGI("AIMBOT: ProcessEvent vtable scan FAILED (best score %d at index %d)", bestScore, bestIdx);
    aimbot_write_trace("AIM: ProcessEvent scan FAILED bestscore=%d index=%d", bestScore, bestIdx);
    return 0;
}

// ---------------------------------------------------------------------------
// Safe inline hook state
//
// A classic trampoline (copy the prologue to a new page) BREAKS if the
// prologue contains a PC-relative instruction (ADRP/ADR/B) - the relocated
// copy would compute the wrong target and corrupt the game.
//
// Instead we do "unpatch and call in place":
//   1. restore the original 4 bytes at the function entry
//   2. call the ORIGINAL function at its real address (PC-relative safe)
//   3. re-install the branch
// Another thread racing through the unpatched window simply runs the
// original - no crash, just a missed hook.
// ---------------------------------------------------------------------------
using ProcessEventFn = void(*)(void* obj, void* func, void* params);
static ProcessEventFn o_ProcessEvent = nullptr;
static uint8_t* g_trampoline = nullptr;
static bool g_hookInstalled = false;

struct SafeHook {
    uintptr_t target = 0;
    uint32_t  origInsn = 0;
    uint32_t  branchInsn = 0;
    bool     patched = false;
    std::atomic<bool> inHook{false};
    void*    origAddr = nullptr;   // callable original
};

static SafeHook g_peHook;         // UObject::ProcessEvent
static SafeHook g_scrHook;        // AController::SetControlRotation

static inline void call_original_pe(void* obj, void* func, void* params);

// ---------------------------------------------------------------------------
// Engine function access (ProcessEvent based)
//
// Instead of guessing UE's rotation conventions we can ASK THE ENGINE:
//   UKismetMathLibrary::FindLookAtRotation(Start, Target) -> FRotator
// That is the ground truth for the pitch sign and the yaw.
//
// We also use the engine to get the real head position:
//   USkeletalMeshSocket::GetSocketLocation(SkelComp) -> FVector (world space)
// ---------------------------------------------------------------------------
static uint8_t* g_setControlRotFunc = nullptr;   // Function Engine.Controller.SetControlRotation
static uint8_t* g_findLookAtFunc    = nullptr;   // Function Engine.KismetMathLibrary.FindLookAtRotation
static uint8_t* g_getSocketLocFunc  = nullptr;   // Function Engine.SkeletalMeshSocket.GetSocketLocation
static uint8_t* g_mathCDO           = nullptr;   // Default__KismetMathLibrary
static uint8_t* g_startFireFunc     = nullptr;   // Function Engine.PlayerController.StartFire

static void find_engine_functions()
{
    if (!UEWrappers::GetObjects()) return;

    if (!g_setControlRotFunc) {
        UE_UObject o = UEWrappers::GetObjects()->FindObject("Function Engine.Controller.SetControlRotation");
        if (o) g_setControlRotFunc = o.GetAddress();
    }
    if (!g_findLookAtFunc) {
        UE_UObject o = UEWrappers::GetObjects()->FindObject("Function Engine.KismetMathLibrary.FindLookAtRotation");
        if (o) g_findLookAtFunc = o.GetAddress();
    }
    if (!g_getSocketLocFunc) {
        UE_UObject o = UEWrappers::GetObjects()->FindObject("Function Engine.SkeletalMeshSocket.GetSocketLocation");
        if (o) g_getSocketLocFunc = o.GetAddress();
    }
    if (!g_mathCDO) {
        UE_UObject o = UEWrappers::GetObjects()->FindObjectFast("Default__KismetMathLibrary");
        if (o) g_mathCDO = o.GetAddress();
    }
    if (!g_startFireFunc) {
        // The trigger pull: StartFire is an [Exec] function, so it goes
        // through ProcessEvent - we zero the spread right before the shot.
        UE_UObject o = UEWrappers::GetObjects()->FindObject("Function Engine.PlayerController.StartFire");
        if (o) {
            g_startFireFunc = o.GetAddress();
            aimbot_write_trace("AIM: StartFire = %p", (uintptr_t)g_startFireFunc);
        }
    }

    static bool logged = false;
    if (!logged && g_setControlRotFunc && g_findLookAtFunc) {
        logged = true;
        aimbot_write_trace("AIM: engine funcs SCR=%p LOOKAT=%p SOCKETLOC=%p CDO=%p",
                            g_setControlRotFunc, g_findLookAtFunc, g_getSocketLocFunc, g_mathCDO);
    }
}

// Find the engine UFunctions we call.  FindObject() walks the whole object
// array (59k objects, GetFullName() on each) and takes SECONDS - that must
// not happen on the aim thread, so it runs on its own thread.
static void* engine_func_thread(void*)
{
    // The bone UFunctions are looked up here too - FindObject() walks the
    // whole object array and takes SECONDS, so it must never run on the game
    // thread (that froze the game and the ANR watchdog killed it).
    while (true) {
        find_engine_functions();
        if (g_setControlRotFunc && g_findLookAtFunc) {
            aimbot_write_trace("AIM: engine funcs ready (SCR=%p LOOKAT=%p SOCKETLOC=%p CDO=%p)",
                                g_setControlRotFunc, g_findLookAtFunc, g_getSocketLocFunc, g_mathCDO);
        }
        if (!g_boneFuncsLookedUp) {
            g_boneFuncsLookedUp = true;
            Intro::find_bone_functions();
        }
        sleep(5);
    }
    return nullptr;
}

// Call a UFunction through the (un-patched) engine ProcessEvent.
//
// ONLY on the game thread: a UFunction call touches the world, so calling it
// from one of our own threads crashes the game.  The callers that used to do
// that (the aim thread's pitch calibration, the socket lookup, PrintString)
// now fall back to their non-engine path.
static void call_engine_function(void* obj, void* func, void* params)
{
    if (!obj || !func) return;
    if (!on_game_thread()) {
        static int warned = 0;
        if (warned++ < 3) {
            aimbot_log("AIM: engine call skipped (not the game thread)");
        }
        return;
    }
    if (g_peHook.patched && o_ProcessEvent) {
        call_original_pe(obj, func, params);
    } else if (g_ProcessEvent) {
        ((ProcessEventFn)g_ProcessEvent)(obj, func, params);
    }
}

// The engine's own "look at" rotation - the ground truth for UE's pitch sign.
static bool engine_look_at(const FVector& from, const FVector& to, FRotator& out)
{
    if (!g_findLookAtFunc || !g_mathCDO) return false;

    static int offStart = -1, offTarget = -1, offRet = -1, psize = 0;
    static uint64_t lastTry = 0;
    if (offStart < 0) {
        uint64_t now = now_us();
        if (lastTry && (now - lastTry) < 2000000) return false;   // retry every 2 s
        lastTry = now;
        UE_Offsets* o = active_offsets();
        if (!o) return false;
        offStart  = find_prop_offset(g_findLookAtFunc, o, "Start");
        offTarget = find_prop_offset(g_findLookAtFunc, o, "Target");
        offRet    = find_prop_offset(g_findLookAtFunc, o, "ReturnValue");
        psize     = func_param_size(g_findLookAtFunc);
        aimbot_log("AIM: FindLookAtRotation layout Start=%d Target=%d Ret=%d parms=%d",
                   offStart, offTarget, offRet, psize);
    }
    if (offStart < 0 || offTarget < 0 || offRet < 0 || psize <= 0 || psize > 0x100) return false;

    uint8_t buf[0x100];
    memset(buf, 0, sizeof(buf));
    FVector s = from, t = to;
    memcpy(buf + offStart, &s, sizeof(FVector));
    memcpy(buf + offTarget, &t, sizeof(FVector));

    call_engine_function(g_mathCDO, g_findLookAtFunc, buf);

    FRotator r{};
    memcpy(&r, buf + offRet, sizeof(FRotator));
    if (!std::isfinite(r.Pitch) || !std::isfinite(r.Yaw)) return false;
    if (r.Pitch < -90.0f || r.Pitch > 90.0f) return false;
    out = r;
    return true;
}

// ---------------------------------------------------------------------------
// Polling aimbot: find MyPlayerController and write ControlRotation.
// Runs entirely in-process (no ptrace, no external reads).
// ---------------------------------------------------------------------------
static uint8_t* g_myPC = nullptr;
static bool g_socketLogged = false;

static int g_pollReason = 0;   // 0=ok 1=no controller 2=read fail 3=enemy null 4=no mesh 5=no root
static int g_pollHeartbeat = 0;

// --- camera helpers ---
struct FCamInfo { FVector loc; FRotator rot; bool valid; };

// The game uses its own AMyPlayerCameraManager, and we do not know which
// cache the live view actually reads.  All of them hold the SAME rotation
// (the camera manager copies the POV into every cache), but only SOME of
// them hold a real location:
//
//   CameraCache        (0x3A0)  -> location reads (0,0,0)  <- UNUSABLE
//   CameraCachePrivate (0x1A20) -> the real one
//   LastFrameCameraCache (0x930) -> location reads (0,0,0)  <- UNUSABLE
//   ViewTarget.POV     (0xEC0)  -> the real one
//
// So the source is picked by "has a real location", never by "moved the
// most" (they all move the same).  Aiming from an empty cache points the
// camera at the world origin -> a 150 deg whip -> the game crashed.
static int g_camSource = 0;      // 0=unknown 1=CameraCache 2=CameraCachePrivate
                                // 3=LastFrameCameraCache 4=ViewTarget.POV 5=ControlRotation
static int g_camSourceCheck = 0; // tick counter for the re-check
static bool g_camSourceLogged = false;

// Pick the camera cache that holds a real (non zero) location.
static int pick_cam_source(uintptr_t camMgr);

static bool read_cam_source(int which, uintptr_t camMgr, FRotator& rot, FVector& loc)
{
    uintptr_t pov = 0;
    switch (which) {
        case 1: pov = camMgr + Offsets::ACamMgr_CameraCache + Offsets::FCameraCacheEntry_POV; break;
        case 2: pov = camMgr + Offsets::ACamMgr_CameraCachePrivate + Offsets::FCameraCacheEntry_POV; break;
        case 3: pov = camMgr + Offsets::ACamMgr_LastFrameCache + Offsets::FCameraCacheEntry_POV; break;
        case 4: pov = camMgr + Offsets::ACamMgr_ViewTarget + Offsets::FViewTarget_POV; break;
        case 5: pov = 0; break;
        default: return false;
    }
    if (!pov) return false;
    if (!UEMemory::vm_rpm_ptr((void*)(pov + Offsets::FMinimalViewInfo_Location), &loc, sizeof(loc))) return false;
    if (!UEMemory::vm_rpm_ptr((void*)(pov + Offsets::FMinimalViewInfo_Rotation), &rot, sizeof(rot))) return false;
    return true;
}

// Pick the camera cache that holds a real (non zero) location.
static int pick_cam_source(uintptr_t camMgr)
{
    const int order[4] = {2, 4, 1, 3};
    for (int k = 0; k < 4; k++) {
        FRotator r{}; FVector l{};
        if (!read_cam_source(order[k], camMgr, r, l)) continue;
        if (l.X == 0.0f && l.Y == 0.0f && l.Z == 0.0f) continue;   // empty cache
        return order[k];
    }
    return 0;   // nothing usable
}

static bool get_camera_info(FCamInfo& out)
{
    out.valid = false;
    if (!g_myPC) return false;
    uintptr_t camMgr = 0;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::APlayerController_CamMgr), &camMgr, sizeof(camMgr))) return false;
    if (!camMgr) return false;

    if (g_camSource == 0 || ++g_camSourceCheck > 200) {
        // (re)pick the source - the camera manager can change during a match
        g_camSourceCheck = 0;
        int picked = pick_cam_source(camMgr);
        if (!picked) {
            if (!g_camSourceLogged) {
                g_camSourceLogged = true;
                aimbot_write_trace("AIM: no camera cache with a real location - using our pawn's eyes");
            }
            // The rotation is still valid in every cache - only the location
            // is missing.  Return it and let the caller fall back to the
            // pawn's eye position.
            for (int w = 1; w <= 4; w++) {
                if (!read_cam_source(w, camMgr, out.rot, out.loc)) continue;
                out.loc = FVector{};
                out.valid = true;
                return true;
            }
            return false;
        }
        if (picked != g_camSource) {
            g_camSource = picked;
            aimbot_write_trace("AIM: camera source = %d (has a real location)", g_camSource);
            // Report every candidate once so the state is visible.
            for (int w = 1; w <= 4; w++) {
                FRotator r{}; FVector l{};
                if (!read_cam_source(w, camMgr, r, l)) continue;
                aimbot_write_trace("AIM: cam cand %d rot=(%.1f,%.1f) loc=(%.0f,%.0f,%.0f)%s",
                                    w, r.Pitch, r.Yaw, l.X, l.Y, l.Z,
                                    (l.X == 0.0f && l.Y == 0.0f && l.Z == 0.0f) ? " (EMPTY)" : "");
            }
        }
    }

    if (g_camSource == 5) {
        if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::AController_ControlRot), &out.rot, sizeof(out.rot))) return false;
        out.loc = FVector{};
        out.valid = true;
        return true;
    }
    if (!read_cam_source(g_camSource, camMgr, out.rot, out.loc)) return false;
    out.valid = true;
    return true;
}

// The camera must belong to the local player: it may never be the world
// origin and it must be close to our own pawn.  Aiming from a stale or
// empty cache points the camera in a completely wrong direction.
static bool camera_is_plausible(const FCamInfo& cam)
{
    if (!std::isfinite(cam.loc.X) || !std::isfinite(cam.loc.Y) || !std::isfinite(cam.loc.Z)) return false;
    if (!std::isfinite(cam.rot.Yaw) || !std::isfinite(cam.rot.Pitch)) return false;
    if (cam.loc.X == 0.0f && cam.loc.Y == 0.0f && cam.loc.Z == 0.0f) return false;
    return true;
}

// The local pawn's eye position.  This is the fallback when the camera
// manager has no usable POV (all the camera caches read (0,0,0) in some
// game states - spawn, spectate, killcam).  Aiming from the world origin
// is what crashed the game, but aiming from our own pawn is close enough
// (the camera sits at the eyes of an FPS character).

static void rot_to_vector(const FRotator& r, FVector& v)
{
    float pitch = r.Pitch * 0.0174532925f;   // deg -> rad
    float yaw   = r.Yaw   * 0.0174532925f;
    float cp = cosf(pitch);
    v.X = cp * cosf(yaw);
    v.Y = cp * sinf(yaw);
    v.Z = sinf(pitch);
}

// ---------------------------------------------------------------------------
// The WORLD position of an actor.
//
// USceneComponent::RelativeLocation (0x164) is the offset INSIDE the actor -
// for a ROOT component (the capsule of a character) it is always (0,0,0)!
// The real world position is the root's ComponentToWorld (an FTransform:
// the quaternion at +0x0, the translation at +0x10).
//
// We do not hard code the offset: we discover it once by looking for the
// transform whose translation is close to the camera and whose quaternion is
// normalised.  Until it is found, actor_world_location() fails and the
// callers use the bone positions instead (they are world positions already).
// ---------------------------------------------------------------------------
static uintptr_t g_ctwOffset = 0;      // the ComponentToWorld offset in USceneComponent

static bool discover_component_to_world(uint8_t* root, const FVector& camLoc)
{
    if (g_ctwOffset) return true;
    if (!root) return false;
    for (uintptr_t off = 0x100; off <= 0x480; off += 8) {
        uint8_t buf[0x30];
        if (!UEMemory::vm_rpm_ptr((void*)(root + off), buf, sizeof(buf))) continue;
        float q[4];
        memcpy(q, buf, 16);
        float qlen = sqrtf(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3]);
        if (fabsf(qlen - 1.0f) > 0.08f) continue;      // not a rotation
        float t[3];
        memcpy(t, buf + 0x10, 12);
        if (!std::isfinite(t[0]) || !std::isfinite(t[1]) || !std::isfinite(t[2])) continue;
        float dx = t[0] - camLoc.X, dy = t[1] - camLoc.Y, dz = t[2] - camLoc.Z;
        float d = sqrtf(dx*dx + dy*dy + dz*dz);
        if (d > 400.0f) continue;                        // not our camera
        g_ctwOffset = off;
        aimbot_log("AIM: ComponentToWorld at +0x%lx (world %.0f,%.0f,%.0f)", off, t[0], t[1], t[2]);
        return true;
    }
    return false;
}

// The world position of an actor (the root component's ComponentToWorld).
static bool actor_world_location(uint8_t* actor, FVector& out)
{
    if (!actor) return false;
    uintptr_t root = 0;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)actor + Offsets::AActor_RootComp), &root, sizeof(root))) return false;
    if (!root) return false;

    if (!g_ctwOffset) {
        FCamInfo cam{};
        if (!get_camera_info(cam)) return false;
        if (!discover_component_to_world((uint8_t*)root, cam.loc)) return false;
    }
    float t[3];
    if (!UEMemory::vm_rpm_ptr((void*)(root + g_ctwOffset + 0x10), t, sizeof(t))) return false;
    if (!std::isfinite(t[0]) || !std::isfinite(t[1]) || !std::isfinite(t[2])) return false;
    out.X = t[0]; out.Y = t[1]; out.Z = t[2];
    return true;
}

// The world position of a scene component (the same ComponentToWorld).
static bool local_eye_position(FVector& out)
{
    if (!g_myPC) return false;
    uintptr_t pawn = 0;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::APlayerController_Pawn), &pawn, sizeof(pawn)) || !pawn)
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::APlayerController_ACKPawn), &pawn, sizeof(pawn));
    if (!pawn) return false;

    uintptr_t root = 0;
    if (!UEMemory::vm_rpm_ptr((void*)(pawn + Offsets::AActor_RootComp), &root, sizeof(root)) || !root) return false;

    // the eyes are ~80% of the capsule half height above its centre
    float eye = 64.0f;
    uintptr_t capsule = 0;
    float half = 0.0f;
    if (UEMemory::vm_rpm_ptr((void*)(pawn + Offsets::AChar_Capsule), &capsule, sizeof(capsule)) && capsule) {
        UEMemory::vm_rpm_ptr((void*)(capsule + Offsets::UCapsule_HalfHeight), &half, sizeof(half));
        if (half > 1.0f) eye = half * 0.80f;
    }

    // The WORLD position of the capsule - the relative location of the root
    // component is always (0,0,0) (it is the offset INSIDE the actor), so
    // using it put the camera at the world origin and the aim was ~24 deg off.
    FVector pl{};
    if (!actor_world_location((uint8_t*)pawn, pl)) {
        if (!UEMemory::vm_rpm_ptr((void*)(root + Offsets::USceneComp_RelLoc), &pl, sizeof(pl))) return false;
    }

    out.X = pl.X;
    out.Y = pl.Y;
    out.Z = pl.Z + eye;
    return true;
}

static bool component_world_location(uintptr_t comp, FVector& out)
{
    if (!comp || !g_ctwOffset) return false;
    float t[3];
    if (!UEMemory::vm_rpm_ptr((void*)(comp + g_ctwOffset + 0x10), t, sizeof(t))) return false;
    if (!std::isfinite(t[0]) || !std::isfinite(t[1]) || !std::isfinite(t[2])) return false;
    out.X = t[0]; out.Y = t[1]; out.Z = t[2];
    return true;
}

// ---------------------------------------------------------------------------
// Keep the object array pointer fresh.
//
// UEWrappers::Init() captures the object array's CHUNK pointer once.  When
// the engine grows the object array (a map load spawns thousands of
// objects) it REALLOCATES that chunk array - and the dumper keeps using the
// old pointer, so every object created after that (the characters!) is
// invisible: the enemy cache stayed empty and the aimbot thought it was
// not in a match.
//
// The pointer is cheap to re-read, so we do it before every scan.
// ---------------------------------------------------------------------------
static void refresh_object_array()
{
    UE_UObjectArray* arr = UEWrappers::GetObjects();
    if (!arr) return;
    // FUObjectArray::ObjObjects (0x10) holds the chunk array pointer DIRECTLY
    // (the dumper reads it as vm_rpm_ptr(ObjObjectsPtr + TUObjectArray.Objects)
    // and TUObjectArray::Objects is at 0x0).
    uintptr_t objects = 0;
    if (!UEMemory::vm_rpm_ptr((void*)(g_GUObjectArray + 0x10), &objects, sizeof(objects))) return;
    if (!objects) return;

    // Sanity check: the first object must be a readable pointer, otherwise we
    // would read garbage for every object.
    uintptr_t first = 0;
    if (!UEMemory::vm_rpm_ptr((void*)objects, &first, sizeof(first))) return;
    if (first && !UEMemory::vm_rpm_ptr((void*)first, &first, sizeof(first))) return;

    if ((uintptr_t)arr->Objects != objects) {
        arr->Objects = (uint8_t**)objects;
        static int logged = 0;
        if (logged++ < 4) {
            aimbot_log("AIM: object array moved -> 0x%lx", (unsigned long)objects);
        }
    }
}

// ---------------------------------------------------------------------------
// The GAME's own list of players.
//
// The aimbot always knows the enemy because the GAME tells it
// (MyPC::EnemyAIM).  For the ESP we use the same idea instead of scanning
// the object array: the game state keeps the authoritative list of the
// players and bots.
//
//   MyPlayerController::MyGM (0x718)      -> AMyGameMode
//   AMyGameMode::MyGameState (0x508)      -> AMyGameState
//   AMyGameState::CurrentPlayersInfo(0x398) -> TArray<FPlayerInfo>
//      FPlayerInfo: PlayerName 0x0 (FString), MyMan 0x18, Team 0x3C,
//                    bOut 0x7F, bInfected 0x80
//   AMyGameState::AllPlayers (0x3A8)      -> TArray<AMan*>  (fallback)
// ---------------------------------------------------------------------------
struct GamePlayer {
    uint8_t* man = nullptr;   // the actor
    int      team = -1;
    bool     out = false;     // eliminated
    bool     infected = false;
    std::string name;
};

// The game's GameState (or null).
static uint8_t* game_state()
{
    if (!g_myPC) return nullptr;
    uintptr_t gm = 0;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_MyGM), &gm, sizeof(gm))) return nullptr;
    if (!gm) return nullptr;
    uintptr_t gs = 0;
    if (!UEMemory::vm_rpm_ptr((void*)(gm + Offsets::GameMode_MyGameState), &gs, sizeof(gs))) return nullptr;
    return (uint8_t*)gs;
}

// Read an FString (TCHAR* Data at +0) into a UTF-8 std::string.
static std::string read_fstring(uintptr_t strAddr)
{
    std::string out;
    if (!strAddr) return out;
    uintptr_t data = 0;
    if (!UEMemory::vm_rpm_ptr((void*)strAddr, &data, sizeof(data))) return out;
    if (!data) return out;
    uint16_t buf[64];
    for (int i = 0; i < 63; i++) {
        uint16_t c = 0;
        if (!UEMemory::vm_rpm_ptr((void*)(data + (uintptr_t)i * 2), &c, sizeof(c))) break;
        if (c == 0) break;
        if (c < 128) out.push_back((char)c);
    }
    return out;
}

// The class pointers of the Man derived actors (Health/Team only exist there)
static std::vector<uint8_t*> g_manClassPtrs;
// The class list is rebuilt by the cache thread while the aim thread AND the
// game thread (the ESP dump / the bone sampler) read it - iterating a
// std::vector while another thread reallocates it crashes the game.
static std::mutex g_manClassMutex;

// The enemy actors (the bots).  The game's player list only holds the REAL
// players, so the bots come from our own object array scan.
static std::vector<uint8_t*> g_enemyCache;
static std::mutex g_enemyMutex;

// Is the actor Man derived?  Only those have the Health/MaxHealth/Team
// fields (AMan is 0x960 bytes, the other character classes are smaller).
//
// The class list is filled by the cache thread, so we take a snapshot under
// the lock - iterating the vector while it is cleared/refilled by the other
// thread crashes the game.
static bool is_man_derived(uint8_t* actor)
{
    if (!actor) return false;
    UE_UClass cls = UE_UObject(actor).GetClass();
    uint8_t* cp = cls ? cls.GetAddress() : nullptr;
    if (!cp) return false;
    std::vector<uint8_t*> copy;
    {
        std::lock_guard<std::mutex> lk(g_manClassMutex);
        copy = g_manClassPtrs;
    }
    for (uint8_t* k : copy) { if (k == cp) return true; }
    return false;
}
// The team of an actor - the AMan::Team field only exists on Man derived
// actors, so we only read it for those.
static void read_actor_team(GamePlayer& p)
{
    if (!p.man) return;
    UE_UClass cls = UE_UObject(p.man).GetClass();
    uint8_t* cp = cls ? cls.GetAddress() : nullptr;
    if (!cp) return;
    bool isMan = false;
    {
        std::vector<uint8_t*> copy;
        {
            std::lock_guard<std::mutex> lk(g_manClassMutex);
            copy = g_manClassPtrs;
        }
        for (uint8_t* k : copy) { if (k == cp) { isMan = true; break; } }
    }
    if (!isMan) return;
    int32_t t = -1;
    UEMemory::vm_rpm_ptr((void*)((uintptr_t)p.man + Offsets::AMan_Team), &t, sizeof(t));
    if (t >= 0) p.team = t;
}

// The game's authoritative player list.
static std::vector<GamePlayer> collect_game_players()
{
    std::vector<GamePlayer> out;
    uint8_t* gs = game_state();
    if (!gs) return out;

    // CurrentPlayersInfo: TArray<FPlayerInfo>
    uintptr_t arr = 0;
    int32_t count = 0;
    if (UEMemory::vm_rpm_ptr((void*)(gs + Offsets::GameState_PlayersInfo), &arr, sizeof(arr)) &&
        UEMemory::vm_rpm_ptr((void*)(gs + Offsets::GameState_PlayersInfo + 8), &count, sizeof(count)) &&
        arr && count > 0 && count < 256) {
        const uintptr_t kEntrySize = 0x84;   // sizeof(FPlayerInfo)
        for (int i = 0; i < count; i++) {
            uintptr_t e = arr + (uintptr_t)i * kEntrySize;
            GamePlayer p;
            uintptr_t man = 0;
            if (!UEMemory::vm_rpm_ptr((void*)(e + Offsets::PlayerInfo_MyMan), &man, sizeof(man))) continue;
            if (!man) continue;
            p.man = (uint8_t*)man;
            int32_t t = -1;
            UEMemory::vm_rpm_ptr((void*)(e + Offsets::PlayerInfo_Team), &t, sizeof(t));
            p.team = t;
            uint8_t o = 0, inf = 0;
            UEMemory::vm_rpm_ptr((void*)(e + Offsets::PlayerInfo_bOut), &o, sizeof(o));
            UEMemory::vm_rpm_ptr((void*)(e + Offsets::PlayerInfo_bInfected), &inf, sizeof(inf));
            p.out = (o != 0);
            p.infected = (inf != 0);
            p.name = read_fstring(e);      // PlayerName @ 0x0
            out.push_back(p);
        }
        if (!out.empty()) return out;
    }

    // AllPlayers (TArray<AMan*>)
    arr = 0; count = 0;
    if (UEMemory::vm_rpm_ptr((void*)(gs + Offsets::GameState_AllPlayers), &arr, sizeof(arr)) &&
        UEMemory::vm_rpm_ptr((void*)(gs + Offsets::GameState_AllPlayers + 8), &count, sizeof(count)) &&
        arr && count > 0 && count < 256) {
        for (int i = 0; i < count; i++) {
            uintptr_t man = 0;
            if (!UEMemory::vm_rpm_ptr((void*)(arr + (uintptr_t)i * sizeof(uintptr_t)), &man, sizeof(man))) continue;
            if (!man) continue;
            bool known = false;
            for (size_t k = 0; k < out.size(); k++) if (out[k].man == (uint8_t*)man) { known = true; break; }
            if (known) continue;
            GamePlayer p;
            p.man = (uint8_t*)man;
            read_actor_team(p);
            out.push_back(p);
        }
    }

    // The CLIENT's player list only contains the local player - the bots are
    // not in it, so we merge our own object array scan (the cache) as well.
    {
        std::lock_guard<std::mutex> lk(g_enemyMutex);
        for (uint8_t* a : g_enemyCache) {
            if (!a) continue;
            bool known = false;
            for (size_t k = 0; k < out.size(); k++) if (out[k].man == a) { known = true; break; }
            if (known) continue;
            GamePlayer p;
            p.man = a;
            read_actor_team(p);
            out.push_back(p);
        }
    }
    return out;
}

// --- enemy selection ---
//
// PERFORMANCE: walking all ~58k UObjects with IsA() (a super-chain walk)
// every frame costs tens of milliseconds and tanks the framerate.
//
// Instead:
//   * g_enemyClassPtrs - the class pointers that derive from Engine.Character
//     (built once; a class check is then a single pointer compare)
//   * g_enemyCache     - the current list of enemy actors, refreshed by a
//     background thread every 500ms
//   * pick_best_enemy_cached() - cheap per-frame selection over that small
//     list (a handful of players, not 58k objects)
static uint8_t* g_targetEnemy = nullptr;

// ---------------------------------------------------------------------------
// The distance list for the APP's floating overlay.
//
// It used to be printed on the screen with UKismetSystemLibrary::PrintString
// (a static UFUNCTION called through ProcessEvent), but that is an engine
// call and it was made from OUR thread - calling into the engine from the
// wrong thread crashes the game.  The app reads /sdcard/aimbot_esp.txt and
// draws it as a panel over the game, so only the file is written now.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// On-screen distance label (ESP-lite)
// ---------------------------------------------------------------------------
static void draw_esp_label()
{
    if (!g_espEnabled) return;
    if (!g_myPC) return;

    // Camera position for distances
    FCamInfo cam{};
    if (!get_camera_info(cam)) return;

    // Collect distances to the cached enemies
    struct Ent { float dist; uint8_t* actor; };
    Ent list[16];
    int count = 0;
    {
        std::lock_guard<std::mutex> lk(g_enemyMutex);
        for (uint8_t* a : g_enemyCache) {
            if (!a) continue;
            // The WORLD position (the root's ComponentToWorld).  The root
            // component's RelativeLocation is always (0,0,0) - it is the
            // offset INSIDE the actor - so reading it gave every distance
            // from the world origin.
            FVector loc{};
            if (!actor_world_location(a, loc)) continue;
            float dx = loc.X - cam.loc.X, dy = loc.Y - cam.loc.Y, dz = loc.Z - cam.loc.Z;
            float d = sqrtf(dx*dx + dy*dy + dz*dz);
            if (d < 50.0f) continue;
            if (count < 16) { list[count].dist = d; list[count].actor = a; count++; }
        }
    }
    // simple selection sort (nearest first)
    for (int i = 0; i < count; i++)
        for (int j = i + 1; j < count; j++)
            if (list[j].dist < list[i].dist) { Ent t = list[i]; list[i] = list[j]; list[j] = t; }

    if (count == 0) return;

    // ---- Write the distance list for the APP's floating overlay ----
    // (The app reads this file and draws it as a panel over the game.)
    //
    // NOTE: the same text used to be printed ON the screen with
    // UKismetSystemLibrary::PrintString.  That is an engine call and it was
    // made from OUR thread (the aim loop) - calling into the engine from the
    // wrong thread crashes the game, so only the file is written now.
    {
        char buf2[256];
        int n2 = snprintf(buf2, sizeof(buf2), "DIST");
        for (int i = 0; i < count && i < 6; i++)
            n2 += snprintf(buf2 + n2, sizeof(buf2) - n2, " %.0fm", list[i].dist);
        if (g_targetEnemy) {
            for (int i = 0; i < count; i++)
                if (list[i].actor == g_targetEnemy) {
                    n2 += snprintf(buf2 + n2, sizeof(buf2) - n2, "  TGT %.0fm", list[i].dist);
                    break;
                }
        }
        FILE* ef = fopen("/sdcard/aimbot_esp.txt", "w");
        if (ef) { fputs(buf2, ef); fputc('\n', ef); fclose(ef); }
    }
}

static std::vector<uint8_t*> g_enemyClassPtrs;
// Class pointers that derive from SpecialForces.Man - only these have valid
// Health (0x868) / Team (0x860) fields.
//
// BOTH class lists are guarded by g_manClassMutex: the cache thread rebuilds
// them while the aim thread AND the game thread (the ESP dump / the bone
// sampler) read them.  Iterating a std::vector while another thread
// reallocates it is an instant crash, so the writers build a LOCAL vector and
// publish it with a swap under the lock - the readers take a snapshot.

// Build the list of UClass objects that derive from Engine.Character.
static void build_enemy_class_list()
{
    if (!UEWrappers::GetObjects()) {
        static int warned = 0;
        if (warned++ < 3) aimbot_write_trace("AIM: class list skip - object array not ready");
        return;
    }

    // Use Engine.Character (the parent of SpecialForces.Man) so we catch
    // EVERY character class - the game's enemies are not necessarily AMan.
    // FindObject() walks the whole object array and takes SECONDS, so the
    // class is resolved once and cached (as a raw pointer - UE_UClass has no
    // default constructor).
    static uint8_t* characterClassPtr = nullptr;
    if (!characterClassPtr) {
        UE_UObject o = UEWrappers::GetObjects()->FindObject("Class Engine.Character");
        if (!o) {
            static int warned = 0;
            if (warned++ < 3) aimbot_log("AIM: class list skip - Character class not found");
            return;
        }
        characterClassPtr = o.GetAddress();
    }
    UE_UClass manClass = UE_UObject(characterClassPtr).Cast<UE_UClass>();
    if (!manClass) {
        static int warned = 0;
        if (warned++ < 3) aimbot_log("AIM: class list skip - Character class not found");
        return;
    }

    // Man-derived class pointers (Health / Team are only valid on these).
    // Built in a LOCAL vector and published with a swap - the readers take a
    // snapshot under the lock, so they never see a half filled vector.
    std::vector<uint8_t*> manClasses;
    {
        // cached - FindObject() is far too slow for a repeated call (raw
        // pointer: UE_UClass has no default constructor)
        static uint8_t* manClassPtr = nullptr;
        if (!manClassPtr) {
            UE_UObject o = UEWrappers::GetObjects()->FindObject("Class SpecialForces.Man");
            if (o) manClassPtr = o.GetAddress();
        }
        if (manClassPtr) {
            UE_UClass manCls = UE_UObject(manClassPtr).Cast<UE_UClass>();
            if (manCls) {
                UEWrappers::GetObjects()->ForEachObjectOfClass(manCls, [&](UE_UObject o) -> bool {
                    if (!o) return false;
                    UE_UClass c = o.GetClass();
                    if (!c) return false;
                    uint8_t* cp = c.GetAddress();
                    if (!cp) return false;
                    bool seen = false;
                    for (uint8_t* k : manClasses) { if (k == cp) { seen = true; break; } }
                    if (!seen) manClasses.push_back(cp);
                    return false;
                });
            }
        }
    }
    size_t manCount = manClasses.size();
    {
        std::lock_guard<std::mutex> lk(g_manClassMutex);
        g_manClassPtrs.swap(manClasses);
    }
    aimbot_log("AIM: man class ptrs = %zu", manCount);

    // ForEachObjectOfClass does the IsA() filtering for us.  We run this ONCE
    // (it is the expensive part) and collect the class pointers of every
    // matching instance, so later refreshes only need a pointer compare.
    //
    // The class pointers go into a LOCAL vector and are published with a swap
    // under the lock - the aim thread and the game thread iterate them.
    std::vector<uint8_t*> actors;
    std::vector<uint8_t*> classes;
    int32_t totalObjects = 0, charDerived = 0;
    UEWrappers::GetObjects()->ForEachObject([&](UE_UObject obj) -> bool {
        totalObjects++;
        if (obj.IsA(manClass)) charDerived++;
        return false;
    });
    UEWrappers::GetObjects()->ForEachObjectOfClass(manClass, [&](UE_UObject obj) -> bool {
        if (!obj) return false;
        // NOTE: the class default objects (Default__Man …) are NOT filtered
        // here with the object flags - UObject::ObjectFlags is not at 0x8 in
        // this build, so the flag check returned garbage and dropped almost
        // every actor.  The CDOs are filtered by their NAME in the ESP dump.
        UE_UClass c = obj.GetClass();
        if (c) {
            uint8_t* cp = c.GetAddress();
            if (cp) {
                bool seen = false;
                for (uint8_t* k : classes) { if (k == cp) { seen = true; break; } }
                if (!seen) classes.push_back(cp);
            }
        }
        actors.push_back(obj.GetAddress());
        return false;
    });
    size_t classCount = classes.size();
    {
        std::lock_guard<std::mutex> lk(g_manClassMutex);
        g_enemyClassPtrs.swap(classes);
    }

    size_t actorCount = actors.size();
    if (!actors.empty()) {
        std::lock_guard<std::mutex> lk(g_enemyMutex);
        g_enemyCache.swap(actors);
    }

    aimbot_log("AIM: scan %d objects, %d Character-derived, %zu class(es), %zu actor(s)",
               totalObjects, charDerived, classCount, actorCount);
    LOGI("AIMBOT: enemy class list built, %zu class(es)", classCount);
    aimbot_write_trace("AIM: enemy class list built, %zu class(es)", classCount);
}

// Scan for standalone camera actors (the game may render from its own
// CameraActor rather than the PlayerCameraManager's view target).
static std::vector<uint8_t*> g_cameraActors;
static uint8_t* g_camActor = nullptr;

static void scan_camera_actors()
{
    if (!UEWrappers::GetObjects()) return;
    // FindObject() walks the whole object array (58k objects, GetFullName on
    // each) and takes SECONDS - it must not run every 500 ms, so the class is
    // resolved once and cached (raw pointer: UE_UClass has no default
    // constructor).
    static uint8_t* camActorClassPtr = nullptr;
    if (!camActorClassPtr) {
        UE_UObject o = UEWrappers::GetObjects()->FindObject("Class Engine.CameraActor");
        if (!o) return;
        camActorClassPtr = o.GetAddress();
    }
    UE_UClass camActorClass = UE_UObject(camActorClassPtr).Cast<UE_UClass>();
    if (!camActorClass) return;

    std::vector<uint8_t*> list;
    UEWrappers::GetObjects()->ForEachObjectOfClass(camActorClass, [&](UE_UObject obj) -> bool {
        if (!obj) return false;
        list.push_back(obj.GetAddress());
        return false;
    });
    std::lock_guard<std::mutex> lk(g_enemyMutex);
    g_cameraActors.swap(list);
}

// Refresh the enemy actor list. Runs on its own thread, 2x per second.
static void refresh_enemy_cache()
{
    if (!UEWrappers::GetObjects()) return;

    // The class list belongs to the cache thread - take a snapshot under its
    // lock instead of iterating the vector while it is rebuilt.
    std::vector<uint8_t*> classes;
    {
        std::lock_guard<std::mutex> lk(g_manClassMutex);
        classes = g_enemyClassPtrs;
    }
    if (classes.empty()) return;

    std::vector<uint8_t*> list;
    UEWrappers::GetObjects()->ForEachObject([&](UE_UObject obj) -> bool {
        if (!obj) return false;
        UE_UClass cls = obj.GetClass();
        if (!cls) return false;
        uint8_t* cp = cls.GetAddress();
        if (!cp) return false;
        for (uint8_t* known : classes) {
            if (known == cp) { list.push_back(obj.GetAddress()); break; }
        }
        return false;
    });

    std::lock_guard<std::mutex> lk(g_enemyMutex);
    g_enemyCache.swap(list);

    static int lastCount = -1;
    if ((int)list.size() != lastCount) {
        lastCount = (int)list.size();
        aimbot_log("AIM: enemy cache refreshed, %d actor(s)", lastCount);
        aimbot_write_trace("AIM: character cache = %d actor(s)", lastCount);
    }
}

static void* enemy_cache_thread(void*)
{
    // Wait for the dumper's profile to initialise before touching the object
    // array - UE_UClass::StaticClass() caches its result on first use, so
    // calling it too early would cache a null forever.
    for (int i = 0; i < 120; i++) {
        if (UEWrappers::GetObjects() && g_libUE4_base) break;
        sleep(1);
    }
    aimbot_write_trace("AIM: enemy cache thread ready (base=0x%lx)", g_libUE4_base);

    int tick = 0;
    while (true) {
        refresh_object_array();     // the engine moves it when it grows
        // Rebuilding the class list and scanning the camera actors means
        // walking ALL 58k objects with an IsA() check - that is expensive.
        // They change very rarely, so it happens every 5 s instead of twice
        // a second (it used to starve the game thread).
        if ((tick++ % 10) == 0) {
            build_enemy_class_list();
            scan_camera_actors();
        }
        refresh_enemy_cache();
        {
            std::lock_guard<std::mutex> lk(g_enemyMutex);
            static int lastCam = -1;
            if ((int)g_cameraActors.size() != lastCam) {
                lastCam = (int)g_cameraActors.size();
                aimbot_write_trace("AIM: CameraActor instances = %d", lastCam);
                for (uint8_t* a : g_cameraActors) {
                    uintptr_t root = 0;
                    FRotator ar{};
                    FVector al{};
                    if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)a + Offsets::AActor_RootComp), &root, sizeof(root)) && root) {
                        UEMemory::vm_rpm_ptr((void*)(root + Offsets::USceneComp_RelRot), &ar, sizeof(ar));
                        UEMemory::vm_rpm_ptr((void*)(root + Offsets::USceneComp_RelLoc), &al, sizeof(al));
                    }
                    aimbot_write_trace("AIM:   CameraActor 0x%lx rot=(%.1f,%.1f) loc=(%.0f,%.0f,%.0f)",
                                        (uintptr_t)a, ar.Pitch, ar.Yaw, al.X, al.Y, al.Z);
                }
            }
        }
        sleep(500);
    }
    return nullptr;
}

// Cheap per-frame pick over the cached (small) enemy list.
static uint8_t* find_best_enemy()
{
    if (!UEWrappers::GetObjects()) return nullptr;

    uintptr_t ownPawn = 0;
    UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::APlayerController_ACKPawn), &ownPawn, sizeof(ownPawn));
    if (!ownPawn)
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::APlayerController_Pawn), &ownPawn, sizeof(ownPawn));

    FCamInfo cam{};
    if (!get_camera_info(cam)) return nullptr;

    FVector fwd{};
    rot_to_vector(cam.rot, fwd);

    // Our own team, so we never target a teammate.  The player is team 0 and
    // the enemies are team 1, so the comparison is meaningful here.
    int myTeam = -1;
    {
        int32_t t = 0;
        if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_PlayerTeam), &t, sizeof(t))) myTeam = t;
    }

    static int skippedDead = 0, skippedTeam = 0;
    skippedDead = 0; skippedTeam = 0;

    uint8_t* best = nullptr;
    float bestScore = -1.0f;

    // The GAME's own player list - the same source the aimbot's EnemyAIM
    // comes from.  It always knows who is alive, which team they are on and
    // what they are called.
    std::vector<GamePlayer> players = collect_game_players();
    if (players.empty()) {
        std::lock_guard<std::mutex> lk(g_enemyMutex);
        for (uint8_t* a : g_enemyCache) { GamePlayer p; p.man = a; players.push_back(p); }
    }

    {
        for (size_t pi = 0; pi < players.size(); pi++) {
            GamePlayer& gp = players[pi];
            uint8_t* actor = gp.man;
            if (!actor) continue;
            if ((uintptr_t)actor == ownPawn) continue;

            // --- dead / teammate checks ---
            {
                // the game knows: bOut (eliminated) and the team
                if (gp.out) { skippedDead++; continue; }
                if (g_skipTeammates && gp.team >= 0 && myTeam >= 0 && gp.team == myTeam) {
                    skippedTeam++;
                    continue;
                }
                if (g_skipDead && is_man_derived(actor)) {
                    int32_t hp = 1, maxhp = 1;
                    UEMemory::vm_rpm_ptr((void*)((uintptr_t)actor + Offsets::AMan_Health), &hp, sizeof(hp));
                    UEMemory::vm_rpm_ptr((void*)((uintptr_t)actor + Offsets::AMan_MaxHealth), &maxhp, sizeof(maxhp));
                    if (maxhp > 0 && hp <= 0) { skippedDead++; continue; }
                }
                // Any actor flagged as being destroyed is skipped.
                {
                    uint8_t flags = 0;
                    if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)actor + Offsets::AActor_BeingDestroyedFlags), &flags, sizeof(flags)) &&
                        (flags & 0x4)) {
                        skippedDead++;
                        continue;
                    }
                }
            }

            FVector loc{};
            if (!actor_world_location(actor, loc)) continue;

            FVector d = loc - cam.loc;
            float len = sqrtf(d.X*d.X + d.Y*d.Y + d.Z*d.Z);
            if (len < 100.0f) continue;
            if (len > 3000.0f) continue;   // absurdly far - probably not visible
            d.X /= len; d.Y /= len; d.Z /= len;

            float dot = d.X*fwd.X + d.Y*fwd.Y + d.Z*fwd.Z;
            if (dot <= 0.0f) continue;
            float score = dot * 1000.0f - len;
            if (score > bestScore) { bestScore = score; best = actor; }
        }
    }

    if (skippedDead || skippedTeam) {
        static int lastD = -1, lastT = -1;
        if (skippedDead != lastD || skippedTeam != lastT) {
            lastD = skippedDead; lastT = skippedTeam;
            aimbot_log("AIM: skipped %d dead, %d teammates (myTeam=%d)", skippedDead, skippedTeam, myTeam);
        }
    }
    return best;
}

// ---------------------------------------------------------------------------
// Are we actually IN A MATCH?
//
// In the lobby / spawn screen there is no camera position (it reads
// (0,0,0)), no characters and no world - the aimbot must stay out of the
// way there instead of aiming at the world origin.
// ---------------------------------------------------------------------------
static bool g_inGame = false;

static bool in_game_state()
{
    if (!g_myPC) return false;
    refresh_object_array();
    uintptr_t world = 0;
    if (!UEMemory::vm_rpm_ptr((void*)g_UWorld, &world, sizeof(world)) || !world) return false;

    FCamInfo cam{};
    if (!get_camera_info(cam)) return false;
    if (!camera_is_plausible(cam)) return false;

    size_t n = 0;
    { std::lock_guard<std::mutex> lk(g_enemyMutex); n = g_enemyCache.size(); }
    return n > 0;
}

// Log the transitions (lobby -> match -> lobby).
static void update_game_state()
{
    bool now = in_game_state();
    if (now == g_inGame) return;
    g_inGame = now;
    aimbot_log("AIM: %s", now ? "IN A MATCH - the aimbot is live"
                                 : "not in a match - the aimbot is idle");
}

// Resolves the current target actor (the game's EnemyAIM if set, otherwise
// our own nearest-to-crosshair search).  Returns 0 on success.
static int resolve_target(uint8_t** outEnemy)
{
    if (!g_myPC) return 1;
    uint8_t* pc = g_myPC;

    uintptr_t enemyAddr = 0;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_EnemyAIM), &enemyAddr, sizeof(enemyAddr)))
        return 2;

    if (!enemyAddr) {
        // The game's own target pointer is empty - use our cached enemy list.
        // (the list itself is refreshed by a background thread, so this is
        //  cheap enough to run every frame)
        g_targetEnemy = find_best_enemy();
        if (!g_targetEnemy) return 3;
        enemyAddr = (uintptr_t)g_targetEnemy;
    } else {
        g_targetEnemy = (uint8_t*)enemyAddr;
    }

    uint8_t* enemy = (uint8_t*)enemyAddr;

    uintptr_t meshAddr = 0;
    UEMemory::vm_rpm_ptr((void*)((uintptr_t)enemy + Offsets::AMan_Mesh), &meshAddr, sizeof(meshAddr));
    if (!meshAddr) return 4;

    uintptr_t rootAddr = 0;
    UEMemory::vm_rpm_ptr((void*)((uintptr_t)enemy + Offsets::AActor_RootComp), &rootAddr, sizeof(rootAddr));
    if (!rootAddr) return 5;

    *outEnemy = enemy;
    return 0;
}

// ---------------------------------------------------------------------------
// HEAD resolution
//
// 1) a socket on the skeletal mesh (the real thing, world space)
//      USkeletalMesh::Sockets -> USkeletalMeshSocket -> GetSocketLocation()
//      (this game has no sockets on the enemy meshes)
// 2) the animated bone transforms (the arrays are EMPTY in this build)
// 3) THE CAPSULE: the character's real size.  head = capsule centre +
//    headZ * CapsuleHalfHeight.  (headZ = 0.8 -> the eyes of a 1.8 m
//    character, the capsule half height is 90 = 1.8 m)
// 4) the skeletal mesh's imported bounds (LAST resort - the bounds of this
//    game are ~2.6 m tall and live in the MESH's space, so they land way
//    above the head if they are used as a world offset)
// ---------------------------------------------------------------------------
static uintptr_t g_headMeshId = 0;        // the USkeletalMesh we cached for
static uint8_t*  g_headSocket = nullptr;  // the socket that looks like a head

// Ask the engine for the world position of the head socket.
static bool get_head_socket_position(uint8_t* actor, FVector& out)
{
    if (!g_getSocketLocFunc) return false;

    uintptr_t meshComp = 0;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)actor + Offsets::AMan_Mesh), &meshComp, sizeof(meshComp))) return false;
    if (!meshComp) return false;

    uintptr_t skelMesh = 0;
    if (!UEMemory::vm_rpm_ptr((void*)(meshComp + Offsets::USkinnedMesh_SkeletalMesh), &skelMesh, sizeof(skelMesh))) return false;
    if (!skelMesh) return false;

    if (skelMesh != g_headMeshId) {
        g_headMeshId = skelMesh;
        g_headSocket = nullptr;

        uintptr_t arr = 0;
        int32_t count = 0;
        bool ok = UEMemory::vm_rpm_ptr((void*)(skelMesh + Offsets::USkeletalMesh_Sockets), &arr, sizeof(arr)) &&
                 UEMemory::vm_rpm_ptr((void*)(skelMesh + Offsets::USkeletalMesh_Sockets + 8), &count, sizeof(count));
        if (!ok || !arr || count <= 0 || count > 64) {
            static int noSockLogged = 0;
            if (noSockLogged++ < 3) {
                aimbot_log("AIM: mesh 0x%lx has no sockets (count=%d)", skelMesh, count);
            }
            return false;
        }
        for (int i = 0; i < count; i++) {
            uintptr_t sock = 0;
            if (!UEMemory::vm_rpm_ptr((void*)(arr + (uintptr_t)i * sizeof(uintptr_t)), &sock, sizeof(sock)) || !sock) continue;
            int32_t nameIdx = -1;
            if (!UEMemory::vm_rpm_ptr((void*)(sock + Offsets::USkelSocket_SocketName), &nameIdx, sizeof(nameIdx))) continue;
            std::string nm = resolve_fname(nameIdx);
            if (nm.empty()) nm = "<unresolved>";
            static int sockLogged = 0;
            if (sockLogged++ < 32) {
                aimbot_log("AIM: socket[%d] '%s' (mesh 0x%lx)", i, nm.c_str(), skelMesh);
            }
            if (g_headSocket) continue;
            std::string low = nm;
            for (size_t k = 0; k < low.size(); k++) low[k] = (char)tolower((unsigned char)low[k]);
            if (low.find("head") != std::string::npos || low.find("skull") != std::string::npos ||
                low.find("neck") != std::string::npos)
                g_headSocket = (uint8_t*)sock;
        }
        aimbot_log("AIM: head socket = %p", (uintptr_t)g_headSocket);
    }

    if (!g_headSocket) return false;

    // Build the parms buffer using the engine's own property offsets.
    static int offRet = -1, offComp = -1, psize = 0;
    static uint64_t lastTry = 0;
    if (offRet < 0) {
        uint64_t now = now_us();
        if (lastTry && (now - lastTry) < 2000000) return false;   // retry every 2 s
        lastTry = now;
        UE_Offsets* o = active_offsets();
        if (!o) return false;
        offRet  = find_prop_offset(g_getSocketLocFunc, o, "ReturnValue");
        offComp = find_prop_offset(g_getSocketLocFunc, o, "SkelComp");
        psize   = func_param_size(g_getSocketLocFunc);
        aimbot_log("AIM: GetSocketLocation layout Ret=%d SkelComp=%d parms=%d", offRet, offComp, psize);
    }
    if (offRet < 0 || offComp < 0 || psize <= 0 || psize > 0x100) return false;

    uint8_t buf[0x100];
    memset(buf, 0, sizeof(buf));
    uintptr_t comp = meshComp;
    memcpy(buf + offComp, &comp, sizeof(uintptr_t));

    call_engine_function(g_headSocket, g_getSocketLocFunc, buf);

    FVector res{};
    memcpy(&res, buf + offRet, sizeof(FVector));
    if (!std::isfinite(res.X) || !std::isfinite(res.Y) || !std::isfinite(res.Z)) return false;
    if (res.X == 0.0f && res.Y == 0.0f && res.Z == 0.0f) return false;
    out = res;
    return true;
}

// The world position of the target's head.
static bool get_head_position(uint8_t* actor, const FVector& actorLoc, FVector& out)
{
    out = actorLoc;

    // 1) the real socket (best)
    FVector sockPos{};
    if (get_head_socket_position(actor, sockPos)) { out = sockPos; return true; }

    uintptr_t mesh = 0;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)actor + Offsets::AMan_Mesh), &mesh, sizeof(mesh))) return false;
    if (!mesh) return false;

    // 2) the animated bone transforms (empty in this build, but try anyway)
    uintptr_t data = 0;
    int32_t count = 0;
    bool got = false;
    for (int arr = 0; arr < 2 && !got; arr++) {
        uintptr_t off = (arr == 0) ? Offsets::USkelMesh_CachedCompXforms
                                   : Offsets::USkelMesh_CachedBoneXforms;
        uintptr_t d = 0;
        int32_t c = 0;
        bool okD = UEMemory::vm_rpm_ptr((void*)(mesh + off), &d, sizeof(d));
        bool okC = UEMemory::vm_rpm_ptr((void*)(mesh + off + 8), &c, sizeof(c));
        static int logged = 0;
        if (logged < 4) {
            logged++;
            aimbot_log("AIM: bone arr%d off=0x%lx data=0x%lx (ok=%d) count=%d (ok=%d)",
                       arr, off, d, okD ? 1 : 0, c, okC ? 1 : 0);
        }
        if (okD && okC && d && c > 0 && c <= 512) { data = d; count = c; got = true; }
    }

    // The actor's yaw (root component relative rotation) - the component
    // space is rotated by it relative to world space.
    float yaw = 0.0f;
    {
        uintptr_t root = 0;
        if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)actor + Offsets::AActor_RootComp), &root, sizeof(root)) && root) {
            FRotator rr{};
            UEMemory::vm_rpm_ptr((void*)(root + Offsets::USceneComp_RelRot), &rr, sizeof(rr));
            yaw = rr.Yaw;
        }
    }
    float cy = cosf(yaw * 0.0174532925f);
    float sy = sinf(yaw * 0.0174532925f);

    FVector best{};
    if (got) {
        const uint32_t kFTransformSize = 0x30;
        const uint32_t kTransOffset = 0x10;

        float bestZ = -1e30f;
        for (int32_t i = 0; i < count; i++) {
            FVector t{};
            if (!UEMemory::vm_rpm_ptr((void*)(data + (uintptr_t)i * kFTransformSize + kTransOffset), &t, sizeof(t))) continue;
            if (t.Z > bestZ) { bestZ = t.Z; best = t; }
        }
        if (bestZ > -1e29f) {
            // component space -> world: rotate XY by the actor yaw, then offset
            FVector world;
            world.X = actorLoc.X + (best.X * cy - best.Y * sy);
            world.Y = actorLoc.Y + (best.X * sy + best.Y * cy);
            world.Z = actorLoc.Z + best.Z;
            out = world;
            return true;
        }
    }

    // 3) THE CAPSULE: the character's real size.  The root component IS the
    //    capsule, so actorLoc is the capsule centre and the head sits
    //    headZ * halfHeight above it.
    {
        uintptr_t capsule = 0;
        float halfHeight = 0.0f;
        if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)actor + Offsets::AChar_Capsule), &capsule, sizeof(capsule)) && capsule) {
            UEMemory::vm_rpm_ptr((void*)(capsule + Offsets::UCapsule_HalfHeight), &halfHeight, sizeof(halfHeight));
        }
        if (halfHeight > 1.0f) {
            out = actorLoc;
            out.Z = actorLoc.Z + halfHeight * g_headZ;
            return true;
        }
    }

    // 4) last resort - a fixed guess (the mesh bounds of this game are ~2.6 m
    //    tall and live in the MESH's space, so they are useless as a world
    //    offset)
    static int warned = 0;
    if (warned++ < 3) {
        aimbot_log("AIM: no capsule - using a fixed head offset");
    }
    out = actorLoc;
    out.Z = actorLoc.Z + 64.0f;
    return true;
}

// Computes the aim rotation for the current target.
// Returns true when a valid rotation was produced.
static FRotator g_lastEngineAim{};
static bool g_haveEngineAim = false;

static bool compute_aim_rotation(FRotator& out)
{
    uint8_t* enemy = nullptr;
    if (resolve_target(&enemy) != 0) return false;
    if (!enemy) return false;

    uintptr_t rootAddr = 0;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)enemy + Offsets::AActor_RootComp), &rootAddr, sizeof(rootAddr))) return false;
    if (!rootAddr) return false;

    FVector loc{};
    if (!actor_world_location(enemy, loc)) {
        // no world position (the ComponentToWorld is not known yet) - fall
        // back to the root's relative location, it is the best we have
        UEMemory::vm_rpm_ptr((void*)(rootAddr + Offsets::USceneComp_RelLoc), &loc, sizeof(loc));
    }

    if (g_headOnly) {
        FVector head{};
        // The real head bone (Bip01-Head) when we have it - much better than
        // the capsule estimate.
        FVector boneHead{};
        if (Intro::head_bone_position(enemy, boneHead)) {
            loc = boneHead;
        } else if (get_head_position(enemy, loc, head)) {
            loc = head;
        } else {
            // Fallback: head near the capsule top.
            uintptr_t capsule = 0;
            float halfHeight = 0.0f;
            if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)enemy + Offsets::AChar_Capsule), &capsule, sizeof(capsule)) && capsule) {
                UEMemory::vm_rpm_ptr((void*)(capsule + Offsets::UCapsule_HalfHeight), &halfHeight, sizeof(halfHeight));
            }
            loc.Z += (halfHeight > 1.0f) ? (halfHeight * g_headZ) : 64.0f;
        }
    }

    FCamInfo cam{};
    if (!get_camera_info(cam)) return false;
    // NEVER aim from a bogus camera (an empty cache reads as the world
    // origin) - that whips the camera around and can crash the game.
    {
        bool camOk = camera_is_plausible(cam);
        FVector eye{};
        bool haveEye = local_eye_position(eye);
        if (camOk && haveEye) {
            // the camera must be close to our own pawn
            float dx = eye.X - cam.loc.X, dy = eye.Y - cam.loc.Y, dz = eye.Z - cam.loc.Z;
            if (dx*dx + dy*dy + dz*dz > 500.0f * 500.0f) camOk = false;
        }
        if (!camOk) {
            // No usable camera cache (spawn / spectate / killcam): use our
            // own pawn's eyes instead of giving up.
            if (!haveEye) return false;
            static int warned = 0;
            if (warned++ < 3) {
                aimbot_log("AIM: no usable camera cache - aiming from our pawn (%.0f,%.0f,%.0f)",
                           eye.X, eye.Y, eye.Z);
            }
            cam.loc = eye;
        }
    }

    FVector delta = loc - cam.loc;
    float horiz = sqrtf(delta.X*delta.X + delta.Y*delta.Y);
    float dist  = sqrtf(horiz*horiz + delta.Z*delta.Z);
    if (dist < 20.0f || dist > 5000.0f) return false;

    // UE's own convention (FVector::Rotation / FindLookAtRotation):
    //   yaw   = atan2(dy, dx)
    //   pitch = atan2(dz, horizontal)   -> positive = looking UP
    float yaw   = atan2f(delta.Y, delta.X) * 180.0f / 3.14159265f;
    float pitch = atan2f(delta.Z, horiz) * 180.0f / 3.14159265f;

    // Ask the engine for the same rotation and use it to calibrate the sign
    // (the engine is the ground truth; if the two disagree about the pitch
    //  sign we flip ours automatically).  Only once a second - it is an
    //  engine call and we only need it for the calibration.
    static uint64_t lastEngineCall = 0;
    uint64_t nowTick = now_us();
    FRotator eng{};
    if ((nowTick - lastEngineCall) > 1000000 && engine_look_at(cam.loc, loc, eng)) {
        lastEngineCall = nowTick;
        float dyaw = fabsf(eng.Yaw - yaw);
        while (dyaw > 180.0f) dyaw = fabsf(dyaw - 360.0f);
        if (dyaw < 15.0f) {
            g_lastEngineAim = eng;
            g_haveEngineAim = true;
            if (fabsf(pitch) > 3.0f && fabsf(eng.Pitch) > 3.0f) {
                float same = fabsf(pitch - eng.Pitch);
                float flip = fabsf(pitch + eng.Pitch);
                int want = (flip + 0.5f < same) ? -1 : 1;
                if (want != g_pitchSign) {
                    g_pitchSign = want;
                    aimbot_log("AIM: pitch sign calibrated to %+d (we %.1f, engine %.1f)",
                               g_pitchSign, pitch, eng.Pitch);
                    aimbot_write_trace("AIM: pitch sign = %+d (engine says %.1f)", g_pitchSign, eng.Pitch);
                }
            }
        }
    }

    out.Pitch = pitch * (float)g_pitchSign;
    out.Yaw   = yaw;
    out.Roll  = 0.0f;

    // UE keeps the rotation wrapped: the pitch in [-90,90], the yaw in
    // [-180,180].  The game ADDS the mouse delta to whatever we write, so an
    // unwrapped value accumulates and the camera flips (we saw the pitch
    // run away to 228/273 degrees -> "the camera goes wild up and down").
    float wantPitch = out.Pitch, wantYaw = out.Yaw;
    while (wantPitch > 90.0f) wantPitch -= 180.0f;
    while (wantPitch < -90.0f) wantPitch += 180.0f;
    while (wantYaw > 180.0f) wantYaw -= 360.0f;
    while (wantYaw < -180.0f) wantYaw += 360.0f;

    // Apply a PARTIAL correction to the current rotation instead of an
    // absolute value: the game adds the player's mouse input to the control
    // rotation every frame, so an absolute write makes the camera fight the
    // player (the camera then flickers between our value and the mouse
    // value).  With a partial correction the mouse still works and the aim
    // is pulled back smoothly.
    {
        FRotator cur{};
        if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::AController_ControlRot), &cur, sizeof(cur))) {
            float curPitch = cur.Pitch;
            while (curPitch > 90.0f) curPitch -= 180.0f;
            while (curPitch < -90.0f) curPitch += 180.0f;
            float curYaw = cur.Yaw;
            while (curYaw > 180.0f) curYaw -= 360.0f;
            while (curYaw < -180.0f) curYaw += 360.0f;

            float dYaw = wantYaw - curYaw;
            while (dYaw > 180.0f) dYaw -= 360.0f;
            while (dYaw < -180.0f) dYaw += 360.0f;
            float dPitch = wantPitch - curPitch;

            // never whip the camera around (a jump this big means stale data)
            if (dYaw > 60.0f) dYaw = 60.0f; else if (dYaw < -60.0f) dYaw = -60.0f;
            if (dPitch > 45.0f) dPitch = 45.0f; else if (dPitch < -45.0f) dPitch = -45.0f;

            float k = g_aimStrength;
            out.Pitch = curPitch + dPitch * k;
            out.Yaw   = curYaw + dYaw * k;

            if (out.Pitch > 90.0f) out.Pitch = 90.0f;
            if (out.Pitch < -90.0f) out.Pitch = -90.0f;
            if (out.Yaw > 180.0f) out.Yaw -= 360.0f;
            if (out.Yaw < -180.0f) out.Yaw += 360.0f;

            static int clamped = 0;
            if (clamped++ < 3 && (fabsf(dYaw) >= 59.9f || fabsf(dPitch) >= 44.9f)) {
                aimbot_log("AIM: clamped a %.1f deg / %.1f deg jump", dYaw, dPitch);
            }
        } else {
            out.Pitch = wantPitch;
            out.Yaw = wantYaw;
        }
    }
    out.Roll  = 0.0f;
    return true;
}

// The aim write is done from two places (the poll thread and the ProcessEvent
// hook), so it must be serialised.
static std::mutex g_aimMutex;

// The poll thread computes the rotation, the GAME THREAD applies it through
// the engine (SetControlRotation touches the world and the network, so it
// must not run on our own thread).
static std::mutex g_pendingMutex;
static FRotator g_pendingAim{};
static bool g_pendingValid = false;
static std::atomic<bool> g_inEngineCall{false};

static void publish_aim(const FRotator& aim)
{
    std::lock_guard<std::mutex> lk(g_pendingMutex);
    g_pendingAim = aim;
    g_pendingValid = true;
}

// Runs on the game thread (from the ProcessEvent hook).
static void apply_pending_aim_engine()
{
    FRotator aim{};
    {
        std::lock_guard<std::mutex> lk(g_pendingMutex);
        if (!g_pendingValid) return;
        aim = g_pendingAim;
        g_pendingValid = false;
    }
    if (!g_myPC || !g_setControlRotFunc || !g_useEngineAim || !g_useEngineCall) return;
    if (!std::isfinite(aim.Yaw) || !std::isfinite(aim.Pitch)) return;
    if (aim.Pitch < -89.0f || aim.Pitch > 89.0f) return;

    // The parms buffer.  The engine copies UFunction::ParamSize bytes OUT of
    // it, so a bare 12 byte FRotator is not enough: the rotation's offset
    // and the buffer size come from the function's own property list.
    static int offRot = -1;
    static int psize = 0;
    if (offRot < 0) {
        UE_Offsets* o = active_offsets();
        if (!o) return;
        offRot = find_prop_offset(g_setControlRotFunc, o, "NewRotation");
        if (offRot < 0) offRot = find_prop_offset(g_setControlRotFunc, o, "Rotation");
        psize = func_param_size(g_setControlRotFunc);
        aimbot_log("AIM: SetControlRotation layout NewRotation=%d parms=%d", offRot, psize);
    }
    if (psize <= 0 || psize > 0x100) return;          // unknown layout - do not call
    if (offRot < 0) offRot = 0;                        // assume the first field
    if (offRot + (int)sizeof(FRotator) > psize) return; // the rotation does not fit

    uint8_t buf[0x100];
    memset(buf, 0, sizeof(buf));
    FRotator p = aim;
    memcpy(buf + offRot, &p, sizeof(FRotator));

    g_inEngineCall.store(true);
    set_last_op("set-control-rotation");
    call_engine_function(g_myPC, g_setControlRotFunc, buf);
    g_inEngineCall.store(false);
    set_last_op("idle");
}

// Returns a reason code: 0 = aimed ok, 1 = no controller, 2 = read failed,
// 3 = EnemyAIM null, 4 = mesh null, 5 = root null.
static int poll_aimbot()
{
    if (!g_myPC) return 1;
    if (!g_inGame) return 6;      // not in a match - do not touch anything
    std::unique_lock<std::mutex> lk(g_aimMutex, std::try_to_lock);
    if (!lk.owns_lock()) return 0;      // another thread is aiming right now

    uint8_t* pc = g_myPC;

    bool bAIM = false;
    UEMemory::vm_rpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_bAIM), &bAIM, sizeof(bAIM));

    FRotator aim{};
    if (!compute_aim_rotation(aim)) {
        uint8_t* dummy = nullptr;
        return resolve_target(&dummy);
    }

    // 0) Read the rotation the game left in ControlRotation BEFORE we write.
    //    If it differs from ours, the game is overwriting it every frame.
    FRotator preRot{};
    UEMemory::vm_rpm_ptr((void*)((uintptr_t)pc + Offsets::AController_ControlRot), &preRot, sizeof(preRot));

    FRotator computedAim = aim;   // keep the value we computed

    // 1) THE ENGINE PATH ----------------------------------------------------
    //    We drive the rotation ourselves: write ControlRotation (the camera
    //    manager builds its view from it) and every camera POV cache, so the
    //    view snaps on the next camera update.
    if (g_useEngineAim) {
        set_last_op("write-control-rotation");
        vm_wpm_ptr((void*)((uintptr_t)pc + Offsets::AController_ControlRot),
                   &computedAim, sizeof(FRotator));
    }

    // 2) The camera POV caches ---------------------------------------------
    //    We do not know which one this custom camera manager renders from,
    //    so we set them all (and the camera actor's own rotation).
    if (g_useEngineAim) {
        uintptr_t camMgr = 0;
        if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)pc + Offsets::APlayerController_CamMgr), &camMgr, sizeof(camMgr)) && camMgr) {
            uintptr_t povBases[4] = {
                camMgr + Offsets::ACamMgr_CameraCache,
                camMgr + Offsets::ACamMgr_CameraCachePrivate,
                camMgr + Offsets::ACamMgr_LastFrameCache,
                camMgr + Offsets::ACamMgr_ViewTarget + Offsets::FViewTarget_POV
            };
            for (int i = 0; i < 4; i++) {
                uintptr_t povRot = povBases[i] + Offsets::FCameraCacheEntry_POV + Offsets::FMinimalViewInfo_Rotation;
                vm_wpm_ptr((void*)povRot, &computedAim, sizeof(FRotator));
            }
            // The ViewTarget actor (and the animation camera actor) are the
            // cameras the manager renders from - rotate them directly.
            uintptr_t camActors[2] = {0, 0};
            UEMemory::vm_rpm_ptr((void*)(camMgr + Offsets::ACamMgr_ViewTarget + Offsets::FViewTarget_Target), &camActors[0], sizeof(uintptr_t));
            UEMemory::vm_rpm_ptr((void*)(camMgr + Offsets::ACamMgr_AnimCamActor), &camActors[1], sizeof(uintptr_t));
            for (int i = 0; i < 2; i++) {
                uintptr_t vtActor = camActors[i];
                if (!vtActor) continue;
                uintptr_t root = 0;
                if (UEMemory::vm_rpm_ptr((void*)(vtActor + Offsets::AActor_RootComp), &root, sizeof(root)) && root) {
                    // USceneComponent::RelativeRotation sits right after
                    // RelativeLocation (0x164 + 0xC) in this build.
                    vm_wpm_ptr((void*)(root + Offsets::USceneComp_RelLoc + 0xC), &computedAim, sizeof(FRotator));
                }
            }
        }
    }

    // 3) THE GAME'S OWN AIM ASSIST ----------------------------------------
    //    The game aims at MyPlayerController::EnemyAIM.  Point it at OUR
    //    target and let the game do the rotating (that way the server gets
    //    the rotation through the game's own code).
    if (g_useGameAssist && g_keepGameAssist) {
        if (g_targetEnemy) {
            uintptr_t tgt = (uintptr_t)g_targetEnemy;
            vm_wpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_EnemyAIM), &tgt, sizeof(uintptr_t));
        }
        bool on = true;
        vm_wpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_bAIM), &on, sizeof(on));
        vm_wpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_bAIMifZoom), &on, sizeof(on));

        float angleAIM = 180.0f;
        float aimStep  = 100.0f;   // big step = instant
        float timerAIM = 0.0f;
        float tAIM     = 0.0f;
        float sens     = 10.0f;
        vm_wpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_AngleAIM), &angleAIM, sizeof(angleAIM));
        vm_wpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_AIMStep), &aimStep, sizeof(aimStep));
        vm_wpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_TimerAIM), &timerAIM, sizeof(timerAIM));
        vm_wpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_tAIM), &tAIM, sizeof(tAIM));
        vm_wpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_Sensitivity), &sens, sizeof(sens));
    } else {
        // Force the game's own assist OFF so we are the only thing driving.
        bool off = false;
        vm_wpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_bAIM), &off, sizeof(off));
        vm_wpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_bAIMifZoom), &off, sizeof(off));
        static int reported = 0;
        if (reported++ < 3) {
            bool r1 = true, r2 = true;
            UEMemory::vm_rpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_bAIM), &r1, sizeof(r1));
            UEMemory::vm_rpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_bAIMifZoom), &r2, sizeof(r2));
            aimbot_write_trace("AIM: game assist forced OFF -> readback bAIM=%d bZoom=%d (1=game overwrote us)",
                                (int)r1, (int)r2);
        }
    }

    if (g_autoFire) {
        bool on = true;
        vm_wpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_bAutoFire), &on, sizeof(on));
    }

    // 3b) FIRE DETECTOR ----------------------------------------------------
    //     The game raises bFireBut / bFireDoubleTap when it pulls the
    //     trigger, so a 0 -> 1 edge means "the gun went off".
    {
        bool fireBut = false, dblTap = false;
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_bFireBut), &fireBut, sizeof(fireBut));
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)pc + Offsets::MyPC_bFireDoubleTap), &dblTap, sizeof(dblTap));
        static bool prevBut = false, prevTap = false;
        if (fireBut && !prevBut) {
            static int shots = 0;
            aimbot_log("AIM: FIRE detected #%d (bFireBut) target=0x%lx autoFire=%d",
                       ++shots, (uintptr_t)g_targetEnemy, g_autoFire ? 1 : 0);
        } else if (dblTap && !prevTap) {
            static int shots = 0;
            aimbot_log("AIM: FIRE detected #%d (bFireDoubleTap) target=0x%lx autoFire=%d",
                       ++shots, (uintptr_t)g_targetEnemy, g_autoFire ? 1 : 0);
        }
        prevBut = fireBut;
        prevTap = dblTap;
    }

    // 4) Hand the rotation to the GAME THREAD --------------------------------
    //    AController::SetControlRotation() is the engine's own way of
    //    changing where the player looks (it also rotates the pawn and
    //    replicates the rotation to the server).  It must run on the game
    //    thread, so we only publish the rotation here - the ProcessEvent
    //    hook (which runs inside the game thread) applies it.
    publish_aim(computedAim);

    // ---- diagnostics -----------------------------------------------------
    static int frame = 0;
    if (++frame >= 20) {
        frame = 0;
        FRotator cur{};
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)pc + Offsets::AController_ControlRot), &cur, sizeof(cur));
        bool overwrote = (fabsf(preRot.Yaw - cur.Yaw) > 0.05f || fabsf(preRot.Pitch - cur.Pitch) > 0.05f);
        aimbot_log("AIM: rot our=(p=%.2f y=%.2f) ctrlNow=(p=%.2f y=%.2f) preWrite=(p=%.2f y=%.2f) %s",
                   computedAim.Pitch, computedAim.Yaw, cur.Pitch, cur.Yaw, preRot.Pitch, preRot.Yaw,
                   overwrote ? "GAME-OVERWROTE" : "ours");
    }

    // Log the whole picture once in a while so we can compare it with what
    // the game actually does.
    static int detail = 0;
    if (++detail >= 20) {
        detail = 0;
        uint8_t* tgt = g_targetEnemy;

        FCamInfo ci{};
        get_camera_info(ci);
        FVector tl{};
        uintptr_t r2 = 0;
        if (tgt) {
            UEMemory::vm_rpm_ptr((void*)((uintptr_t)tgt + Offsets::AActor_RootComp), &r2, sizeof(r2));
            if (r2) UEMemory::vm_rpm_ptr((void*)(r2 + Offsets::USceneComp_RelLoc), &tl, sizeof(tl));
        }
        // the camera ACTOR's own rotation (what actually renders)
        float actorYaw = 0.0f, actorPitch = 0.0f;
        uintptr_t camActor = 0;
        {
            uintptr_t camMgr = 0;
            if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::APlayerController_CamMgr), &camMgr, sizeof(camMgr)) && camMgr) {
                UEMemory::vm_rpm_ptr((void*)(camMgr + Offsets::ACamMgr_ViewTarget + Offsets::FViewTarget_Target), &camActor, sizeof(camActor));
            }
            if (camActor) {
                uintptr_t root = 0;
                if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)camActor + Offsets::AActor_RootComp), &root, sizeof(root)) && root) {
                    FRotator ar{};
                    UEMemory::vm_rpm_ptr((void*)(root + Offsets::USceneComp_RelRot), &ar, sizeof(ar));
                    actorYaw = ar.Yaw; actorPitch = ar.Pitch;
                }
            }
        }
        // the game's own control rotation (read back AFTER our write)
        FRotator ctrl{};
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)pc + Offsets::AController_ControlRot), &ctrl, sizeof(ctrl));

        // where the head actually is
        FVector headPt{};
        bool haveHead = false;
        float capsuleHalf = 0.0f;
        if (tgt) {
            uintptr_t root = 0, cap = 0;
            if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)tgt + Offsets::AActor_RootComp), &root, sizeof(root)) && root) {
                FVector rootLoc{};
                UEMemory::vm_rpm_ptr((void*)(root + Offsets::USceneComp_RelLoc), &rootLoc, sizeof(rootLoc));
                haveHead = get_head_position(tgt, rootLoc, headPt);
            }
            if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)tgt + Offsets::AChar_Capsule), &cap, sizeof(cap)) && cap) {
                UEMemory::vm_rpm_ptr((void*)(cap + Offsets::UCapsule_HalfHeight), &capsuleHalf, sizeof(capsuleHalf));
            }
        }

        aimbot_log("AIM: SHOT cam=(%.0f,%.0f,%.0f) tgt=(%.0f,%.0f,%.0f) head=(%.0f,%.0f,%.0f) headOk=%d capHalf=%.0f headZ=%.2f "
                   "ourAim=(%.1f,%.1f) engineAim=(%.1f,%.1f) ctrlRot=(%.1f,%.1f) pov=(%.1f,%.1f) camActorRot=(%.1f,%.1f) camActor=0x%lx",
                   ci.loc.X, ci.loc.Y, ci.loc.Z, tl.X, tl.Y, tl.Z,
                   headPt.X, headPt.Y, headPt.Z, haveHead ? 1 : 0, capsuleHalf, g_headZ,
                   computedAim.Yaw, computedAim.Pitch,
                   g_haveEngineAim ? g_lastEngineAim.Yaw : 0.0f,
                   g_haveEngineAim ? g_lastEngineAim.Pitch : 0.0f,
                   ctrl.Yaw, ctrl.Pitch, ci.rot.Yaw, ci.rot.Pitch,
                   actorYaw, actorPitch, camActor);
    }
    return 0;
}



static void hk_ProcessEvent(void* obj, void* func, void* params)
{
    static long long hookCalls = 0;
    // Remember which thread the game calls ProcessEvent from - that is the
    // only thread that may call into the engine.
    if (!g_gameThreadTid.load(std::memory_order_relaxed)) {
        g_gameThreadTid.store((uint64_t)pthread_self(), std::memory_order_relaxed);
    }
    if (++hookCalls == 1) {
        LOGI("AIMBOT: hook entered (first call)");
        aimbot_write_trace("AIM: hook entered");
    }
    // How often does the game call ProcessEvent?  (that is the rate at which
    //  we can touch the engine from the game thread)
    {
        static uint64_t lastRate = 0;
        static long long lastCount = 0;
        uint64_t nowT = now_us();
        if (!lastRate) { lastRate = nowT; lastCount = hookCalls; }
        else if (nowT - lastRate > 1000000) {
            aimbot_log("AIM: ProcessEvent hook rate = %lld calls/s", hookCalls - lastCount);
            lastRate = nowT;
            lastCount = hookCalls;
        }
    }
    read_config_throttled();
    static bool cfgLogged = false;
    if (!cfgLogged) {
        cfgLogged = true;
        LOGI("AIMBOT: config enabled=%d head=%d instant=%d autofire=%d",
             g_aimbotEnabled, g_headOnly, g_instantAim, g_autoFire);
        aimbot_write_trace("AIM: config enabled=%d head=%d instant=%d autofire=%d gameassist=%d esp=%d engineaim=%d gameaim=%d",
                            g_aimbotEnabled, g_headOnly, g_instantAim, g_autoFire, g_keepGameAssist,
                            g_espEnabled, g_useEngineAim, g_useGameAssist);
    }

    // Re-entrancy guard: if ProcessEvent is called recursively we must not
    // un-patch/re-patch out of order.
    bool expected = false;
    if (!g_peHook.inHook.compare_exchange_strong(expected, true)) {
        // Re-entrant call: the entry is currently PATCHED, so we must un-patch
        // before calling the original - otherwise we would jump straight back
        // into this stub and recurse forever.
        call_original_pe(obj, func, params);
        return;
    }

    // The trigger pull: zero the spread and the recoil right BEFORE the
    // weapon fires (the game recomputes the spread in its own tick, so the
    // periodic zeroing is not enough).
    if (g_startFireFunc && func == (void*)g_startFireFunc) {
        set_last_op("start-fire");
        Intro::remove_bullet_spread();
        set_last_op("idle");
    }

    // Let the game run its own logic first (it sets ControlRotation).
    set_last_op("pe-hook-call-original");
    call_original_pe(obj, func, params);

    // Then override the aim if this is our player controller.
    if (g_aimbotEnabled && g_myPC) {
        // We are inside the GAME THREAD here, so this is the safe place to
        // hand our rotation to the engine (SetControlRotation).
        static uint64_t lastApply = 0;
        uint64_t nowT = now_us();
        if (nowT - lastApply > 30000) {      // at most every 30 ms
            lastApply = nowT;
            apply_pending_aim_engine();
        }
        // Remove the bullet spread here (game thread): the game recomputes
        // the spread in its own tick, so it has to be zeroed right before the
        // shot is fired.
        {
            static uint64_t lastSpread = 0;
            if (nowT - lastSpread > 1000) {    // every ms
                lastSpread = nowT;
                Intro::remove_bullet_spread();
            }
        }
        // The bone data is only valid on the game thread -> sample it here.
        static uint64_t lastBones = 0;
        if (nowT - lastBones > 200000) {     // 5 Hz
            lastBones = nowT;
            set_last_op("sample_bones");
            Intro::sample_bones();
            set_last_op("idle");
        }
        // NOTE: the full dump does NOT run here - it is a long scan and this
        // is the GAME THREAD (it froze the game and the ANR watchdog killed
        // it).  It runs on the aim thread instead (see the poll loop).
        if (obj && (uint8_t*)obj == g_myPC) {
            poll_aimbot();
        }
    }

    g_peHook.inHook.store(false);
}
// ---------------------------------------------------------------------------
// AController::SetControlRotation(FRotator& NewRotation)
//
// This is where the game hands its computed look rotation to the engine.
// Replacing the rotation HERE means the camera uses our value on the very
// same frame - unlike polling ControlRotation, which the game overwrites
// every tick with the player's mouse input.
// ---------------------------------------------------------------------------
typedef void (*SetControlRotationFn)(void* self, FRotator* newRot);
static SetControlRotationFn o_SetControlRotation = nullptr;

static void hk_SetControlRotation(void* self, FRotator* newRot)
{
    // We are the ones calling the engine (apply_pending_aim_engine) - do not
    // recurse into the aim logic, just pass the rotation through.
    if (g_inEngineCall.load()) {
        if (g_scrHook.patched && o_SetControlRotation) {
            memcpy((void*)g_scrHook.target, &g_scrHook.origInsn, 4);
            __builtin___clear_cache((char*)g_scrHook.target, (char*)g_scrHook.target + 4);
            o_SetControlRotation(self, newRot);
            memcpy((void*)g_scrHook.target, &g_scrHook.branchInsn, 4);
            __builtin___clear_cache((char*)g_scrHook.target, (char*)g_scrHook.target + 4);
        } else if (o_SetControlRotation) {
            o_SetControlRotation(self, newRot);
        }
        return;
    }

    bool expected = false;
    if (!g_scrHook.inHook.compare_exchange_strong(expected, true)) {
        if (o_SetControlRotation) o_SetControlRotation(self, newRot);
        return;
    }

    // Compute our aim (does not write ControlRotation itself here).
    int reason = poll_aimbot();

    // Diagnostics: show that the hook is reached, and whether the call comes
    // from OUR controller.
    static int scrCalls = 0;
    if (scrCalls < 5) {
        scrCalls++;
        bool mine = (self && g_myPC && (uint8_t*)self == g_myPC);
        aimbot_write_trace("AIM: SetControlRotation call #%d self=0x%lx myPC=0x%lx isMine=%d reason=%d rot=(%.1f,%.1f)",
                            scrCalls, (uintptr_t)self, (uintptr_t)g_myPC, mine, reason,
                            newRot ? newRot->Pitch : 0.0f, newRot ? newRot->Yaw : 0.0f);
    }

    if (self && g_myPC && (uint8_t*)self == g_myPC && g_aimbotEnabled && newRot && reason == 0) {
        FRotator aim{};
        if (compute_aim_rotation(aim)) {
            *newRot = aim;   // the engine will use THIS rotation
            static int hits = 0;
            if (++hits <= 5) {
                uint8_t* tgt = g_targetEnemy;
                FVector tl{};
                if (tgt) {
                    uintptr_t r = 0;
                    if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)tgt + Offsets::AActor_RootComp), &r, sizeof(r)) && r) {
                        UEMemory::vm_rpm_ptr((void*)(r + Offsets::USceneComp_RelLoc), &tl, sizeof(tl));
                    }
                }
                aimbot_write_trace("AIM: override #%d target=0x%lx at (%.0f,%.0f,%.0f) -> p=%.2f y=%.2f",
                                    hits, (uintptr_t)tgt, tl.X, tl.Y, tl.Z, aim.Pitch, aim.Yaw);
            }
        }
    }

    // Call the original in place.
    if (g_scrHook.patched && o_SetControlRotation) {
        memcpy((void*)g_scrHook.target, &g_scrHook.origInsn, 4);
        __builtin___clear_cache((char*)g_scrHook.target, (char*)g_scrHook.target + 4);
        o_SetControlRotation(self, newRot);
        memcpy((void*)g_scrHook.target, &g_scrHook.branchInsn, 4);
        __builtin___clear_cache((char*)g_scrHook.target, (char*)g_scrHook.target + 4);
    } else if (o_SetControlRotation) {
        o_SetControlRotation(self, newRot);
    }

    g_scrHook.inHook.store(false);
}


// Safe inline hook.
//
// A classic trampoline (copy the prologue to a new page) BREAKS if the
// prologue contains a PC-relative instruction (ADRP/ADR/B) - the relocated
// copy would compute the wrong target and corrupt the game.
//
// Instead we do "unpatch and call in place":
//   1. restore the original 4 bytes at the function entry
//   2. call the ORIGINAL function at its real address (PC-relative safe)
//   3. re-install the branch
// Another thread racing through the unpatched window simply runs the
// original - no crash, just a missed hook.
static bool install_safe_hook(SafeHook& h, uintptr_t target, void* stub)
{
    if (!target) return false;

    uintptr_t stub_addr = reinterpret_cast<uintptr_t>(stub);
    int64_t off = (int64_t)(stub_addr - target) >> 2;
    if (off < -(1 << 25) || off > (1 << 25)) {
        LOGE("AIMBOT: hook target too far (0x%lx)", target);
        return false;
    }
    h.branchInsn = 0x14000000 | (uint32_t)(off & 0x3FFFFFF);

    if (!UEMemory::vm_rpm_ptr((void*)target, &h.origInsn, sizeof(h.origInsn))) {
        LOGE("AIMBOT: cannot read original instruction at 0x%lx", target);
        return false;
    }

    uintptr_t page = target & ~(uintptr_t)4095;
    if (mprotect((void*)page, 4096, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("AIMBOT: mprotect failed for hook at 0x%lx", target);
        return false;
    }
    memcpy((void*)target, &h.branchInsn, 4);
    __builtin___clear_cache((char*)target, (char*)target + 4);
    h.patched = true;
    h.target = target;
    h.origAddr = (void*)target;
    return true;
}

// Restore the original instruction, run the original, then re-patch.
template <typename F>
static inline void call_original(SafeHook& h, F fn, void* a, void* b, void* c)
{
    if (!h.patched || !h.origAddr) {
        if (h.origAddr) ((F)h.origAddr)(a, b, c);
        return;
    }
    memcpy((void*)h.target, &h.origInsn, 4);
    __builtin___clear_cache((char*)h.target, (char*)h.target + 4);

    fn(a, b, c);   // original runs IN PLACE

    memcpy((void*)h.target, &h.branchInsn, 4);
    __builtin___clear_cache((char*)h.target, (char*)h.target + 4);
}

// Restore the original instruction, run the original, then re-patch.
static inline void call_original_pe(void* obj, void* func, void* params)
{
    if (!g_peHook.patched || !o_ProcessEvent) {
        if (o_ProcessEvent) o_ProcessEvent(obj, func, params);
        return;
    }
    memcpy((void*)g_peHook.target, &g_peHook.origInsn, 4);
    __builtin___clear_cache((char*)g_peHook.target, (char*)g_peHook.target + 4);

    o_ProcessEvent(obj, func, params);   // original runs IN PLACE

    memcpy((void*)g_peHook.target, &g_peHook.branchInsn, 4);
    __builtin___clear_cache((char*)g_peHook.target, (char*)g_peHook.target + 4);
}

// Call MANY engine functions with ONE un-patch / re-patch cycle.
//
// Every un-patch/re-patch flushes the instruction cache of the game's hottest
// code page - doing that per call cost ~900 icache flushes per bone sample
// and dropped the game to 3 FPS (and then it froze).  Batching makes it two
// flushes per sample.
static void call_engine_functions_batch(void* const* objs, void* const* funcs, void* const* params, int count)
{
    if (!o_ProcessEvent) return;
    if (!on_game_thread()) {
        static int warned = 0;
        if (warned++ < 3) {
            aimbot_log("AIM: engine batch skipped (not the game thread)");
        }
        return;
    }
    set_last_op("engine-batch");
    if (g_peHook.patched) {
        memcpy((void*)g_peHook.target, &g_peHook.origInsn, 4);
        __builtin___clear_cache((char*)g_peHook.target, (char*)g_peHook.target + 4);
        for (int i = 0; i < count; i++) {
            if (objs[i] && funcs[i]) o_ProcessEvent(objs[i], funcs[i], (void*)params[i]);
        }
        memcpy((void*)g_peHook.target, &g_peHook.branchInsn, 4);
        __builtin___clear_cache((char*)g_peHook.target, (char*)g_peHook.target + 4);
    } else {
        for (int i = 0; i < count; i++) {
            if (objs[i] && funcs[i]) o_ProcessEvent(objs[i], funcs[i], (void*)params[i]);
        }
    }
    set_last_op("idle");
}

// Find the LOCAL player controller.  APlayerController::Player (ULocalPlayer*)
// is only set on the local controller, so it identifies the one whose camera
// we control.  Also used to re-acquire the controller after a game reset
// (the object is destroyed and re-created).
static uint8_t* find_local_controller(int maxAttempts)
{
    for (int attempt = 0; attempt < maxAttempts; attempt++) {
        if (!UEWrappers::GetObjects()) { sleep(1); continue; }
        UE_UClass myPCClass = UEWrappers::GetObjects()->FindObject("Class SpecialForces.MyPlayerController").Cast<UE_UClass>();
        UEWrappers::GetObjects()->ForEachObject([&](UE_UObject obj) {
            if (g_myPC || !obj) return false;
            if (myPCClass && !obj.IsA(myPCClass)) return false;
            uintptr_t playerPtr = 0;
            if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)obj.GetAddress() + Offsets::APlayerController_Player), &playerPtr, sizeof(playerPtr)))
                return false;
            if (!playerPtr) return false;   // not the local controller
            g_myPC = (uint8_t*)obj.GetAddress();
            return true;
        });
        if (g_myPC) return g_myPC;
        sleep(1);
    }
    return nullptr;
}

// Is the controller still alive?  After a game reset the object is gone and
// the pointer dangles - reading it would crash the game.
static bool controller_is_valid(uint8_t* pc)
{
    if (!pc) return false;
    uintptr_t player = 0;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)pc + Offsets::APlayerController_Player), &player, sizeof(player)))
        return false;
    return player != 0;
}

// Watchdog: re-acquire the controller when the game resets.
static void check_controller_alive()
{
    static uint64_t lastCheck = 0;
    uint64_t now = now_us();
    if (lastCheck && (now - lastCheck) < 1000000) return;   // 1 s
    lastCheck = now;
    if (controller_is_valid(g_myPC)) return;

    set_last_op("reacquire-controller");
    aimbot_log("AIM: controller 0x%lx is gone (game reset?) - re-acquiring", (unsigned long)(uintptr_t)g_myPC);
    g_myPC = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_enemyMutex);
        g_enemyCache.clear();
    }
    {
        // the class list belongs to the cache thread - clear it under ITS
        // lock, otherwise we race with its rebuild
        std::lock_guard<std::mutex> lk(g_manClassMutex);
        g_enemyClassPtrs.clear();
    }
    g_targetEnemy = nullptr;

    if (find_local_controller(60)) {
        aimbot_log("AIM: controller re-acquired: 0x%lx", (unsigned long)(uintptr_t)g_myPC);
        // the new controller needs a fresh hook
        uintptr_t scrAddr = g_libUE4_base + Offsets::SetControlRotation;
        if (!g_scrHook.patched) {
            if (install_safe_hook(g_scrHook, scrAddr, (void*)hk_SetControlRotation)) {
                o_SetControlRotation = reinterpret_cast<SetControlRotationFn>(g_scrHook.origAddr);
                aimbot_log("AIM: SetControlRotation hook re-installed");
            }
        }
    } else {
        aimbot_log("AIM: FAILED - no local controller after the reset");
    }
}

static void* aimbot_thread(void*) {
    install_crash_handler();
    LOGI("AIMBOT: thread started");
    aimbot_write_trace("AIM: thread started (crash handler installed)");
    sleep(2);
    LOGI("AIMBOT: woke up after 2s");

    // Wait for the dumper's profile to finish InitUEVars().
    //
    // IMPORTANT: do NOT call GetUnrealELF() here. It uses kMgr.findMemElf(),
    // but kMgr.initialize() only runs inside dump_thread (t=20s) - calling it
    // earlier crashes. GetUnrealELF() also has a shared static, so calling it
    // from two threads races with the dump thread.
    // Instead wait for the profile's UEVars to be populated, which happens
    // inside InitUEVars() and gives us the base address directly.
    uintptr_t base = 0;
    for (int attempt = 0; attempt < 90 && base == 0; attempt++) {
        for (auto &it : UE_Games)
        {
            auto vars = it->GetUEVars();
            if (vars) {
                base = vars->GetBaseAddress();
                // UEWrappers::Init() (which builds the object array) runs AFTER
                // BaseAddress is set, so wait for both.
                if (base && UEWrappers::GetObjects()) break;
                base = 0;
            }
        }
        if (base == 0) {
            LOGI("AIMBOT: profile not initialised yet (attempt %d)", attempt + 1);
            sleep(1);
        }
    }

    if (base == 0) {
        LOGE("AIMBOT: profile never initialised (no base address)");
        aimbot_write_trace("AIM: FAILED - profile not initialised");
        return nullptr;
    }

    g_libUE4_base = base;
    g_GNames = base + Offsets::GNames;
    g_GUObjectArray = base + Offsets::GUObjectArray;
    g_UWorld = base + Offsets::UWorld;
    LOGI("AIMBOT: base=0x%lx GUObjArray=0x%lx", g_libUE4_base, g_GUObjectArray);

    // --- Start the enemy cache thread (refreshes the enemy list 2x/sec) ---
    {
        pthread_t cache_tid;
        pthread_create(&cache_tid, nullptr, enemy_cache_thread, nullptr);
        pthread_detach(cache_tid);
        LOGI("AIMBOT: enemy cache thread started");
    }

    // --- Look the engine UFunctions up (slow, own thread) ---
    {
        pthread_t func_tid;
        pthread_create(&func_tid, nullptr, engine_func_thread, nullptr);
        pthread_detach(func_tid);
    }

    // --- Find the LOCAL player controller ---
    // APlayerController::Player (ULocalPlayer*) is only set on the local
    // controller, so it identifies the one whose camera we control.
    find_local_controller(120);
    if (g_myPC) {
        LOGI("AIMBOT: MyPlayerController found at 0x%lx", (uintptr_t)g_myPC);
        aimbot_write_trace("AIM: controller=0x%lx", (uintptr_t)g_myPC);

        // Hook AController::SetControlRotation - the game hands its computed
        // look rotation to the engine there, so overriding it applies on the
        // same frame instead of being overwritten by mouse input.
        uintptr_t scrAddr = g_libUE4_base + Offsets::SetControlRotation;
        if (install_safe_hook(g_scrHook, scrAddr, (void*)hk_SetControlRotation)) {
            o_SetControlRotation = reinterpret_cast<SetControlRotationFn>(g_scrHook.origAddr);
            LOGI("AIMBOT: SetControlRotation hook INSTALLED at 0x%lx", scrAddr);
            aimbot_write_trace("AIM: SetControlRotation hook installed at 0x%lx", scrAddr);
        } else {
            LOGE("AIMBOT: SetControlRotation hook FAILED");
            aimbot_write_trace("AIM: FAILED - SetControlRotation hook");
        }
    } else {
        LOGE("AIMBOT: MyPlayerController NOT FOUND");
        aimbot_write_trace("AIM: FAILED - MyPlayerController not found");
    }

    // --- Find a live UObject to scan the vtable: GWorld pointer ---
    // (done AFTER the controller is found so the aim feature works even if
    //  the vtable scan is imperfect)
    if (g_myPC) {
        uint8_t* gworldObj = nullptr;
        for (int attempt = 0; attempt < 30 && !gworldObj; attempt++) {
            uintptr_t wptr = 0;
            if (UEMemory::vm_rpm_ptr((void*)g_UWorld, &wptr, sizeof(wptr)) && wptr) gworldObj = (uint8_t*)wptr;
            if (!gworldObj) sleep(1);
        }
        if (gworldObj) {
            LOGI("AIMBOT: GWorld obj=0x%lx", (uintptr_t)gworldObj);
            aimbot_write_trace("AIM: GWorld obj=0x%lx", (uintptr_t)gworldObj);
            uintptr_t pe = scan_vtable_for_process_event(gworldObj);
            if (pe) {
                g_ProcessEvent = pe;
                if (install_safe_hook(g_peHook, pe, (void*)hk_ProcessEvent)) {
                    o_ProcessEvent = reinterpret_cast<ProcessEventFn>(g_peHook.origAddr);
                    g_hookInstalled = true;
                    LOGI("AIMBOT: ProcessEvent hook INSTALLED at 0x%lx", pe);
                    aimbot_write_trace("AIM: PE hook installed at 0x%lx", pe);
                } else {
                    aimbot_write_trace("AIM: FAILED - PE hook install");
                }
            }
        } else {
            aimbot_write_trace("AIM: GWorld object not found (no vtable scan)");
        }
    }

    // --- Aim loop ---
    int lastReason = -1;
    int hb = 0;
    while (true) {
        read_config_throttled();
        // The game can reset (the controller object is destroyed and
        // re-created) - re-acquire it when that happens.
        check_controller_alive();
        // Only work when we are really in a match.
        {
            static uint64_t lastState = 0;
            if (now_us() - lastState > 500000) {   // twice a second
                lastState = now_us();
                update_game_state();
            }
        }
        // A full dump of everything we might need (the bone array offsets,
        // the UFunction layouts) - it is a long scan, so it runs HERE (the
        // aim thread), never on the game thread.
        if (g_fullDumpRequested) {
            g_fullDumpRequested = false;
            set_last_op("full-dump");
            Intro::dump_full_info();
            set_last_op("idle");
        }
        {
            // and automatically a few times, until it captured a live enemy
            static int dumpAttempts = 0;
            static uint64_t nextDump = 0;
            uint64_t nowD = now_us();
            if (dumpAttempts < 20 && nowD > 15000000 && nowD > nextDump && g_inGame) {
                dumpAttempts++;
                nextDump = nowD + 15000000;   // try again in 15 s
                set_last_op("full-dump");
                if (Intro::dump_full_info()) dumpAttempts = 99;   // got everything
                set_last_op("idle");
            }
        }
        if (g_aimbotEnabled && g_myPC) {
            // Always poll: ProcessEvent is only invoked for reflected
            // functions, so the controller may never be seen by the hook.
            int reason = poll_aimbot();
            if (reason != lastReason) {
                lastReason = reason;
                uintptr_t enemyRaw = 0;
                bool bAIM = false;
                UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_EnemyAIM), &enemyRaw, sizeof(enemyRaw));
                UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_bAIM), &bAIM, sizeof(bAIM));
                aimbot_log("AIM: poll pc=0x%lx bAIM=%d EnemyAIM=0x%lx reason=%d",
                                    (uintptr_t)g_myPC, (int)bAIM, enemyRaw, reason);
            }
            if (++hb >= 100) {   // heartbeat every second
                hb = 0;
                uintptr_t enemyRaw = 0;
                bool bAIM = false, bAutoFire = false, bFireBut = false;
                UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_EnemyAIM), &enemyRaw, sizeof(enemyRaw));
                UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_bAIM), &bAIM, sizeof(bAIM));
                UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_bAutoFire), &bAutoFire, sizeof(bAutoFire));
                UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_bFireBut), &bFireBut, sizeof(bFireBut));

                // Full snapshot: everything we know in one line.
                FRotator ctrlRot{}; FVector camLoc{}; FRotator camRot{};
                UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::AController_ControlRot), &ctrlRot, sizeof(ctrlRot));
                FCamInfo ci{};
                if (get_camera_info(ci)) { camLoc = ci.loc; camRot = ci.rot; }

                float gAngle = 0, gStep = 0, gTimer = 0, gT = 0, gSens = 0;
                UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_AngleAIM), &gAngle, sizeof(gAngle));
                UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_AIMStep), &gStep, sizeof(gStep));
                UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_TimerAIM), &gTimer, sizeof(gTimer));
                UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_tAIM), &gT, sizeof(gT));
                UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_Sensitivity), &gSens, sizeof(gSens));

                size_t cacheCount = 0;
                { std::lock_guard<std::mutex> lk(g_enemyMutex); cacheCount = g_enemyCache.size(); }

                // The heartbeat: a full state dump every second, so a crash
                // is always preceded by a snapshot of what we were doing.
                char hbOp[160] = {0}, hbDetail[160] = {0};
                {
                    std::lock_guard<std::mutex> lk(g_lastOpMutex);
                    snprintf(hbOp, sizeof(hbOp), "%s", g_lastOp);
                    snprintf(hbDetail, sizeof(hbDetail), "%s", g_lastDetail);
                }
                aimbot_log("AIM: HB pc=0x%lx reason=%d ctrl=(%.1f,%.1f) camSrc=%d cam=(%.0f,%.0f,%.0f) camRot=(%.1f,%.1f) "
                           "tgt=0x%lx enemy=0x%lx cache=%zu bones=%d lastOp=%s %s",
                           (unsigned long)(uintptr_t)g_myPC, lastReason, ctrlRot.Pitch, ctrlRot.Yaw,
                           g_camSource, camLoc.X, camLoc.Y, camLoc.Z, camRot.Pitch, camRot.Yaw,
                           (unsigned long)(uintptr_t)g_targetEnemy, enemyRaw, cacheCount,
                           g_bonesEnabled ? 1 : 0, hbOp, hbDetail);

                aimbot_log("AIM: SNAP pc=0x%lx reason=%d bAIM=%d autoFire=%d fireBut=%d EnemyAIM=0x%lx tgt=0x%lx cache=%zu",
                                    (uintptr_t)g_myPC, lastReason, (int)bAIM, (int)bAutoFire, (int)bFireBut,
                                    enemyRaw, (uintptr_t)g_targetEnemy, cacheCount);
                aimbot_log("AIM: SNAP camSrc=%d camRot=(%.1f,%.1f) camLoc=(%.0f,%.0f,%.0f) ctrlRot=(%.1f,%.1f) gameAim(angle=%.1f step=%.1f timer=%.1f t=%.1f sens=%.1f) pitchSign=%+d",
                                    g_camSource, camRot.Pitch, camRot.Yaw, camLoc.X, camLoc.Y, camLoc.Z,
                                    ctrlRot.Pitch, ctrlRot.Yaw, gAngle, gStep, gTimer, gT, gSens, g_pitchSign);
            }
        }
        // ESP label at ~10Hz
        static int espTick = 0;
        if (++espTick >= 10) {
            espTick = 0;
            draw_esp_label();
            // Introspection dumps (targets JSON, sockets, vtables, state)
            Intro::tick(g_screenW, g_screenH);
        }
        usleep(10000);  // 10ms - the SetControlRotation hook does the real work
    }
    return nullptr;
}

// ===== END AIMBOT GLOBALS =====
'''

# NOTE: UE_Games is defined LATER in the file, so the aimbot block must be
# inserted AFTER that definition (it references UE_Games and IGameProfile).
if "AIMBOT GLOBALS" not in s:
    idx = s.find("std::vector<IGameProfile *> UE_Games = {")
    assert idx != -1, "UE_Games definition not found"
    end_idx = s.find("};", idx)
    assert end_idx != -1, "UE_Games end not found"
    insert_at = end_idx + 2
    s = s[:insert_at] + "\n" + aimbot_globals + s[insert_at:]
else:
    print("aimbot globals already present, skipping insertion")

# 3. Modify onload() to also start aimbot thread
# NOTE: patch_toast.py runs BEFORE this script and inserts a
# write_status(...) line into onload(), so the onload body does NOT match the
# pristine upstream text. Anchor on the stable "callMe(false);" call instead.
old_onload = "    callMe(false);"
new_onload = """    callMe(false);
    // Start aimbot thread
    LOGI("AIMBOT: spawning aimbot thread");
    pthread_t aim_tid;
    pthread_create(&aim_tid, nullptr, aimbot_thread, nullptr);
    pthread_detach(aim_tid);
    LOGI("AIMBOT: aimbot thread spawned");"""

assert old_onload in s, "callMe(false) anchor not found"
if "AIMBOT: spawning aimbot thread" not in s:
    s = s.replace(old_onload, new_onload, 1)

open(p, "w").write(s)
print("library.cpp patched with aimbot (vtable scanner + polling + real writes)")
