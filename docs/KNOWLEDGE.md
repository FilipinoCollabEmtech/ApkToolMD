# ApkToolMD — Complete Knowledge Transfer

Everything known about this project, written for a fresh agent/developer to pick up
cold. Last updated: 2026-10-21.

> ### 📦 APK (always include this link when you report a build)
> **https://github.com/FilipinoCollabEmtech/ApkToolMD/releases/download/latest/app-debug.apk**
>
> Repo: https://github.com/FilipinoCollabEmtech/ApkToolMD ·
> Build poll: `python3 check_build.py` · Notify: `python3 ping.py "<msg + link>"`

---

## 1. What this project is

`ApkToolMD` (pkg `com.fluxin.apktoolmd`) is an **offline APK inspector + UE4 dumper** for
Android, with a growing **in-game mod (aimbot) framework**.

- Inspects an imported APK: detects the game engine (Unity / Unreal / Godot / Cocos / …),
  extracts UE4 assets (`.so`, `.pak`) out of the archive.
- **Auto-inject**: patches the game's `libUE4.so` to add a `DT_NEEDED` entry pointing at a
  bundled dumper `.so`, re-signs the APK with an embedded PKCS12 keystore, and offers an
  **Install patched** button.
- The dumper `.so` loads inside the game process and dumps the live UE4 SDK
  (`AIOHeader.hpp`, `Objects.txt`, `Offsets.hpp`, `script.json`, `Logs.txt`) into
  `/sdcard/Android/data/<pkg>/files/UEDump3r/<pkg>/`.
- A floating **debug overlay** (app process) tails the dumper's status/trace files and
  shows them over the game.
- The same dumper `.so` now also carries an **aimbot** (see §6).

### Target game
| | |
|---|---|
| Package | `com.ForgeGames.SpecialForcesGroup2` |
| APK | `/sdcard/MT2/apks/SpecialForcesGroup2_4.21.apk` (85,214,737 bytes) |
| Engine | **UE4.22** (GNames / `TNameEntryArray`, *not* FNamePool) |
| Lib | `lib/arm64-v8a/libUE4.so` (74,162,088 bytes, **fully symbol-stripped**) |
| Anti-tamper | `libpglarmor.so` — **blocks any second injected `.so`** (see §8.1) |
| Mod | `libpglarmor.so`, `libshared_UEDump3r_arm64.so` |

