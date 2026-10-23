#!/usr/bin/env python3
"""
A small AArch64 (ARM64) disassembler + helper for the stripped libUE4.so.

Why: the game binary is stripped (no symbols) and there is no objdump /
capstone in this environment, so to answer questions like "how does the
game compute the bullet spread" we have to look at the raw code ourselves.

What it does:
  * decode the common ARM64 encodings into a readable form
  * search a binary for instructions that touch a given immediate
    (e.g. the property offset 0x4C8 -> "ldr s0, [x0, #0x4c8]")
  * find a string in the binary and dump the code around it (the UE4
    property registration code lives right next to the property name)

Usage:
    python3 tools/disasm.py disasm <bin> <addr> [count]
    python3 tools/disasm.py imm    <bin> <imm> [limit]
    python3 tools/disasm.py str    <bin> <text> [before] [after]
    python3 tools/disasm.py sect   <bin> <addr> <size>
"""

import struct
import sys

# ---------------------------------------------------------------- registers

XREG = ["x%d" % i for i in range(31)] + ["xzr", "sp"]
WREG = ["w%d" % i for i in range(31)] + ["wzr", "wsp"]
VREG = ["v%d" % i for i in range(32)] + ["v31d", "v31d_0", "v31d_1"]


def x(n):
    return XREG[n]


def w(n):
    return WREG[n]


def v(n):
    return VREG[n & 31]


# ------------------------------------------------------------- bit helpers


def bits(val, hi, lo):
    return (val >> lo) & ((1 << (hi - lo + 1)) - 1)


def bit(val, n):
    return (val >> n) & 1


def sign_extend(val, nbits):
    if val & (1 << (nbits - 1)):
        val -= (1 << nbits)
    return val


def sx(value, hi, lo):
    """sign extended bit field"""
    n = hi - lo + 1
    return sign_extend(bits(value, hi, lo), n)


def u(value, hi, lo):
    return bits(value, hi, lo)


# --------------------------------------------------------------- cond codes

COND = {
    0x0: "eq", 0x1: "ne", 0x2: "cs", 0x3: "cc", 0x4: "mi", 0x5: "pl",
    0x6: "vs", 0x7: "vc", 0x8: "hi", 0x9: "ls", 0xa: "ge", 0xb: "lt",
    0xc: "gt", 0xd: "le", 0xe: "al",
}


def decode_condition(cond):
    if cond & 1:
        return COND.get(cond >> 1, "?")
    return COND.get(cond, "?")


# --------------------------------------------------------------- decoders

SHIFT_AMOUNT = [0, 2, 4, 8, 16, 32, 64, 128]


def decode_add_sub_imm(ins, sf, op, s, shift, imm, rn, rd):
    opname = "add" if op == 0 else "sub"
    if s:
        opname += "s"
    if shift:
        opname += " lsl #%d" % (imm << 12)
    return "%s %s, %s, #%d" % (opname, w(rd) if sf == 0 else x(rd),
                               w(rn) if sf == 0 else x(rn), imm)


def decode_logical_imm(ins, sf, opc, n, immr, imms, rn, rd):
    opnames = {0: "and", 1: "orr", 2: "eor", 3: "ands"}
    name = opnames[opc]
    if opc == 1 and rn == 31:
        # MOV (to register) alias
        return "mov %s, #%d" % (w(rd) if sf == 0 else x(rd), (immr << 6) | imms)
    imm = (immr << 6) | imms
    # decode the bitmask immediate
    if n == 0:
        return "%s %s, %s, #%d" % (name, w(rd) if sf == 0 else x(rd),
                                    w(rn) if sf == 0 else x(rn), imm)
    size = 1 << n
    levels = size - 1
    s = imms & levels
    r = immr & levels
    if s == levels:
        return "%s %s, %s, #%d" % (name, w(rd) if sf == 0 else x(rd),
                                    w(rn) if sf == 0 else x(rn), imm)
    esize = 1 << n
    welem = 0
    while welem < 64:
        if welem >= 32 and esize == 1:
            pass
        welem += esize
    # simple approximation of the immediate value
    return "%s %s, %s, #0x%x" % (name, w(rd) if sf == 0 else x(rd),
                                  w(rn) if sf == 0 else x(rn), imm)


