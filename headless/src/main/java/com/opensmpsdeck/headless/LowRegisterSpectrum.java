package com.opensmpsdeck.headless;

/** Pitch-aligned magnitude profile from a low note's attack and early sustain. */
final class LowRegisterSpectrum {
    static final int MAX_PITCH = 48; // C3, about 131 Hz
    private static final int HARMONICS = 16;
    private static final double FLOOR = .01;

    record Profile(double[] harmonics, double centroid, double lowPower, double subharmonicPower) {}

    private final java.util.Map<Integer, double[]> windows = new java.util.HashMap<>();
    private final LeadEffectMatcher.Spectrum fft = new LeadEffectMatcher.Spectrum();

    static boolean lowRegister(java.util.List<ReconstructionAllocation.Entry> notes) {
        long ticks = 0, lowTicks = 0;
        for (var note : notes) {
            int length = note.endTick() - note.startTick();
            ticks += length;
            if (note.note().pitch() <= MAX_PITCH) lowTicks += length;
        }
        return ticks > 0 && 3 * lowTicks >= 2 * ticks;
    }

    Profile profile(float[] input, int pitch) {
        return profile(input, 0, input.length, pitch);
    }

    Profile profile(float[] input, int start, int length, int pitch) {
        double fundamental = 440 * Math.pow(2, (pitch - 69) / 12.0);
        double binWidth = VoiceSpectra.RATE / (double) fft.real.length;
        if (fundamental * length / VoiceSpectra.RATE < 3) return null;
        double[] hann = windows.computeIfAbsent(length, size -> {
            var weights = new double[size];
            for (int i = 0; i < size; i++) weights[i] = .5 - .5 * Math.cos(Math.TAU * i / (size - 1));
            return weights;
        });
        fft.transform(input, start, hann);
        double[] profile = new double[HARMONICS];
        for (int h = 1; h <= HARMONICS; h++) {
            // The peak is allowed to move for vibrato, tuning and chip pitch error,
            // but cannot leave its harmonic's band.
            double halfWidth = Math.min(fundamental * .35, 1.5 * binWidth + .025 * h * fundamental);
            int from = Math.max(1, (int) Math.ceil((h * fundamental - halfWidth) / binWidth));
            int to = (int) Math.floor((h * fundamental + halfWidth) / binWidth);
            for (int bin = from; bin <= to; bin++) profile[h - 1] = Math.max(profile[h - 1], fft.power(bin));
            profile[h - 1] = Math.sqrt(profile[h - 1]);
        }
        double max = java.util.Arrays.stream(profile).max().orElse(0);
        double total = 0, firstMoment = 0, low = 0, below = 0, peak = 0;
        for (int bin = (int) Math.ceil(25 / binWidth); bin * binWidth <= 5_000; bin++) {
            double frequency = bin * binWidth;
            double power = fft.power(bin);
            total += power;
            firstMoment += frequency * power;
            if (frequency < 2.5 * fundamental) low += power;
            if (frequency < .72 * fundamental) below += power;
            peak = Math.max(peak, power);
        }
        // Decline a note window dominated by an unrelated pitch or stem bleed.
        if (max < .01 || max * max < .2 * peak) return null;
        // Scale against the whole harmonic stack so a missing fundamental is
        // measurable even when H1 is too weak to use as a denominator.
        for (int i = 0; i < profile.length; i++)
            profile[i] = 20 * Math.log10(Math.max(FLOOR, profile[i] / max));
        return new Profile(profile, firstMoment / (total * fundamental), low / total, below / total);
    }

    static double distance(Profile target, Profile candidate) {
        if (candidate == null) return 1_600;
        double error = 0;
        for (int i = 0; i < HARMONICS; i++) {
            double delta = candidate.harmonics()[i] - target.harmonics()[i];
            // Weight the pitch-bearing harmonics while still charging for
            // upper harmonics that are prominent in the patch but weak in the stem.
            double audibility = Math.pow(10, Math.max(target.harmonics()[i], candidate.harmonics()[i]) / 40);
            double excess = i >= 3 && delta > 0 ? 1.5 : 1;
            error += delta * delta * audibility * excess / Math.sqrt(i + 1);
        }
        // Include the low spectral envelope and energy between or above the
        // harmonics. A patch can have the right harmonic ratios and still buzz.
        double centroid = Math.log(candidate.centroid() / target.centroid()) / Math.log(2);
        double lowPower = candidate.lowPower() - target.lowPower();
        double subharmonic = candidate.subharmonicPower() - target.subharmonicPower();
        return error + (centroid > 0 ? 100 : 50) * centroid * centroid + 160 * lowPower * lowPower
                + 350 * Math.max(0, subharmonic) * Math.max(0, subharmonic);
    }
}
