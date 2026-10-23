# ApkToolMD Mini Militia (libcocos2dcpp.so) Analysis
# Location: /workspace/ApkToolMD/analysis/

# ============================================================================
# 1. FILES IN THIS FOLDER
# ============================================================================

# scripts/   -> Python analysis scripts (ELF section parsing, .dynsym parsing,
#               .text scanning for ADRP/BL/LDR references, capstone disassembly)
# dumps/     -> Raw dumps copied from the device (/storage/emulated/0/ApkToolMD/)
# reports/   -> Human-readable findings (this README + function-address reports)

# ============================================================================
# 2. TARGET FILES (on the device)
# ============================================================================

# Mini Militia APK (patched-signed): /storage/emulated/0/ApkToolMD/patched-signed.apk
# libcocos2dcpp.so inside it: lib/arm64-v8a/libcocos2dcpp.so (22MB, STRIPPED)
# Cocos2d dumper log: /storage/emulated/0/ApkToolMD/cocos_dump.log
# Dumped xxtea key: /storage/emulated/0/ApkToolMD/cocos_dump/xxtea_key.txt
# Built APK (ApkToolMD app): /storage/emulated/0/ApkToolMD/app-debug.apk

# ============================================================================
# 3. KEY FINDINGS (libcocos2dcpp.so is STRIPPED but .dynsym still has names)
# ============================================================================

# .text section: addr 0x501000, off 0x501000, size 0xb7d90c
# .rodata section: addr 0x107e910, off 0x107e910, size 0xc4808 (strings live here)
# .dynsym: 34,763 entries (symbol names present even though stripped)

# STRING ADDRESSES (in .rodata):
#   WeaponOffset            -> 0x107f59c   <-- AIM LOGIC (aim offset property)
#   WeaponOffsetMethod      -> 0x107f5a9
#   CCPlayerDataObject      -> 0x1081990   <-- player data (pos/health/team)
#   EnemyAdded              -> 0x1081c50
#   SendEnemyPosition       -> 0x1081c68
#   InputAimType            -> 0x1081fe6   <-- aim input handling

# FUNCTION ADDRESSES (from .dynsym):
#   CoOpStage::getClosestPlayerLocation(cpVect)  -> 0x5ba500   (0x254 bytes)
#   CoOpStage::onEnemyAdded(CCObject*)           -> 0x5b8758   (0x128 bytes)
#   CoOpStage::sendEnemyPositionData(float)      -> 0x5b93b4   (0xab0 bytes)
#   SurvivalStage::onEnemyAdded(CCObject*)       -> 0x91c8ec   (0x128 bytes)
#   CCPlayerDataObject::C1Ev (ctor)              -> 0x5b3210   (0x80 bytes)
#   CCPlayerDataObject::init()                   -> 0x5b3764   (0x4c bytes)
#   CCNode::getPositionX()                       -> 0xc22d04   (0x18 bytes)
#   CCNode::getPositionY()                       -> 0xc22d1c   (0x18 bytes)
#   CCNode::getPosition()                        -> 0xc22be8   (0x18 bytes)

# KEY INSIGHT:
#   getClosestPlayerLocation (0x5ba500) uses fcvt/fcmp (float compare) to
#   find the nearest player. This is the core of "aim at nearest player".

# ============================================================================
# 4. HOW TO RUN THE SCRIPTS
# ============================================================================

# Prerequisites:
#   pip3 install --break-system-packages capstone
#   (already installed in this environment)

# Scripts are in analysis/scripts/
# Extract libcocos2dcpp.so from the patched APK first:
#   cd /tmp/opencode
#   unzip -o -q /storage/emulated/0/ApkToolMD/patched-signed.apk \
#     "lib/arm64-v8a/libcocos2dcpp.so"

# Then run any script from analysis/scripts/:
#   python3 analysis/scripts/elf_sections.py lib/arm64-v8a/libcocos2dcpp.so
#   python3 analysis/scripts/dynsym_find.py lib/arm64-v8a/libcocos2dcpp.so \
#       CCPlayerDataObject WeaponOffset EnemyAdded

# ============================================================================
# 5. PATCH STRATEGY (aim at nearest player)
# ============================================================================

# Method A: Hook the aim function (reads WeaponOffset property)
#   - Find code that reads string at 0x107f59c ("WeaponOffset")
#   - Hook it, compute nearest enemy via getClosestPlayerLocation (0x5ba500)
#   - Write nearest enemy position to WeaponOffset instead of mouse/crosshair

# Method B: Hook getClosestPlayerLocation directly (0x5ba500)
#   - Modify which player is considered "closest"
#   - E.g., always return nearest enemy regardless of line-of-sight

# Hook technique: use "unpatch-and-call-in-place" (same as libcocos_dumper.so):
#   memcpy(pe, &g_origInsn, 4);    // restore original instruction
#   o_ProcessEvent(obj, func, params);  // original runs at real address
#   memcpy(pe, &g_branchInsn, 4);      // re-install the branch hook
#
#   This avoids breaking PC-relative ADRP/ADR/B instructions in the prologue.

# ============================================================================
# 6. IMPORTANT WARNINGS
# ============================================================================

# - libcocos2dcpp.so is FULLY STRIPPED (no .text symbol names).
#   .dynsym still has C++ mangled names (how we found the addresses above).
# - The cocos_dump.log only shows file-decryption hooks (CCFileUtils, xxtea).
#   Game logic (aim/player/enemy) is native C++ in the .so, NOT in decryptable
#   scripts. That's why cocos_dump only contained xxtea_key.txt.
# - Mini Militia rejects re-signed packages (ships stamp-cert-sha256). APK
#   patching won't work. Use the ptrace injector or DT_NEEDED path instead.
# - Device is rooted but PRoot terminal can't see /data. Root injection must
#   be driven by the app itself (Inject dumper button in Cocos2D profile).

# ============================================================================
# 7. QUICK REFERENCE COMMANDS
# ============================================================================

# Extract .so from patched APK:
#   unzip -o -q /storage/emulated/0/ApkToolMD/patched-signed.apk \
#     "lib/arm64-v8a/libcocos2dcpp.so" -d /tmp/opencode/

# Dump all dynamic symbols:
#   nm -D lib/arm64-v8a/libcocos2dcpp.so | head -50

# Find strings in .so:
#   strings -t x lib/arm64-v8a/libcocos2dcpp.so | grep -i "aim\|enemy\|player\|weapon"

# Disassemble getClosestPlayerLocation:
#   python3 analysis/scripts/disasm_range.py lib/arm64-v8a/libcocos2dcpp.so \
#       0x5ba500 0x5ba754
