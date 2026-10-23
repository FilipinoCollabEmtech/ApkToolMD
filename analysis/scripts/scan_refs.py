#!/usr/bin/env python3
"""
scan_refs.py
Scan .text for instructions that reference target addresses (string/global
addresses). Looks for ADRP+ADD/LDR pairs and literal-pool loads.
Usage: python3 scan_refs.py <path_to_libcocos2dcpp.so> <addr_hex1> [addr_hex2 ...]
Example:
    python3 scan_refs.py lib/arm64-v8a/libcocos2dcpp.so 0x107f59c 0x1081990
"""
import struct, sys

def main(path, addr_strs):
    with open(path, 'rb') as f:
        data = f.read()

    TEXT_ADDR = 0x501000
    TEXT_OFF = 0x501000
    TEXT_SIZE = 0xb7d90c

    targets = {int(a, 16): a for a in addr_strs}

    def is_adrp(inst):
        return (inst & 0xFC000000) == 0x90000000 and ((inst >> 29) & 3) == 0

    def decode_adrp(inst):
        immhi = (inst >> 7) & 0x3FFFFF
        immlo = (inst >> 5) & 3
        imm = (immhi << 2) | immlo
        if imm & (1 << 23):
            imm -= (1 << 24)
        return imm

    def is_ldr_lit(inst):
        return (inst & 0xFFE003E0) == 0xF94003E0

    def decode_imm12(inst):
        return (inst >> 12) & 0xFFF

    print(f"Scanning .text (0x{TEXT_ADDR:x}-0x{TEXT_ADDR+TEXT_SIZE:x}) for references to:")
    for a, s in targets.items():
        print(f"  0x{a:x} ({s})")
    print()

    matches = []
    for off in range(TEXT_OFF, TEXT_OFF + TEXT_SIZE - 3, 4):
        inst = struct.unpack('<I', data[off:off+4])[0]
        pc = TEXT_ADDR + (off - TEXT_OFF)

        # ADRP -> loads a page address
        if is_adrp(inst):
            imm = decode_adrp(inst)
            pc_page = pc & ~0xFFF
            target_page = pc_page + (imm << 12)
            if target_page in targets:
                matches.append((pc, target_page, targets[target_page], 'ADRP', hex(inst)))

        # LDR literal -> loads exact address
        if is_ldr_lit(inst):
            lit_addr = (pc & ~0xFFF) + (decode_imm12(inst) * 4)
            if lit_addr in targets:
                matches.append((pc, lit_addr, targets[lit_addr], 'LDR lit', hex(inst)))

    print(f"\nTotal references found: {len(matches)}")
    for m in matches:
        print(f"  0x{m[0]:x}: {m[3]} -> 0x{m[1]:x} ({m[2]}) inst=0x{m[4]}")

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1], sys.argv[2:])
