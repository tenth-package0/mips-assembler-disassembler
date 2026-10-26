# Module 5: MIPS Assembler and Disassembler

Repository: https://github.com/tenth-package0/mips-assembler-disassembler

This Java project reads FizzBuzz from an assembly **file**, writes readable
machine-code digits and raw binary bytes, and reconstructs assembly from either.
The original program prints FizzBuzz for integers 1 through 100, one per line.

## Run

Requirements: JDK 8 or later (`java` and `javac`). No Java libraries are required.
From this project directory:

```sh
javac Assembler.java Image.java Disassembler.java Tests.java
java Assembler fizzbuzz.asm
java Disassembler fizzbuzz.mhc-bin reconstructed.asm
java Assembler reconstructed.asm roundtrip
java Disassembler fizzbuzz.mhc-ascii reconstructed-ascii.asm
java Assembler reconstructed-ascii.asm roundtrip-ascii
java Tests
```

`Assembler` accepts a source filename and an optional output prefix. Without a
prefix, it removes the source filename extension. `Disassembler` requires the
input image and output assembly filename. Existing output files are replaced.
Missing files, invalid assembly, unsupported instructions, and malformed images
produce an error on stderr and exit code 1. The output directory must exist.

For automated comparison of both image formats, install Python 3.8+ and run:

```sh
python3 verify.py
```

That verifies both binary and ASCII round trips and the submitted fixtures.
For the additional execution test, download MARS 4.5 from its official release:
https://github.com/dpetersanderson/MARS/releases/tag/v.4.5.1

```sh
python3 verify.py --mars /path/to/Mars4_5.jar
java -jar /path/to/Mars4_5.jar nc sm fizzbuzz.asm
java -jar /path/to/Mars4_5.jar nc sm reconstructed.asm
```

Use MARS's default memory configuration and **disable delayed branching**.
The CLI commands above omit `db`, so delayed branching is disabled. In the GUI,
uncheck Settings > Delayed branching. Start at `main`. The project assumes the
entry instruction is the first instruction at 0x00400000; it is not a linker.
The .mhc files are custom containers, not directly runnable MARS or ELF files.
Run the reconstructed .asm in MARS.

## Supported features

- Real instructions: addi, addiu, add, addu, sub, ori, lui, div (two registers),
  mfhi, sll, beq, bne, j, jal, jr, lw, sw, syscall (no code operand).
- Pseudoinstructions: li, la, move, rem (immediate divisor), nop.
- Directives: .text, .data, .globl, .global, .asciiz, .byte.
- Labels: case sensitive; letters/underscore first, then letters/digits/underscore.
  Multiple labels may precede a statement. Forward references are supported.
- Named and numeric registers, including $s8; decimal, hexadecimal, binary,
  signed literals, and ASCII character literals; comments beginning with #.
- Strings support ASCII and escapes \n, \r, \t, \0, \\, \', and \".
  .asciiz takes one string and appends a zero byte. .byte takes comma-separated
  unsigned values 0 through 255. Data is packed without implicit alignment.
- Only fixed/default segment addresses are supported. Unlisted directives and
  instructions are rejected. Arithmetic expressions and relocations are omitted.

Pass 1 allocates data, expands pseudoinstructions, and records label addresses.
Pass 2 encodes instructions after all label addresses are known. Signed branch
immediates use (target - (PC + 4)) / 4. Jumps store target >> 2 in 26 bits and
require the same upper four address bits as PC + 4.

`li` expands to addiu for signed 16-bit values, ori for other unsigned 16-bit
values, or lui/ori for larger values. `la` always emits lui/ori, keeping label
addresses stable. `move` emits addu; `nop` emits sll $zero, $zero, 0. `rem` loads
the immediate into $at, uses signed div, then mfhi. It reserves $at and rejects
zero divisors. It does not reproduce MARS's extra runtime division checks.
This is sufficient for the positive FizzBuzz operands and divisors 3 and 5.