### Environment constraints (hard rules)
- Device: Android 8.1, SDK 27, **arm64-v8a only**.
- PRoot (Alpine aarch64) container — `uid=0` but **NOT device-root**:
  `/data` is not mounted, `pm`/binder blocked, **logcat is per-uid isolated** (we cannot read
  the game's logcat — all in-game logging goes through **files**).
- **NEVER build locally.** No `java`/`zipalign`/`aapt` locally (JVM can't even start under
  PRoot). All builds are **cloud CI** (GitHub Actions).
- Local tooling available: `python3`, `node`, `go`, `git`, `jq`, `gh` (authed as
  `FilipinoCollabEmtech`), `curl`, `strings`, `grep -a`.
- `su` is **not** available → no ptrace injection from the app.

---

## 2. Repo & build pipeline

- Repo: `https://github.com/FilipinoCollabEmtech/ApkToolMD`
- APK: `https://github.com/FilipinoCollabEmtech/ApkToolMD/releases/download/latest/app-debug.apk`
- CI: `.github/workflows/android.yml` — Ubuntu runner, JDK 17 then JDK 8, Android SDK
  platform 29, build-tools 29.0.3, **NDK 21.4.7075529**.

### CI order (important — patches run sequentially on the same files)
```
git clone --recurse-submodules https://github.com/DreamFekk/Android_UE_Dumper_CN.git /tmp/uecn
cp patches/SFG2.hpp      /tmp/uecn/AndUEDumper/src/UE/UEGameProfiles/SFG2.hpp
python3 patches/patch_library.py          # activates SFG2 profile (include + registration)
python3 patches/patch_toast.py            # JNI resolver, show_notification, write_status, PHASE breadcrumbs
python3 patches/patch_perms.py            # v-perm6 storage-permission guard thread
python3 patches/patch_wrappers.py         # caps chain-walk loops
python3 patches/patch_aimbot_into_dumper.py   # ALL the aimbot code (main file)
python3 patches/patch_introspect.py       # the introspection module (needs the aimbot block)
python3 patches/patch_dump_guard.py       # nodump=1 + throttles the SDK dump (ANR watchdog)
python3 patches/patch_aimbot_mod.py       # builds mod_src/ + stages assets
cd /tmp/uecn/AndUEDumper
"$NDK/ndk-build" -j4 -B NDK_PROJECT_PATH=. NDK_APPLICATION_MK=./Application.mk APP_ABI=arm64-v8a APP_OPTIM=release
```
Then: build `patchelf` (aarch64, static) → stage dumper `.so` + `patchelf` into
`app/src/main/assets/dumper/` → `mod_src` outputs into `app/src/main/assets/mod/` →
signing keystore into `app/src/main/assets/keystore/` → `./gradlew assembleDebug` →
publish to the `latest` release.

### Patch scripts (`patches/`)
| Script | What it does |
|---|---|
| `patch_library.py` | Adds `#include "UE/UEGameProfiles/SFG2.hpp"` + `new SFG2Profile(),` |
| `patch_toast.py` | JNI `_dyn_sym` VM resolver, `show_notification()`, `write_status()`, PHASE breadcrumbs, progress % |
| `patch_perms.py` | Permission guard thread (`plog()`), v-perm6 |
| `patch_wrappers.py` | Caps UE class-chain walk loops |
| `patch_aimbot_into_dumper.py` | **Everything aimbot** (see §6) |
| `patch_introspect.py` | GNames walker, projection, `dump_targets`/`dump_esp_frame`/`sample_bones`/`remove_bullet_spread` |
| `patch_dump_guard.py` | `nodump=1` switch + throttles the SDK dump (the dump used to starve the game thread → ANR → SIGKILL, no crash report) |
| `patch_aimbot_mod.py` | Builds `mod_src/` (standalone `libaimbot_mod.so` + `ptrace_injector`) and patches `Dumper.cpp` to emit `sdk_full.json` |
| `patch_mod.py` | *Not wired into CI* — old diagnostic thread experiment |

> **Traps already hit** (do not repeat):
> - `patch_toast.py` **modifies `onload()`** (adds a `write_status` line). Any later patch
>   that matches `onload()` verbatim will **silently no-op** (Python `str.replace` doesn't
>   error). Always `assert` your anchor matched, and prefer anchoring on a stable fragment
>   like `callMe(false);`.
> - `patch_toast.py` defines `write_status(const char*)` in `library.cpp` → **name collision**
>   if another patch defines the same name. The aimbot uses `aimbot_write_status` /
>   `aimbot_write_trace` / `aimbot_log` to avoid this.
> - Multiple patches editing the same file → declare your symbols **before first use**
>   (the aimbot block is inserted *after* `std::vector<IGameProfile*> UE_Games = {…}` for
>   exactly this reason).

### Build / release commands
```bash
cd /workspace/ApkToolMD
git add -A && git commit -m "..." && git push origin main   # push triggers CI
python3 check_build.py            # polls the latest run until done (5 min cap)
python3 check_build.py <run_id>   # poll a specific run
python3 ping.py "<message>"      # notify (ntfy.sh/chat_completed) — required before
                                  # asking the user a question and before finishing
gh run list --limit 3
gh run view <run_id> --log-failed | grep -E "error:"
gh release view latest --json assets
```

---

## 3. Dumper internals (what we learned by reading the source)

Base: `DreamFekk/Android_UE_Dumper_CN`, sources under `/tmp/uecn/AndUEDumper/src/`.

| File | Role |
|---|---|
| `library.cpp` | `onload()` (constructor) → `callMe()` → `dump_thread()`; **this is where the aimbot is merged in** |
| `Dumper.cpp` | `Dump()` orchestrates; `DumpOffsetsInfo()` finds GEngine/GWorld; `GatherUObjects()` walks the object array; emits `script.json` |
| `UE/UEGameProfile.cpp` | `InitUEVars()`, `GetUnrealELF()`, `GetNameByID()` |
| `UE/UEGameProfiles/SFG2.hpp` | **our** profile — structural `scanGNames()` (no symbols!) |
| `UE/UEWrappers.hpp/.cpp` | `UE_UObject`, `UE_UClass`, `UE_UObjectArray` (`GetObjects()`, `ForEachObject`, `ForEachObjectOfClass`, `FindObject`) |
| `UE/UEOffsets.hpp/.cpp` | `UE_Offsets` (struct offsets) + `UE_Pointers` (globals) |
| `UE/UEMemory.hpp/.cpp` | `vm_rpm_ptr()` — **the safe memory reader** |
| `deps/KittyMemoryEx/` | `KittyMemoryMgr` (process memory), `KittyScanner` (`ElfScanner`, `findMemElf`), `KittyArm64` (arm64 decode helpers) |

### Key dumper behaviours
- **`GetUnrealELF()`** (UEGameProfile.cpp:210) looks for `libUE4.so` / `libUnreal.so` via
  `kMgr.findMemElf()`, then **inside the APK zip** via `findMemElfInZip()` (split-config
  games), then the linker solist. **It has a `static ElfScanner ue_elf` → shared state,
  not thread-safe.**
- **`kMgr.initialize()`** happens in `library.cpp` `dump_thread()` at **t = 20 s**
  (`WAIT_TIME_SEC`). Anything touching `kMgr` before that is invalid.
- **`InitUEVars()`** sets `_UEVars.BaseAddress` (line 47) and calls `UEWrappers::Init()`
  (line 91) which builds the object array. → **`BaseAddress != 0` does NOT mean the object
  array is ready**; check `UEWrappers::GetObjects()` too.
- **`script.json` "UObject$$ProcessInternal" is MISLABELLED.** Dumper.cpp:376 pushes the
  *first blueprint-event function it finds* under that name. It is **not** `ProcessEvent`.
  Its RVA for this build was `0x13C949C` — **wrong for ProcessEvent**.
- The **real** `UObject::ProcessEvent` for this build: **vtable index 65, RVA `0x13C3DE0`**
  (found by our vtable scanner, see §6.3).
- `write_status()` (patch_toast) **truncates** `/sdcard/UEDump3r_status.txt` on every call
  → the file only ever holds the latest line.
- `UE_UClass::StaticClass()` **caches on first use** → calling it before the object array
  exists caches null forever.

---

## 4. Verified offsets (all from the live dump on device)

Source of truth: `/storage/emulated/0/ApkToolMD/dumps/` (`AIOHeader.hpp`, `Offsets.hpp`,
`Objects.txt`, `script.json`) — dumped from **this exact build** of the game.

### 4.1 Globals (RVA from `libUE4.so` base) — `Offsets.hpp` → `namespace UEPointers`
| Symbol | RVA | Note |
|---|---|---|
| GNames | `0x46D2E90` | `TNameEntryArray`; `FNameEntry.Name` at **+0xC**; `IsUsingFNamePool=false` |
| GUObjectArray | `0x4822620` | |
| ObjObjects | `0x4822630` | |
| GEngine | `0x493C1F8` | |
| GWorld | `0x493F400` | |
| Matrix | `0x492D2B8` | |
| Physx | `0x0` | **NOT FOUND** (1 candidate, ambiguous) |
| FrameCount | `0x0` | **NOT FOUND** (3 candidates) |
| `UObject::ProcessEvent` | `0x13C3DE0` | vtable **index 65** (scanner-verified) |
| `AController::SetControlRotation` | `0x27F5BD8` | from `AIOHeader.hpp` `Engine.Controller.SetControlRotation` |

> `script.json`'s `UObject$$ProcessInternal` = `0x13C949C` — **do NOT use as ProcessEvent.**

### 4.2 UE4.22 struct offsets — `Offsets.hpp`
```
FName            : ComparisonIndex 0x0, Number 0x4, DisplayIndex 0x0, Size 0x8
FNameEntry       : Index 0x8, Name 0xC
FUObjectArray    : ObjObjects 0x10
TUObjectArray    : Objects 0x0, NumElements 0x14, NumElementsPerChunk 0x10000
FUObjectItem     : Object 0x0, Size 0x18
UObject          : ObjectFlags 0x8, InternalIndex 0xC, ClassPrivate 0x10,
                   NamePrivate 0x18, OuterPrivate 0x20
UField           : Next 0x28
UEnum            : Names 0x40
UStruct          : SuperStruct 0x40, Children 0x48, ChildProperties 0x0, PropertiesSize 0x50
UFunction        : EFunctionFlags 0x98, NumParams 0x9C, ParamSize 0x9E, Func 0xC0
```

### 4.3 Class layouts — `AIOHeader.hpp`
```
AActor                size 0x330   RootComponent 0x158
USceneComponent               AttachParent 0x108, AttachSocketName 0x110,
                              AttachChildren 0x118, RelativeLocation 0x164,
                              RelativeRotation 0x170, RelativeScale3D 0x17C
ACharacter                    Mesh 0x390, CharacterMovement 0x398, CapsuleComponent 0x3A0
AController          size 0x3A8   Pawn 0x360, Character 0x370, ControlRotation 0x398
APlayerController             Player 0x3A8 (ULocalPlayer* - local only!),
                              AcknowledgedPawn 0x3B0, MyHUD 0x3C0,
                              PlayerCameraManager 0x3C8
APlayerCameraManager          PCOwner 0x330, TransformComponent 0x338,
                              CameraCache 0x3A0, LastFrameCameraCache 0x930,
                              ViewTarget 0xEC0, PendingViewTarget 0x1460,
                              CameraCachePrivate 0x1A20
FTViewTarget                 Target 0x0 (AActor*), POV 0x10, PlayerState 0x590
FCameraCacheEntry             Timestamp 0x0, POV 0x10
FMinimalViewInfo              Location 0x0, Rotation 0xC, FOV 0x18
FTransform                    Rotation 0x0, Translation 0x10, Scale3D 0x1C (size 0x30)
FBoxSphereBounds              Origin 0x0, BoxExtent 0xC, SphereRadius 0x18
USkeletalMeshComponent        CachedBoneSpaceTransforms 0x798, CachedComponentSpaceTransforms 0x7A8
USkinnedMeshComponent         SkeletalMesh 0x5A0
USkeletalMesh                 ImportedBounds 0x50, ExtendedBounds 0x6C, Sockets 0x330
```

**`SpecialForces.MyPlayerController`** (size 0x880, parent `APlayerController` 0x678):
```
TimerAIM 0x728   tAIM 0x72C   Sensitivity 0x730
AngleAIM 0x734   AIMStep 0x738
EnemyAIM 0x740   (AMan* - the game's own target pointer)
bAIM 0x820   bAIMifZoom 0x821   bAutoZoom 0x822   bUseIronSight 0x823
bAutoFire 0x832
```
**`SpecialForces.MyPlayerCameraManager`** (size 0x2670): `TypeCamera 0x2654`,
`TypeCameraDead 0x265C`, `MyPC 0x2668`.

### 4.4 Units
**1 UE unit = 1 centimetre. 100 units = 1 metre.**
Proof from the dump: enemy capsule half-heights of `90` (1.8 m tall) and `50` (1.0 m).

### 4.5 FName string offsets in `libUE4.so` (for IDA/Ghidra xrefs)
| Name | File offset |
|---|---|
| `bAutoFire` | `0x33CF8EA` |
| `bAIMifZoom` | `0x33CF943` |
| `EnemyAIM` | `0x33CF9A9` |
| `AIMStep` | `0x33CF9B2` |
| `AngleAIM` | `0x33CF9BA` |
| `TimerAIM` | `0x33CF9C8` |

### 4.6 Class objects (for `FindObject("Class …")`)
`Class Engine.Character` · `Class Engine.Controller` · `Class Engine.PlayerController` ·
`Class Engine.HUD` · `Class Engine.Engine` · `Class Engine.CameraActor` ·
`Class SpecialForces.Man` · `Class SpecialForces.MyPlayerController` ·
`Class SpecialForces.MyAIController` · `Class SpecialForces.MyPlayerCameraManager` ·
`Class SpecialForces.HUDWidget` · `Class SpecialForces.GamePlayActor` · `Class SpecialForces.Hand` ·
`Class SpecialForces.GPWeapon`

`FindObject()` matches on `GetFullName()`, so the string must be e.g.
`"Class SpecialForces.MyPlayerController"` or `"Function Engine.Engine.AddOnScreenDebugMessage"`.

---

## 5. App structure

| Path | Purpose |
|---|---|
| `app/src/main/java/com/fluxin/apktoolmd/MainActivity.kt` | nav host; bottom-nav → fragment |
| `…/ui/HomeFragment.kt` | Import APK, Analyze, Dump UE4, **Patch/Auto-inject**, **Install patched**, **Inject Aimbot** |
| `…/ui/DebugFragment.kt` | Floating-logger toggle, **Aimbot toggles**, config file writer |
| `…/ui/ProcessEventFragment.kt` | "Process Event" page: parse `script.json` / `Offsets.hpp` / `.so` for ProcessEvent |
| `…/ui/SettingsFragment.kt` | theme/about |
| `…/debug/DebugLog.kt` | logcat bus + **file tails** (`UEDump3r_status.txt`, `UEDump3r_trace.txt`, `ApkToolMD/debug.log`) |
| `…/debug/DebugOverlayService.kt` | foreground service: floating blob + console panel + **ESP distance panel** |
| `…/debug/DumperBridge.kt` | connection check |
| `patches/SFG2.hpp` | our game profile: **structural `scanGNames()`** (finds GNames without symbols) |
| `mod_src/aimbot_mod.cpp` | standalone aimbot `.so` (kept but **not injected** — see §8.1) |
| `mod_src/ptrace_injector.cpp` | ptrace injector (**needs root — unusable here**) |

### File channels (how in-game code talks to the app)
| File | Written by | Read by | Purpose |
|---|---|---|---|
| `/sdcard/UEDump3r_status.txt` | dumper (`write_status`) | overlay (tail) | one-line status (truncated each write) |
| `/sdcard/UEDump3r_trace.txt` | dumper (`ue_trace`) | overlay (tail) | verbose trace (**overlay console**) |
| `/sdcard/UEDump3r_diag.txt` | SFG2 profile | manual | offset diagnostics |
| `/sdcard/UEDump3r_perm.txt` | perm guard | manual | permission guard log |
| `/sdcard/aimbot_config.txt` | **app** (Debug tab) | aimbot | toggles |
| `/sdcard/aimbot_debug.log` | aimbot | manual | **aimbot verbose log (NOT tailed by overlay)** |
| `/sdcard/aimbot_esp.txt` | aimbot | **overlay ESP panel** | distance list |

> **Overlay console spam**: the overlay tails `UEDump3r_trace.txt`. Never write per-frame
> logs there — write them to `aimbot_debug.log` instead. Only rare one-off events may
> mirror into the trace file (the aimbot mirrors at most 12).

### Config format (`/sdcard/aimbot_config.txt`) — written by the app, read by the aimbot
```
enabled=1      # aimbot master switch
head=1         # aim at the head (not the body)
instant=1      # write the rotation directly (no smoothing)
autofire=1     # drive the game's bAutoFire
gameassist=1   # 1 = use the game's own aim assist (we point it at our
               #     target), 0 = force it off
engineaim=1    # 1 = we write ControlRotation + the camera POV caches
               #     ourselves (the "engine path")
headz=0.80     # head height = capsule centre + headz * CapsuleHalfHeight
               #     (0.80 = the eyes of a 1.8 m character; live slider in the app)
esp=1          # on-screen distance label
```
> The native parser accepts `true`/`false`/`1`/`0`. **It used to use `atoi()` which returns 0
> for "true" as well as "false" — the aimbot was silently disabled. Never use `atoi` on a
> boolean string.**
>
> The config file is read **at most every 100 ms** (`read_config_throttled()`). It used to be
> read on *every* ProcessEvent call (the game calls it thousands of times/second).

---

## 6. The aimbot (merged into the dumper `.so`)

All code lives in `patches/patch_aimbot_into_dumper.py`, inserted into `library.cpp`.

### 6.0 THE BIG ONE: `vm_rpm_ptr()` is READ-ONLY
`UEMemory::vm_rpm_ptr(address, result, len)` calls `kMgr.readMem()` — it **reads**.
Every "write" in the old aimbot used it, so **the aimbot never changed a single byte of
the game** (that is why the crosshair never moved, despite the logs looking healthy).
All writes now go through the new helper:

```cpp
static bool vm_wpm_ptr(void* address, const void* src, size_t len)
{
    if (!UEMemory::kPtrValidator.isPtrWritable((uintptr_t)address, len)) return false;
    if (UEMemory::kMgr.writeMem((uintptr_t)address, (void*)src, len) == len) return true;
    memcpy(address, src, len);      // we ARE the game process
    return true;
}
```
> `kMgr` is initialised by `dump_thread()` at t = 20 s for the game's **own pid**, so a
> write is a `process_vm_writev` into ourselves. `KittyMemoryMgr::writeMem()` and
> `KittyPtrValidator::isPtrWritable()` exist — use them.

### 6.1 Flow
```
onload()  (constructor)
  ├─ callMe(false)      → dump thread (t=20 s, does the SDK dump)
  └─ pthread_create     → aimbot_thread()
       ├─ wait for profile init:  vars->GetBaseAddress() != 0 && UEWrappers::GetObjects()
       ├─ start enemy-cache thread (2 Hz)
       ├─ find the LOCAL player controller (APlayerController::Player @0x3A8 != null)
       ├─ hook AController::SetControlRotation (RVA 0x27F5BD8)
       ├─ vtable-scan UObject::ProcessEvent on GWorld → RVA + index
       └─ install the ProcessEvent hook (safe unpatch-and-call)
poll loop (10 ms): read config → resolve target → compute aim →
       write ControlRotation + all 4 camera POV caches + the camera actors
       publish the rotation for the game thread
ProcessEvent hook (GAME thread): every ≤30 ms apply the published rotation through
       AController::SetControlRotation (via ProcessEvent) → replicated to the server
```

### 6.2 Target resolution
1. The game's own `EnemyAIM` (0x740) if non-null, **else**
2. Our cached character list → pick the actor whose direction is **closest to the camera
   forward vector** (max dot), skipping our own pawn, `d < 100` or `d > 3000` units.

The character cache is built from **`Class Engine.Character`** (not `SpecialForces.Man` —
the game's enemies are not all `AMan`). Refreshed 2×/s on a background thread using a
**class-pointer set** (built once) so the per-frame check is a pointer compare, not `IsA()`.

### 6.3 ProcessEvent vtable scanner
Scans GWorld's vtable (120 slots), reads 0x200 bytes of each function **safely**
(`vm_rpm_ptr`) and scores it on the UE4.22 `ProcessEvent` signature:
ADRP→GUObjectArray/ObjObjects, LDR imm == `UObject::InternalIndex` (0xC),
`FUObjectItem::Size` (0x18), `UStruct::PropertiesSize` (0x50), `UFunction::ParamSize` (0x9E),
`UStruct::Children` (0x48).
**Result for this build: index 65, RVA `0x13C3DE0`, score 5.**

### 6.4 The safe inline hook (important!)
A classic trampoline (copy the prologue to a new page) **breaks** if the prologue contains a
PC-relative instruction (ADRP/ADR/B) — the relocated copy computes the wrong address and
corrupts the game. That killed the game twice.

Instead: **unpatch-and-call-in-place**
```cpp
memcpy(pe, &g_origInsn, 4);        // restore original instruction
o_ProcessEvent(obj, func, params); // original runs AT ITS REAL ADDRESS
memcpy(pe, &g_branchInsn, 4);      // re-install the branch
```
A thread racing through the unpatched window just runs the original — no crash.
Re-entrancy guard: the re-entrant path **must also un-patch first**, otherwise
`o_ProcessEvent` points at the *patched* entry → infinite recursion (this was a real bug).

### 6.5 Aim math — and the pitch sign
```
camera  = PlayerCameraManager → CameraCachePrivate(0x1A20) → POV(+0x10) → Location/Rotation
          (CameraCache 0x3A0 and LastFrameCameraCache 0x930 are ZERO in this build!)
target  = head:  USkeletalMesh::Sockets → USkeletalMeshSocket → GetSocketLocation()
                 (world space, exact) - but this game has NO sockets
          else:  the animated bone arrays (EMPTY in this build)
          else:  THE CAPSULE: capsule centre + headz * CapsuleHalfHeight
                 (the root IS the capsule; half height 90 = 1.8 m, so headz
                  0.80 aims at the eyes ≈ 1.62 m above the feet)
          else:  a fixed 64 cm offset
delta   = head - camera.Location
yaw     = atan2(dy, dx) * 180/PI
pitch   = atan2(dz, hypot(dx,dy)) * 180/PI      ← UE convention: positive = looking UP
```
> **The pitch sign was inverted before.** UE's `FVector::Rotation()` /
> `UKismetMathLibrary::FindLookAtRotation` use `pitch = atan2(Z, hypot(X,Y))`, i.e.
> **positive pitch = looking up**. The old code used `-asinf(dz/len)`, i.e. the opposite.
> The new code does not guess: it calls the **engine's own**
> `UKismetMathLibrary::FindLookAtRotation(Start, Target)` through ProcessEvent and
> **calibrates the sign automatically** (`g_pitchSign`, logged as `pitch sign = ±1`).

Written to **both** `AController::ControlRotation` (0x398) and every camera POV cache, plus
the camera actors' root rotation.

> **The game adds the player's mouse delta to `ControlRotation` every frame.** An absolute
> write therefore fights the player: the camera flickers between our value and the mouse
> value. Worse, the game does **not** clamp the pitch, so an unwrapped pitch accumulates
> (we saw 228° / 273° → "the camera goes wild up and down").
>
> The fix: **wrap** the rotation (pitch ±90, yaw ±180) and apply a **partial correction**
> to the current rotation instead of an absolute value:
> `new = cur + (desired - cur) * aimstr` (aimstr 0.05..1.0, 0.5 = the mouse still moves
> the view and the aim is pulled back smoothly, 1.0 = a hard lock). The step is clamped to
> 60°/45° per update so the camera can never whip around.

### 6.6 Engine calls (how the game is really driven)
`UObject::ProcessEvent` is the only safe way to call a reflected function. Useful ones in
this build (all present in `Objects.txt`):

| UFunction | Params | Use |
|---|---|---|
| `Function Engine.Controller.SetControlRotation` | FRotator @0x0 | **the** way to change where the player looks (rotates the pawn + replicates to the server) |
| `Function Engine.KismetMathLibrary.FindLookAtRotation` | Start @0x0, Target @0xC, ReturnValue @0x18 | the engine's own look-at rotation (ground truth) |
| `Function Engine.SkeletalMeshSocket.GetSocketLocation` | ReturnValue @0x0, SkelComp @0xC | the world position of a mesh socket (the head) |
| `Function Engine.KismetSystemLibrary.PrintString` | see §5 | on-screen text |

> The parms buffer layout is **not** guessed: the code reads the real offsets from the
> UFunction's property list (`UStruct::Children` → `UProperty::Offset_Internal`, via
> `UE_Offsets` from the active profile) and the real size from `UFunction::ParamSize` (0x9E).
> `resolve_fname()` (own GNames walk) resolves the property names.
>
> **Thread safety:** `SetControlRotation` touches the world and the network → it must run on
> the **game thread**. The poll thread only *publishes* the rotation; the ProcessEvent hook
> (which runs inside the game thread) applies it, at most every 30 ms.

### 6.7 Game-specific findings (important!)- The game has a **custom camera manager** (`SpecialForces.MyPlayerCameraManager`).
- **`AController::SetControlRotation` is NEVER called by this game** — our hook installed
  fine but never fired (until we started calling it ourselves).
- The live POV cache is **`CameraCachePrivate` (0x1A20)**; `CameraCache` (0x3A0) and
  `LastFrameCameraCache` (0x930) read back all zeros.
- **The camera caches are NOT interchangeable** (this caused a crash!):
  | cache | offset | rotation | location |
  |---|---|---|---|
  | `CameraCache` | 0x3A0 | real | **(0,0,0) — empty** |
  | `CameraCachePrivate` | 0x1A20 | real | real ← **use this one** |
  | `LastFrameCameraCache` | 0x930 | real | **(0,0,0) — empty** |
  | `ViewTarget.POV` | 0xEC0+0x10 | real | real |
  All of them hold the **same rotation** (the manager copies the POV into every cache), so
  "which one moved the most" cannot identify the live one — pick the one with a **real
  location** instead. Aiming from an empty cache means aiming from the world origin →
  a 150° camera whip → **the game crashed**.
  Always validate the camera before aiming: not (0,0,0), finite, and within 500 units of
  our own pawn (`camera_is_plausible()`). A rotation jump of more than 60° is clamped
  (a jump that big means something is stale).
  **Some game states have NO usable camera at all** (spawn, spectate, killcam): every
  cache and every camera actor reads (0,0,0). Then the aim is computed from the **local
  pawn's eyes** (`local_eye_position()`: the pawn's root + 0.8 × capsule half height) —
  never from the world origin, that crashes the game.
