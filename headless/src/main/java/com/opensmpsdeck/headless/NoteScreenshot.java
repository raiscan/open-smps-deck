package com.opensmpsdeck.headless;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.WriterException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** PNG handoffs retain their payload through metadata, lossless RGB, or the footer's local reference. */
public final class NoteScreenshot {
    static final int PAYLOAD_LIMIT = 8 * 1024 * 1024;
    private static final int PIXEL_LIMIT = 36_000_000;
    private static final int QR_SIZE = 116; // four pixels per version-1 module and quiet zone
    private static final String QR_PREFIX = "OSD1:";
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final byte[] KEYWORD = "opensmpsdeck-notes".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER = 12;
    private static final int MAGIC = 0x4f534431; // OSD1
    private static final int[] CHANNEL_SHIFT = {16, 8, 0};

    private NoteScreenshot() {}

    public record Encoded(byte[] png, BufferedImage image) {}

    record Decoded(String method, String payload, NoteHandoff.Block block) {}

    public static Encoded capture(BufferedImage crop, NoteHandoff.Capture capture) throws IOException {
        return capture(crop, capture, NoteClipRegistry.user());
    }

    static Encoded capture(BufferedImage crop, NoteHandoff.Capture capture, NoteClipRegistry registry) throws IOException {
        var text = capture.payload().getBytes(StandardCharsets.UTF_8);
        if (text.length > PAYLOAD_LIMIT) throw new IOException("Lasso payload is too large");
        var zipped = new ByteArrayOutputStream();
        try (var compress = new DeflaterOutputStream(zipped)) {
            compress.write(text);
        }
        byte[] body = zipped.toByteArray();
        byte[] bits = ByteBuffer.allocate(HEADER + body.length).putInt(MAGIC).putInt(body.length)
                .putInt(crc(body)).put(body).array();
        var image = footer(crop, capture, bits.length);
        writeBits(rgb(image), bits);
        var png = png(image, text);
        registry.store(capture);
        return new Encoded(png, image);
    }

    public static String describe(Path input, boolean json) throws IOException {
        var found = decode(input, NoteClipRegistry.user());
        if (!json) return "decoded: " + found.method + "\n" + found.payload;
        var output = NoteHandoff.JSON.toJsonTree(found.block).getAsJsonObject();
        output.addProperty("method", found.method);
        output.addProperty("payload", found.payload);
        return NoteHandoff.JSON.toJson(output) + "\n";
    }

    static Decoded decode(Path input, NoteClipRegistry registry) throws IOException {
        var found = decoded("metadata", metadata(input));
        if (found != null) return found;
        var image = readImage(input);
        found = decoded("lsb", pixels(image));
        if (found != null) return found;
        String ref = reference(image);
        if (ref != null) {
            found = decoded("registry", registry.find(ref));
            if (found != null && ref.equals(found.block.reference())) return found;
            throw new IOException("No local payload for lasso reference " + ref);
        }
        throw new IOException("No OpenSMPSDeck lasso payload or readable reference in the image");
    }

    private static Decoded decoded(String method, String payload) {
        if (payload == null) return null;
        try {
            var block = NoteHandoff.parse(payload);
            return block.lasso() == null ? null : new Decoded(method, payload, block);
        } catch (IllegalArgumentException | com.google.gson.JsonParseException invalid) {
            return null;
        }
    }