The disassembler emits real instructions and numeric registers. Labels are
regenerated as L_<eight hexadecimal address digits>. Every instruction address
gets a label. String data becomes .byte directives, preserving every byte.
It verifies the generated assembly by reassembling it before writing the file;
unsupported opcodes and noncanonical reserved bits are rejected.

## MHC1 file layout and byte order

All integer fields and instruction words use **big-endian** order. Both formats
contain the same container bytes; the ASCII form simply represents each byte
as eight readable binary digits. There is no separate metadata sidecar.

| File offset | Length | Content |
| --- | --- | --- |
| 0 | 4 | Magic/version: ASCII MHC1 = 4D 48 43 31 |
| 4 | 4 | Text start address: 00 40 00 00 = 0x00400000 |
| 8 | 4 | Text length in bytes (180 for FizzBuzz) |
| 12 | 4 | Data start address: 10 01 00 00 = 0x10010000 |
| 16 | 4 | Data length in bytes (10 for FizzBuzz) |
| 20 | text length | Machine instructions, four bytes each |
| 20 + text length | data length | Raw data bytes, no padding |

The header distinguishes executable instructions from string data. It records
virtual addresses; the large address gap is not stored in the file. The header
is metadata, not machine instructions. There are 45 encoded instructions
(180 bytes) and 10 data bytes: `Fizz\0Buzz\0`. The binary file is 210 bytes
including its 20-byte header.

`mhc-ascii` contains only 0/1 characters, spaces, and LF newlines. Bytes are
separated by spaces, with four bytes per line (the final line may be shorter).
An instruction needs 32 digit characters, plus formatting. `mhc-bin` stores
four actual bytes per instruction. A textual '0' is byte 0x30 and a textual '1'
is byte 0x31; neither represents a single stored bit in the ASCII file.
ASCII header/data bytes follow the same representation. A hex dump in the PDF
is a readable view of the binary file, not the binary file's actual text.

## Files and validation

- Assembler.java, Disassembler.java, Image.java: complete implementation.
- fizzbuzz.asm: existing FizzBuzz extracted from the supplied Java DEMO.
- fizzbuzz.mhc-ascii and fizzbuzz.mhc-bin: complete generated images.
- reconstructed.asm: canonical source reconstructed from the binary image;
  reconstruction from the ASCII image is identical.
- Tests.java: 29 encoding, parsing, validation, and round-trip tests.
- verify.py: reproducible checks, including optional external MARS execution.
- test-evidence.txt: captured results, including successful MARS execution.
- fizzbuzz-output.txt: the 100-line output captured from MARS.
- Module-5-Submission.pdf: Parts 1/2, documentation, source listings, images,
  reconstructed source, and evidence. Project files are also embedded as PDF
  attachments so the exact binary bytes are included in the single PDF.

All 29 tests passed using OpenJDK 17. Both formats reconstructed successfully,
and reassembly reproduced both output files byte for byte. MARS 4.5 independently
executed the original and both reconstructions; each matched all 100 expected
lines. Without --mars, verify.py explicitly reports that execution was skipped.

## Sources and collaboration

- Course assignment: Module 5, Assembler and Disassembler; assigned reading:
  ZyBooks Chapter 4. The chapter text was not supplied or consulted here.
- User-provided `Assembler (14) (1).java`: starting parser, instruction encoder,
  and existing FizzBuzz DEMO. FizzBuzz statements were extracted into a file;
  its saved/restored return address and control flow were retained.
- OpenAI Codex assisted with file I/O, the MHC1 format, disassembler, additional
  instruction/directive support, tests, documentation, and PDF preparation.
- University of Waterloo, MIPS Encoding Reference (field layouts/opcodes):
  https://student.cs.uwaterloo.ca/~isg/res/mips/opcodes
- MARS 4.5 documentation, settings and syscall services:
  https://dpetersanderson.github.io/Help/MarsHelpSettings.html
  https://dpetersanderson.github.io/Help/SyscallHelp.html
- MARS command-line options and official release used for execution testing:
  https://dpetersanderson.github.io/Help/MarsHelpCommand.html
  https://github.com/dpetersanderson/MARS/releases/tag/v.4.5.1

No third-party JARs or compiled class files are distributed in this repository.
