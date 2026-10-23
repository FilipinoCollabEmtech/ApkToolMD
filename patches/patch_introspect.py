# Comprehensive in-game introspection for the SFG2 (UE4.22) target.
#
# Adds to library.cpp:
#   * a GNames/FName walker (object + class names)
#   * camera basis + world->screen projection
#   * per-target JSON dump  -> /sdcard/aimbot_targets.json
#   * socket names per mesh  -> /sdcard/aimbot_sockets.txt
#   * vtable dumps            -> /sdcard/aimbot_vtables.txt
#   * player / world state    -> /sdcard/aimbot_state.json
#   * aim-function pattern scan -> /sdcard/aimbot_aimfunc.txt
#
# The app (ApkToolMD) also mirrors a compact summary into the floating overlay
# and writes the screen size into the config file so we can project to pixels.

p = "/tmp/uecn/AndUEDumper/src/library.cpp"
s = open(p).read()

block = r'''
// ===== INTROSPECTION MODULE =====
namespace Intro {

// Defined at the bottom; declared here so the aimbot poll loop can call it.
void tick(int screenW, int screenH);

// ---- GNames / FName walker -----------------------------------------------
// GNames (RVA 0x46D2E90) -> FNameEntryArray -> Chunks[] -> FNameEntry*[] -> entry
// FNameEntry: 0x0 ptr, 0x8 int32 Index, 0xC char Name[]
static constexpr int32_t kElementsPerChunk = 16384;
static uintptr_t g_gNamesArray = 0;

static bool init_gnames()
{
    if (g_gNamesArray) return true;
    uintptr_t namesPtr = g_libUE4_base + Offsets::GNames;
    uintptr_t g = 0;
    if (!UEMemory::vm_rpm_ptr((void*)namesPtr, &g, sizeof(g))) return false;
    if (!g) return false;
    g_gNamesArray = g;
    return true;
}

// Returns the FNameEntry* for an id, or null.
static uint8_t* name_entry(int32_t id)
{
    if (id < 0) return nullptr;
    if (!init_gnames()) return nullptr;

    int32_t chunkIndex = id / kElementsPerChunk;
    int32_t within = id % kElementsPerChunk;

    uintptr_t chunkArray = 0;
    if (!UEMemory::vm_rpm_ptr((void*)(g_gNamesArray + (uintptr_t)chunkIndex * sizeof(uintptr_t)), &chunkArray, sizeof(chunkArray))) return nullptr;
    if (!chunkArray) return nullptr;

    uintptr_t entry = 0;
    if (!UEMemory::vm_rpm_ptr((void*)(chunkArray + (uintptr_t)within * sizeof(uintptr_t)), &entry, sizeof(entry))) return nullptr;
    if (!entry) return nullptr;
    return (uint8_t*)entry;
}

// Copies the name of an FName id into buf (truncated).
static bool fname_str(int32_t id, char* buf, int cap)
{
    if (cap <= 0) return false;
    buf[0] = 0;
    uint8_t* e = name_entry(id);
    if (!e) return false;
    char tmp[256];
    if (!UEMemory::vm_rpm_ptr((void*)(e + 0xC), tmp, sizeof(tmp))) return false;
    tmp[sizeof(tmp) - 1] = 0;
    int i = 0;
    for (; tmp[i] && i < cap - 1; i++) buf[i] = tmp[i];
    buf[i] = 0;
    return i > 0;
}

// An FName on an object is {int32 ComparisonIndex; int32 Number}.
static int32_t obj_fname_id(void* obj, uintptr_t offset)
{
    int32_t id = -1;
    if (!obj) return -1;
    UEMemory::vm_rpm_ptr((void*)((uintptr_t)obj + offset), &id, sizeof(id));
    return id;
}

static bool obj_name(void* obj, char* buf, int cap)
{
    // UObject::NamePrivate = 0x18
    return fname_str(obj_fname_id(obj, 0x18), buf, cap);
}

static bool obj_class_name(void* obj, char* buf, int cap)
{
    if (cap <= 0) return false;
    buf[0] = 0;
    uintptr_t cls = 0;
    if (!obj) return false;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)obj + 0x10), &cls, sizeof(cls))) return false;
    if (!cls) return false;
    return obj_name((void*)cls, buf, cap);
}

// ---- camera basis + world->screen projection ------------------------------
struct Basis { FVector fwd, right, up; FVector loc; FRotator rot; bool valid; };

static Basis camera_basis()
{
    Basis b{};
    FCamInfo ci{};
    if (!get_camera_info(ci)) return b;

    b.loc = ci.loc;
    b.rot = ci.rot;

    float p = ci.rot.Pitch * 0.0174532925f;
    float y = ci.rot.Yaw   * 0.0174532925f;
    float r = ci.rot.Roll  * 0.0174532925f;

    float sp = sinf(p), cp = cosf(p);
    float sy = sinf(y), cy = cosf(y);
    float sr = sinf(r), cr = cosf(r);

    // UE: X forward, Y right, Z up
    b.fwd   = FVector{ cp * cy, cp * sy, sp };
    b.right = FVector{ sr * sp * cy - cr * sy, sr * sp * sy + cr * cy, -sr * cp };
    b.up    = FVector{ -(cr * sp * cy + sr * sy), -(cr * sp * sy - sr * cy), cr * cp };
    b.valid = true;
    return b;
}

// Projects a world point to screen pixels.  Returns false if behind the camera.
static bool project_to_screen(const Basis& b, const FVector& world, int screenW, int screenH, float& outX, float& outY, float& outDepth)
{
    if (!b.valid || screenW <= 0 || screenH <= 0) return false;

    FVector d = world - b.loc;
    float x = d.X * b.fwd.X + d.Y * b.fwd.Y + d.Z * b.fwd.Z;    // depth
    float y = d.X * b.right.X + d.Y * b.right.Y + d.Z * b.right.Z;  // right
    float z = d.X * b.up.X + d.Y * b.up.Y + d.Z * b.up.Z;           // up

    outDepth = x;
    if (x <= 1.0f) return false;   // behind / too close

    // UE horizontal FOV default 90 deg -> tan(45) = 1
    float fovScale = 1.0f;
    outX = (screenW * 0.5f) + (y / (x * fovScale)) * (screenW * 0.5f);
    outY = (screenH * 0.5f) - (z / (x * fovScale)) * (screenH * 0.5f);
    return true;
}

// ---- mesh helpers -------------------------------------------------------
static uintptr_t actor_mesh(void* actor)
{
    uintptr_t m = 0;
    if (!actor) return 0;
    UEMemory::vm_rpm_ptr((void*)((uintptr_t)actor + Offsets::AMan_Mesh), &m, sizeof(m));
    return m;
}

static uintptr_t actor_root(void* actor)
{
    uintptr_t r = 0;
    if (!actor) return 0;
    UEMemory::vm_rpm_ptr((void*)((uintptr_t)actor + Offsets::AActor_RootComp), &r, sizeof(r));
    return r;
}

static bool actor_location(void* actor, FVector& out)
{
    // The WORLD position (the root's ComponentToWorld) - the relative
    // location of a root component is always (0,0,0).
    return actor_world_location((uint8_t*)actor, out);
}

// The mesh's local-space bounds (imported) - used for the on-screen box size.
static bool mesh_bounds(uintptr_t mesh, FVector& origin, FVector& extent)
{
    if (!mesh) return false;
    uintptr_t skel = 0;
    if (!UEMemory::vm_rpm_ptr((void*)(mesh + Offsets::USkinnedMesh_SkeletalMesh), &skel, sizeof(skel))) return false;
    if (!skel) return false;
    if (!UEMemory::vm_rpm_ptr((void*)(skel + Offsets::USkeletalMesh_ImportedBounds), &origin, sizeof(origin))) return false;
    if (!UEMemory::vm_rpm_ptr((void*)(skel + Offsets::USkeletalMesh_ImportedBounds + 0xC), &extent, sizeof(extent))) return false;
    return true;
}

// ---- JSON writer ---------------------------------------------------------
static void json_str(FILE* f, const char* s)
{
    fputc('"', f);
    for (const char* p = s; p && *p; p++) {
        if (*p == '"' || *p == '\\') { fputc('\\', f); fputc(*p, f); }
        else if ((unsigned char)*p < 0x20) fputc(' ', f);
        else fputc(*p, f);
    }
    fputc('"', f);
}

// ---- per-target dump -----------------------------------------------------
static void dump_targets(const Basis& basis, int screenW, int screenH)
{
    FILE* f = fopen("/sdcard/aimbot_targets.json", "w");
    if (!f) return;
    if (!g_inGame) { fclose(f); return; }   // no match -> nothing to dump
    refresh_object_array();                 // the engine moves it when it grows

    int myTeam = -1;
    int32_t mt = 0;
    if (g_myPC) UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_PlayerTeam), &mt, sizeof(mt));
    myTeam = mt;

    fputs("{\n  \"targets\": [\n", f);
    bool first = true;
    int emitted = 0;

    // NOTE: the radar anchor ("P x z yaw") used to be written HERE, which
    // made this file invalid JSON, and the app never saw it anyway - it
    // parses the radar anchor out of /sdcard/aimbot_esp_frames.txt.
    // It is written there now (see dump_esp_frame).

    std::vector<GamePlayer> players = collect_game_players();
    if (players.empty()) {
        // fall back to our own object array scan
        std::vector<uint8_t*> actors;
        {
            std::lock_guard<std::mutex> lk(g_enemyMutex);
            actors = g_enemyCache;
        }
        for (uint8_t* a : actors) {
            GamePlayer p;
            p.man = a;
            players.push_back(p);
        }
    }

    // our own pawn - we never draw ourselves
    uintptr_t ownPawn = 0;
    if (g_myPC) {
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::APlayerController_ACKPawn), &ownPawn, sizeof(ownPawn));
        if (!ownPawn)
            UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::APlayerController_Pawn), &ownPawn, sizeof(ownPawn));
    }

    for (size_t pi = 0; pi < players.size(); pi++) {
        GamePlayer& gp = players[pi];
        uint8_t* a = gp.man;
        if (!a) continue;
        if (ownPawn && (uintptr_t)a == ownPawn) continue;   // that is us
        FVector loc{};
        if (!actor_location(a, loc)) continue;
        // The mesh component follows the ragdoll, the capsule stays where
        // the character died - use the mesh so the ESP stays on the body.
        {
            uintptr_t mc = actor_mesh(a);
            FVector ml{};
            if (mc && component_world_location(mc, ml)) loc = ml;
        }

        FVector d = loc - basis.loc;
        float dist = sqrtf(d.X*d.X + d.Y*d.Y + d.Z*d.Z);
        if (dist < 50.0f || dist > 5000.0f) continue;

        char name[128] = {0}, cls[128] = {0};
        obj_name(a, name, sizeof(name));
        obj_class_name(a, cls, sizeof(cls));

        int32_t hp = -1, maxhp = -1, team = -2;
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)a + Offsets::AMan_Health), &hp, sizeof(hp));
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)a + Offsets::AMan_MaxHealth), &maxhp, sizeof(maxhp));
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)a + Offsets::AMan_Team), &team, sizeof(team));

        uintptr_t mesh = actor_mesh(a);
        FVector bOrigin{}, bExtent{};
        mesh_bounds(mesh, bOrigin, bExtent);

        // head point
        FVector head = loc;
        {
            uintptr_t cap = 0;
            float half = 0.0f;
            if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)a + Offsets::AChar_Capsule), &cap, sizeof(cap)) && cap) {
                UEMemory::vm_rpm_ptr((void*)(cap + Offsets::UCapsule_HalfHeight), &half, sizeof(half));
            }
            if (half > 1.0f) head.Z += half * g_headZ;
        }

        float sx = 0, sy = 0, sdepth = 0;
        bool onScreen = project_to_screen(basis, head, screenW, screenH, sx, sy, sdepth);

        if (!first) fputs(",\n", f);
        first = false;
        emitted++;

        fputs("    {", f);
        fputs("\"addr\":", f); fprintf(f, "\"0x%lx\",", (unsigned long)(uintptr_t)a);
        fputs("\"name\":", f); json_str(f, name); fputc(',', f);
        fputs("\"class\":", f); json_str(f, cls); fputc(',', f);
        fprintf(f, "\"health\":%d,\"maxHealth\":%d,\"team\":%d,", hp, maxhp, team);
        fprintf(f, "\"distCm\":%.1f,\"distM\":%.2f,", dist, dist / 100.0f);
        fprintf(f, "\"world\":[%.1f,%.1f,%.1f],", loc.X, loc.Y, loc.Z);
        fprintf(f, "\"head\":[%.1f,%.1f,%.1f],", head.X, head.Y, head.Z);
        fprintf(f, "\"meshExtent\":[%.1f,%.1f,%.1f],", bExtent.X, bExtent.Y, bExtent.Z);
        fprintf(f, "\"onScreen\":%s,", onScreen ? "true" : "false");
        fprintf(f, "\"screen\":[%.1f,%.1f]", sx, sy);
        fputc('}', f);

        if (emitted >= 64) break;
    }
    fputs("\n  ]\n}\n", f);
    fclose(f);
}

// ---- socket names --------------------------------------------------------
// USkeletalMesh::Sockets is a TArray<USkeletalMeshSocket*> (0x330).
// USkeletalMeshSocket: SocketName FName @0x28, BoneName FName @0x30.
static void dump_sockets()
{
    FILE* f = fopen("/sdcard/aimbot_sockets.txt", "w");
    if (!f) return;

    std::vector<uint8_t*> actors;
    {
        std::lock_guard<std::mutex> lk(g_enemyMutex);
        actors = g_enemyCache;
    }

    int written = 0;
    for (uint8_t* a : actors) {
        if (!a) continue;
        uintptr_t mesh = actor_mesh(a);
        if (!mesh) continue;
        uintptr_t skel = 0;
        if (!UEMemory::vm_rpm_ptr((void*)(mesh + Offsets::USkinnedMesh_SkeletalMesh), &skel, sizeof(skel))) continue;
        if (!skel) continue;

        uintptr_t arrData = 0;
        int32_t arrCount = 0;
        if (!UEMemory::vm_rpm_ptr((void*)(skel + Offsets::USkeletalMesh_Sockets), &arrData, sizeof(arrData))) continue;
        if (!UEMemory::vm_rpm_ptr((void*)(skel + Offsets::USkeletalMesh_Sockets + 8), &arrCount, sizeof(arrCount))) continue;
        if (!arrData || arrCount <= 0 || arrCount > 256) continue;

        char cls[128] = {0};
        obj_class_name(a, cls, sizeof(cls));
        fprintf(f, "=== %s (0x%lx) sockets=%d ===\n", cls, (unsigned long)(uintptr_t)a, arrCount);

        for (int32_t i = 0; i < arrCount; i++) {
            uintptr_t sock = 0;
            if (!UEMemory::vm_rpm_ptr((void*)(arrData + (uintptr_t)i * sizeof(uintptr_t)), &sock, sizeof(sock))) continue;
            if (!sock) continue;
            char sname[128] = {0}, bname[128] = {0};
            fname_str(obj_fname_id((void*)sock, 0x28), sname, sizeof(sname));
            fname_str(obj_fname_id((void*)sock, 0x30), bname, sizeof(bname));
            fprintf(f, "  socket[%d] '%s' bone='%s'\n", i, sname, bname);
        }
        written++;
        if (written >= 8) break;
    }
    fclose(f);
}

// ---- vtable dumps --------------------------------------------------------
static void dump_vtable(void* obj, const char* label, FILE* f)
{
    if (!obj || !f) return;
    // The vtable pointer is read through the safe reader - a direct
    // dereference of a stale object pointer crashes the game.
    uintptr_t vft = 0;
    if (!UEMemory::vm_rpm_ptr((void*)obj, &vft, sizeof(vft))) return;
    if (!vft) return;
    fprintf(f, "=== %s vtable (obj=0x%lx) ===\n", label, (unsigned long)(uintptr_t)obj);
    for (int i = 0; i < 220; i++) {
        uintptr_t fn = 0;
        if (!UEMemory::vm_rpm_ptr((void*)(vft + (uintptr_t)i * sizeof(uintptr_t)), &fn, sizeof(fn))) break;
        if (!fn) break;
        fprintf(f, "  slot %3d  rva 0x%08lx  addr 0x%lx\n", i, (unsigned long)(fn - g_libUE4_base), (unsigned long)fn);
    }
}

static void dump_vtables()
{
    FILE* f = fopen("/sdcard/aimbot_vtables.txt", "w");
    if (!f) return;
    if (g_myPC) dump_vtable(g_myPC, "AMyPlayerController", f);

    // A representative enemy character
    std::vector<uint8_t*> actors;
    {
        std::lock_guard<std::mutex> lk(g_enemyMutex);
        actors = g_enemyCache;
    }
    for (uint8_t* a : actors) {
        if (a) { dump_vtable(a, "AMan", f); break; }
    }
    fclose(f);
}

// ---- player + world state ------------------------------------------------
static void dump_state()
{
    FILE* f = fopen("/sdcard/aimbot_state.json", "w");
    if (!f) return;

    fputs("{\n", f);
    if (g_myPC) {
        int32_t hp = -1, maxhp = -1, team = -1;
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_PlayerTeam), &team, sizeof(team));

        uintptr_t pawn = 0;
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::APlayerController_ACKPawn), &pawn, sizeof(pawn));
        if (!pawn) UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::APlayerController_Pawn), &pawn, sizeof(pawn));

        if (pawn) {
            UEMemory::vm_rpm_ptr((void*)((uintptr_t)pawn + Offsets::AMan_Health), &hp, sizeof(hp));
            UEMemory::vm_rpm_ptr((void*)((uintptr_t)pawn + Offsets::AMan_MaxHealth), &maxhp, sizeof(maxhp));
        }
        uintptr_t weapon = 0;
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)pawn + 0x8C0), &weapon, sizeof(weapon));
        char wname[128] = {0}, wcls[128] = {0};
        if (weapon) { obj_name((void*)weapon, wname, sizeof(wname)); obj_class_name((void*)weapon, wcls, sizeof(wcls)); }

        char pcname[128] = {0}, pccls[128] = {0};
        obj_name(g_myPC, pcname, sizeof(pcname));
        obj_class_name(g_myPC, pccls, sizeof(pccls));

        fputs("  \"player\": {", f);
        fputs("\"name\":", f); json_str(f, pcname); fputc(',', f);
        fputs("\"class\":", f); json_str(f, pccls); fputc(',', f);
        fprintf(f, "\"health\":%d,\"maxHealth\":%d,\"team\":%d,", hp, maxhp, team);
        fputs("\"weapon\":", f); json_str(f, wname); fputc(',', f);
        fputs("\"weaponClass\":", f); json_str(f, wcls); fputc(',', f);
        fprintf(f, "\"pawn\":\"0x%lx\"", (unsigned long)pawn);
        fputs("},\n", f);
    }

    // World / match state
    {
        uintptr_t world = 0;
        UEMemory::vm_rpm_ptr((void*)(g_libUE4_base + Offsets::UWorld), &world, sizeof(world));
        fputs("  \"world\": {", f);
        fprintf(f, "\"ptr\":\"0x%lx\"", (unsigned long)world);
        fputs("},\n", f);
    }
    fputs("  \"note\": \"extend with AMyGameState fields once probed\"\n", f);
    fputs("}\n", f);
    fclose(f);
}

// ---- aim function pattern scan ------------------------------------------
// Scans the executable segments of libUE4.so for instructions that store to
// [base + 0x740] (the EnemyAIM field) - that is the game's own aim logic.
static void scan_aim_functions()
{
    // This scans every executable segment of libUE4.so (40+ MB of code) and
    // GetUnrealELF() has a shared static - so it runs ONCE, not every 3 s.
    static bool done = false;
    if (done) return;
    done = true;

    FILE* f = fopen("/sdcard/aimbot_aimfunc.txt", "w");
    if (!f) return;

    auto elf = UE_Games.empty() ? ElfScanner{} : UE_Games[0]->GetUnrealELF();
    if (!elf.isValid()) { fclose(f); return; }

    fprintf(f, "scan for stores to [this+0x740] (EnemyAIM)\n");
    int hits = 0;
    for (auto& seg : elf.segments()) {
        if (!seg.executable) continue;
        uintptr_t start = seg.startAddress;
        uintptr_t len = seg.length;
        if (!start || !len || len > 0x40000000) continue;

        std::vector<uint32_t> insns(len / 4);
        if (!UEMemory::vm_rpm_ptr((void*)start, insns.data(), insns.size() * 4)) continue;

        for (size_t i = 0; i < insns.size(); i++) {
            uint32_t ins = insns[i];
            // 64-bit STR/STRB unsigned imm12: 1111 1001 00 imm12 10 Rn Rt
            // scaled imm12 * 8 for 64-bit stores -> 0x740 / 8 = 0xE8
            if ((ins & 0xFFC00000) == 0xF9000000) {
                uint32_t imm12 = (ins >> 10) & 0xFFF;
                if (imm12 == 0xE8) {
                    fprintf(f, "  STR x,[x?,#0x740] at rva 0x%lx\n", (unsigned long)(start + i * 4 - g_libUE4_base));
                    if (++hits > 200) break;
                }
            }
        }
    }
    fprintf(f, "total hits: %d\n", hits);
    fclose(f);
}

// ---- real bone data (the open source way) ---------------------------------
//
// The game's Cached* bone transform arrays are EMPTY (data=0, count=0), so
// the bones are read the way every UE4 external does it - through the
// engine's own UFunctions:
//
//   USkinnedMeshComponent::GetNumBones()      -> int32   (params 0x4)
//   USkinnedMeshComponent::GetBoneName(int32) -> FName   (params 0xC:
//                                   BoneIndex @0x0, ReturnValue @0x4)
//   USceneComponent::GetSocketLocation(FName) -> FVector (params 0x14:
//                                   InSocketName @0x0, ReturnValue @0x8)
//
// GetSocketLocation also resolves BONE names (UE falls back to the bone
// transform when the name is not a socket), so one call per bone is enough.
//
// IMPORTANT: bone data is only valid on the GAME THREAD (after the
// animation ticked), so the sampling happens in the ProcessEvent hook.
static uint8_t* g_getNumBonesFunc = nullptr;
static uint8_t* g_getBoneNameFunc = nullptr;
static uint8_t* g_getSocketLocFunc = nullptr;

static const int kNumBoneSlots = 16;
struct BoneSlot { const char* label; const char* patterns[4]; };
// This game's rig uses the classic 3ds Max "Bip01" naming (the UE4 mannequin
// names are kept as a fallback):
//   Bip01-Head, Bip01-Neck, Bip01-Spine1/2/3, Bip01-Pelvis,
//   Bip01-L/R-Clavicle, Bip01-L/R-UpperArm, Bip01-L/R-Forearm,
//   Bip01-L/R-Hand, Bip01-L/R-Thigh, Bip01-L/R-Calf, Bip01-L/R-Foot
static const BoneSlot kBoneSlots[kNumBoneSlots] = {
    { "head",     { "bip01-head", "head", "bip01_head", "head_end" } },
    { "neck",     { "bip01-neck", "neck", "neck_01", "bip01-neck_sub" } },
    { "chest",    { "bip01-spine2", "bip01-spine1", "spine_02", "chest" } },
    { "pelvis",   { "bip01-pelvis", "pelvis", "hips", "root" } },
    { "clav_l",   { "bip01-l-clavicle", "clavicle_l", "shoulder_l", "left_clavicle" } },
    { "clav_r",   { "bip01-r-clavicle", "clavicle_r", "shoulder_r", "right_clavicle" } },
    { "uparm_l",  { "bip01-l-upperarm", "upperarm_l", "leftupperarm", "arm_l" } },
    { "uparm_r",  { "bip01-r-upperarm", "upperarm_r", "rightupperarm", "arm_r" } },
    { "hand_l",   { "bip01-l-hand", "hand_l", "lefthand", "hand_l" } },
    { "hand_r",   { "bip01-r-hand", "hand_r", "righthand", "hand_r" } },
    { "thigh_l",  { "bip01-l-thigh", "thigh_l", "leftthigh", "thigh_l" } },
    { "thigh_r",  { "bip01-r-thigh", "thigh_r", "rightthigh", "thigh_r" } },
    { "calf_l",   { "bip01-l-calf", "calf_l", "leftcalf", "calf_l" } },
    { "calf_r",   { "bip01-r-calf", "calf_r", "rightcalf", "calf_r" } },
    { "foot_l",   { "bip01-l-foot", "foot_l", "leftfoot", "ball_l" } },
    { "foot_r",   { "bip01-r-foot", "foot_r", "rightfoot", "ball_r" } },
};

// The resolved FName (index + number) of every slot, cached per mesh.
// There are several meshes in a match, so we keep a few entries - with a
// single entry the map was re-resolved for every actor of every sample
// (40+ engine calls each time -> the game froze and crashed).
struct BoneMeshMap {
    uintptr_t skelMesh = 0;
    int32_t idx[kNumBoneSlots] = {0};
    int32_t num[kNumBoneSlots] = {0};
    bool    ok[kNumBoneSlots] = {false};
    int     numBones = 0;
    bool    valid = false;
};
static const int kNumBoneMaps = 8;
static BoneMeshMap g_boneMaps[kNumBoneMaps];
static std::mutex g_boneMutex;

// The last sampled world positions, per actor.
struct BoneSample {
    uint8_t* actor = nullptr;
    FVector  pts[kNumBoneSlots];
    bool     valid = false;
};
static BoneSample g_boneSamples[32];
static std::mutex g_boneSamplesMutex;

// Look the bone UFunctions up.  MUST run on the aim thread: FindObject()
// walks the whole object array and takes seconds.
void find_bone_functions()
{
    if (g_getNumBonesFunc && g_getBoneNameFunc && g_getSocketLocFunc) return;
    if (!UEWrappers::GetObjects()) return;

    if (!g_getNumBonesFunc) {
        UE_UObject o = UEWrappers::GetObjects()->FindObject("Function Engine.SkinnedMeshComponent.GetNumBones");
        if (o) g_getNumBonesFunc = o.GetAddress();
    }
    if (!g_getBoneNameFunc) {
        UE_UObject o = UEWrappers::GetObjects()->FindObject("Function Engine.SkinnedMeshComponent.GetBoneName");
        if (o) g_getBoneNameFunc = o.GetAddress();
    }
    if (!g_getSocketLocFunc) {
        UE_UObject o = UEWrappers::GetObjects()->FindObject("Function Engine.SceneComponent.GetSocketLocation");
        if (o) g_getSocketLocFunc = o.GetAddress();
    }
    static bool logged = false;
    if (!logged && g_getNumBonesFunc && g_getBoneNameFunc && g_getSocketLocFunc) {
        logged = true;
        aimbot_write_trace("AIM: bone funcs NUMBONES=%p BONENAME=%p SOCKETLOC=%p",
                            g_getNumBonesFunc, g_getBoneNameFunc, g_getSocketLocFunc);
    }
}

static bool call_get_num_bones(uint8_t* comp, int32_t& out)
{
    if (!g_getNumBonesFunc) return false;
    uint8_t buf[16];
    memset(buf, 0, sizeof(buf));
    call_engine_function(comp, g_getNumBonesFunc, buf);
    memcpy(&out, buf, 4);
    return true;
}

static bool call_get_bone_name(uint8_t* comp, int32_t index, int32_t& outIdx, int32_t& outNum)
{
    if (!g_getBoneNameFunc) return false;
    uint8_t buf[16];
    memset(buf, 0, sizeof(buf));
    int32_t idx = index;
    memcpy(buf, &idx, 4);
    call_engine_function(comp, g_getBoneNameFunc, buf);
    memcpy(&outIdx, buf + 4, 4);
    memcpy(&outNum, buf + 8, 4);
    return true;
}

static bool call_get_socket_location(uint8_t* comp, int32_t nameIdx, int32_t nameNum, FVector& out)
{
    if (!g_getSocketLocFunc) return false;
    uint8_t buf[32];
    memset(buf, 0, sizeof(buf));
    int32_t n[2] = { nameIdx, nameNum };
    memcpy(buf, n, 8);
    call_engine_function(comp, g_getSocketLocFunc, buf);
    memcpy(&out, buf + 8, 12);
    return true;
}

static bool name_matches(const std::string& bone, const char* pattern)
{
    // exact (case insensitive) first, then "contains"
    std::string a = bone, b = pattern;
    for (size_t i = 0; i < a.size(); i++) a[i] = (char)tolower((unsigned char)a[i]);
    for (size_t i = 0; i < b.size(); i++) b[i] = (char)tolower((unsigned char)b[i]);
    if (a == b) return true;
    if (a.find(b) != std::string::npos) {
        // a "contains" match must not swallow the other side (hand vs hand_l)
        if (b.size() >= 5 && a.size() > b.size() + 1) {
            // e.g. "lefthand" must not match "hand"
            if (b == "hand" || b == "foot" || b == "arm" || b == "leg") return false;
        }
        return true;
    }
    return false;
}

// Resolve the slot -> FName map for a mesh (once per mesh).
static bool resolve_bone_map(uint8_t* comp, BoneMeshMap& out)
{
    out = BoneMeshMap();
    uintptr_t skelMesh = 0;
    if (!comp) return false;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)comp + Offsets::USkinnedMesh_SkeletalMesh), &skelMesh, sizeof(skelMesh))) return false;
    if (!skelMesh) return false;

    {
        std::lock_guard<std::mutex> lk(g_boneMutex);
        for (int i = 0; i < kNumBoneMaps; i++) {
            if (g_boneMaps[i].valid && g_boneMaps[i].skelMesh == skelMesh) {
                out = g_boneMaps[i];
                return true;
            }
        }
    }

    BoneMeshMap m;
    m.skelMesh = skelMesh;
    call_get_num_bones(comp, m.numBones);
    if (m.numBones <= 0 || m.numBones > 512) {
        static int nbFail = 0;
        if (nbFail++ < 3) {
            aimbot_log("AIM: GetNumBones returned %d for comp=0x%lx", m.numBones, (unsigned long)comp);
        }
        std::lock_guard<std::mutex> lk(g_boneMutex);
        out = m;
        return false;
    }

    // Enumerate the bone names - ONE batch for all of them (an un-patch /
    // re-patch per name would flush the instruction cache hundreds of times
    // and freeze the game).
    {
        static uint8_t pbuf[256][16];
        static void*   objs[256];
        static void*   fns[256];
        static void*   pptr[256];
        int n = 0;
        for (int i = 0; i < m.numBones && i < 256; i++) {
            uint8_t* b = pbuf[n];
            memset(b, 0, 16);
            int32_t idx = i;
            memcpy(b, &idx, 4);
            objs[n] = comp;
            fns[n] = g_getBoneNameFunc;
            pptr[n] = b;
            n++;
        }
        set_last_op("bone-names");
        call_engine_functions_batch(objs, fns, pptr, n);

        for (int i = 0; i < n; i++) {
            int32_t idx = -1, num = 0;
            memcpy(&idx, pbuf[i] + 4, 4);
            memcpy(&num, pbuf[i] + 8, 4);
            if (idx < 0) continue;
            char nm[96] = {0};
            if (!fname_str(idx, nm, sizeof(nm))) continue;
            std::string bn = nm;
            // log the names once so the slot patterns can be fixed
            if (i < 64) {
                aimbot_log("AIM: bone %2d = '%s' (idx=%d)", i, nm, idx);
            }
            for (int s = 0; s < kNumBoneSlots; s++) {
                if (m.ok[s]) continue;
                for (int p = 0; p < 3; p++) {
                    if (name_matches(bn, kBoneSlots[s].patterns[p])) {
                        m.idx[s] = idx;
                        m.num[s] = num;
                        m.ok[s] = true;
                        break;
                    }
                }
            }
        }
    }

    m.valid = true;
    {
        std::lock_guard<std::mutex> lk(g_boneMutex);
        int slot = -1;
        for (int i = 0; i < kNumBoneMaps; i++) {
            if (!g_boneMaps[i].valid) { slot = i; break; }
        }
        if (slot < 0) slot = 0;   // reuse the first entry
        g_boneMaps[slot] = m;
    }
    int found = 0;
    for (int s = 0; s < kNumBoneSlots; s++) if (m.ok[s]) found++;
    aimbot_log("AIM: bone map for mesh 0x%lx: %d bones, %d/%d slots resolved",
               skelMesh, m.numBones, found, kNumBoneSlots);
    out = m;
    return found > 0;
}

// Sample the real skeleton of the nearest enemies.  GAME THREAD ONLY.
//
// PERFORMANCE: all the engine calls of one sample are made in ONE batch
// (a single un-patch / re-patch of the ProcessEvent entry).  Doing them one
// by one cost ~900 instruction cache flushes per sample and dropped the game
// to 3 FPS.
void sample_bones()
{
    if (!g_bonesEnabled) return;      // safety switch (bones=0 in the config)
    if (!g_inGame) return;            // only in a match
    refresh_object_array();           // the engine moves it when it grows
    // NOTE: the UFunction lookup is NOT done here - FindObject() walks all
    // 58k objects and takes seconds, which froze the game (the ANR watchdog
    // killed it).  It runs on the aim thread (see engine_func_thread).
    if (!g_getSocketLocFunc || !g_getBoneNameFunc) return;

    // Only sample in a normal play state.  In the menu / spawn / death cam
    // the camera has no position and the components may be half initialised -
    // calling into the engine there crashed the game.
    {
        FCamInfo ci{};
        if (!get_camera_info(ci)) return;
        if (!camera_is_plausible(ci)) return;
    }

    // The GAME's player list (the same data the aimbot trusts)
    std::vector<GamePlayer> players = collect_game_players();
    if (players.empty()) {
        std::lock_guard<std::mutex> lk(g_enemyMutex);
        players.clear();
        for (uint8_t* a : g_enemyCache) { GamePlayer p; p.man = a; players.push_back(p); }
    }
    if (players.empty()) return;

    // The nearest few actors only - the bones of a guy 200 m away are useless.
    const int kMaxActors = 6;
    struct Cand { uint8_t* a; float dist; };
    Cand cands[16];
    int nc = 0;
    Basis b = camera_basis();
    for (size_t pi = 0; pi < players.size(); pi++) {
        uint8_t* a = players[pi].man;
        if (!a || nc >= 16) continue;
        FVector loc{};
        if (!actor_location(a, loc)) continue;
        float d = 0;
        if (b.valid) {
            FVector dd = loc - b.loc;
            d = sqrtf(dd.X * dd.X + dd.Y * dd.Y + dd.Z * dd.Z);
        }
        cands[nc].a = a;
        cands[nc].dist = d;
        nc++;
    }
    // nearest first
    for (int i = 0; i < nc; i++) {
        for (int j = i + 1; j < nc; j++) {
            if (cands[j].dist < cands[i].dist) { Cand t = cands[i]; cands[i] = cands[j]; cands[j] = t; }
        }
    }

    static uint8_t g_boneParams[192][32];
    static void*    g_boneObjs[192];
    static void*    g_boneFuncs[192];
    static void*    g_boneParamPtr[192];

    BoneSample out[8];
    int outCount = 0;
    int nCalls = 0;

    // Only sample actors that have been around for a while: during a level
    // load the actors are created and destroyed, and calling into the engine
    // with a half initialised component crashed the game.
    static uint8_t* seenActors[64];
    static uint64_t seenAt[64];
    static int seenCount = 0;
    uint64_t nowMs = now_us();

    for (int ci = 0; ci < nc && outCount < kMaxActors; ci++) {
        uint8_t* a = cands[ci].a;
        if (cands[ci].dist > 6000.0f) break;      // 60 m
        // NOTE: no on-screen filter here - the projection depends on the
        // screen size the app publishes, and when that is wrong (portrait
        // while the game runs in landscape) every actor would be skipped and
        // we would lose the real head bone.
        uintptr_t comp = actor_mesh(a);
        if (!comp) continue;
        // dead bodies: no bones to sample (and no engine calls on a corpse)
        {
            int32_t hp = -1, maxhp = -1;
            UEMemory::vm_rpm_ptr((void*)((uintptr_t)a + Offsets::AMan_Health), &hp, sizeof(hp));
            UEMemory::vm_rpm_ptr((void*)((uintptr_t)a + Offsets::AMan_MaxHealth), &maxhp, sizeof(maxhp));
            if (is_man_derived(a) && maxhp > 0 && hp <= 0) continue;
        }
        uintptr_t skel = 0;
        if (!UEMemory::vm_rpm_ptr((void*)(comp + Offsets::USkinnedMesh_SkeletalMesh), &skel, sizeof(skel)) || !skel) continue;

        // the actor must not be on its way out
        {
            uint8_t flags = 0;
            if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)a + Offsets::AActor_BeingDestroyedFlags), &flags, sizeof(flags)) &&
                (flags & 0x4)) continue;
        }

        // stability window: the actor has to exist for 1.5 s
        {
            int found = -1;
            for (int i = 0; i < seenCount; i++) if (seenActors[i] == a) { found = i; break; }
            if (found < 0) {
                if (seenCount < 64) { seenActors[seenCount] = a; seenAt[seenCount] = nowMs; seenCount++; }
                continue;                       // too new -> skip this round
            }
            if (nowMs - seenAt[found] < 1500000) continue;   // 1.5 s
        }

        uint8_t* compPtr = (uint8_t*)comp;

        BoneMeshMap mapCopy;
        set_last_op("bone-map");
        if (!resolve_bone_map(compPtr, mapCopy)) {
            static int mapFail = 0;
            if (mapFail++ < 3) {
                aimbot_log("AIM: bone map failed for comp=0x%lx skel=0x%lx (numBones=%d)",
                           (unsigned long)comp, (unsigned long)skel, mapCopy.numBones);
            }
            continue;
        }

        BoneSample s;
        s.actor = a;
        s.valid = true;
        int firstCall = nCalls;
        for (int i = 0; i < kNumBoneSlots; i++) {
            s.pts[i] = FVector{};
            if (!mapCopy.ok[i]) continue;
            if (nCalls >= 192) break;
            uint8_t* buf = g_boneParams[nCalls];
            memset(buf, 0, 32);
            int32_t n[2] = { mapCopy.idx[i], mapCopy.num[i] };
            memcpy(buf, n, 8);
            g_boneObjs[nCalls] = compPtr;
            g_boneFuncs[nCalls] = g_getSocketLocFunc;
            g_boneParamPtr[nCalls] = buf;
            nCalls++;
        }
        out[outCount++] = s;
        (void)firstCall;
    }

    if (nCalls == 0) return;

    // ONE un-patch / re-patch for the whole sample.
    set_last_op("bone-sockets");
    call_engine_functions_batch(g_boneObjs, g_boneFuncs, g_boneParamPtr, nCalls);
    set_last_op("idle");

    // read the results back
    int call = 0;
    for (int o = 0; o < outCount; o++) {
        uintptr_t comp = actor_mesh(out[o].actor);
        if (!comp) continue;
        BoneMeshMap mapCopy;
        if (!resolve_bone_map((uint8_t*)comp, mapCopy)) continue;
        for (int i = 0; i < kNumBoneSlots; i++) {
            if (!mapCopy.ok[i]) continue;
            if (call >= nCalls) break;
            FVector p{};
            memcpy(&p, g_boneParams[call] + 8, 12);
            call++;
            if (!std::isfinite(p.X) || !std::isfinite(p.Y) || !std::isfinite(p.Z)) continue;
            out[o].pts[i] = p;
        }
    }

    std::lock_guard<std::mutex> lk(g_boneSamplesMutex);
    int n = 0;
    for (int i = 0; i < outCount && n < 32; i++) g_boneSamples[n++] = out[i];
    for (int i = n; i < 32; i++) { g_boneSamples[i].actor = nullptr; g_boneSamples[i].valid = false; }
}

// The sampled bone positions of an actor (or null).
static const BoneSample* find_bone_sample(uint8_t* actor)
{
    std::lock_guard<std::mutex> lk(g_boneSamplesMutex);
    for (int i = 0; i < 32; i++) {
        if (g_boneSamples[i].valid && g_boneSamples[i].actor == actor) return &g_boneSamples[i];
    }
    return nullptr;
}

// The REAL head bone position of an actor (world space) - much better than
// the capsule estimate.  False when we do not have it.
bool head_bone_position(uint8_t* actor, FVector& out)
{
    const BoneSample* s = find_bone_sample(actor);
    if (!s) return false;
    const FVector& h = s->pts[0];   // slot 0 = head
    if (!std::isfinite(h.X) || !std::isfinite(h.Y) || !std::isfinite(h.Z)) return false;
    if (h.X == 0.0f && h.Y == 0.0f && h.Z == 0.0f) return false;
    out = h;
    return true;
}

// ---- ESP frame (for the app's floating overlay) ----------------------------
//
// One line per target, space separated, the NAME is last (it may contain
// spaces):
//   addr hp maxhp distM team onScreen npoints x1 y1 x2 y2 ... name
//
// The points are the projected body points used by the overlay:
//   0 head  1 neck  2 chest  3 pelvis  4 lfoot  5 rfoot
//   6 lhand 7 rhand 8 box-left 9 box-right
//
// The bone transform arrays of this game are EMPTY, so the skeleton is built
// from the capsule (head at headZ * half height, feet at the capsule bottom).
static int ext_flag_int(const char* key, int def)
{
    FILE* fp = fopen("/sdcard/ApkToolMD/external_flags.txt", "r");
    if (!fp) return def;
    char line[128];
    int val = def;
    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, key, strlen(key)) == 0 && line[strlen(key)] == '=') {
            val = atoi(line + strlen(key) + 1);
            break;
        }
    }
    fclose(fp);
    return val;
}

static void dump_esp_frame(const Basis& basis, int screenW, int screenH)
{
    FILE* f = fopen("/sdcard/aimbot_esp_frames.txt", "w");
    if (!f) return;
    if (!g_inGame) { fclose(f); return; }   // no match -> nothing to draw
    refresh_object_array();                 // the engine moves it when it grows

    const int   kMaxDistM  = ext_flag_int("espmaxdist", 120);
    const int   kOnlyEnemy = ext_flag_int("esponly", 1);
    const float kMinDistCm = 50.0f;
    const float kMaxDistCm = (float)kMaxDistM * 100.0f;

    int myTeam = 0;
    if (g_myPC) {
        int32_t mt = 0;
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::MyPC_PlayerTeam), &mt, sizeof(mt));
        myTeam = mt;
    }

    // The radar anchor: the player's own position (x, z) and yaw.  The app
    // (EspOverlayView) looks for this line in THIS file - without it the
    // radar never draws.  (It was written into aimbot_targets.json, where
    // it also broke the JSON.)
    {
        FVector eye{};
        if (local_eye_position(eye)) {
            float yaw = 0.0f;
            if (basis.valid) yaw = basis.rot.Yaw;
            fprintf(f, "P %.1f %.1f %.1f\n", eye.X, eye.Z, yaw);
        }
    }

    // The GAME's own player list (the same data the aimbot trusts)
    std::vector<GamePlayer> players = collect_game_players();
    if (players.empty()) {
        std::vector<uint8_t*> actors;
        {
            std::lock_guard<std::mutex> lk(g_enemyMutex);
            actors = g_enemyCache;
        }
        for (uint8_t* a : actors) {
            GamePlayer p;
            p.man = a;
            players.push_back(p);
        }
    }

    int skipLoc = 0, skipDist = 0, skipTeam = 0, skipDead = 0, skipUnnamed = 0, drawn = 0;
    for (size_t pi = 0; pi < players.size(); pi++) {
        GamePlayer& gp = players[pi];
        uint8_t* a = gp.man;
        if (!a) continue;
        FVector loc{};
        if (!actor_location(a, loc)) { skipLoc++; continue; }
        // The mesh component follows the ragdoll while the capsule stays
        // where the character died - use the mesh so the ESP stays on the
        // body.  (Only if it is sane: some classes have no component at
        // that offset and would give a garbage position.)
        {
            uintptr_t mc = actor_mesh(a);
            FVector ml{};
            if (mc && component_world_location(mc, ml)) {
                float dx = ml.X - basis.loc.X, dy = ml.Y - basis.loc.Y, dz = ml.Z - basis.loc.Z;
                float md = sqrtf(dx * dx + dy * dy + dz * dz);
                if (md < 300000.0f) loc = ml;
            }
        }

        FVector d = loc - basis.loc;
        float dist = sqrtf(d.X*d.X + d.Y*d.Y + d.Z*d.Z);
        if (dist < kMinDistCm || dist > kMaxDistCm) { skipDist++; continue; }
        drawn++;

        int32_t hp = -1, maxhp = -1, team = gp.team;
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)a + Offsets::AMan_Health), &hp, sizeof(hp));
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)a + Offsets::AMan_MaxHealth), &maxhp, sizeof(maxhp));
        if (team < 0) {
            UEMemory::vm_rpm_ptr((void*)((uintptr_t)a + Offsets::AMan_Team), &team, sizeof(team));
        }
        // Dead bodies are not drawn (the health field only exists on the Man
        // derived classes - on the others the read is garbage).
        if (is_man_derived(a) && maxhp > 0 && hp <= 0) { skipDead++; continue; }

        if (kOnlyEnemy && team == myTeam && team >= 0) { skipTeam++; continue; }   // never show our own squad
        // NOTE: dead bodies ARE drawn - the box/skeleton has to stay attached
        // to the corpse.  (The "fake ESP" was the class default objects, and
        // those are filtered out in the cache.)

        // capsule size + the actor's yaw (for the lateral offsets)
        uintptr_t cap = 0;
        float half = 90.0f, radius = 40.0f;
        if (UEMemory::vm_rpm_ptr((void*)((uintptr_t)a + Offsets::AChar_Capsule), &cap, sizeof(cap)) && cap) {
            UEMemory::vm_rpm_ptr((void*)(cap + Offsets::UCapsule_HalfHeight), &half, sizeof(half));
            UEMemory::vm_rpm_ptr((void*)(cap + Offsets::UCapsule_Radius), &radius, sizeof(radius));
        }
        if (half <= 1.0f) half = 90.0f;

        float yaw = 0.0f;
        {
            uintptr_t root = actor_root(a);
            if (root) {
                FRotator rr{};
                UEMemory::vm_rpm_ptr((void*)(root + Offsets::USceneComp_RelRot), &rr, sizeof(rr));
                yaw = rr.Yaw * 0.0174532925f;
            }
        }
        float ax = cosf(yaw), ay = sinf(yaw);      // the actor's right vector

        // body points in world space
        FVector pHead{ loc.X, loc.Y, loc.Z + half * g_headZ };
        FVector pNeck{ loc.X, loc.Y, loc.Z + half * 0.62f };
        FVector pChest{ loc.X, loc.Y, loc.Z + half * 0.30f };
        FVector pPelvis{ loc.X, loc.Y, loc.Z };
        FVector pFootL{ loc.X - ax * radius * 0.55f, loc.Y - ay * radius * 0.55f, loc.Z - half };
        FVector pFootR{ loc.X + ax * radius * 0.55f, loc.Y + ay * radius * 0.55f, loc.Z - half };
        FVector pHandL{ loc.X - ax * radius * 0.75f, loc.Y - ay * radius * 0.75f, loc.Z + half * 0.25f };
        FVector pHandR{ loc.X + ax * radius * 0.75f, loc.Y + ay * radius * 0.75f, loc.Z + half * 0.25f };
        FVector pBoxL{ pHead.X - ax * radius, pHead.Y - ay * radius, pHead.Z };
        FVector pBoxR{ pHead.X + ax * radius, pHead.Y + ay * radius, pHead.Z };

        // The real skeleton when the engine gave us the bones, otherwise the
        // capsule based fallback.
        //
        // IMPORTANT: the app maps the points by their POSITION in the line
        // (0 = head, 1 = neck, ...), so the pairs must be contiguous.  A bone
        // that failed to project used to be skipped and every following bone
        // was shifted one slot to the left.
        float sx[16], sy[16];
        int npts = 0;
        bool onScreen = false;
        const BoneSample* bs = find_bone_sample(a);
        bool realBones = false;
        if (bs) {
            // ALL the bones have to project - if one is behind the camera we
            // fall back to the capsule skeleton instead of shifting them.
            bool allOk = true;
            for (int i = 0; i < kNumBoneSlots; i++) {
                float d = 0;
                if (!project_to_screen(basis, bs->pts[i], screenW, screenH, sx[i], sy[i], d)) { allOk = false; break; }
            }
            realBones = allOk;
        }

        if (realBones) {
            npts = kNumBoneSlots;
        } else {
            const FVector* pts[10] = { &pHead, &pNeck, &pChest, &pPelvis, &pFootL,
                                       &pFootR, &pHandL, &pHandR, &pBoxL, &pBoxR };
            // A contiguous prefix only (stop at the first point that does not
            // project) so the app's point mapping stays correct.
            for (int i = 0; i < 10; i++) {
                float depth = 0;
                if (!project_to_screen(basis, *pts[i], screenW, screenH, sx[i], sy[i], depth)) break;
                npts++;
            }
        }
        if (npts > 0) {
            onScreen = (sx[0] > -screenW && sx[0] < screenW * 2 && sy[0] > -screenH && sy[0] < screenH * 2);
        }

        char name[96] = {0};
        if (!gp.name.empty()) {
            snprintf(name, sizeof(name), "%s", gp.name.c_str());
        } else {
            obj_name(a, name, sizeof(name));
        }
        // The class default objects (Default__Man …) are Character derived
        // and sit at the world origin - they were the "fake ESP" pile.  They
        // are filtered by NAME here (the object flags are not at 0x8 in this
        // build, so the flag check returned garbage).
        if (strncmp(name, "Default__", 9) == 0) { skipUnnamed++; continue; }
        // A name that resolves to "None" means the FName of that object could
        // not be read - a stale pointer to a destroyed actor.  Do not draw it.
        if (name[0] == '\0' || strcmp(name, "None") == 0) { skipUnnamed++; continue; }

        fprintf(f, "0x%lx %d %d %.2f %d %d %d %d %.1f %.1f", (unsigned long)(uintptr_t)a, hp, maxhp, dist / 100.0f,
                team, onScreen ? 1 : 0, realBones ? 1 : 0, npts, loc.X, loc.Z);
        for (int i = 0; i < 16; i++) {
            if (i >= npts) break;
            fprintf(f, " %.1f %.1f", sx[i], sy[i]);
        }
        fprintf(f, " %s\n", name);
    }
    fclose(f);

    // A one line summary so we can see WHY the other players are missing
    // (no position / out of range / same team / filtered as a CDO).
    static int lastDrawn = -1;
    if (drawn != lastDrawn) {
        lastDrawn = drawn;
        aimbot_log("AIM: esp %d drawn / %zu players (no-pos %d, out-of-range %d, same-team %d, dead %d, unnamed %d)",
                   drawn, players.size(), skipLoc, skipDist, skipTeam, skipDead, skipUnnamed);
    }
}

// ---- full dump ------------------------------------------------------------
//
// Everything we might need in order to build an ESP for this game, in one
// file: the TArray candidates of the mesh component and of the mesh asset
// (that is where the real bone arrays hide), the bone names, and the
// property layout of the UFunctions we call.
static void dump_tarray_region(uintptr_t base, uintptr_t start, uintptr_t end, const char* label, FILE* f)
{
    for (uintptr_t off = start; off + 0x10 <= end; off += 0x08) {
        uintptr_t data = 0;
        int32_t cnt = 0, mx = 0;
        if (!UEMemory::vm_rpm_ptr((void*)(base + off), &data, sizeof(data))) continue;
        if (!UEMemory::vm_rpm_ptr((void*)(base + off + 8), &cnt, sizeof(cnt))) continue;
        UEMemory::vm_rpm_ptr((void*)(base + off + 0xC), &mx, sizeof(mx));
        if (!data || cnt <= 0 || cnt > 4096) continue;
        if (mx < cnt) continue;
        fprintf(f, "  %s +0x%lx: data=0x%lx count=%d max=%d\n",
                label, (unsigned long)off, (unsigned long)data, cnt, mx);
        int show = cnt < 4 ? cnt : 4;
        for (int i = 0; i < show; i++) {
            uint8_t buf[0x30];
            if (!UEMemory::vm_rpm_ptr((void*)(data + (uintptr_t)i * 0x30), buf, sizeof(buf))) continue;
            float tr[3] = { 0, 0, 0 };
            memcpy(tr, buf + 0x10, 12);       // FTransform::Translation
            fprintf(f, "     [%d] ", i);
            for (int k = 0; k < 0x30; k++) fprintf(f, "%02x", buf[k]);
            fprintf(f, "  translation=(%.1f,%.1f,%.1f)\n", tr[0], tr[1], tr[2]);
        }
    }
}

static void dump_ufunction_layout(FILE* f)
{
    const char* names[] = {
        "Function Engine.SkinnedMeshComponent.GetNumBones",
        "Function Engine.SkinnedMeshComponent.GetBoneName",
        "Function Engine.SceneComponent.GetSocketLocation",
        "Function Engine.KismetMathLibrary.FindLookAtRotation",
        "Function Engine.Controller.SetControlRotation",
    };
    for (int n = 0; n < 5; n++) {
        UE_UObject o = UEWrappers::GetObjects() ? UEWrappers::GetObjects()->FindObject(names[n]) : UE_UObject();
        if (!o) { fprintf(f, "  %s: NOT FOUND\n", names[n]); continue; }
        fprintf(f, "  %s @0x%lx\n", names[n], (unsigned long)(uintptr_t)o.GetAddress());
        uintptr_t child = 0;
        if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)o.GetAddress() + Offsets::UStruct_Children), &child, sizeof(child))) continue;
        int guard = 0;
        while (child && guard++ < 32) {
            int32_t idx = -1;
            if (UEMemory::vm_rpm_ptr((void*)(child + 0x18), &idx, sizeof(idx))) {
                char nm[96] = {0};
                fname_str(idx, nm, sizeof(nm));
                int32_t poff = -1;
                UEMemory::vm_rpm_ptr((void*)(child + 0x44), &poff, sizeof(poff));
                fprintf(f, "      prop '%s' idx=%d offset=%d\n", nm, idx, poff);
            }
            uintptr_t next = 0;
            if (!UEMemory::vm_rpm_ptr((void*)(child + 0x28), &next, sizeof(next))) break;
            child = next;
        }
    }
}

bool dump_full_info()
{
    FILE* f = fopen("/sdcard/aimbot_fulldump.txt", "w");
    if (!f) return false;
    bool gotActor = false;

    fprintf(f, "=== ApkToolMD full dump ===\n");
    fprintf(f, "base=0x%lx  gnames=0x%lx  myPC=%p\n", (unsigned long)g_libUE4_base, (unsigned long)g_GNames, (void*)g_myPC);

    // the bone slots that were resolved (from the cache, no engine calls)
    {
        std::lock_guard<std::mutex> lk(g_boneMutex);
        for (int i = 0; i < kNumBoneMaps; i++) {
            if (!g_boneMaps[i].valid) continue;
            fprintf(f, "\n=== bone map for mesh 0x%lx (%d bones) ===\n",
                    (unsigned long)g_boneMaps[i].skelMesh, g_boneMaps[i].numBones);
            for (int s = 0; s < kNumBoneSlots; s++) {
                if (!g_boneMaps[i].ok[s]) continue;
                char nm[96] = {0};
                fname_str(g_boneMaps[i].idx[s], nm, sizeof(nm));
                fprintf(f, "  %-10s -> '%s' (idx=%d)\n", kBoneSlots[s].label, nm, g_boneMaps[i].idx[s]);
            }
        }
    }

    // the mesh component + the mesh asset of the first few enemies
    std::vector<uint8_t*> actors;
    {
        std::lock_guard<std::mutex> lk(g_enemyMutex);
        actors = g_enemyCache;
    }
    int dumped = 0;
    for (uint8_t* a : actors) {
        if (!a) continue;
        uintptr_t comp = actor_mesh(a);
        if (!comp) continue;
        FVector loc{};
        actor_location(a, loc);
        fprintf(f, "\n--- actor %p  comp=0x%lx  loc=(%.0f,%.0f,%.0f) ---\n",
                (void*)a, (unsigned long)comp, loc.X, loc.Y, loc.Z);
        dump_tarray_region(comp, 0x700, 0x900, "component", f);
        uintptr_t skel = 0;
        if (UEMemory::vm_rpm_ptr((void*)(comp + Offsets::USkinnedMesh_SkeletalMesh), &skel, sizeof(skel)) && skel) {
            fprintf(f, "  skelMesh=0x%lx\n", (unsigned long)skel);
            dump_tarray_region(skel, 0x28, 0x400, "mesh", f);
        }
        gotActor = true;
        if (++dumped >= 2) break;
    }

    // NOTE: no engine calls in here.  Calling into the engine while the
    // level is still loading (half initialised components) crashed the
    // game - the bone names are logged separately by the bone sampler.

    fprintf(f, "\n=== UFunction property layouts ===\n");
    dump_ufunction_layout(f);

    fprintf(f, "\n=== done ===\n");
    fclose(f);
    aimbot_log("AIM: full dump written to /sdcard/aimbot_fulldump.txt (gotActor=%d)", gotActor ? 1 : 0);
    return gotActor;
}


// ---------------------------------------------------------------------------
// Bullet spread - resolved from the game's OWN reflection data.
//
// The hard coded offsets (AMan::ManSpread 0x870, AWeapon::WeaponSpread
// 0x4C8 ...) only work for the exact class they were taken from: the player
// is NOT an AMan (it is a SpecialForcesCharacter, which is smaller) and the
// weapons are Blueprint classes, so reading 0x870 on them gives garbage.
//
// Instead we ask the game: walk the class chain, find the UProperty with the
// name we want and use ITS offset.  That works for every class, including
// the Blueprint ones.
// ---------------------------------------------------------------------------
static int g_offManSpread      = -1;
static bool g_spreadResolved = false;

// The spread/recoil offsets of a WEAPON depend on the weapon's own class (a
// grenade and a rifle do not have the same layout), so they are resolved per
// class and cached - resolving them walks the class chain and resolves FNames.
struct WeaponSpreadOffsets {
    uintptr_t cls = 0;
    int weaponSpread = -1;
    int zoomSpread = -1;
    int currentRecoil = -1;
    int crosshairRecoil = -1;
};
static const int kMaxWeaponClasses = 8;
static WeaponSpreadOffsets g_weaponSpreadCache[kMaxWeaponClasses];

// A pointer must be a live UObject before we walk it: the class pointer has
// to be readable, otherwise we would walk a garbage class chain and write to
// a random address.
static bool plausible_object(uintptr_t p)
{
    if (!p || (p & 7)) return false;
    uintptr_t probe = 0;
    if (!UEMemory::vm_rpm_ptr((void*)(p + 0x18), &probe, sizeof(probe))) return false;
    return true;
}

// Find a property's offset inside a class (walking up the super chain).
static int find_class_prop_offset(UE_UClass cls, const char* name)
{
    for (UE_UStruct s = cls; s; s = s.GetSuper()) {
        uintptr_t child = 0;
        if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)s.GetAddress() + Offsets::UStruct_Children), &child, sizeof(child))) break;
        int guard = 0;
        while (child && guard++ < 96) {
            int32_t idx = -1;
            if (UEMemory::vm_rpm_ptr((void*)(child + 0x18), &idx, sizeof(idx))) {
                char nm[96] = {0};
                if (fname_str(idx, nm, sizeof(nm)) && strcmp(nm, name) == 0) {
                    int32_t off = -1;
                    UEMemory::vm_rpm_ptr((void*)(child + 0x44), &off, sizeof(off));
                    return off;
                }
            }
            uintptr_t next = 0;
            if (!UEMemory::vm_rpm_ptr((void*)(child + 0x28), &next, sizeof(next))) break;
            child = next;
        }
    }
    return -1;
}

// Resolve the spread offsets of ONE weapon (by its own class) - cached.
static const WeaponSpreadOffsets* weapon_offsets_for(uint8_t* weapon)
{
    if (!weapon) return nullptr;
    uintptr_t clsPtr = 0;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)weapon + 0x10), &clsPtr, sizeof(clsPtr))) return nullptr;
    if (!plausible_object(clsPtr)) return nullptr;

    for (int i = 0; i < kMaxWeaponClasses; i++) {
        if (g_weaponSpreadCache[i].cls == clsPtr) return &g_weaponSpreadCache[i];
    }
    int slot = -1;
    for (int i = 0; i < kMaxWeaponClasses; i++) {
        if (!g_weaponSpreadCache[i].cls) { slot = i; break; }
    }
    if (slot < 0) slot = 0;

    UE_UClass wcls = UE_UObject(weapon).GetClass();
    WeaponSpreadOffsets o;
    o.cls = clsPtr;
    if (wcls) {
        o.weaponSpread    = find_class_prop_offset(wcls, "WeaponSpread");
        o.zoomSpread      = find_class_prop_offset(wcls, "ZoomSpread");
        o.currentRecoil   = find_class_prop_offset(wcls, "CurrentRecoil");
        o.crosshairRecoil = find_class_prop_offset(wcls, "CrosshairRecoil");
    }
    g_weaponSpreadCache[slot] = o;
    aimbot_log("AIM: weapon class 0x%lx -> spread=%d zoom=%d recoil=%d cross=%d",
               (unsigned long)clsPtr, o.weaponSpread, o.zoomSpread, o.currentRecoil, o.crosshairRecoil);
    return &g_weaponSpreadCache[slot];
}

// Zero the spread (and the visual recoil) of our pawn and its weapons.
void remove_bullet_spread()
{
    if (!g_noSpread) return;
    const float zero = 0.0f;

    uintptr_t pawn = 0;
    if (!g_myPC) return;
    if (!UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::APlayerController_Pawn), &pawn, sizeof(pawn)) || !pawn)
        UEMemory::vm_rpm_ptr((void*)((uintptr_t)g_myPC + Offsets::APlayerController_ACKPawn), &pawn, sizeof(pawn));
    if (!pawn) return;
    if (!plausible_object(pawn)) return;
    uint8_t* actor = (uint8_t*)pawn;

    // The pawn's own spread (AMan::ManSpread).  The offset comes from the
    // game's own reflection data, so it is valid for the pawn's class.
    if (!g_spreadResolved) {
        UE_UClass acls = UE_UObject(actor).GetClass();
        if (!acls) return;
        g_offManSpread = find_class_prop_offset(acls, "ManSpread");
        g_spreadResolved = true;
        aimbot_log("AIM: spread offsets resolved: ManSpread=%d", g_offManSpread);
    }

    // The weapon list is a plain C array inside AMan (Weapons[6] @ 0x758), so
    // it only exists on the Man derived actors.  When the pawn is not Man
    // derived there is no array to walk - do not read it (the old code did,
    // and the garbage pointers it found were the crash).
    if (g_offManSpread >= 0) {
        for (int i = 0; i < 6; i++) {
            uintptr_t w = 0;
            if (!UEMemory::vm_rpm_ptr((void*)(pawn + Offsets::AMan_Weapons + (uintptr_t)i * sizeof(uintptr_t)), &w, sizeof(w))) break;
            if (!w) continue;
            // Every weapon has its own class -> its own offsets.  Writing the
            // first weapon's offsets into the others corrupts unrelated
            // fields of a different weapon class.
            const WeaponSpreadOffsets* o = weapon_offsets_for((uint8_t*)w);
            if (!o) continue;
            if (o->weaponSpread >= 0)    vm_wpm_ptr((void*)(w + o->weaponSpread), &zero, sizeof(zero));
            if (o->zoomSpread >= 0)      vm_wpm_ptr((void*)(w + o->zoomSpread), &zero, sizeof(zero));
            if (o->currentRecoil >= 0)   vm_wpm_ptr((void*)(w + o->currentRecoil), &zero, sizeof(zero));
            if (o->crosshairRecoil >= 0) vm_wpm_ptr((void*)(w + o->crosshairRecoil), &zero, sizeof(zero));
        }
    }

    if (g_offManSpread >= 0) {
        vm_wpm_ptr((void*)(pawn + g_offManSpread), &zero, sizeof(zero));
    }
}

// ---- main entry ----------------------------------------------------------
static int g_screenW = 0, g_screenH = 0;
static int g_slowTick = 0;

void tick(int screenW, int screenH)
{
    // The overlay service knows the CURRENT orientation (the game runs in
    // landscape) and publishes it in the external flags - prefer it over the
    // (portrait) size the app wrote into the config.
    int ew = ext_flag_int("screenw", 0);
    int eh = ext_flag_int("screenh", 0);
    if (ew > 0 && eh > 0) { screenW = ew; screenH = eh; }

    g_screenW = screenW;
    g_screenH = screenH;
    static int lastLogW = 0, lastLogH = 0;
    if (screenW != lastLogW || screenH != lastLogH) {
        lastLogW = screenW;
        lastLogH = screenH;
        aimbot_log("AIM: projecting with screen %dx%d", screenW, screenH);
    }

    Basis b = camera_basis();

    // fast: targets JSON every tick (cheap enough at 10 Hz)
    dump_targets(b, screenW, screenH);
    dump_esp_frame(b, screenW, screenH);

    // slow: heavier dumps every ~3 s
    if ((g_slowTick++ % 30) == 0) {
        dump_sockets();
        dump_vtables();
        dump_state();
        scan_aim_functions();
    }
}

}  // namespace Intro
// ===== END INTROSPECTION MODULE =====
'''

# Insert right after the aimbot globals block ends
anchor = '// ===== END AIMBOT GLOBALS ====='
if anchor not in s:
    print("ERROR: aimbot globals end marker not found")
    exit(1)

s = s.replace(anchor, anchor + "\n" + block, 1)
open(p, "w").write(s)
print("introspection module added")
