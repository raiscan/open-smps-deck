import com.opensmpsdeck.headless.S3kRenderer;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.openggf.audio.session.OwnedSmpsAudioStream;
import com.openggf.audio.session.SmpsHardwareFrameBudget;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.FmCoreSelection;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsPhysicalPolicy;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsData;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Compiled independently against original Git objects and the vendored core. */
public final class S3kParityProbe {
    public static void main(String[] args) throws Exception {
        probe("fm", fm(false));
        probe("fm-call-loop", fm(true));
        probe("psg", psg());
        probeProduction();
        probeProductionDac();
    }

    private static void probeProduction() throws Exception {
        MessageDigest pcm = MessageDigest.getInstance("SHA-256");
        long[] energy = {0};
        S3kRenderer.render(
                com.opensmpsdeck.headless.S3kDemo.song(), 120, samples -> {
                    for (short sample : samples) {
                        pcm.update((byte) sample); pcm.update((byte) (sample >>> 8));
                        energy[0] += Math.abs((int) sample);
                    }
                });
        if (energy[0] == 0) throw new AssertionError("production demo rendered silence");
        System.out.println("production-demo pcm=" + HexFormat.of().formatHex(pcm.digest())
                + " energy=" + energy[0]);
    }

    private static void probeProductionDac() throws Exception {
        byte[] bank = {0, 0, 1, 0, 1, 0, 10, 0, 0, 0,
                (byte) 0x81, 2, (byte) 0x80, 3, (byte) 0x82, 8, (byte) 0xF2};
        byte[] slow = new byte[120];
        Arrays.fill(slow, (byte) 0x20);
        byte[] fast = {(byte) 0xFE, 0, (byte) 0xFF, 3, 5, 0x60};
        var project = new CompiledProject(bank, List.of(), List.of(
                DacSample.of("slow", "Slow", 0, slow), DacSample.of("fast", "Fast", 1, fast)));
        MessageDigest pcm = MessageDigest.getInstance("SHA-256");
        MessageDigest writes = MessageDigest.getInstance("SHA-256");
        int[] streamBytes = {0};
        var observer = new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                if (port == 0 && (register == 0x2A || register == 0x2B)) {
                    writes.update((byte) register);
                    writes.update((byte) value);
                    if (register == 0x2A) streamBytes[0]++;
                }
            }
            @Override public void onPsgWrite(int value) {}
        };
        S3kRenderer.render(project, 15, samples -> {
            for (short sample : samples) {
                pcm.update((byte) sample);
                pcm.update((byte) (sample >>> 8));
            }
        }, observer);
        if (streamBytes[0] <= fast.length || streamBytes[0] >= slow.length + fast.length)
            throw new AssertionError("production DAC did not stream and replace its samples");
        System.out.println("production-dac pcm=" + HexFormat.of().formatHex(pcm.digest())
                + " writes=" + HexFormat.of().formatHex(writes.digest()) + " bytes=" + streamBytes[0]);
    }

    private static void probe(String name, byte[] song) throws Exception {
        MessageDigest writes = MessageDigest.getInstance("SHA-256");
        MessageDigest pcm = MessageDigest.getInstance("SHA-256");
        ChipWriteObserver observer = new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                writes.update(new byte[]{0, (byte) port, (byte) register, (byte) value});
            }
            @Override public void onPsgWrite(int value) {
                writes.update(new byte[]{1, (byte) value});
            }
        };
        long energy = 0;
        var settings = SmpsHardwareFrameBudget.presentationSettings(
                48_000, 60, false, false, FmCoreSelection.ACCURATE, null);
        try (var stream = new OwnedSmpsAudioStream("s3k", 0, settings,
                Sonic3kSmpsPhysicalPolicy.INSTANCE, observer)) {
            var data = new Sonic3kSmpsData(song, 0);
            var config = Sonic3kSmpsSequencerConfig.create(new Sonic3kCoordFlagHandler());
            var dac = new DacData(java.util.Map.of(), java.util.Map.of(), 297);
            var sequencer = new SmpsSequencer(data, dac, stream.logicalDriver(),
                    () -> { throw new IllegalStateException("unexpected music restore"); }, config);
            stream.logicalDriver().addSequencer(sequencer, false);
            short[] buffer = new short[1_600];
            for (int frame = 0; frame < 120; frame++) {
                stream.serviceAndRenderFrame(buffer, 800);
                for (short sample : buffer) {
                    pcm.update((byte) sample);
                    pcm.update((byte) (sample >>> 8));
                    energy += Math.abs((int) sample);
                }
            }
            if (!stream.isComplete()) throw new AssertionError(name + " did not terminate");
        }
        if (energy == 0) throw new AssertionError(name + " rendered silence");
        System.out.println(name + " pcm=" + HexFormat.of().formatHex(pcm.digest())
                + " writes=" + HexFormat.of().formatHex(writes.digest()) + " energy=" + energy);
    }

    // DAC stub followed by FM1; local, deliberately asymmetric native voice.
    private static byte[] fm(boolean controlFlow) {
        int[] track = controlFlow
                ? new int[]{0xEF, 0, 0xE0, 0xC0,
                    0xF8, 33, 0,                 // CALL subroutine
                    0xF7, 1, 3, 19, 0,           // repeat CALL with loop slot 1
                    0xE5, 9, 2,                  // S3K ignores first volume operand
                    0xAD, 12, 0xF2,
                    0xA5, 12, 0xF9}              // subroutine note and S3K RETURN
                : new int[]{0xEF, 0, 0xE0, 0xC0, 0xA5, 24, 0xAD, 24, 0xF2};
        int voice = 15 + track.length;
        byte[] data = new byte[voice + 25];
        word(data, 0, voice);
        data[2] = 2; data[4] = 1; data[5] = (byte) 0x80;
        word(data, 6, 14); word(data, 10, 15);
        data[14] = (byte) 0xF2;
        for (int i = 0; i < track.length; i++) data[15 + i] = (byte) track[i];
        int[] patch = {7, 1, 2, 3, 4, 0x1F, 0x1E, 0x1D, 0x1C,
                0, 0, 0, 0, 0, 0, 0, 0, 0x0F, 0x0F, 0x0F, 0x0F, 16, 24, 32, 40};
        for (int i = 0; i < patch.length; i++) data[voice + i] = (byte) patch[i];
        return data;
    }

    private static byte[] psg() {
        byte[] data = new byte[17];
        data[3] = 1; data[4] = 1; data[5] = (byte) 0x80;
        word(data, 6, 12);
        data[12] = (byte) 0xA5; data[13] = 24;
        data[14] = (byte) 0xAD; data[15] = 24; data[16] = (byte) 0xF2;
        return data;
    }

    private static void word(byte[] data, int offset, int value) {
        data[offset] = (byte) value; data[offset + 1] = (byte) (value >>> 8);
    }
}