- `FindObject()` (full name) walks all 59k objects and takes **seconds** — never call it
  on the aim thread (it starved the aim loop for minutes once). The engine UFunction
  lookup runs on its own thread now.
- The render camera actor is `ViewTarget.Target` (0xEC0 + 0x0); its root-component rotation
  tracks the POV. `APlayerCameraManager::AnimCameraActor` is at 0x2628.
- The game has its **own aim assist** (`bAIM`, `AngleAIM`, `AIMStep`, `TimerAIM`, `tAIM`,
  `Sensitivity`) and **resets those fields every frame** — writing them has no lasting
  effect. `gameassist=0` forces `bAIM`/`bAIMifZoom` off each frame.
- The game's aim assist aims at `EnemyAIM` (0x740) → pointing that at our target and
  setting `bAIM=1` makes the **game itself** do the aiming (best path for an online game,
  because the rotation then goes through the game's own code to the server).


---

### 6.8 Threads: what may be called from where

| Thread | May do | May NOT do |
|---|---|---|
| **aim thread** (10 ms loop) | read the game memory, compute the rotation, write `ControlRotation` + the POV caches, write the files | **call any engine UFunction** |
| **game thread** (inside the ProcessEvent hook) | call engine UFunctions (`SetControlRotation`, `GetSocketLocation`, `GetBoneName`) | long scans (the full dump froze the game → ANR → SIGKILL) |
| **cache thread** (2 Hz) | walk the object array, refresh the enemy list | `FindObject()` (a full-name walk over 58k objects — it takes **seconds**) |

`on_game_thread()` (patch_aimbot_into_dumper.py) compares `pthread_self()` with the
thread id captured the first time the ProcessEvent hook was entered. Every engine
call goes through `call_engine_function()` / `call_engine_functions_batch()`, which
refuse to run off the game thread. That is why:

* the pitch-sign calibration (`FindLookAtRotation`) only happens on the game thread,
* `GetSocketLocation` (the head) is only used on the game thread (the sampled
  `Bip01-*` bones work everywhere),
* the on-screen `PrintString` distance label was removed — the app draws the
  distance panel from `/sdcard/aimbot_esp.txt` instead.

### 6.9 The bullet spread (no-spread)

* The offsets come from the game's **own reflection data** (`find_class_prop_offset`
  walks the class chain and reads `UProperty::Offset_Internal`):
  `ManSpread` 0x870, `WeaponSpread` 0x4C8, `ZoomSpread` 0x514,
  `CurrentRecoil` 0x3C4, `CrosshairRecoil` 0x3C8.
