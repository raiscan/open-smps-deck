package com.opensmpsdeck.project;

import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Original unsigned eight-bit mono PCM together with the native S3K rate byte. */
public record DacSample(String id, String name, int rate, String pcmBase64, AssetOrigin origin) {
    public DacSample(String id, String name, int rate, String pcmBase64) {
        this(id, name, rate, pcmBase64, null);
    }

    public DacSample withOrigin(AssetOrigin origin) {
        return new DacSample(id, name, rate, pcmBase64, origin);
    }

    public static final int BASE_CYCLES = 297;
    public static final int FIRST_NOTE = 0x81;
    public static final int MAX_SAMPLES = 95;
    public static final int MAX_SAMPLE_BYTES = 65535;
    public static final int MAX_TOTAL_PCM_BYTES = 512 * 1024;
    private static final int MAX_ENCODED_CHARS = ((MAX_SAMPLE_BYTES + 2) / 3) * 4;

    /** Returns a fresh decoded PCM array; malformed or oversized encodings are rejected. */
    public byte[] pcm() {
        if (pcmBase64 == null || pcmBase64.isEmpty() || pcmBase64.length() > MAX_ENCODED_CHARS)
            throw invalid("PCM must contain 1.." + MAX_SAMPLE_BYTES + " bytes");
        final byte[] decoded;
        try { decoded = Base64.getDecoder().decode(pcmBase64); }
        catch (IllegalArgumentException exception) { throw invalid("PCM must use canonical padded standard base64"); }
        if (decoded.length < 1 || decoded.length > MAX_SAMPLE_BYTES)
            throw invalid("PCM must contain 1.." + MAX_SAMPLE_BYTES + " bytes");
        if (!Base64.getEncoder().encodeToString(decoded).equals(pcmBase64))
            throw invalid("PCM must use canonical padded standard base64");
        return decoded;
    }

    public static DacSample of(String id, String name, int rate, byte[] pcm) {
        Objects.requireNonNull(pcm, "pcm");
        if (pcm.length < 1 || pcm.length > MAX_SAMPLE_BYTES)
            throw new IllegalArgumentException("DAC sample PCM must contain 1.." + MAX_SAMPLE_BYTES + " bytes");
        DacSample sample = new DacSample(id, name, rate, Base64.getEncoder().encodeToString(pcm));
        validateAll(List.of(sample));
        return sample;
    }

    /** Applies linear gain in place around the unsigned PCM midpoint. */
    public static byte[] applyGain(byte[] pcm, double gain) {
        if (gain == 1) return pcm;
        for (int i = 0; i < pcm.length; i++) {
            int amplitude = Byte.toUnsignedInt(pcm[i]) - 128;
            pcm[i] = (byte) Math.clamp(Math.round(amplitude * gain) + 128, 0, 255);
        }
        return pcm;
    }

    public double sampleRateHz() { return sampleRateHz(rate); }

    /** Native DJNZ rate zero means 256 loops, rather than the fastest rate. */
    public static double sampleRateHz(int rate) {
        int cycles = periodCycles(rate);
        return (53693175.0 / 15.0) * 2.0 / cycles;
    }

    static int periodCycles(int rate) {
        if (rate < 0 || rate > 255) throw new IllegalArgumentException("DAC sample rate must be in 0..255 (was " + rate + ")");
        int loops = rate == 0 ? 256 : rate;
        return BASE_CYCLES + 26 * (loops - 1);
    }

    public static void validateAll(List<DacSample> samples) {
        if (samples == null) throw new IllegalArgumentException("DAC samples are required");
        if (samples.size() > MAX_SAMPLES) throw new IllegalArgumentException("At most " + MAX_SAMPLES + " DAC samples can be addressed");
        Set<String> ids = new HashSet<>();
        long total = 0;
        for (DacSample sample : samples) {
            if (sample == null) throw new IllegalArgumentException("DAC sample must not be null");
            if (sample.id == null || sample.id.isBlank()) throw sample.invalid("id must be a nonblank string");
            if (sample.name == null || sample.name.isBlank()) throw sample.invalid("name must be a nonblank string");
            if (!ids.add(sample.id)) throw sample.invalid("duplicate sample id");
            periodCycles(sample.rate);
            total += sample.pcm().length;
            if (total > MAX_TOTAL_PCM_BYTES) throw new IllegalArgumentException("DAC samples exceed the 512 KiB decoded PCM limit");
        }
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("DAC sample '" + id + "': " + message);
    }
}
