import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Two-pass, file-based MIPS subset assembler. Java 8+. */
public class Assembler {
    static final int TEXT_BASE = 0x00400000;
    static final int DATA_BASE = 0x10010000;

    private static final String[] REGISTER_NAMES = {
        "zero", "at", "v0", "v1", "a0", "a1", "a2", "a3",
        "t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7",
        "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7",
        "t8", "t9", "k0", "k1", "gp", "sp", "fp", "ra"
    };

    static class Instruction {
        final int line, address;
        final String text, sourceText;
        Instruction(int line, int address, String text, String sourceText) {
            this.line = line;
            this.address = address;
            this.text = text;
            this.sourceText = sourceText;
        }
    }

    static class EncodedInstruction {
        final Instruction source;
        final int word;
        final String mips;
        final Map<String, Integer> labelLines;
        EncodedInstruction(Instruction source, int word, String mips,
                           Map<String, Integer> labelLines) {
            this.source = source;
            this.word = word;
            this.mips = mips;
            this.labelLines = labelLines;
        }
    }

    static class Result {
        final byte[] text, data;
        final List<EncodedInstruction> instructions;
        Result(byte[] text, byte[] data, List<EncodedInstruction> instructions) {
            this.text = text;
            this.data = data;
            this.instructions = instructions;
        }
    }

    private final Map<String, Integer> labels = new HashMap<>();
    private final Map<String, Integer> labelLines = new HashMap<>();
    private final Set<String> textLabels = new HashSet<>();
    private final List<String> globals = new ArrayList<>();
    private final List<Instruction> instructions = new ArrayList<>();
    private final List<Byte> data = new ArrayList<>();
    private boolean inData = false;
    private int pc = TEXT_BASE;

    public static void main(String[] args) {
        try {
            if (args.length < 1 || args.length > 2) {
                fail("Usage: java Assembler <source.asm> [output-prefix]");
            }
            java.nio.file.Path input = java.nio.file.Paths.get(args[0]);
            String source = new String(java.nio.file.Files.readAllBytes(input),
                                       java.nio.charset.StandardCharsets.UTF_8);
            String prefix = args.length == 2 ? args[1]
                : args[0].replaceFirst("\\.[^./\\\\]+$", "");
            Result result = assemble(source);
            Image.write(prefix, result.text, result.data);
            System.out.println("Assembled " + result.instructions.size()
                + " instructions; " + result.data.length + " data bytes.");
            System.out.println("Wrote " + prefix + ".mhc-ascii and "
                + prefix + ".mhc-bin");
        } catch (java.io.IOException | IllegalArgumentException e) {
            System.err.println("Assembly failed: " + e.getMessage());
            System.exit(1);
        }
    }