* The **weapon** offsets are resolved **per weapon class** (a grenade and a rifle
  do not have the same layout) and cached.
* `AMan::Weapons[6]` (0x758) is a plain C array — it only exists on the
  **Man derived** actors, so it is only walked when the pawn's class really has
  `ManSpread`.
* Every weapon pointer is validated (its class pointer must be readable) before
  a single byte is written.
* The writes go through `vm_wpm_ptr()` — `vm_rpm_ptr()` is **read-only**, so the
  old code never actually zeroed anything.
* The zeroing happens on the game thread: once right before `StartFire` and once
  per millisecond.

---

## 7. Diagnostics that exist (and where the output goes)

| Signal | Where |
|---|---|
| Hook install, controller found, ProcessEvent found, engine UFunctions, config | `UEDump3r_status.txt` (overlay) + `aimbot_debug.log` |
| `AIM: ProcessEvent hook rate = N calls/s` | how often the game calls ProcessEvent (= how often we can touch the engine from the game thread) |
| `AIM: rot our=(p,y) ctrlNow=(p,y) preWrite=(p,y) ours\|GAME-OVERWROTE` | whether the game overwrites our rotation every frame |
| `AIM: SHOT cam= tgt= head= headOk= ourAim= engineAim= ctrlRot= pov= camActorRot=` | the full picture in one sample: our rotation, the engine's rotation, what the game actually has |
| `AIM: pitch sign calibrated to ±1` | the engine disagreed with our pitch sign → we flipped it automatically |
| `AIM: FindLookAtRotation layout Start= Target= Ret= parms=` | the real parms layout read from the UFunction |
| `AIM: socket[i] 'name'` / `AIM: head socket = 0x…` | every socket of the enemy mesh + the one we picked |
| `AIM: using mesh bounds origin= extent=` | the ImportedBounds fallback |
| `AIM: bone arr0/arr1 data= count=` | raw data pointer/count of the bone transform arrays |
| `AIM: game assist forced OFF -> readback bAIM= bZoom=` | whether the game accepts our flag writes |
| `AIM: cam cand 1..5` / `AIM: camera source = N (moved=…)` | every camera cache candidate + how much it moved between the two probe samples |

