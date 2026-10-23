#!/usr/bin/env python3
"""
Server side analysis of the stripped libUE4.so (runs on a Colab runtime).

What it does
------------
UE4 registers a native class' UProperties in code.  The property NAME lives in
.rodata, the property OFFSET is a compile time immediate next to the
registration code, and the name is reached through a relocated pointer in
.data.rel.ro.

So for every property name we:
  1. find the string in .rodata
  2. find the relocation in .data.rel.ro that points at it   (readelf -r)
  3. find the code that loads THAT data slot  (ADRP + ADD/LDR)
  4. dump the code around it - the property offset is the immediate that
     follows (mov/str with the offset)

Usage:  python3 colab_analyze.py <libUE4.so> [PropertyName ...]
"""

import re
import subprocess
import sys

try:
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
    HAVE_CAPSTONE = True
except Exception:
    HAVE_CAPSTONE = False


def sections(path):
    out = subprocess.check_output(["readelf", "-S", "-W", path], text=True)
    secs = []
    for line in out.splitlines():
        m = re.match(r"\s*\[\s*\d+\]\s+(\S+)\s+(\S+)\s+([0-9a-f]+)\s+([0-9a-f]+)\s+([0-9a-f]+)",
                     line)
        if m:
            name, typ, addr, off, size = m.groups()
            secs.append((name, int(addr, 16), int(off, 16), int(size, 16)))
    return secs


def find_strings(data, names):
    out = {}
    for n in names:
        b = n.encode()
        i = data.find(b)
        if i >= 0:
            out[n] = i
    return out


def relocs_to(path, targets):
    """addresses in .data.rel.ro that point at the given file offsets"""
    out = subprocess.check_output(["readelf", "-r", "-W", path], text=True)
    hits = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 3:
            continue
        try:
            off = int(parts[0], 16)
        except ValueError:
            continue
        # the addend is the last field
        for tva, tname in targets.items():
            if tva in line and str(hex(tva)) in line.lower():
                hits.setdefault(tname, []).append(off)
    return hits


def code_refs(data, text_off, text_size, text_addr, data_va, extra=6):
    """find ADRP(+ADD/LDR) pairs in .text that reference data_va"""
    refs = []
    words = []
    for off in range(text_off, text_off + text_size, 4):
        w = int.from_bytes(data[off:off + 4], "little")
        words.append((off, w))
    for i, (off, w) in enumerate(words):
        if (w & 0x9F000000) != 0x90000000:
            continue
        immlo = (w >> 29) & 3
        immhi = (w >> 5) & 0x7FFFF
        imm = (immhi << 2) | immlo
        if imm & (1 << 20):
            imm -= 1 << 21
        page = (off & ~0xFFF) + (imm << 12)
        rd = w & 0x1F
        for k in range(1, extra + 1):
            if i + k >= len(words):
                break
            o2, w2 = words[i + k]
            # ADD Xd, Xn, #imm12
            if (w2 & 0x7F800000) == 0x11000000 and (w2 & 0x1F) == rd:
                imm12 = (w2 >> 10) & 0xFFF
                sh = (w2 >> 22) & 1
                if page + (imm12 << (12 if sh else 0)) == data_va:
                    refs.append((off, o2))
                    break
            # LDR Xt, [Xn, #imm]  (the data slot address itself)
            if (w2 & 0xFFC00000) == 0xF9400000:
                rn = (w2 >> 5) & 0x1F
                if rn == rd:
                    imm12 = (w2 >> 10) & 0xFFF
                    if page + (imm12 * 8) == data_va:
                        refs.append((off, o2))
                        break
    return refs


def main():
    path = sys.argv[1]
    names = sys.argv[2:] or ["WeaponSpread", "ManSpread", "ZoomSpread",
                             "CurrentRecoil", "CrosshairRecoil", "PlayerSpread"]
    data = open(path, "rb").read()
    secs = sections(path)
    text = [s for s in secs if s[0] == ".text"][0]
    _, text_addr, text_off, text_size = text
    print("sections: %d, .text at 0x%x size 0x%x" % (len(secs), text_addr, text_size))

    strs = find_strings(data, names)
    print("strings found:", {k: hex(v) for k, v in strs.items()})

    if not HAVE_CAPSTONE:
        print("!! capstone is not installed - pip install capstone")
        return

    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    for name, sva in strs.items():
        print("\n================ %s (string @0x%x) ================" % (name, sva))
        # 1) the relocated pointer to the string
        for line in subprocess.check_output(
                ["readelf", "-r", "-W", path], text=True).splitlines():
            if hex(sva)[2:] in line.lower() or str(sva) in line:
                print("  reloc:", line.strip())
        # 2) code that references the string page directly
        refs = code_refs(data, text_off, text_size, text_addr, sva)
        for off, o2 in refs[:4]:
            print("  --- code ref at 0x%x ---" % off)
            for ins in md.disasm(data[off - 32:off + 96], off - 32):
                mark = "  <<<" if ins.address in (off, o2) else ""
                print("    0x%08x  %-8s %s %s%s" % (ins.address, ins.mnemonic,
                                                     ins.op_str, "", mark))


if __name__ == "__main__":
    main()