def decode_add_sub_reg(ins, sf, op, s, rm, option, imm3, rn, rd):
    opname = "add" if op == 0 else "sub"
    if s:
        opname += "s"
    shift = ""
    if imm3 == 1:
        if option == 2:
            shift = ", lsl #6"
        elif option == 1:
            shift = ", lsr #6"
        elif option == 4:
            shift = ", asr #6"
        elif option == 6:
            shift = ", ror #6"
    return "%s %s, %s, %s%s" % (opname, w(rd) if sf == 0 else x(rd),
                                 w(rn) if sf == 0 else x(rn),
                                 w(rm) if sf == 0 else x(rm), shift)


def decode_movz_movk(ins, sf, opc, hw, imm16, rd):
    if opc == 2:
        return "mov %s, #-%d" % (w(rd) if sf == 0 else x(rd), imm16 << (hw * 16))
    if opc == 3:
        return "movk %s, #%d, lsl #%d" % (w(rd) if sf == 0 else x(rd),
                                           imm16, hw * 16)
    return "mov %s, #%d" % (w(rd) if sf == 0 else x(rd), imm16 << (hw * 16))


def decode_add_sub_immshift(ins, sf, op, s, sh, imm12, rn, rd):
    opname = "add" if op == 0 else "sub"
    if s:
        opname += "s"
    if sh:
        opname += " lsl #12"
    return "%s %s, %s, #%d" % (opname, w(rd) if sf == 0 else x(rd),
                               w(rn) if sf == 0 else x(rn), imm12)


def decode_ldst(ins, size, opc, vbit, imm9, rn, rt):
    """LDR/STR (immediate, 9 bit scaled)"""
    if vbit:
        # SIMD
        if opc & 1:
            return "str %s, [%s, #%d]" % (v(rt), x(rn), imm9)
        return "ldr %s, [%s, #%d]" % (v(rt), x(rn), imm9)
    names = {0: "strb", 1: "strh", 2: "ldr", 3: "ldrsw",
             4: "str", 5: "ldur", 6: "ldr", 7: "ldrsw"}
    if opc == 2:
        if size == 3:
            name = "ldr x%d" % rt
        elif size == 2:
            name = "ldr w%d" % rt
        else:
            name = "ldrb w%d" % rt
        return "%s, [%s, #%d]" % (name, x(rn), imm9)
    if opc == 0:
        if size == 3:
            name = "str x%d" % rt
        elif size == 2:
            name = "str w%d" % rt
        else:
            name = "strb w%d" % rt
        return "%s, [%s, #%d]" % (name, x(rn), imm9)
    return "ldst(0x%03x) %s, [%s, #%d]" % (opc, x(rt), x(rn), imm9)


def decode_ldr_imm12(ins, size, opc, imm12, rn, rt, vbit):
    if vbit:
        if opc & 1:
            return "str %s, [%s, #%d]" % (v(rt), x(rn), imm12 * 16), 4
        return "ldr %s, [%s, #%d]" % (v(rt), x(rn), imm12 * 16), 4
    if opc == 2:
        name = "ldr x%d" % rt if size == 3 else "ldr w%d" % rt
    elif opc == 0:
        name = "str x%d" % rt if size == 3 else "str w%d" % rt
    else:
        return "ldst12 opc=%d size=%d %s, [%s, #%d]" % (opc, size, x(rt), x(rn), imm12 * 16)
    return "%s, [%s, #%d]" % (name, x(rn), imm12 * 16)