> Per-frame logs go to `aimbot_debug.log` **only** — the overlay tails
> `UEDump3r_trace.txt`, and the aimbot mirrors at most 12 lines there.

---

## 8. The two hard blockers (and how we worked around them)

### 8.1 pglarmor blocks any second `.so`
`patchelf --add-needed` of a *second* library into `libUE4.so` → the game **crashes at
launch, no logs at all**. Tested with: full aimbot, `JNI_OnLoad`→`JNI_ERR`, thread-only,
empty hook, minimal `JNI_VERSION_1_6`. **All crashed.** The dumper `.so`
(`libshared_UEDump3r_arm64.so`) is whitelisted; a new name is not.

**Workaround (current):** the aimbot is **merged into the dumper `.so`**, so only one
`.so` is injected. The separate `libaimbot_mod.so` still builds but is **not injected**.

### 8.2 No root → no ptrace
`mod_src/ptrace_injector.cpp` exists but requires `su`. Unusable on this device.
The **DT_NEEDED** path is the only working injection method.

---

## 9. Crash post-mortem (all fixed — don't repeat these)

| # | Symptom | Root cause | Fix |
|---|---|---|---|
| 1 | Game crashes at launch after auto-inject | pglarmor blocks the 2nd `.so` | merge aimbot into the dumper `.so` |
| 2 | Game crashes right after "PE hook installed" | trampoline copied a PC-relative ADRP | unpatch-and-call-in-place |
| 3 | `AIM: FAILED - libUE4.so not found in /proc/self/maps` | libs inside an APK map under the `.apk` path | use `GetUnrealELF().base()` |
| 4 | Crash ~30 s in, right after `GWorld obj=` | vtable scan `memcpy` from unmapped addresses | all reads via `vm_rpm_ptr` |
| 5 | Crash ~2 s in, no logs | aimbot called `GetUnrealELF()` before `kMgr.initialize()` (t=2 s vs t=20 s) + shared-static race | wait for `GetUEVars()->GetBaseAddress()` |
| 6 | Aimbot never fired | `atoi("true") == 0` → config always disabled | real boolean parser |
| 7 | Aimbot thread never spawned | `patch_toast.py` changed `onload()` → our exact-match `replace()` silently no-oped | anchor on `callMe(false);` + `assert` |
| 8 | 3 FPS while playing | walked all 58 268 UObjects with `IsA()` every 10 ms | cached character list, 2 Hz refresh, pointer compare |
| 9 | Build errors: "redefinition of FVector" | duplicated declarations inside the patch block | one copy each |
| 10 | Build errors: "call to 'write_status' is ambiguous" | `patch_toast.py` already defines `write_status` | renamed to `aimbot_write_status` |
| 11 | Build errors: "no member named 'Functions' in 'UE_UClass'" | `UE_UClass` has no `Functions` member (only the dumper's own `UPackageGenerator::Struct` does) | use `jsonFunctions` |
| 12 | Build errors: `Unresolved reference: lowercase` | Kotlin 1.3 has no `String.lowercase()` | `toLowerCase()` |
| 13 | Build errors: `externalFilesDir` unresolved | Kotlin property vs `getExternalFilesDir(null)` | use the getter |
| 14 | Build errors: unescaped apostrophe in `strings.xml` | AAPT | avoid `'` |
| 15 | Latent: infinite recursion on nested `ProcessEvent` | re-entrancy branch called the *patched* entry | un-patch first (§6.4) |
| 16 | Overlay console flooded | per-frame logs written to the tailed trace file | separate `aimbot_debug.log` (§5) |
| 17 | **The aimbot never moved the view at all** | every "write" used `vm_rpm_ptr()`, which is **read-only** — the aimbot never touched the game | new `vm_wpm_ptr()` (kMgr.writeMem) + real writes (§6.0) |
| 18 | The aim pointed below/above the target | the pitch sign was inverted (`-asinf` instead of UE's `+atan2(dz,horiz)`) | use the engine's own `FindLookAtRotation` and auto-calibrate the sign (§6.5) |
| 19 | The head was the capsule centre | the `ImportedBounds` fallback was unreachable (an early `return false` above it) and the bone arrays are empty | socket-based head via `USkeletalMeshSocket::GetSocketLocation` (§6.5) |
| 19b | The lock landed **~2 m above the head** | the `ImportedBounds` fallback added the bounds' *local* Z (264 cm!) to the actor's world Z — but the bounds live in the **mesh's** space and this game's models are ~2.6 m tall | the head is now derived from the **capsule** (`CapsuleHalfHeight` 0x590) with a live `headz` slider (§6.5) |
| 20 | The camera source was picked at random | the probe compared against `g_camPrev`, which was never updated | sample every candidate twice, pick the one that moved (§6.5) |
| 21 | The game lagged / stuttered | the config file was read on *every* ProcessEvent call (thousands/s) | `read_config_throttled()` (100 ms) |
| 22 | **The game crashed in-game** and the lock went to the wrong place | the camera source was picked by "which cache moved the most", but all caches hold the same rotation — it picked `CameraCache`, whose **location reads (0,0,0)**, so the aim was computed from the **world origin** → a 150° camera whip | pick the cache with a **real location**, validate the camera against our own pawn, clamp rotation jumps (§6.7) |
| 23 | The aim loop froze for minutes | `FindObject()` (59k objects, `GetFullName()` each) ran on the aim thread | the engine UFunction lookup moved to its own thread |
| 24 | The log lines showed garbage values (`gameaim=1936876912`) | `aimbot_write_trace()` called `aimbot_log(fmt)` **without the varargs** → the format string was re-formatted with stack garbage | format once into a buffer, then write it |
| 25 | **The game crashed while the ESP was rendering** (build `038b705`) | a `std::vector` of class pointers was cleared + refilled by the cache thread while the aim thread AND the game thread iterated it — iterating a vector that is being reallocated is an instant crash | publish the class lists with a **swap under a mutex** (build a local vector, `swap()` it in) — commit `2783d8b` |
| 26 | Crash inside `SetControlRotation` (`lastOp=set-control-rotation`, SIGSEGV + SIGABRT) | the parms buffer was a bare 12-byte `FRotator`, but the engine copies `UFunction::ParamSize` bytes **out** of it → it read past the buffer | resolve the property offset + `ParamSize` from the UFunction's own property list and pass a zeroed buffer of that size |
| 27 | The game could crash at any time (latent) | engine calls (`ProcessEvent`) were made from **our** thread (the pitch calibration, the socket lookup, `PrintString`) | `on_game_thread()` guard: engine calls only from the thread that called `ProcessEvent`; the off-thread paths fall back to the capsule/bone maths |
| 28 | The bullet spread was never actually removed | the writes used `vm_rpm_ptr()` — which is **read-only** (§6.0) — so every zeroing was silently a read | `vm_wpm_ptr()`, per-weapon-class offsets, and a pointer validation before every write (§6.8) |
| 29 | The radar never drew anything | the `P` anchor line was written into `aimbot_targets.json` (breaking the JSON) but the app parses it out of **`aimbot_esp_frames.txt`** | write the `P` line into `aimbot_esp_frames.txt`, keep the JSON clean |
| 30 | The game froze / 3 FPS at times | the cache thread walked **all 58k objects** with `IsA()` 2×/s, and `FindObject()` (a full-name walk, *seconds*) ran 2×/s as well | the class list + camera actor scan run every **5 s**, the `FindObject()` results are cached, and the expensive full dump runs once |

---

## 10. Status of every offset / claim

| Claim | Source | Status |
|---|---|---|
| `EnemyAIM` 0x740, `bAIM` 0x820, `bAutoFire` 0x832, `AngleAIM` 0x734, `AIMStep` 0x738, `TimerAIM` 0x728, `tAIM` 0x72C, `Sensitivity` 0x730 | `AIOHeader.hpp` | **Verified** (dump) |
| `ControlRotation` 0x398 | `AIOHeader.hpp` (`AController`) | **Verified** (dump) |
| `RootComponent` 0x158, `RelativeLocation` 0x164, `RelativeRotation` 0x170 | `AIOHeader.hpp` | **Verified** (dump) |
| `Mesh` 0x390, `CapsuleComponent` 0x3A0, `CapsuleHalfHeight` 0x590 | `AIOHeader.hpp` | **Verified** (dump) |
| `Player` 0x3A8, `AcknowledgedPawn` 0x3B0, `PlayerCameraManager` 0x3C8 | `AIOHeader.hpp` | **Verified** (dump) |
| `CameraCache` 0x3A0, `CameraCachePrivate` 0x1A20, `ViewTarget` 0xEC0, `FTViewTarget.Target` 0x0 | `AIOHeader.hpp` | **Verified** (dump) |
| `ProcessEvent` RVA `0x13C3DE0`, vtable index 65 | our vtable scanner, runtime | **Verified at runtime** (found every launch) |
| `SetControlRotation` RVA `0x27F5BD8` | `AIOHeader.hpp` | **Verified** (dump) — but the game never calls it |
| GNames `0x46D2E90`, GUObjectArray `0x4822620`, GWorld `0x493F400`, GEngine `0x493C1F8` | `Offsets.hpp` | **Verified** (dump) |
| `ImportedBounds` 0x50, `SkeletalMesh` 0x5A0 | `AIOHeader.hpp` | **Verified** (dump) |
| Bone arrays at 0x798/0x7A8 | `AIOHeader.hpp` | Offset **verified**; **arrays are EMPTY in this build** |
| 1 unit = 1 cm | capsule half-heights 50/90 | **Verified** (inferred, consistent) |
| Yaw math | `SHOT` line: our yaw == expected yaw | **Verified at runtime** |
| Pitch sign | UE's `FVector::Rotation()` = `atan2(Z, hypot(X,Y))` → positive = up; the old code used the opposite | **Fixed 2026-10-20**; the new code asks the engine and auto-calibrates |
| Camera actor rotation == our aim | `SHOT` line | **Verified at runtime** |
| Game resets its own aim fields every frame | `gameAim(angle=1.0 step=0.1 …)` after our writes | **Verified** |
| `bAIM` accept/overwrite | `game assist forced OFF -> readback` | **Untested** (the writes were reads before) |
| Head bone position | `bone arr0/arr1 data=0x0 count=0` | **Untested** — the socket path is new, ImportedBounds fallback was unreachable before |
| In-game `PrintString` ESP | `PrintString UFunction found` never logged | **Not working** — replaced by the file→overlay panel |
| ESP distances correct | user reports wrong targets | **Fixed** (class → `Engine.Character`), **needs retest** |
| Physx / FrameCount offsets | not found | **Unresolved** (low priority) |
| `kMgr.writeMem()` works on the game's own pid | dumper inits `kMgr` for `getpid()` | **Assumed** (needs the first device test) |

---

## 11. Current state & next steps

**Working today**
- APK inspection / engine detection / asset extraction
- Auto-inject → dumper runs → full SDK dump on device (verified: 58 268 objects, 181 packages,
  1983 classes, 1284 structs, 706 enums, 5160 functions)
- Floating overlay + console + ESP distance panel
- Aimbot: finds the local controller, resolves a target, computes a camera-relative rotation
  and **really writes it** (ControlRotation + every camera POV cache + the camera actors),
  and hands it to the engine's `SetControlRotation` on the game thread.

**Changed on 2026-10-20 (the "it never wrote anything" fix)**
Before this, the aimbot's writes were reads, so nothing moved. The new build adds real
writes, the UE pitch convention, the socket-based head and honest diagnostics. It has
**not been tested on the device yet**.

**Not yet achieved**
- The crosshair snapping to the head is still unverified on the device.

**Suggested next actions**
1. Install the new build (`app-debug.apk` from the `latest` release), enable the overlay,
   set `gameassist=0` + `engineaim=1` + `esp=1`, play, and read
   `/sdcard/aimbot_debug.log` (not the overlay console).
2. Look for `AIM: rot our=(…) ctrlNow=(…) … ours|GAME-OVERWROTE`:
   * `ours` → the game accepts our ControlRotation → the view should snap.
   * `GAME-OVERWROTE` → the game rewrites the rotation every frame; then use the game's own
     assist instead (`gameassist=1`, `engineaim=0`) — the game aims at `EnemyAIM`, which we
     point at our target.
3. Look for `AIM: pitch sign calibrated to ±1` — if it says `-1`, the build was wrong about
   UE's pitch sign and it has corrected itself.
4. Look for `AIM: socket[i] '…'` — if the enemy mesh has a head socket, the head is exact;
   if not, the aim falls back to the mesh bounds / capsule top.
5. Check `AIM: ProcessEvent hook rate = N calls/s` — if N is small (< 10/s), the game-thread
   path (`SetControlRotation`) can only run that often; raise the poll rate or find another
   per-frame hook.
6. Optional: implement `GetAllSocketNames()`-style socket enumeration and write every
   socket name to the trace so the correct head socket can be picked from the log.

**Do not forget**
- every build → `python3 check_build.py` → `python3 ping.py "<msg with APK link>"`
- never build locally; never write per-frame logs to `UEDump3r_trace.txt`
- **never use `vm_rpm_ptr()` to write** — it is read-only (§6.0)

