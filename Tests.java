import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Deterministic encoding, parser, image-format, and rejection tests. */
public final class Tests {
    private static int passed;
    interface Check { void run() throws Exception; }

    static void check(String name, Check check) throws Exception {
        check.run();
        passed++;
        System.out.println("PASS " + name);
    }

    static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static void rejects(String source, String fragment) {
        try {
            Assembler.assemble(source);
            throw new AssertionError("Invalid assembly was accepted");
        } catch (IllegalArgumentException e) {
            require(e.getMessage().contains(fragment), e.getMessage());
            System.out.println("  Expected error: " + e.getMessage());
        }
    }

    static void badImage(Path file, byte[] bytes, String fragment) throws Exception {
        Files.write(file, bytes);
        try {
            Image.read(file);
            throw new AssertionError("Invalid image was accepted");
        } catch (IllegalArgumentException e) {
            require(e.getMessage().contains(fragment), e.getMessage());
            System.out.println("  Expected error: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        check("Part 1 known instruction words", () -> {
            Assembler.Result r = Assembler.assemble(
                "addi $t0, $zero, 3\naddi $t1, $zero, 5\n"
                + "add $t2, $t0, $t1\nsub $t3, $t1, $t0");
            ByteBuffer b = ByteBuffer.wrap(r.text);
            int[] expected = {0x20080003, 0x20090005, 0x01095020, 0x01285822};
            for (int word : expected) require(b.getInt() == word, "Word mismatch");
        });
        check("Forward/backward branch offsets and jump target", () -> {
            byte[] bytes = Assembler.assemble("start: beq $0, $0, end\n"
                + "bne $8, $0, start\nend: j start").text;
            ByteBuffer b = ByteBuffer.wrap(bytes);
            require(b.getInt() == 0x10000001, "Forward branch");
            require(b.getInt() == 0x1500FFFE, "Backward branch");
            require(b.getInt() == 0x08100000, "Jump address");
        });
        check("li widths and immediate boundaries", () -> {
            ByteBuffer b = ByteBuffer.wrap(Assembler.assemble(
                "li $t0, -32768\nli $t0, 65535\nli $t0, 0x12345678").text);
            int[] expected = {0x24088000, 0x3408FFFF, 0x3C081234, 0x35085678};
            for (int word : expected) require(b.getInt() == word, "li encoding");
        });
        check("All supported real instructions and pseudos round trip", () -> {
            String source = ".data\ns: .asciiz \"A#B\\n\"\n.byte 0, 255\n.text\n"
                + "main: li $t0, 0x12345678\nla $a0, s\nmove $t1, $t0\n"
                + "rem $t2, $t1, 3\nnop\naddi $t0, $t0, -1\n"
                + "add $t0, $t1, $t2\nsub $t0, $t1, $t2\n"
                + "sw $ra, -4($sp)\nlw $ra, -4($sp)\n"
                + "beq $t0, $zero, done\nbne $t0, $zero, main\n"
                + "jal done\nj done\ndone: syscall\njr $ra";
            Assembler.Result r = Assembler.assemble(source);
            String rebuilt = Disassembler.disassemble(new Image(r.text, r.data));
            Assembler.Result round = Assembler.assemble(rebuilt);
            require(Arrays.equals(r.text, round.text), "Text mismatch");
            require(Arrays.equals(r.data, round.data), "Data mismatch");
            require(r.data.length == 7 && r.data[1] == '#', "Quoted comment handling");
        });
        check("Parser rejects unsupported instruction with line number", () ->
            rejects(".text\nmul $t0, $t1, $t2", "Line 2: Unsupported instruction"));
        check("Undefined label", () -> rejects("j missing", "Undefined label"));
        check("Duplicate label", () -> rejects("x: nop\nx: nop", "Duplicate label"));
        check("Invalid register", () -> rejects("li $t99, 1", "Unknown register"));
        check("Signed immediate overflow", () ->
            rejects("addi $t0, $0, 32768", "Value out of range"));
        check("Unsigned immediate overflow", () ->
            rejects("ori $t0, $0, -1", "Value out of range"));
        check("Wrong operand count", () -> rejects("add $t0, $t1", "operand(s)"));
        check("Unsupported directive", () -> rejects(".word 1", "Unsupported directive"));
        check("Instruction in data", () -> rejects(".data\nnop", "must be in .text"));
        check("Branch into data", () -> rejects(
            ".data\nx: .asciiz \"x\"\n.text\nj x", "not in .text"));
        check("Unclosed quote", () -> rejects(".data\n.asciiz \"x", "Unclosed quote"));
        check("Zero divisor", () -> rejects("rem $t0, $t1, 0", "cannot be zero"));
        check("Reserved temporary register", () ->
            rejects("rem $t0, $at, 3", "reserves $at"));
        check("Bad byte", () -> rejects(".data\n.byte 256\n.text\nnop", "out of range"));
        check("BOM, CRLF, comment characters, and character literal", () -> {
            Assembler.Result r = Assembler.assemble(
                "\uFEFF.text\r\nli $a0, ',' # comment\r\nnop");
            require(ByteBuffer.wrap(r.text).getInt() == 0x2404002C, "Character literal");
        });
        Path dir = Files.createTempDirectory("mhc-tests-");
        try {
            Path binary = dir.resolve("case.mhc-bin");
            Path ascii = dir.resolve("case.mhc-ascii");
            Assembler.Result r = Assembler.assemble(".data\n.byte 255\n.text\nnop");
            byte[] valid = Image.pack(r.text, r.data);
            check("Both image formats preserve non-word-aligned data", () -> {
                Image.write(dir.resolve("case").toString(), r.text, r.data);
                for (Path file : new Path[]{binary, ascii}) {
                    Image image = Image.read(file);
                    require(Arrays.equals(image.text, r.text), "Text mismatch");
                    require(Arrays.equals(image.data, r.data), "Data mismatch");
                }
                require(Files.size(binary) == 25, "Unexpected padding");
            });
            check("Truncated header", () -> badImage(binary, new byte[4], "Truncated"));
            check("Bad magic", () -> {
                byte[] bad = valid.clone(); bad[0] = 0;
                badImage(binary, bad, "magic");
            });
            check("Wrong starting address", () -> {
                byte[] bad = valid.clone(); bad[4] = 1;
                badImage(binary, bad, "starting addresses");
            });
            check("Wrong segment length", () -> {
                byte[] bad = valid.clone(); bad[11] = 3;
                badImage(binary, bad, "lengths");
            });
            check("Trailing bytes", () ->
                badImage(binary, Arrays.copyOf(valid, valid.length + 1), "lengths"));
            check("Invalid ASCII character", () -> badImage(ascii,
                "010x".getBytes(StandardCharsets.US_ASCII), "only 0, 1"));
            check("Incomplete ASCII byte", () -> badImage(ascii,
                "010".getBytes(StandardCharsets.US_ASCII), "Incomplete"));
            check("Unknown opcode rejected", () -> {
                try {
                    Disassembler.disassemble(new Image(
                        new byte[]{(byte) 0xFC, 0, 0, 0}, new byte[0]));
                    throw new AssertionError("Accepted unknown opcode");
                } catch (IllegalArgumentException e) {
                    require(e.getMessage().contains("Unsupported opcode"), e.getMessage());
                }
            });
            check("Reserved bits rejected instead of silently discarded", () -> {
                try {
                    Disassembler.disassemble(new Image(
                        new byte[]{0, 0x20, 0, 0}, new byte[0]));
                    throw new AssertionError("Accepted sll with nonzero rs");
                } catch (IllegalArgumentException e) {
                    require(e.getMessage().contains("reserved"), e.getMessage());
                }
            });
        } finally {
            Files.deleteIfExists(dir.resolve("case.mhc-bin"));
            Files.deleteIfExists(dir.resolve("case.mhc-ascii"));
            Files.delete(dir);
        }
        System.out.println("ALL " + passed + " TESTS PASSED");
    }
}