    static Result assemble(String source) {
        Assembler assembler = new Assembler();
        assembler.firstPass(source);
        if (assembler.instructions.isEmpty()) fail("No instructions found.");
        for (String name : assembler.globals) assembler.label(name);

        // Pass 2: all labels now have final addresses.
        byte[] text = new byte[assembler.instructions.size() * 4];
        List<EncodedInstruction> encoded = new ArrayList<>();
        int index = 0;
        for (Instruction instruction : assembler.instructions) {
            try {
                int word = assembler.encode(instruction);
                encoded.add(new EncodedInstruction(instruction, word,
                            assembler.resolvedMips(instruction), assembler.labelLines));
                for (int shift = 24; shift >= 0; shift -= 8) {
                    text[index++] = (byte) (word >>> shift);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Line " + instruction.line + ": " + e.getMessage());
            }
        }
        byte[] bytes = new byte[assembler.data.size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = assembler.data.get(i);
        return new Result(text, bytes, encoded);
    }

    // Pass 1: expand pseudos BEFORE calculating following label addresses.
    private void firstPass(String source) {
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        String[] lines = source.split("\\r\\n|\\r|\\n");
        for (int i = 0; i < lines.length; i++) {
            try {
                String text = withoutComment(lines[i]).trim();
                while (true) {
                    int colon = text.indexOf(':');
                    if (colon < 0) break;
                    String name = text.substring(0, colon).trim();
                    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) break;
                    if (labels.containsKey(name)) fail("Duplicate label: " + name);
                    labels.put(name, inData ? DATA_BASE + data.size() : pc);
                    labelLines.put(name, i + 1);
                    if (!inData) textLabels.add(name);
                    text = text.substring(colon + 1).trim();
                }
                if (text.isEmpty()) continue;
                if (text.startsWith(".")) {
                    directive(text);
                } else {
                    if (inData) fail("Instructions must be in .text.");
                    expand(text, i + 1);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Line " + (i + 1) + ": " + e.getMessage());
            }
        }
    }

    private void directive(String text) {
        String[] p = text.split("\\s+", 2);
        String value = p.length == 2 ? p[1].trim() : "";
        switch (p[0]) {
            case ".text":
            case ".data":
                boolean nextData = p[0].equals(".data");
                int base = nextData ? DATA_BASE : TEXT_BASE;
                if (!value.isEmpty()) {
                    if (number(value) != base) fail("Only the default segment base is supported.");
                    if (nextData ? !data.isEmpty() : !instructions.isEmpty()) {
                        fail("Explicit segment address must precede its contents.");
                    }
                }
                inData = nextData;
                break;
            case ".globl":
            case ".global":
                if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) fail("Expected one global label.");
                globals.add(value);
                break;
            case ".byte":
                if (!inData) fail(".byte must be in .data.");
                for (String item : value.split(",", -1)) {
                    data.add((byte) range(number(item), 0, 255));
                }
                break;
            case ".asciiz":
                if (!inData) fail(".asciiz must be in .data.");
                String decoded = quoted(value, '"');
                for (int i = 0; i < decoded.length(); i++) data.add((byte) decoded.charAt(i));
                data.add((byte) 0);
                break;
            default:
                fail("Unsupported directive: " + p[0]);
        }
    }

    private void emit(String text, int line, String sourceText) {
        instructions.add(new Instruction(line, pc, text, sourceText));
        pc += 4;
    }

    private void loadImmediate(String register, int value, int line, String sourceText) {
        reg(register);
        if (value >= -32768 && value <= 32767) {
            emit("addiu " + register + ", $zero, " + value, line, sourceText);
        } else if (value >= 0 && value <= 65535) {
            emit("ori " + register + ", $zero, " + value, line, sourceText);
        } else {
            emit("lui " + register + ", " + (value >>> 16), line, sourceText);
            emit("ori " + register + ", " + register + ", " + (value & 65535), line, sourceText);
        }
    }

    private void expand(String text, int line) {
        String[] p = text.split("\\s+", 2);
        String op = lowerAscii(p[0]);
        String[] a = operands(p);
        switch (op) {
            case "li":
                count(a, 2);
                loadImmediate(a[0], number(a[1]), line, text);
                break;
            case "la":
                count(a, 2);
                reg(a[0]);
                if (!a[1].matches("[A-Za-z_][A-Za-z0-9_]*")) fail("la expects a label.");
                emit("lui " + a[0] + ", hi(" + a[1] + ")", line, text);
                emit("ori " + a[0] + ", " + a[0] + ", lo(" + a[1] + ")", line, text);
                break;
            case "move":
                count(a, 2);
                emit("addu " + a[0] + ", " + a[1] + ", $zero", line, text);
                break;
            case "rem":
                count(a, 3);
                if (reg(a[0]) == 1 || reg(a[1]) == 1) fail("rem reserves $at.");
                int divisor = number(a[2]);
                if (divisor == 0) fail("rem divisor cannot be zero.");
                loadImmediate("$at", divisor, line, text);
                emit("div " + a[1] + ", $at", line, text);
                emit("mfhi " + a[0], line, text);
                break;
            case "nop":
                count(a, 0);
                emit("sll $zero, $zero, 0", line, text);
                break;
            default:
                emit(text, line, text);
        }
    }

    private int encode(Instruction instruction) {
        String[] p = instruction.text.split("\\s+", 2);
        String op = lowerAscii(p[0]);
        String[] a = operands(p);
        switch (op) {
            case "addi":
            case "addiu":
            case "ori":
                count(a, 3);
                int immediate = value(a[2]);
                range(immediate, op.equals("ori") ? 0 : -32768,
                                 op.equals("ori") ? 65535 : 32767);
                return iType(op.equals("addi") ? 8 : op.equals("addiu") ? 9 : 13,
                             reg(a[1]), reg(a[0]), immediate);
            case "lui":
                count(a, 2);
                return iType(15, 0, reg(a[0]), range(value(a[1]), 0, 65535));
            case "add":
            case "sub":
            case "addu":
                count(a, 3);
                int function = op.equals("add") ? 0x20
                    : op.equals("sub") ? 0x22 : 0x21;
                return rType(reg(a[1]), reg(a[2]), reg(a[0]), 0, function);
            case "div":
                count(a, 2);
                return rType(reg(a[0]), reg(a[1]), 0, 0, 0x1A);
            case "mfhi":
                count(a, 1);
                return rType(0, 0, reg(a[0]), 0, 0x10);
            case "sll":
                count(a, 3);
                return rType(0, reg(a[1]), reg(a[0]), range(number(a[2]), 0, 31), 0);
            case "beq":
            case "bne":
                count(a, 3);
                int offset = (codeLabel(a[2]) - (instruction.address + 4)) / 4;
                return iType(op.equals("beq") ? 4 : 5, reg(a[0]), reg(a[1]),
                             range(offset, -32768, 32767));
            case "j":
            case "jal":
                count(a, 1);
                int target = codeLabel(a[0]);
                if ((target & 0xF0000000) != ((instruction.address + 4) & 0xF0000000)) {
                    fail("Jump target is out of range.");
                }
                return ((op.equals("j") ? 2 : 3) << 26) | ((target >>> 2) & 0x03FFFFFF);
            case "jr":
                count(a, 1);
                return rType(reg(a[0]), 0, 0, 0, 8);
            case "lw":
            case "sw":
                count(a, 2);
                String memory = a[1].replaceAll("\\s+", "");
                if (!memory.matches("[^()]+\\(\\$[A-Za-z0-9]+\\)")) {
                    fail("Expected offset($register): " + a[1]);
                }
                int left = memory.indexOf('(');
                int base = reg(memory.substring(left + 1, memory.length() - 1));
                int displacement = range(number(memory.substring(0, left)), -32768, 32767);
                return iType(op.equals("lw") ? 35 : 43, base, reg(a[0]), displacement);
            case "syscall":
                count(a, 0);
                return 0x0000000C;
            default:
                throw new IllegalArgumentException("Unsupported instruction: " + op);
        }
    }

    // R: opcode(6), rs(5), rt(5), rd(5), shamt(5), funct(6).
    private static int rType(int rs, int rt, int rd, int shift, int function) {
        return (rs << 21) | (rt << 16) | (rd << 11) | (shift << 6) | function;
    }

    // I: opcode(6), rs(5), rt(5), immediate(16).
    private static int iType(int opcode, int rs, int rt, int immediate) {
        return (opcode << 26) | (rs << 21) | (rt << 16) | (immediate & 0xFFFF);
    }

    private int label(String name) {
        Integer result = labels.get(name);
        if (result == null) fail("Undefined label: " + name);
        return result;
    }

    private int codeLabel(String name) {
        int address = label(name);
        if (!textLabels.contains(name)) fail("Branch/jump target is not in .text: " + name);
        return address;
    }

    private int value(String text) {
        if (text.startsWith("hi(") && text.endsWith(")")) {
            return label(text.substring(3, text.length() - 1)) >>> 16;
        }
        if (text.startsWith("lo(") && text.endsWith(")")) {
            return label(text.substring(3, text.length() - 1)) & 65535;
        }
        return number(text);
    }

    private static int reg(String name) {
        name = lowerAscii(name.trim());
        if (name.equals("$s8")) return 30;
        for (int i = 0; i < REGISTER_NAMES.length; i++) {
            if (name.equals("$" + REGISTER_NAMES[i]) || name.equals("$" + i)) return i;
        }
        throw new IllegalArgumentException("Unknown register: " + name);
    }

    private static int number(String text) {
        text = text.trim();
        if (text.startsWith("'")) {
            String character = quoted(text, '\'');
            if (character.length() != 1) fail("Expected one character: " + text);
            return character.charAt(0);
        }
        try {
            boolean negative = text.startsWith("-");
            String digits = text;
            if (digits.startsWith("-") || digits.startsWith("+")) digits = digits.substring(1);
            int radix = 10;
            if (digits.startsWith("0x") || digits.startsWith("0X")) {
                radix = 16;
                digits = digits.substring(2);
            } else if (digits.startsWith("0b") || digits.startsWith("0B")) {
                radix = 2;
                digits = digits.substring(2);
            }
            if (digits.isEmpty() || digits.startsWith("-") || digits.startsWith("+")) {
                fail("Invalid integer: " + text);
            }
            long result = Long.parseLong(digits, radix);
            if (negative) result = -result;
            if (result < Integer.MIN_VALUE || result > 0xFFFFFFFFL) fail("Integer too large: " + text);
            return (int) result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer: " + text);
        }
    }

    // Keep # inside quoted strings or character literals.
    private static String withoutComment(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#') {
                return text.substring(0, i);
            }
        }
        if (quote != 0) fail("Unclosed quote.");
        return text;
    }

    private static String quoted(String text, char quote) {
        if (text.length() < 2 || text.charAt(0) != quote
                || text.charAt(text.length() - 1) != quote) fail("Expected quoted literal: " + text);
        StringBuilder decoded = new StringBuilder();
        for (int i = 1; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == quote) fail("Unexpected quote: " + text);
            if (c == '\\') {
                if (++i >= text.length() - 1) fail("Incomplete escape.");
                switch (text.charAt(i)) {
                    case 'n': c = '\n'; break;
                    case 'r': c = '\r'; break;
                    case 't': c = '\t'; break;
                    case '0': c = 0; break;
                    case '\\': c = '\\'; break;
                    case '\'': c = '\''; break;
                    case '"': c = '"'; break;
                    default: fail("Unsupported escape: \\" + text.charAt(i));
                }
            }
            if (c > 127) fail("This toy assembler accepts ASCII literals only.");
            decoded.append(c);
        }
        return decoded.toString();
    }

    private static String[] operands(String[] p) {
        if (p.length == 1 || p[1].trim().isEmpty()) return new String[0];
        // Split commas outside quotes so li $a0, ',' also works.
        List<String> result = new ArrayList<>();
        char quote = 0;
        int start = 0;
        for (int i = 0; i < p[1].length(); i++) {
            char c = p[1].charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ',') {
                result.add(p[1].substring(start, i).trim());
                start = i + 1;
            }
        }
        result.add(p[1].substring(start).trim());
        return result.toArray(new String[0]);
    }

    private static void count(String[] a, int expected) {
        if (a.length != expected) fail("Expected " + expected + " comma-separated operand(s).");
        for (String operand : a) if (operand.isEmpty()) fail("Empty operand.");
    }

    private static int range(int value, int min, int max) {
        if (value < min || value > max) fail("Value out of range: " + value);
        return value;
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }

    // Replace assembler-internal hi/lo expressions with the encoded immediates.
    private String resolvedMips(Instruction instruction) {
        String[] p = instruction.text.split("\\s+", 2);
        String op = lowerAscii(p[0]);
        String[] a = operands(p);
        if (op.equals("lui")) return "lui " + a[0] + ", " + value(a[1]);
        if (op.equals("ori")) return "ori " + a[0] + ", " + a[1] + ", " + value(a[2]);
        return instruction.text;
    }

    private static String lowerAscii(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            result.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return result.toString();
    }
}

