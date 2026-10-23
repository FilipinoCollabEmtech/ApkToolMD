# ApkToolMD Mini Militia Analysis Report
# Target: com.appsomniacs.mmc (Mini Militia Classic)
# File: lib/arm64-v8a/libcocos2dcpp.so (22,671,113 bytes, STRIPPED)
# Device: Android 8.1, SDK 27, arm64-v8a

# ============================================================================
# 1. WHAT WE FOUND
# ============================================================================

# The cocos2d runtime dumper (libcocos_dumper.so) successfully loaded into
# Mini Militia and hooked:
#   - CCFileUtils::getFileData           (0x7e07be82d4)
#   - CCFileUtils::getFileDataFromZip    (0x7e07be8638)
#   - xxtea_decrypt (via dlsym)          (0x7e078be4b8)
#
# It dumped: xxtea_key.txt (32 bytes)
#   Key: 7374643a3a617000736f756e640073
#
# cocos_dump.log does NOT contain aim/player/enemy logic because that logic is
# compiled NATIVE C++ inside the stripped libcocos2dcpp.so — it is NOT in
# decryptable script assets. The dumper only sees file decryption.

# ============================================================================
# 2. KEY STRINGS (from .rodata, via strings -t x)
# ============================================================================

#   String            Address    Purpose
#   ----------------- --------   -----------------------------------------
#   WeaponOffset      0x107f59c  AIM OFFSET — the aim logic property
#   WeaponOffsetMethod 0x107f5a9 Aim offset method
#   CCPlayerDataObject 0x1081990 Player data (position, health, team)
#   EnemyAdded        0x1081c50  Enemy event
#   SendEnemyPosition 0x1081c68  Send enemy position (network)
#   InputAimType      0x1081fe6  Aim input type

# ============================================================================
# 3. KEY FUNCTIONS (from .dynsym — survives stripping)
# ============================================================================

#   Function                          Address   Size   Purpose
#   --------------------------------- ------- ------ -------------------------------------------
#   CoOpStage::getClosestPlayerLocation 0x5ba500 0x254 FIND NEAREST PLAYER (fcvt/fcmp distance compare)
#   CoOpStage::onEnemyAdded             0x5b8758 0x128 Enemy added tracking
#   CoOpStage::sendEnemyPositionData    0x5b93b4 0xab0 Send enemy position over network
#   SurvivalStage::onEnemyAdded         0x91c8ec  0x128 Enemy added (survival mode)
#   CCPlayerDataObject::ctor            0x5b3210  0x80 Player data creation
#   CCPlayerDataObject::init            0x5b3764  0x4c Player data init
#   CCNode::getPositionX                0xc22d04  0x18 Get X position
#   CCNode::getPositionY                0xc22d1c  0x18 Get Y position
#   CCNode::getPosition                 0xc22be8  0x18 Get full position

# ============================================================================
# 4. getClosestPlayerLocation DISASSEMBLY (0x5ba500 - 0x5ba754)
# ============================================================================

# Confirmed as nearest-player finder:
#   - fcvt at 0x5ba5b0, 0x5ba6ac  (convert distance to float)
#   - fcmp at 0x5ba6b0           (compare distances)
#   - b.pl  at 0x5ba6b4          (keep closer target)
#   - Multiple ADRP+LDR pairs    (loads global addresses)
#   - Multiple BL calls          (calls engine functions)
#
# Logic: iterates CCPlayerDataObject instances, computes distance from local
# player to each, keeps the nearest via float compare.

# ============================================================================
# 5. PATCH STRATEGY: AIM AT NEAREST PLAYER
# ============================================================================

# METHOD A: Hook the aim function
#   1. Find code that reads "WeaponOffset" string (0x107f59c)
#   2. Hook it with inline hook (unpatch-and-call-in-place, see cocos_dumper.cpp)
#   3. In hook: call getClosestPlayerLocation(0x5ba500) to get nearest enemy
#   4. Write nearest enemy position to WeaponOffset instead of mouse aim
#   5. Call original aim function

# METHOD B: Hook getClosestPlayerLocation directly (0x5ba500)
#   - Modify which player is returned as "closest"
#   - E.g., filter for enemies only, skip teammates, prioritize headshots

# HOOK TECHNIQUE (from libcocos_dumper.cpp):
#   The dumper uses inline hooks that:
#     1. Save original instruction bytes
#     2. On entry: restore original, call original function at real address
#     3. After: re-install branch hook
#   This avoids breaking PC-relative ADRP/ADR/B in prologues.

# ============================================================================
# 6. IMPORTANT WARNINGS
# ============================================================================

# - libcocos2dcpp.so is FULLY STRIPPED — no .text symbol names.
#   .dynsym still has C++ mangled names (how we found addresses above).
# - cocos_dump.log only shows file-decryption hooks. Game logic (aim/player/
#   enemy) is native C++ in the stripped .so — NOT decryptable scripts.
#   That's why cocos_dump only contained xxtea_key.txt.
# - Mini Militia rejects re-signed packages (ships stamp-cert-sha256).
#   APK patching won't work. Use ptrace injector or DT_NEEDED path instead.
# - Device is rooted but PRoot terminal can't see /data. Root injection must
#   be driven by the app itself (Inject dumper button in Cocos2D profile).
# - To find where WeaponOffset is read: scan .text for ADRP->0x107f59c +
#   ADD/LDR, then disassemble that function.

# ============================================================================
# 7. SCRIPTS (analysis/scripts/)
# ============================================================================

#   elf_sections.py     - Parse ELF section headers (.text, .rodata, .dynsym)
#   dynsym_find.py      - Search .dynsym for symbol names
#   disasm_range.py     - Disassemble an address range with capstone
#   scan_refs.py        - Scan .text for ADRP/LDR references to addresses
#   find_bl_targets.py  - Find BL calls to target addresses

# Example workflow:
#   1. python3 scripts/elf_sections.py lib/arm64-v8a/libcocos2dcpp.so
#   2. python3 scripts/dynsym_find.py lib/... WeaponOffset CCPlayerDataObject EnemyAdded
#   3. python3 scripts/disasm_range.py lib/... 0x5ba500 0x5ba754   (getClosestPlayerLocation)
#   4. python3 scripts/scan_refs.py lib/... 0x107f59c             (find WeaponOffset refs)
#   5. python3 scripts/find_bl_targets.py lib/... 0x5ba500        (find who calls nearest-player)

# ============================================================================
# 8. DEVICE FILES
# ============================================================================

#   /storage/emulated/0/ApkToolMD/app-debug.apk          (ApkToolMD app, 9.2MB)
#   /storage/emulated/0/ApkToolMD/patched-signed.apk     (Mini Militia patched)
#   /storage/emulated/0/ApkToolMD/cocos_dump.log         (dumper log)
#   /storage/emulated/0/ApkToolMD/cocos_dump/xxtea_key.txt (xxtea key: 32 bytes)
#   /storage/emulated/0/ApkToolMD/libcocos_dumper.so     (runtime dumper .so)
#   /storage/emulated/0/ApkToolMD/ptrace_injector        (ptrace injector binary)
#   /storage/emulated/0/ApkToolMD/inject.sh              (inject script)
