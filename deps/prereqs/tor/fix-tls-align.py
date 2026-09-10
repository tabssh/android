#!/usr/bin/env python3
##@Version 202609100000-git
"""fix-tls-align.py — patch an underaligned ELF PT_TLS segment in place.

Android's Bionic dynamic linker rejects an executable whose PT_TLS program
header has p_align < 32 (32-bit ABIs) or < 64 (64-bit ABIs), failing to even
start the process with:

    executable's TLS segment is underaligned: alignment is 8, needs to be
    at least 64 for ARM64 Bionic

The static openssl/libevent/tor build in build-android.sh can still emit a
PT_TLS segment aligned to only 8 bytes, because the NDK r26d toolchain's
static libc/CRT objects for API level below 29 predate the upstream LLVM
fix (D53906/D61824) that overaligns .tbss for Bionic's reserved TCB
slots. Confirmed against the termux-packages issue tracker, report
number 8273. Our build targets API 24 (TabSSH's minSdk), so it is
always affected.

Patching p_align in place (rather than relinking) only tightens an
alignment constraint metadata field; it does not move, resize, or
renumber any segment, and does not touch the actual TLS data or the
compiler-emitted, thread-pointer-relative offsets used to access it.
This is safe specifically because Bionic's ARM/AArch64 TLS layout is
"variant 1" (local-exec offsets are fixed relative to the thread
pointer, independent of p_align, which Bionic's linker only consults as
a static "does this fit past our reserved TCB" safety check). This does
NOT hold for every libc/arch: an isolated test against glibc/x86_64
(which uses "variant 2" TLS, computing each module's static-TLS offset
from p_align at load time) showed the same one-field patch silently
corrupting TLS-initialized data — irrelevant to this Bionic/ARM64
target, but proof the technique is not universally safe and must not be
reused outside it.

This exact single-field fix — patch PT_TLS's p_align up to 32 (32-bit) /
64 (64-bit) bytes when smaller, touch nothing else — is what
termux-elf-cleaner (github.com/termux/termux-elf-cleaner,
elf-cleaner.cpp's process_elf) does in production, gated the same way on
api_level below 29, and has shipped that way across countless real
Bionic/ARM64 devices via Termux's package builds for years. Verified by
reading its source directly rather than assuming. Adapted here (rather
than shelling out to a compiled C++ tool, to avoid an extra toolchain
dependency) from the older, buggy
https://github.com/Lzhiyong/termux-ndk (patches/align_fix.py), whose
64-bit branch wrote only the low 2 bytes of the 8-byte Elf64_Xword
p_align field instead of the full field — fixed here.

Usage: fix-tls-align.py <path-to-elf-binary>
"""

import struct
import sys

PT_TLS = 7
MIN_ALIGN_32 = 32
MIN_ALIGN_64 = 64


def fix(path: str) -> None:
    with open(path, "r+b") as f:
        ident = f.read(16)
        if ident[0:4] != b"\x7fELF":
            raise SystemExit(f"{path}: not an ELF file")

        ei_class = ident[4]
        if ei_class == 1:
            # 32-bit: e_phoff @0x1c (u32), e_phentsize @0x2a (u16), e_phnum @0x2c (u16).
            f.seek(0x1C)
            phoff = struct.unpack("<I", f.read(4))[0]
            f.seek(0x2A)
            phentsize, phnum = struct.unpack("<HH", f.read(4))
            # Elf32_Phdr: p_type(u32) p_offset(u32) p_vaddr(u32) p_paddr(u32)
            # p_filesz(u32) p_memsz(u32) p_flags(u32) p_align(u32) — align at +28.
            align_offset_in_phdr = 28
            align_size = 4
            align_fmt = "<I"
            min_align = MIN_ALIGN_32
        elif ei_class == 2:
            # 64-bit: e_phoff @0x20 (u64), e_phentsize @0x36 (u16), e_phnum @0x38 (u16).
            f.seek(0x20)
            phoff = struct.unpack("<Q", f.read(8))[0]
            f.seek(0x36)
            phentsize, phnum = struct.unpack("<HH", f.read(4))
            # Elf64_Phdr: p_type(u32) p_flags(u32) p_offset(u64) p_vaddr(u64)
            # p_paddr(u64) p_filesz(u64) p_memsz(u64) p_align(u64) — align at +48.
            align_offset_in_phdr = 48
            align_size = 8
            align_fmt = "<Q"
            min_align = MIN_ALIGN_64
        else:
            raise SystemExit(f"{path}: unknown ELF class {ei_class}")

        patched = False
        found_tls = False
        for i in range(phnum):
            phdr_start = phoff + i * phentsize
            f.seek(phdr_start)
            p_type = struct.unpack("<I", f.read(4))[0]
            if p_type != PT_TLS:
                continue
            found_tls = True
            align_field_offset = phdr_start + align_offset_in_phdr
            f.seek(align_field_offset)
            align = struct.unpack(align_fmt, f.read(align_size))[0]
            print(f"{path}: found PT_TLS with p_align={align}")
            if align < min_align:
                print(f"{path}: underaligned, patching p_align to {min_align}")
                f.seek(align_field_offset)
                f.write(struct.pack(align_fmt, min_align))
                patched = True
            break

        if not found_tls:
            print(f"{path}: no PT_TLS segment present — nothing to patch")
        elif not patched:
            print(f"{path}: no patch needed")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <elf-binary>", file=sys.stderr)
        raise SystemExit(1)
    fix(sys.argv[1])
