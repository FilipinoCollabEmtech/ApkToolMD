#!/usr/bin/env python3
"""
elf_sections.py
Parse and print ELF section headers from a stripped .so (ARM64).
Tells you where .text, .rodata, .dynsym live so you know where to search.
Usage: python3 elf_sections.py <path_to_libcocos2dcpp.so>
"""
import struct, sys

def main(path):
    with open(path, 'rb') as f:
        data = f.read()

    # ELF header
    e_shoff   = struct.unpack('<Q', data[0x28:0x30])[0]
    e_shentsize = struct.unpack('<H', data[0x3a:0x3c])[0]
    e_shnum   = struct.unpack('<H', data[0x3c:0x3e])[0]
    e_shstrndx= struct.unpack('<H', data[0x3e:0x40])[0]

    # Section header string table offset
    sh_off = e_shoff + e_shstrndx * e_shentsize
    shstr_offset = struct.unpack('<Q', data[sh_off+0x18:sh_off+0x20])[0]
    shstr_size = struct.unpack('<Q', data[sh_off+0x20:sh_off+0x28])[0]
    shstr_data = data[shstr_offset:shstr_offset+shstr_size]

    def get_name(off):
        end = data.index(b'\x00', shstr_offset + off)
        return data[shstr_offset+off:end].decode()

    print(f"{'Name':30s} {'Type':6s} {'Addr':10s} {'Offset':10s} {'Size':10s}")
    print('-' * 70)
    for i in range(e_shnum):
        o = e_shoff + i * e_shentsize
        name_off = struct.unpack('<I', data[o:o+4])[0]
        typ  = struct.unpack('<I', data[o+4:o+8])[0]
        addr = struct.unpack('<Q', data[o+16:o+24])[0]
        off  = struct.unpack('<Q', data[o+24:o+32])[0]
        sz   = struct.unpack('<Q', data[o+32:o+40])[0]
        print(f"{get_name(name_off):30s} {typ:6d} 0x{addr:08x} 0x{off:08x} 0x{sz:08x}")

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1])
