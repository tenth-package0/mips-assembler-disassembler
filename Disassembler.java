import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** Rebuilds canonical assembly from either MHC1 representation. */
public final class Disassembler {
    public static void main(String[] args) {
        try {
            if (args.length != 2) {
                throw new IllegalArgumentException(
                    "Usage: java Disassembler <input.mhc-bin|input.mhc-ascii> <out.asm>");
            }
            Image image = Image.read(Paths.get(args[0]));
            String source = disassemble(image);
            Files.write(Paths.get(args[1]), source.getBytes(StandardCharsets.UTF_8));
            System.out.println("Wrote " + args[1] + "; reassembly bytes verified.");
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Disassembly failed: " + e.getMessage());
            System.exit(1);
        }
    }

    static String disassemble(Image image) {
        StringBuilder s = new StringBuilder();
        s.append("# Reconstructed MHC1 image; delayed branching OFF.\n.data\n");
        for (int i = 0; i < image.data.length; i += 16) {
            s.append("    .byte ");
            for (int j = i; j < Math.min(i + 16, image.data.length); j++) {
                if (j != i) s.append(", ");
                s.append(image.data[j] & 255);
            }
            s.append('\n');
        }
        s.append(".text\n.globl main\nmain:\n");
        ByteBuffer b = ByteBuffer.wrap(image.text).order(ByteOrder.BIG_ENDIAN);
        for (int offset = 0; offset < image.text.length; offset += 4) {
            int pc = Assembler.TEXT_BASE + offset;
            int word = b.getInt();
            try {
                s.append(label(pc)).append(":\n    ");
                s.append(decode(word, pc, image.text.length)).append('\n');
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(String.format(
                    "At 0x%08X (word 0x%08X): %s", pc, word, e.getMessage()));
            }
        }
        // A label immediately after the last instruction is a valid source label.
        s.append(label(Assembler.TEXT_BASE + image.text.length)).append(":\n");
        Assembler.Result rebuilt = Assembler.assemble(s.toString());
        if (!Arrays.equals(image.text, rebuilt.text)
            || !Arrays.equals(image.data, rebuilt.data)) {
            throw new IllegalArgumentException(
                "Noncanonical/reserved instruction bits; cannot reproduce bytes.");
        }
        return s.toString();
    }

    private static String reg(int number) { return "$" + number; }
    private static String label(int address) {
        return String.format("L_%08X", address);
    }

    private static String target(int address, int length) {
        long end = (long) Assembler.TEXT_BASE + length;
        if (address < Assembler.TEXT_BASE || address > end || (address & 3) != 0) {
            throw new IllegalArgumentException("Control-flow target outside text segment.");
        }
        return label(address);
    }

    private static String decode(int w, int pc, int length) {
        int op = w >>> 26, rs = (w >>> 21) & 31, rt = (w >>> 16) & 31;
        int rd = (w >>> 11) & 31, shift = (w >>> 6) & 31, fn = w & 63;
        int unsigned = w & 65535, signed = (short) unsigned;
        if (op == 0) {
            switch (fn) {
                case 0: return "sll " + reg(rd) + ", " + reg(rt) + ", " + shift;
                case 8: return "jr " + reg(rs);
                case 12: return "syscall";
                case 16: return "mfhi " + reg(rd);
                case 26: return "div " + reg(rs) + ", " + reg(rt);
                case 32: case 33: case 34:
                    String mnemonic = fn == 32 ? "add" : fn == 33 ? "addu" : "sub";
                    return mnemonic + " " + reg(rd) + ", " + reg(rs) + ", " + reg(rt);
                default: throw new IllegalArgumentException("Unsupported R function: " + fn);
            }
        }
        switch (op) {
            case 2: case 3:
                int address = ((pc + 4) & 0xF0000000) | ((w & 0x03FFFFFF) << 2);
                return (op == 2 ? "j " : "jal ") + target(address, length);
            case 4: case 5:
                return (op == 4 ? "beq " : "bne ") + reg(rs) + ", " + reg(rt)
                    + ", " + target(pc + 4 + signed * 4, length);
            case 8: case 9: case 13:
                String mnemonic = op == 8 ? "addi" : op == 9 ? "addiu" : "ori";
                return mnemonic + " " + reg(rt) + ", " + reg(rs) + ", "
                    + (op == 13 ? unsigned : signed);
            case 15: return "lui " + reg(rt) + ", " + unsigned;
            case 35: case 43:
                return (op == 35 ? "lw " : "sw ") + reg(rt) + ", "
                    + signed + "(" + reg(rs) + ")";
            default: throw new IllegalArgumentException("Unsupported opcode: " + op);
        }
    }
}
