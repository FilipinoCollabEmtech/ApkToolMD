#pragma once

#include "../UEGameProfile.hpp"

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>
#include <sys/mman.h>
#include <ctime>
#include <time.h>

using namespace UEMemory;

// Diagnostic sink: writes resolved addresses so a single device run shows
// exactly what each pattern found. Writes to the always-writable app data dir
// and (when storage is granted) to /sdcard.
static void sfg2_diag(const char *fmt, ...)
{
    char buf[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);

    std::string base = "/sdcard/Android/data/" + std::string(getprogname()) + "/files/UEDump3r_diag.txt";
    std::ofstream f(base.c_str(), std::ofstream::out | std::ofstream::app);
    if (f.is_open()) { f << buf << "\n"; f.close(); }

    std::ofstream g("/sdcard/UEDump3r_diag.txt", std::ofstream::out | std::ofstream::app);
    if (g.is_open()) { g << buf << "\n"; g.close(); }
}

class SFG2Profile : public IGameProfile
{
public:
    SFG2Profile() = default;

    bool ArchSupprted() const override
    {
        return GetUnrealELF().header().e_machine == EM_AARCH64;
    }

    std::string GetAppName() const override
    {
        return "Special Forces Group 2";
    }

    std::vector<std::string> GetAppIDs() const override
    {
        return {"com.ForgeGames.SpecialForcesGroup2"};
    }

    bool isUsingCasePreservingName() const override
    {
        return false;
    }

    bool IsUsingFNamePool() const override
    {
        return false;
    }

    bool isUsingOutlineNumberName() const override
    {
        return false;
    }

    uintptr_t GetGUObjectArrayPtr() const override
    {
        std::vector<std::pair<std::string, int>> idaPatterns = {
            {"B4 21 0C 40 B9 ? ? ? ? ? ? ? 91", 5},
            {"69 3e 40 b9 1f 01 09 6b ? ? ? 54 e1 03 13 aa ? ? ? ? f4 4f ? a9 ? ? ? ? ? ? ? 91", 0x18},
            {"91 E1 03 ? AA E0 03 08 AA E2 03 1F 2A", -7},
            {"0B ? 40 B9 7F 01 0A 6B AD ? 00 54 08 ? 40 F9", -8},
            {"2B ? 40 B9 7F 01 0A 6B CD ? 00 54 29 ? 40 F9", -8},
        };

        PATTERN_MAP_TYPE map_type = isEmulator() ? PATTERN_MAP_TYPE::ANY_R : PATTERN_MAP_TYPE::ANY_X;

        for (const auto &it : idaPatterns)
        {
            uintptr_t adrl = Arm64::Decode_ADRP_ADD(findIdaPattern(map_type, it.first, it.second));
            if (adrl != 0)
            {
                sfg2_diag("GUObjectArray = %p (pat %s)", (void *)adrl, it.first.c_str());
                return adrl;
            }
        }
        sfg2_diag("GUObjectArray = NOT FOUND");
        return 0;
    }

    uintptr_t GetFrameCount() const override
    {
        // Runtime probe: take two snapshots of every writable segment ~16ms
        // apart and find uint64 slots that incremented by exactly 1. The main
        // frame counter advances once per rendered frame; collect candidates,
        // log them, and return the first (if any).
        auto elf = GetUnrealELF();
        if (elf.isValid())
        {
            const size_t CH = 1 << 20;
            std::vector<uint8_t> a(CH), b(CH);
            std::vector<uintptr_t> cands;
            for (const auto &seg : elf.segments())
            {
                if (!(seg.protection & PROT_WRITE)) continue;
                uintptr_t base = seg.startAddress;
                uintptr_t end = seg.startAddress + seg.length;
                for (uintptr_t p0 = base; p0 < end; p0 += CH)
                {
                    size_t n = (p0 + CH > end) ? (size_t)(end - p0) : CH;
                    if (!vm_rpm_ptr((void *)p0, a.data(), n)) continue;
                    struct timespec ts; ts.tv_sec = 0; ts.tv_nsec = 16000000; nanosleep(&ts, nullptr);
                    if (!vm_rpm_ptr((void *)p0, b.data(), n)) continue;
                    for (size_t i = 0; i + 8 <= n; i += 8)
                    {
                        uint64_t v1 = *(uint64_t *)(a.data() + i);
                        uint64_t v2 = *(uint64_t *)(b.data() + i);
                        if (v1 == 0 || v1 > 0x7fffffff) continue;
                        if (v2 == v1 + 1)
                        {
                            cands.push_back(p0 + i);
                            if (cands.size() >= 16) break;
                        }
                    }
                    if (cands.size() >= 16) break;
                }
                if (cands.size() >= 16) break;
            }
            if (!cands.empty())
            {
                for (size_t k = 0; k < cands.size() && k < 8; k++)
                    sfg2_diag("FrameCount candidate[%zu] = %p", k, (void *)cands[k]);
                sfg2_diag("FrameCount = %p (runtime probe, %zu candidates)", (void *)cands[0], cands.size());
                return cands[0];
            }
            sfg2_diag("FrameCount = no runtime candidate");
        }
        // Pattern fallbacks (never matched on this binary)
        PATTERN_MAP_TYPE map_type = isEmulator() ? PATTERN_MAP_TYPE::ANY_R : PATTERN_MAP_TYPE::ANY_X;
        std::string ida_pattern = "C0 03 5F D6 ? ? ? ? 08 ? ? ? 00 01 40 F9 C0 03 5F D6 FD 7B BF A9";
        const int step = 4;
        auto FrameOff = Arm64::Decode_ADRP_LDR(findIdaPattern(map_type, ida_pattern, step));
        if (FrameOff != 0)
            return vm_rpm_ptr<uintptr_t>((void *)FrameOff);
        sfg2_diag("FrameCount = NOT FOUND");
        return 0;
    }

