#!/usr/bin/env python3
"""
find_bl_targets.py
Find all BL (Branch with Link) calls to a set of target addresses in .text.
Usage: python3 find_bl_targets.py <path_to_libcocos2dcpp.so> <addr_hex1> [addr_hex2 ...]
Example:
    python3 find_bl_targets.py lib/arm64-v8a/libcocos2dcpp.so 0x5ba500 0x5b8758
"""
import struct, sys

def main(path, addr_strs):
    with open(path, 'rb') as f:
        data = f.read()

    TEXT_ADDR = 0x501000
    TEXT_OFF = 0x501000
    TEXT_SIZE = 0xb7d90c

    targets = {int(a, 16): a for a in addr_strs}

    def is_bl(inst):
        return (inst & 0xFC000000) == 0x90000000 and (inst & 0x02000000) != 0

    def decode_bl(inst):
        imm26 = inst & 0x3FFFFFF
        if imm26 & (1 << 25):
            imm26 -= (1 << 26)
        return imm26

    print(f"Scanning .text for BL calls to:")
    for a, s in targets.items():
        print(f"  0x{a:x} ({s})")
    print()

    matches = []
    for off in range(TEXT_OFF, TEXT_OFF + TEXT_SIZE - 3, 4):
        inst = struct.unpack('<I', data[off:off+4])[0]
        pc = TEXT_ADDR + (off - TEXT_OFF)
        if is_bl(inst):
            imm26 = decode_bl(inst)
            target = pc + (imm26 << 2)
            if target in targets:
                matches.append((pc, target, targets[target], hex(inst)))

    print(f"\nTotal BL calls to targets: {len(matches)}")
    for m in matches:
        print(f"  BL 0x{m[0]:x} -> 0x{m[1]:x} ({m[2]}) inst=0x{m[3]}")

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1], sys.argv[2:])