def decode(ins, addr=0):
    """decode one 32 bit instruction word -> (text, length)"""
    if ins == 0xd503201f:
        return "nop", 4
    if ins == 0xd65f03c0:
        return "ret", 4
    if (ins & 0xfffffc1f) == 0xd65f0000:
        return "ret x%d" % (ins >> 5 & 0x1f), 4

    # branches
    if (ins & 0xfc000000) == 0x14000000:
        off = sign_extend(ins & 0x3ffffff, 26) * 4
        if ins & 0x80000000:
            return "bl #0x%x" % (addr + off), 4
        return "b #0x%x" % (addr + off), 4
    if (ins & 0xfe000000) == 0x54000000:   # B.cond
        off = sign_extend(bits(ins, 23, 5), 19) * 4
        return "b.%s #0x%x" % (decode_condition(bits(ins, 3, 0)), addr + off), 4
    if (ins & 0x7e000000) == 0x35000000:
        imm = bits(ins, 20, 5)
        if ins & 0x10:
            return "cbnz w%d, #0x%x" % (ins & 0x1f, addr + imm * 4), 4
        return "cbz w%d, #0x%x" % (ins & 0x1f, addr + imm * 4), 4
    if (ins & 0x7e000000) == 0x37000000:
        imm = bits(ins, 20, 5)
        if ins & 0x10:
            return "tbz w%d, #%d, #0x%x" % (ins & 0x1f, bits(ins, 4, 0), addr + imm * 4), 4
        return "tbnz w%d, #%d, #0x%x" % (ins & 0x1f, bits(ins, 4, 0), addr + imm * 4), 4
    if (ins & 0xfffffc1f) == 0xd61f0000:
        return "br x%d" % (ins >> 5 & 0x1f), 4
    if (ins & 0xfffffc1f) == 0xd63f0000:
        return "blr x%d" % (ins >> 5 & 0x1f), 4

    # add/sub immediate (with shift)
    if (ins & 0x1f000000) == 0x11000000:
        sf = bit(ins, 31)
        op = bit(ins, 30)
        s = bit(ins, 29)
        sh = bit(ins, 22)
        imm12 = bits(ins, 21, 10)
        rn = bits(ins, 9, 5)
        rd = bits(ins, 4, 0)
        return decode_add_sub_immshift(ins, sf, op, s, sh, imm12, rn, rd), 4

    # add/sub immediate (extended register)
    if (ins & 0x1f000000) == 0x12000000:
        sf = bit(ins, 31)
        op = bit(ins, 30)
        s = bit(ins, 29)
        rm = bits(ins, 20, 16)
        opt = bits(ins, 15, 13)
        imm3 = bits(ins, 12, 10)
        rn = bits(ins, 9, 5)
        rd = bits(ins, 4, 0)
        ext = {0: "uxtb", 1: "uxth", 2: "uxtw", 3: "uxtx",
               4: "sxtb", 5: "sxth", 6: "sxtw", 7: "sxtx"}[opt]
        txt = "%s%s %s, %s, %s, %s #%d" % (
            "add" if op == 0 else "sub", "s" if s else "",
            w(rd) if sf == 0 else x(rd), w(rn) if sf == 0 else x(rn),
            w(rm) if sf == 0 else x(rm), ext, imm3)
        return txt, 4

    # add/sub (shifted register)
    if (ins & 0x1f000000) == 0x0b000000:
        sf = bit(ins, 31)
        op = bit(ins, 30)
        s = bit(ins, 29)
        rm = bits(ins, 20, 16)
        option = bits(ins, 15, 13)
        imm3 = bits(ins, 12, 10)
        rn = bits(ins, 9, 5)
        rd = bits(ins, 4, 0)
        return decode_add_sub_reg(ins, sf, op, s, rm, option, imm3, rn, rd), 4

    # add/sub (with carry)
    if (ins & 0x1fe00000) == 0x1a000000:
        sf = bit(ins, 31)
        op = bit(ins, 30)
        rm = bits(ins, 20, 16)
        rn = bits(ins, 9, 5)
        rd = bits(ins, 4, 0)
        return "%s%s %s, %s, %s" % ("adc" if op else "addc", "s" if bit(ins, 28) else "",
                                   w(rd) if sf == 0 else x(rd),
                                   w(rn) if sf == 0 else x(rn),
                                   w(rm) if sf == 0 else x(rm)), 4

    # logical (shifted register)
    if (ins & 0x1f000000) == 0x0a000000:
        sf = bit(ins, 31)
        opc = bits(ins, 30, 29)
        names = {0: "and", 1: "orr", 2: "eor", 3: "ands"}
        rm = bits(ins, 20, 16)
        rn = bits(ins, 9, 5)
        rd = bits(ins, 4, 0)
        if opc == 1 and rn == 31:
            return "mov %s, %s" % (w(rd) if sf == 0 else x(rd),
                                   w(rm) if sf == 0 else x(rm)), 4
        return "%s %s, %s, %s" % (names[opc], w(rd) if sf == 0 else x(rd),
                                  w(rn) if sf == 0 else x(rn),
                                  w(rm) if sf == 0 else x(rm)), 4

    # move wide immediate
    if (ins & 0x1f800000) == 0x12800000:
        sf = bit(ins, 31)
        opc = bits(ins, 30, 29)
        hw = bits(ins, 22, 21)
        imm16 = bits(ins, 20, 5)
        rd = bits(ins, 4, 0)
        return decode_movz_movk(ins, sf, opc, hw, imm16, rd), 4

    # bitfield / extract handled roughly
    if (ins & 0x1f800000) == 0x13000000:
        return "bfm/bitfield 0x%08x" % ins, 4

    # logical immediate
    if (ins & 0x1f800000) == 0x12000000:
        pass  # handled above (add/sub ext)

    if (ins & 0x1f800000) == 0x12000000:
        pass

    # conditional compare (ccmp/csel)
    if (ins & 0x1fe00000) == 0x1a800000:
        return "ccmp/csel 0x%08x" % ins, 4

    # data processing (3 source)
    if (ins & 0x1f000000) == 0x1b000000:
        sf = bit(ins, 31)
        op31 = bits(ins, 30, 21)
        rm = bits(ins, 20, 16)
        o0 = bit(ins, 15)
        ra = bits(ins, 14, 10)
        rn = bits(ins, 9, 5)
        rd = bits(ins, 4, 0)
        if o0 and op31 in (0, 1):
            return "madd %s, %s, %s, %s" % (w(rd) if sf == 0 else x(rd),
                                              w(rn) if sf == 0 else x(rn),
                                              w(rm) if sf == 0 else x(rm),
                                              w(ra) if sf == 0 else x(ra)), 4
        return "data3 0x%08x" % ins, 4

    # load/store register offset
    if (ins & 0x3b000000) == 0x38000000:
        size = bits(ins, 31, 30)
        opc = bits(ins, 23, 22)
        rn = bits(ins, 9, 5)
        rt = bits(ins, 4, 0)
        rm = bits(ins, 20, 16)
        return "ldr/str reg x%d, [x%d, x%d]" % (rt, rn, rm), 4

    # load/store pair
    if (ins & 0x3a000000) == 0x28000000:
        return "ldp/stp 0x%08x" % ins, 4

    # load literal
    if (ins & 0x3b000000) == 0x18000000:
        off = sign_extend(bits(ins, 23, 5), 19) * 4
        return "ldr x%d, #0x%x" % (bits(ins, 4, 0), addr + off), 4

    # SIMD/FP load/store (unsigned immediate, 9/12 bit)
    if (ins & 0x3f000000) == 0x3d000000:
        size = bits(ins, 31, 30)
        opc = bits(ins, 23, 22)
        rn = bits(ins, 9, 5)
        rt = bits(ins, 4, 0)
        imm12 = bits(ins, 21, 10)
        scale = 4 << size
        kind = {0: "s", 1: "d", 2: "q"}[size]
        name = "ldr" if (opc & 1) else "str"
        return "%s %s%d, [%s, #%d]" % (name, kind, rt, x(rn), imm12 * scale), 4
    if (ins & 0x3f000000) == 0x3c000000:
        size = bits(ins, 31, 30)
        opc = bits(ins, 23, 22)
        rn = bits(ins, 9, 5)
        rt = bits(ins, 4, 0)
        imm12 = bits(ins, 21, 10)
        scale = 4 << size
        kind = {0: "s", 1: "d", 2: "q"}[size]
        name = "ldr" if (opc & 1) else "str"
        return "%s %s%d, [%s, #%d]" % (name, kind, rt, x(rn), imm12 * scale), 4

    # LDR/STR (unsigned immediate)
    if (ins & 0x3b000000) == 0x39000000:
        size = bits(ins, 31, 30)
        opc = bits(ins, 23, 22)
        rn = bits(ins, 9, 5)
        rt = bits(ins, 4, 0)
        imm12 = bits(ins, 21, 10)
        return decode_ldr_imm12(ins, size, opc, imm12, rn, rt, 0), 4

    # LDR/STR (immediate, 9 bit, scaled)
    if (ins & 0x3e000000) == 0x34000000:
        size = bits(ins, 31, 30)
        opc = bits(ins, 23, 22)
        vbit = bit(ins, 26)
        imm9 = sign_extend(bits(ins, 20, 12), 9)
        rn = bits(ins, 9, 5)
        rt = bits(ins, 4, 0)
        if vbit:
            if opc & 1:
                return "str %s, [%s, #%d]" % (v(rt), x(rn), imm9 * 16)
            return "ldr %s, [%s, #%d]" % (v(rt), x(rn), imm9 * 16)
        return decode_ldst(ins, size, opc, vbit, imm9, rn, rt), 4

    # FP data processing
    if (ins & 0x5f000000) == 0x1e000000:
        return "fp 0x%08x" % ins, 4
    if (ins & 0x5f000000) == 0x1e200000:
        return "fcmp 0x%08x" % ins, 4
    if (ins & 0x5f000000) == 0x1e400000:
        return "fcvt 0x%08x" % ins, 4
    if (ins & 0x5f000000) == 0x1e600000:
        return "fmov/fcmp 0x%08x" % ins, 4
    if (ins & 0x5f000000) == 0x1e800000:
        return "fcvt 0x%08x" % ins, 4

    # unconditional branch (register)
    if (ins & 0xfffffc1f) == 0xd61f0000:
        return "br x%d" % (ins >> 5 & 0x1f), 4

    # system
    if (ins & 0xffc00000) == 0xd5000000:
        return "sys/msr 0x%08x" % ins, 4
    if (ins & 0xffe0001f) == 0xd4000001:
        return "svc #%d" % bits(ins, 20, 5), 4

    # compare and branch / test
    if (ins & 0x7e000000) == 0x34000000:
        pass

    return "unk 0x%08x" % ins, 4


