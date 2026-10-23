#!/usr/bin/env python3
"""
disasm_range.py
Disassemble a range of .text addresses from a stripped .so using capstone.
Usage: python3 disasm_range.py <path_to_libcocos2dcpp.so> <start_addr_hex> <end_addr_hex>
Example:
    python3 disasm_range.py lib/arm64-v8a/libcocos2dcpp.so 0x5ba500 0x5ba754
"""
from capstone import *
import struct, sys

def main(path, start_str, end_str):
    with open(path, 'rb') as f:
        data = f.read()

    start = int(start_str, 16)
    end = int(end_str, 16)

    # .text: addr==off for this .so
    code = data[start:end]
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)

    print(f"Disassembly 0x{start:x} - 0x{end:x} ({end-start} bytes)")
    print("=" * 70)
    for i in md.disasm(code, start):
        try:
            opstr = i.opstr
        except Exception:
            opstr = '<decode error>'
        print(f"0x{i.address:x}:\t{i.mnemonic}\t{opstr}")

if __name__ == '__main__':
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1], sys.argv[2], sys.argv[3])
