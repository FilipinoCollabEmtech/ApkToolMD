#pragma once

#include "../GameProfile.h"

// Generic / experimental profile.
// Attempts the common PUBG-style ARM64 patterns as a best-effort auto-scan.
// Works only on UE4 games whose codegen matches; may fail on protected/encrypted builds.

class GenericUE4Profile : public IGameProfile
{
public:
    GenericUE4Profile() = default;

    virtual bool ArchSupprted() const override
    {
        auto e_machine = GetUE4ELF().header().e_machine;
        return e_machine == EM_AARCH64 || e_machine == EM_ARM;
    }

    std::string GetAppName() const override
    {
        return "Generic UE4";
    }

    std::vector<std::string> GetAppIDs() const override
    {
        return {}; // matches any game via fallback in library.cpp
    }

    bool IsUsingFNamePool() const override
    {
        return false;
    }

    uintptr_t GetGUObjectArrayPtr() const override
    {
        auto e_machine = GetUE4ELF().header().e_machine;
        if (e_machine == EM_AARCH64)
        {
            PATTERN_MAP_TYPE map_type = isEmulator() ? PATTERN_MAP_TYPE::ANY_R : PATTERN_MAP_TYPE::ANY_X;
            std::string ida_pattern = "12 40 B9 ? 3E 40 B9 ? ? ? 6B ? ? ? 54 ? ? ? ? ? ? ? 91";
            int step = 0xF;
            uintptr_t insn_address = findIdaPattern(map_type, ida_pattern, step);
            if (insn_address == 0)
            {
                LOGE("Generic GUObjectArray pattern failed.");
                return 0;
            }
            int64_t adrp_pc_rel = 0;
            int32_t add_imm12 = 0;
            uintptr_t page_off = INSN_PAGE_OFFSET(insn_address);
            uint32_t adrp_insn = 0, add_insn = 0;
            kMgr.readMem((insn_address), &adrp_insn, sizeof(uint32_t));
            kMgr.readMem((insn_address + sizeof(uint32_t)), &add_insn, sizeof(uint32_t));
            if (adrp_insn == 0 || add_insn == 0) return 0;
            if (!KittyArm64::decode_adr_imm(adrp_insn, &adrp_pc_rel) || adrp_pc_rel == 0) return 0;
            add_imm12 = KittyArm64::decode_addsub_imm(add_insn);
            return (page_off + adrp_pc_rel + add_imm12);
        }
        return 0;
    }

    uintptr_t GetNamesPtr() const override
    {
        auto e_machine = GetUE4ELF().header().e_machine;
        if (e_machine == EM_AARCH64)
        {
            PATTERN_MAP_TYPE map_type = isEmulator() ? PATTERN_MAP_TYPE::ANY_R : PATTERN_MAP_TYPE::ANY_X;
            std::string ida_pattern = "81 80 52 ? ? ? ? ? 03 1F 2A";
            int step = 0x17;
            uintptr_t insn_address = findIdaPattern(map_type, ida_pattern, step);
            if (insn_address == 0)
            {
                LOGE("Generic GNames pattern failed.");
                return 0;
            }
            int64_t adrp_pc_rel = 0;
            int32_t add_imm12 = 0, ldrb_imm12 = 0;
            uintptr_t page_off = INSN_PAGE_OFFSET(insn_address);
            uint32_t adrp_insn = 0, add_insn = 0, ldrb_insn = 0;
            kMgr.readMem((insn_address), &adrp_insn, sizeof(uint32_t));
            kMgr.readMem((insn_address + 4), &add_insn, sizeof(uint32_t));
            kMgr.readMem((insn_address + 8), &ldrb_insn, sizeof(uint32_t));
            if (adrp_insn == 0 || add_insn == 0 || ldrb_insn == 0) return 0;
            if (!KittyArm64::decode_adr_imm(adrp_insn, &adrp_pc_rel) || adrp_pc_rel == 0) return 0;
            add_imm12 = KittyArm64::decode_addsub_imm(add_insn);
            if (!KittyArm64::decode_ldrstr_uimm(ldrb_insn, &ldrb_imm12)) return 0;
            uintptr_t enc_names = (page_off + adrp_pc_rel + add_imm12 + ldrb_imm12 - 4);
            if (enc_names == 0) return 0;
            int32_t in;
            uintptr_t out[16];
            kMgr.readMem(enc_names, &in, sizeof(int32_t));
            in = (in - 100) / 3;
            kMgr.readMem(enc_names + 8, &out[in - 1], sizeof(uintptr_t));
            while (in - 2 >= 0)
            {
                kMgr.readMem(out[in - 1], &out[in - 2], sizeof(uintptr_t));
                --in;
            }
            uintptr_t ret = 0;
            kMgr.readMem(out[0], &ret, sizeof(uintptr_t));
            return ret;
        }
        return 0;
    }

    UE_Offsets *GetOffsets() const override
    {
        // reuse 64-bit PUBG-style offsets
        struct
        {
            uint16 Stride = 0;
            uint16 FNamePoolBlocks = 0;
            uint16 FNameMaxSize = 0xff;
            struct { uint16 Number = 4; } FName;
            struct { uint16 Name = 0xC; } FNameEntry;
            struct { uint16 Info = 0; uint16 WideBit = 0; uint16 LenBit = 0; uint16 HeaderSize = 0; } FNameEntry23;
            struct { uint16 ObjObjects = 0x10; } FUObjectArray;
            struct { uint16 NumElements = 0xC; } TUObjectArray;
            struct { uint16 Size = 0x18; } FUObjectItem;
            struct { uint16 ObjectFlags = 0x8; uint16 InternalIndex = 0xC; uint16 ClassPrivate = 0x10; uint16 NamePrivate = 0x18; uint16 OuterPrivate = 0x20; } UObject;
            struct { uint16 Next = 0x28; } UField;
            struct { uint16 SuperStruct = 0x30; uint16 Children = 0x38; uint16 ChildProperties = 0; uint16 PropertiesSize = 0x40; } UStruct;
            struct { uint16 Names = 0x40; } UEnum;
            struct { uint16 EFunctionFlags = 0x88; uint16 NumParams = 0x8C; uint16 ParamSize = 0x8E; uint16 Func = 0xB0; } UFunction;
            struct { uint16 ClassPrivate = 0; uint16 Next = 0; uint16 NamePrivate = 0; uint16 FlagsPrivate = 0; } FField;
            struct { uint16 ArrayDim = 0; uint16 ElementSize = 0; uint16 PropertyFlags = 0; uint16 Offset_Internal = 0; uint16 Size = 0; } FProperty;
            struct { uint16 ArrayDim = 0x30; uint16 ElementSize = 0x34; uint16 PropertyFlags = 0x38; uint16 Offset_Internal = 0x44; uint16 Size = 0x70; } UProperty;
        } static profile64;
        static_assert(sizeof(profile64) == sizeof(UE_Offsets));
#ifdef __LP64__
        return (UE_Offsets *)&profile64;
#else
        return nullptr;
#endif
    }
};