def disasm(data, base, count=1, vma=0):
    out = []
    off = 0
    for i in range(count):
        if off + 4 > len(data):
            break
        ins = struct.unpack_from("<I", data, off)[0]
        res = decode(ins, vma + off if vma else base + off)
        text, size = res if isinstance(res, tuple) else (res, 4)
        out.append("%016x: %08x  %s" % (base + off, ins, text))
        off += size
    return out


# --------------------------------------------------------------- searching

def find_string(data, needle):
    """all offsets of a byte string"""
    res = []
    start = 0
    n = needle.encode() if isinstance(needle, str) else needle
    while True:
        i = data.find(n, start)
        if i < 0:
            break
        res.append(i)
        start = i + 1
    return res


def find_imm(data, imm, limit=60):
    """find instructions that reference the immediate (any encoding)"""
    hits = []
    for off in range(0, len(data) - 3, 4):
        ins = struct.unpack_from("<I", data, off)[0]
        text, _ = decode(ins, off)
        if ("#%d" % imm) in text or ("#0x%x" % imm) in text:
            hits.append((off, text))
            if len(hits) >= limit:
                break
    return hits


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return
    cmd = sys.argv[1]
    path = sys.argv[2]
    with open(path, "rb") as f:
        data = f.read()

    if cmd == "disasm":
        addr = int(sys.argv[3], 0)
        count = int(sys.argv[4]) if len(sys.argv) > 4 else 32
        # the address is a file offset for our purposes (the .so is not
        # mapped in this tool)
        for line in disasm(data[addr:addr + count * 4], addr, count):
            print(line)
    elif cmd == "imm":
        imm = int(sys.argv[3], 0)
        limit = int(sys.argv[4]) if len(sys.argv) > 4 else 60
        for off, text in find_imm(data, imm, limit):
            print("%016x  %s" % (off, text))
    elif cmd == "str":
        needle = sys.argv[3]
        before = int(sys.argv[4]) if len(sys.argv) > 4 else 64
        after = int(sys.argv[5]) if len(sys.argv) > 5 else 96
        for off in find_string(data, needle):
            print("=== %r at file offset 0x%x ===" % (needle, off))
            start = max(0, off - before)
            for line in disasm(data[start:off + after], start, (before + after) // 4):
                print(line)
    elif cmd == "sect":
        addr = int(sys.argv[3], 0)
        size = int(sys.argv[4], 0)
        for line in disasm(data[addr:addr + size], addr, size // 4):
            print(line)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()


# ------------------------------------------------------------- ELF parsing

def elf_sections(data):
    """[(name, addr, offset, size)] from the ELF header"""
    if data[:4] != b"\x7fELF":
        return []
    is64 = data[4] == 2
    little = data[5] == 1
    end = "<" if little else ">"
    if is64:
        e_shoff = struct.unpack_from(end + "Q", data, 0x28)[0]
        e_shentsize = struct.unpack_from(end + "H", data, 0x3A)[0]
        e_shnum = struct.unpack_from(end + "H", data, 0x3C)[0]
        e_shstrndx = struct.unpack_from(end + "H", data, 0x3E)[0]
    else:
        e_shoff = struct.unpack_from(end + "I", data, 0x20)[0]
        e_shentsize = struct.unpack_from(end + "H", data, 0x2E)[0]
        e_shnum = struct.unpack_from(end + "H", data, 0x30)[0]
        e_shstrndx = struct.unpack_from(end + "H", data, 0x32)[0]
    secs = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        if is64:
            nameoff, stype, flags, addr, offset, size = struct.unpack_from(end + "IIQQQQ", data, off)
        else:
            nameoff, stype, flags, addr, offset, size = struct.unpack_from(end + "IIIIII", data, off)
        secs.append((nameoff, stype, addr, offset, size))
    strtab_off = secs[e_shstrndx][3]
    out = []
    for nameoff, stype, addr, offset, size in secs:
        endn = data.index(b"\0", strtab_off + nameoff)
        name = data[strtab_off + nameoff:endn].decode("ascii", "replace")
        out.append((name, addr, offset, size))
    return out
