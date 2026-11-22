package com.opensmpsdeck.headless;

import com.openggf.audio.synth.ChipWriteObserver;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static com.openggf.audio.session.SmpsHardwareFrameBudget.NTSC_MASTER_CLOCK;
import static com.openggf.audio.session.SmpsHardwareFrameBudget.NTSC_MASTER_CLOCKS_PER_FRAME;

/** VGM 1.71 subset for the Mega Drive's YM2612 and integrated SN76489. */
final class VgmFile {
    private static final int RATE = 44_100;
    private static final int HEADER = 0x100;
    private static final int VERSION = 0x171;
    private static final int PSG_CLOCK = (int) Math.round(NTSC_MASTER_CLOCK / 15);
    private static final int YM_CLOCK = (int) Math.round(NTSC_MASTER_CLOCK / 7);
    // Sega integrated PSG: 16-bit shift register, taps 0 and 3, active-low output.
    private static final int PSG_FEEDBACK = 0x0009;
    private static final int PSG_WIDTH = 16;
    private static final int PSG_FLAGS = 0x06;
    private static final int PSG = 0x50, YM = 0x52, YM_PORT_2 = 0x53;
    private static final int WAIT = 0x61, END = 0x66;

    private VgmFile() {}

    static void write(TargetPlayback playback, String title, int frames, NativeExportLength.Loop loop,
                      Path path, ReconstructionStages.Progress progress) throws IOException {
        int total = sample((long) frames * NTSC_MASTER_CLOCKS_PER_FRAME);
        int loopSample = loop == null ? -1 : sample((long) loop.start() * NTSC_MASTER_CLOCKS_PER_FRAME);
        int gd3Offset;
        int loopOffset;
        try (var stream = new BufferedOutputStream(Files.newOutputStream(path))) {
            stream.write(new byte[HEADER]);
            var recorder = new Recorder(stream, total, loopSample);
            progress.report(0, frames + 1, "Recording chip writes");
            try {
                playback.render(frames, S3kRenderer.ChannelMask.NONE, pcm -> {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("VGM export cancelled");
                    recorder.presentation();
                    if (recorder.frames < frames && recorder.frames % 30 == 0)
                        progress.report(recorder.frames, frames + 1, "Recording chip writes");
                }, recorder);
            } catch (UncheckedIOException failure) {
                throw failure.getCause();
            }
            recorder.finish();
            loopOffset = recorder.loopOffset;
            gd3Offset = recorder.offset;
            byte[] tag = (title.replace('\0', ' ') + "\0".repeat(11)).getBytes(StandardCharsets.UTF_16LE);
            stream.write("Gd3 ".getBytes(StandardCharsets.US_ASCII));
            littleInt(stream, 0x100);
            littleInt(stream, tag.length);
            stream.write(tag);
        }

        var header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        header.put("Vgm ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(0x04, Math.toIntExact(Files.size(path) - 4));
        header.putInt(0x08, VERSION);
        header.putInt(0x0c, PSG_CLOCK);
        header.putInt(0x14, gd3Offset - 0x14);
        header.putInt(0x18, total);
        if (loopOffset != -1) {
            header.putInt(0x1c, loopOffset - 0x1c);
            header.putInt(0x20, total - loopSample);
        }
        header.putShort(0x28, (short) PSG_FEEDBACK);
        header.put(0x2a, (byte) PSG_WIDTH);
        header.put(0x2b, (byte) PSG_FLAGS);
        header.putInt(0x2c, YM_CLOCK);
        header.putInt(0x34, HEADER - 0x34);
        try (var file = new RandomAccessFile(path.toFile(), "rw")) {
            file.write(header.array());
        }
        progress.report(frames, frames + 1, "Validating VGM");
        read(path, (at, command, register, value) -> {});
    }

    private static int sample(long masterClocks) {
        return (int) Math.round(masterClocks * (double) RATE / NTSC_MASTER_CLOCK);
    }

    private static void littleInt(OutputStream out, int value) throws IOException {
        for (int shift = 0; shift < 32; shift += 8) out.write(value >>> shift);
    }

    private static final class Recorder implements ChipWriteObserver {
        final OutputStream out;
        final int total, loopSample;
        final int[] addresses = new int[2];
        long[] writes = new long[4096];
        int pending, frames, sample, offset = HEADER, loopOffset = -1;

        Recorder(OutputStream out, int total, int loopSample) {
            this.out = out;
            this.total = total;
            this.loopSample = loopSample;
        }

        @Override public void onYm2612Write(int port, int register, int value) {}
        @Override public void onPsgWrite(int value) {}
        @Override public boolean observesPhysicalWrites() { return true; }

        @Override public void onYm2612BusWrite(long cycle, int busPort, int value, PhysicalWriteOrigin origin) {
            if (origin == PhysicalWriteOrigin.DAC_INTERPOLATION) return;
            int port = busPort >>> 1;
            if ((busPort & 1) == 0) addresses[port] = value;
            else add(cycle * 42, YM + port, addresses[port], value);
        }

        @Override public void onPsgBusWrite(long tick, int value) {
            add(tick * 240, PSG, 0, value);
        }

        private void add(long masterClocks, int command, int register, int value) {
            if (pending == writes.length) writes = Arrays.copyOf(writes, writes.length * 2);
            // VGM sample order, stable within each chip when timestamps tie.
            writes[pending] = ((long) sample(masterClocks) << 38) | ((long) pending << 24)
                    | (command << 16) | (register << 8) | value;
            pending++;
        }

        void presentation() throws IOException {
            frames++;
            Arrays.sort(writes, 0, pending);
            for (int i = 0; i < pending; i++) {
                long write = writes[i];
                int at = (int) (write >>> 38);
                if (at >= total) continue;
                advance(at);
                int command = (int) (write >>> 16) & 0xff;
                emit(command);
                if (command != PSG) emit((int) (write >>> 8) & 0xff);
                emit((int) write & 0xff);
            }
            pending = 0;
        }

        void finish() throws IOException {
            advance(total);
            emit(END);
        }

        private void advance(int at) throws IOException {
            if (loopSample >= 0 && loopOffset < 0 && at >= loopSample) {
                waitUntil(loopSample);
                loopOffset = offset;
            }
            waitUntil(at);
        }

        private void waitUntil(int at) throws IOException {
            while (at > sample) {
                int count = Math.min(at - sample, 0xffff);
                if (count <= 16) emit(0x6f + count);
                else {
                    emit(WAIT);
                    emit(count & 0xff);
                    emit(count >>> 8);
                }
                sample += count;
            }
        }

        private void emit(int value) throws IOException {
            out.write(value);
            offset++;
        }
    }

    record Info(int samples, int loopSample, String title) {}

    @FunctionalInterface
    interface WriteSink {
        void accept(int sample, int command, int register, int value) throws IOException;
    }

    /** Checks command boundaries, waits, loop position and GD3 before publication. */
    static Info read(Path path, WriteSink sink) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            var raw = in.readNBytes(HEADER);
            require(raw.length == HEADER, "short header");
            var header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            require(header.getInt() == 0x206d6756 && header.getInt(8) == VERSION, "invalid signature or version");
            require(header.getInt(4) + 4L == Files.size(path), "incorrect EOF offset");
            require(header.getInt(0x0c) == PSG_CLOCK && header.getInt(0x2c) == YM_CLOCK, "incorrect chip clocks");
            require(header.getInt(0x34) + 0x34 == HEADER, "incorrect data offset");
            require(header.getShort(0x28) == PSG_FEEDBACK && header.get(0x2a) == PSG_WIDTH
                    && header.get(0x2b) == PSG_FLAGS, "incorrect PSG configuration");
            int gd3 = header.getInt(0x14) + 0x14;
            int total = header.getInt(0x18);
            int loopOffset = header.getInt(0x1c) == 0 ? -1 : header.getInt(0x1c) + 0x1c;
            int at = 0, offset = HEADER, loopSample = -1;
            boolean ended = false;
            while (offset < gd3) {
                if (offset == loopOffset) loopSample = at;
                int command = in.readUnsignedByte();
                offset++;
                if (command == END) {
                    ended = true;
                    break;
                }
                if (command >= 0x70 && command <= 0x7f) at += command - 0x6f;
                else if (command == WAIT) {
                    at += in.readUnsignedByte() | in.readUnsignedByte() << 8;
                    offset += 2;
                } else if (command == PSG || command == YM || command == YM_PORT_2) {
                    int register = command == PSG ? 0 : in.readUnsignedByte();
                    sink.accept(at, command, register, in.readUnsignedByte());
                    offset += command == PSG ? 1 : 2;
                } else throw new IOException("Invalid VGM command " + command);
                require(at <= total && offset < gd3, "command stream exceeds declared length");
            }
            require(ended && offset == gd3 && at == total, "missing end command or incorrect sample count");
            require(loopOffset == -1 ? header.getInt(0x20) == 0
                    : loopSample >= 0 && loopSample < total && header.getInt(0x20) == total - loopSample,
                    "incorrect loop sample count or offset");
            require(in.readInt() == 0x47643320 && Integer.reverseBytes(in.readInt()) == 0x100, "invalid GD3 header");
            int size = Integer.reverseBytes(in.readInt());
            require(size >= 22 && size % 2 == 0 && gd3 + 12L + size == Files.size(path), "invalid GD3 length");
            byte[] data = in.readNBytes(size);
            var fields = new String(data, StandardCharsets.UTF_16LE).split("\0", -1);
            require(data.length == size && fields.length == 12 && fields[11].isEmpty(), "invalid GD3 fields");
            return new Info(total, loopSample, fields[0]);
        } catch (EOFException failure) {
            throw new IOException("Truncated VGM", failure);
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException("Invalid VGM: " + message);
    }
}
