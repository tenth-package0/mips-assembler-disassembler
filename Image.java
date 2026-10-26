import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** MHC1 container shared by both tools. All integers are big-endian. */
public final class Image {
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