    uintptr_t GetMatrix() const override
    {
        std::vector<std::pair<std::string, int>> idaPatterns = {
            {"08 3D 40 F9 00 01 3F D6 E8 03 13 AA ? ? ? F9", 0x18},
            {"FD ? ? A9 28 ? ? F9 F3 ? ? F8 C0 03 5F D6", -0x1c},
            {"00 01 3F D6 E8 03 13 AA 60 ? 00 F9 ? ? ? A9", 14},
        };

        PATTERN_MAP_TYPE map_type = isEmulator() ? PATTERN_MAP_TYPE::ANY_R : PATTERN_MAP_TYPE::ANY_X;

        for (const auto &it : idaPatterns)
        {
            uintptr_t adrl = Arm64::Decode_ADRP_LDR(findIdaPattern(map_type, it.first, it.second));
            if (adrl != 0)
            {
                sfg2_diag("Matrix = %p (pat %s)", (void *)adrl, it.first.c_str());
                return adrl;
            }
        }
        sfg2_diag("Matrix = NOT FOUND");
        return 0;
    }

    uintptr_t GetPhysx() const override
    {
        // Runtime probe: walk .text ADRP+LDR pairs, resolve the global each
        // one addresses, and collect those whose stored value is a heap pointer
        // whose first qword is a vtable inside .text (i.e. a UObject/scene
        // global). Log candidates; only commit if a small, unambiguous set.
        auto elf = GetUnrealELF();
        if (elf.isValid())
        {
            uintptr_t textStart = 0, textEnd = 0, base = elf.base();
            for (const auto &seg : elf.segments())
            {
                if (seg.protection & PROT_EXEC)
                {
                    if (textStart == 0) textStart = seg.startAddress;
                    textEnd = seg.startAddress + seg.length;
                }
            }
            std::vector<uintptr_t> cands;
            for (const auto &seg : elf.segments())
            {
                if (!(seg.protection & PROT_EXEC)) continue;
                uintptr_t a = seg.startAddress;
                uintptr_t end = seg.startAddress + seg.length;
                for (; a + 4 <= end; a += 4)
                {
                    uint32_t ins = vm_rpm_ptr<uint32_t>((void *)a);
                    // ADRP: opcode 0x90000000, mask 0x9F000000
                    if ((ins & 0x9F000000u) != 0x90000000u) continue;
                    int rd = ins & 0x1F;
                    uint32_t immlo = (ins >> 29) & 0x3;
                    uint32_t immhi = (ins >> 5) & 0x7FFFF;
                    int64_t imm = ((int64_t)((immhi << 2) | immlo) << 12);
                    // sign-extend 33-bit? ADRP imm is 21-bit scaled; handle as signed
                    if (imm & (1LL << 32)) imm -= (1LL << 33);
                    uintptr_t adrpBase = (a & ~0xFFFULL) + (uintptr_t)imm;
                    // look ahead up to 8 insns for LDR (unsigned) using rd as base
                    for (int k = 1; k <= 8; k++)
                    {
                        if (a + k*4 + 4 > end) break;
                        uint32_t ldr = vm_rpm_ptr<uint32_t>((void *)(a + k*4));
                        // LDR (immediate, unsigned): 0xF9400000 mask 0xFFC00000, opc=10 (64-bit)
                        if ((ldr & 0xFFC00000u) != 0xF9400000u) continue;
                        int rn = (ldr >> 5) & 0x1F;
                        if (rn != rd) continue;
                        uint32_t uimm12 = (ldr >> 10) & 0xFFF;
                        uintptr_t glob = adrpBase + (uintptr_t)uimm12 * 8;
                        // global must be in a writable segment
                        bool wseg = false;
                        for (const auto &s2 : elf.segments())
                            if ((s2.protection & PROT_WRITE) && glob >= s2.startAddress && glob < s2.startAddress + s2.length) { wseg = true; break; }
                        if (!wseg) break;
                        uint64_t val = vm_rpm_ptr<uint64_t>((void *)glob);
                        if (val < 0x10000ULL || val > 0x7fffffffffffULL) break;
                        // outside ELF (heap) and first qword is a vtable in .text
                        if (val >= base && val < base + 0x20000000ULL) break;
                        uint64_t vt = vm_rpm_ptr<uint64_t>((void *)val);
                        if (vt < textStart || vt >= textEnd) break;
                        // dedup
                        bool dup = false;
                        for (auto c : cands) if (c == glob) { dup = true; break; }
                        if (!dup) cands.push_back(glob);
                        break;
                    }
                    if (cands.size() >= 32) break;
                }
                if (cands.size() >= 32) break;
            }
            if (!cands.empty())
            {
                for (size_t k = 0; k < cands.size() && k < 8; k++)
                    sfg2_diag("Physx candidate[%zu] = %p", k, (void *)cands[k]);
                sfg2_diag("Physx = %zu candidates found (no unique pick; leaving 0)", cands.size());
            }
            else
            {
                sfg2_diag("Physx = no runtime candidate (likely accessed via UWorld)");
            }
        }
        // Pattern fallbacks
        std::vector<std::pair<std::string, int>> idaPatterns = {
            {"E1 ? ? ? 40 00 40 BD F4 03 02 AA", 0x28},
            {"48 ? ? ? F3 03 04 AA F5 03 03 2A", -0xc},
            {"? ? ? B0 00 03 3F D6", 0x0},
        };

        PATTERN_MAP_TYPE map_type = isEmulator() ? PATTERN_MAP_TYPE::ANY_R : PATTERN_MAP_TYPE::ANY_X;

        for (const auto &it : idaPatterns)
        {
            uintptr_t adrl = Arm64::Decode_ADRP_LDR(findIdaPattern(map_type, it.first, it.second), 8);
            if (adrl != 0)
            {
                uintptr_t val = vm_rpm_ptr<uintptr_t>((void *)adrl);
                sfg2_diag("Physx = %p (pat %s)", (void *)val, it.first.c_str());
                return val;
            }
        }
        sfg2_diag("Physx = NOT FOUND");
        return 0;
    }