    private static BufferedImage footer(BufferedImage crop, NoteHandoff.Capture capture, int bytes) throws IOException {
        int width = Math.max(crop.getWidth(), 260);
        int height = Math.max(crop.getHeight() + QR_SIZE, (int) Math.ceil(bytes * 8.0 / (width * 3.0)));
        if ((long) width * height > PIXEL_LIMIT) throw new IOException("Lasso image is too large");
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            g.setColor(new Color(0x17202e));
            g.fillRect(0, 0, width, height);
            g.drawImage(crop, 0, 0, null);
            int top = height - QR_SIZE;
            var code = new QRCodeWriter().encode(QR_PREFIX + capture.reference(), BarcodeFormat.QR_CODE,
                    QR_SIZE, QR_SIZE, Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
            g.setColor(Color.WHITE);
            g.fillRect(0, top, QR_SIZE, QR_SIZE);
            g.setColor(Color.BLACK);
            for (int y = 0; y < QR_SIZE; y++)
                for (int x = 0; x < QR_SIZE; x++)
                    if (code.get(x, y)) g.fillRect(x, top + y, 1, 1);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int x = QR_SIZE + 10, y = top + 24;
            for (String line : capture.footer().lines().toList()) {
                String shown = line;
                while (shown.length() > 1 && g.getFontMetrics().stringWidth(shown) > width - x - 8)
                    shown = line.substring(0, shown.length() - 2) + "…";
                g.drawString(shown, x, y);
                y += 17;
            }
        } catch (WriterException failed) {
            throw new IOException("Could not generate lasso reference code", failed);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static int[] rgb(BufferedImage image) {
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    static void writeBits(int[] rgb, byte[] data) {
        int i = 0;
        for (byte value : data) for (int bit = 7; bit >= 0; bit--, i++) {
            int shift = CHANNEL_SHIFT[i % 3];
            rgb[i / 3] = rgb[i / 3] & ~(1 << shift) | ((value >>> bit) & 1) << shift;
        }
    }

    private static byte[] readBits(int[] rgb, int offset, int length) {
        var bytes = new byte[length];
        int i = offset * 8;
        for (int j = 0; j < length; j++) for (int bit = 7; bit >= 0; bit--, i++)
            bytes[j] |= (byte) (((rgb[i / 3] >>> CHANNEL_SHIFT[i % 3]) & 1) << bit);
        return bytes;
    }

    static String pixels(BufferedImage image) {
        long capacity = (long) image.getWidth() * image.getHeight() * 3 / 8;
        if (capacity < HEADER) return null;
        int[] rgb = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        var header = ByteBuffer.wrap(readBits(rgb, 0, HEADER));
        if (header.getInt() != MAGIC) return null;
        int length = header.getInt(), checksum = header.getInt();
        if (length <= 0 || length > PAYLOAD_LIMIT || length > capacity - HEADER) return null;
        byte[] compressed = readBits(rgb, HEADER, length);
        if (crc(compressed) != checksum) return null;
        try (var in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            byte[] payload = in.readNBytes(PAYLOAD_LIMIT + 1);
            return payload.length > PAYLOAD_LIMIT ? null : new String(payload, StandardCharsets.UTF_8);
        } catch (IOException corrupt) {
            return null;
        }
    }

    private static String reference(BufferedImage image) {
        int width = Math.min(image.getWidth(), QR_SIZE * 2), height = Math.min(image.getHeight(), QR_SIZE * 2);
        var rgb = image.getRGB(0, image.getHeight() - height, width, height, null, 0, width);
        var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, rgb)));
        try {
            String qr = new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, true)).getText();
            String ref = qr.startsWith(QR_PREFIX) ? qr.substring(QR_PREFIX.length()) : null;
            return NoteClipRegistry.valid(ref) ? ref : null;
        } catch (ReaderException absent) {
            return null;
        }
    }

    private static BufferedImage readImage(Path path) throws IOException {
        try (var in = ImageIO.createImageInputStream(path.toFile())) {
            if (in == null) throw new IOException("Could not open the lasso image");
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("Expected a PNG or JPEG lasso image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > PIXEL_LIMIT)
                    throw new IOException("Lasso image is too large");
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static byte[] png(BufferedImage image, byte[] payload) throws IOException {
        var raw = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "PNG", raw)) throw new IOException("No PNG encoder");
        var result = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(result)) {
            byte[] png = raw.toByteArray();
            byte[] type = "iTXt".getBytes(StandardCharsets.US_ASCII);
            byte[] data = ByteBuffer.allocate(KEYWORD.length + 5 + payload.length)
                    .put(KEYWORD).put(new byte[5]).put(payload).array();
            int afterHeader = 33; // PNG signature and IHDR
            out.write(png, 0, afterHeader);
            out.writeInt(data.length);
            out.write(type);
            out.write(data);
            var crc = new CRC32();
            crc.update(type);
            crc.update(data);
            out.writeInt((int) crc.getValue());
            out.write(png, afterHeader, png.length - afterHeader);
        }
        return result.toByteArray();
    }

    private static String metadata(Path path) throws IOException {
        try (var in = new DataInputStream(Files.newInputStream(path))) {
            if (!Arrays.equals(in.readNBytes(8), SIGNATURE)) return null;
            while (true) {
                int length = in.readInt();
                String type = new String(in.readNBytes(4), StandardCharsets.US_ASCII);
                if (length < 0 || type.equals("IEND")) return null;
                if (type.equals("iTXt") && length >= KEYWORD.length + 5 && length <= PAYLOAD_LIMIT + KEYWORD.length + 5) {
                    byte[] data = in.readNBytes(length);
                    long expected = Integer.toUnsignedLong(in.readInt());
                    var crc = new CRC32();
                    crc.update(type.getBytes(StandardCharsets.US_ASCII));
                    crc.update(data);
                    var prefix = ByteBuffer.allocate(KEYWORD.length + 5).put(KEYWORD).array();
                    if (crc.getValue() == expected && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length))
                        return new String(data, prefix.length, data.length - prefix.length, StandardCharsets.UTF_8);
                } else {
                    in.skipNBytes(length + 4L);
                }
            }
        } catch (EOFException incomplete) {
            return null;
        }
    }

    private static int crc(byte[] data) {
        var crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }
}
