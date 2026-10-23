#!/usr/bin/env python3
"""
dynsym_find.py
Parse .dynsym (dynamic symbol table) from a stripped .so and search for
symbol names containing a substring.
Usage: python3 dynsym_find.py <path_to_libcocos2dcpp.so> <substring1> [substring2 ...]
Example:
    python3 dynsym_find.py lib/arm64-v8a/libcocos2dcpp.so CCPlayerDataObject WeaponOffset EnemyAdded
"""
import struct, sys

def main(path, substrings):
    with open(path, 'rb') as f:
        data = f.read()

    # .dynsym section (hardcoded from elf_sections.py output)
    dynsym_off = 0x42130
    dynsym_size = 0xcbb08
    dynstr_off = 0x1412568  # .dynstr section offset
    ESIZE = 24

    nentries = dynsym_size // ESIZE

    def get_str(off):
        if off >= len(data):
            return ''
        end = data.index(b'\x00', off)
        return data[off:end].decode('utf-8', 'replace')

    print(f"{'Symbol Name':50s} {'Addr':10s} {'Size':8s} {'Shndx':6s}")
    print('-' * 80)
    found = 0
    for i in range(nentries):
        o = dynsym_off + i * ESIZE
        st_name = struct.unpack('<I', data[o:o+4])[0]
        st_info = data[o+4]
        st_shndx = struct.unpack('<H', data[o+6:o+8])[0]
        st_value = struct.unpack('<Q', data[o+8:o+16])[0]
        st_size = struct.unpack('<Q', data[o+16:o+24])[0]
        s = get_str(dynstr_off + st_name)
        if any(sub in s for sub in substrings):
            print(f"{s:50s} 0x{st_value:08x} 0x{st_size:06x} {st_shndx}")
            found += 1
    print(f'\nTotal matches: {found}')

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1], sys.argv[2:])