    uintptr_t GetNamesPtr() const override
    {
        uintptr_t scanned = scanGNames();
        if (scanned != 0)
            return scanned;

        std::vector<std::pair<std::string, int>> idaPatterns = {
            {"F4 4F 01 A9 FD 7B 02 A9 FD 83 00 91 ? ? ? ? A8 02 ? 39", 0x24},
            {"C8 00 00 37 ? ? ? ? ? ? ? 91 ? ? ? 97", 4},
        };

        PATTERN_MAP_TYPE map_type = isEmulator() ? PATTERN_MAP_TYPE::ANY_R : PATTERN_MAP_TYPE::ANY_X;

        for (const auto &it : idaPatterns)
        {
            uintptr_t adrl = Arm64::Decode_ADRP_ADD(findIdaPattern(map_type, it.first, it.second));
            if (adrl != 0)
            {
                sfg2_diag("NamesPtr = %p (pat fallback)", (void *)adrl);
                return adrl;
            }
        }
        sfg2_diag("NamesPtr = NOT FOUND");
        return 0;
    }

private:
    bool isGNamesSlot(uintptr_t slot) const
    {
        uint64_t g = vm_rpm_ptr<uint64_t>((void *)slot);
        if (g < 0x10000ull || g > 0x10000000000ull)
            return false;
        uint64_t chunk0 = vm_rpm_ptr<uint64_t>((void *)g);
        if (chunk0 < 0x10000ull || chunk0 > 0x10000000000ull)
            return false;
        uint64_t entry0 = vm_rpm_ptr<uint64_t>((void *)chunk0);
        if (entry0 < 0x10000ull || entry0 > 0x10000000000ull)
            return false;
        char c[8] = {0};
        if (!vm_rpm_ptr((void *)(entry0 + 12), c, 4))
            return false;
        return c[0] == 'N' && c[1] == 'o' && c[2] == 'n' && c[3] == 'e';
    }

    uintptr_t scanGNames() const
    {
        auto elf = GetUnrealELF();
        if (!elf.isValid())
            return 0;
        const size_t CH = 1 << 20;
        std::vector<uint8_t> buf(CH);
        for (const auto &seg : elf.segments())
        {
            if (!(seg.protection & PROT_WRITE))
                continue;
            uintptr_t a = seg.startAddress;
            uintptr_t end = uintptr_t(seg.startAddress) + seg.length;
            for (; a < end; a += CH)
            {
                size_t n = CH;
                if (a + n > end)
                    n = (size_t)(end - a);
                if (!vm_rpm_ptr((void *)a, buf.data(), n))
                    continue;
                for (size_t i = 0; i + 8 <= n; i += 8)
                {
                    uint64_t v;
                    memcpy(&v, buf.data() + i, 8);
                    if (v < 0x10000ull || v > 0x10000000000ull)
                        continue;
                    if (isGNamesSlot(a + i))
                    {
                        sfg2_diag("NamesPtr = %p (structural scan)", (void *)(a + i));
                        return a + i;
                    }
                }
            }
        }
        return 0;
    }

public:
    UE_Offsets *GetOffsets() const override
    {
        static UE_Offsets offsets = UE_DefaultOffsets::UE4_22(isUsingCasePreservingName());
        return &offsets;
    }
};