/** MHC1 container shared by both tools. All integers are big-endian. */
final class Image {
    static final int MAGIC = 0x4D484331; // ASCII MHC1
    static final int HEADER_SIZE = 20;
    final byte[] text, data;

    Image(byte[] text, byte[] data) {
        this.text = text;
        this.data = data;
    }

    static byte[] pack(byte[] text, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + text.length + data.length)
            .order(ByteOrder.BIG_ENDIAN);
        b.putInt(MAGIC).putInt(Assembler.TEXT_BASE).putInt(text.length);
        b.putInt(Assembler.DATA_BASE).putInt(data.length);
        b.put(text).put(data);
        return b.array();
    }

    static void write(String prefix, byte[] text, byte[] data) throws IOException {
        byte[] raw = pack(text, data);
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < raw.length; i++) {
            for (int bit = 7; bit >= 0; bit--) {
                ascii.append(((raw[i] & 255) >>> bit) & 1);
            }
            ascii.append(i % 4 == 3 || i == raw.length - 1 ? '\n' : ' ');
        }
        Files.write(Paths.get(prefix + ".mhc-bin"), raw);
        Files.write(Paths.get(prefix + ".mhc-ascii"),
                    ascii.toString().getBytes(StandardCharsets.US_ASCII));
    }

    static Image read(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        String name = path.getFileName().toString();
        if (name.endsWith(".mhc-ascii")) {
            StringBuilder bits = new StringBuilder();
            for (byte b : raw) {
                if (b == '0' || b == '1') bits.append((char) b);
                else if (b != ' ' && b != '\n' && b != '\r' && b != '\t') {
                    throw new IllegalArgumentException(
                        "ASCII image accepts only 0, 1, and whitespace.");
                }
            }
            if (bits.length() % 8 != 0) {
                throw new IllegalArgumentException("Incomplete byte in ASCII image.");
            }
            raw = new byte[bits.length() / 8];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
            }
        } else if (!name.endsWith(".mhc-bin")) {
            throw new IllegalArgumentException("Expected .mhc-ascii or .mhc-bin input.");
        }
        if (raw.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Truncated MHC1 header.");
        }
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != MAGIC) {
            throw new IllegalArgumentException("Bad MHC1 magic/version.");
        }
        int textBase = b.getInt(), textSize = b.getInt();
        int dataBase = b.getInt(), dataSize = b.getInt();
        if (textBase != Assembler.TEXT_BASE || dataBase != Assembler.DATA_BASE) {
            throw new IllegalArgumentException("Unsupported segment starting addresses.");
        }
        if (textSize <= 0 || textSize % 4 != 0 || dataSize < 0
            || HEADER_SIZE + (long) textSize + dataSize != raw.length
            || (long) textBase + textSize > dataBase
            || (long) dataBase + dataSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid segment lengths or file size.");
        }
        return new Image(Arrays.copyOfRange(raw, HEADER_SIZE, HEADER_SIZE + textSize),
            Arrays.copyOfRange(raw, HEADER_SIZE + textSize, raw.length));
    }
}
