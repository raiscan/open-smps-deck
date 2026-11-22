package com.opensmpsdeck.project;

/** Driver limits and clocks used when building a reconstruction. */
public record TargetProfile(TargetGame game, PitchRange psg, PitchRange fm, int dacBaseCycles,
                            EnvelopeGrammar envelopeGrammar, boolean modulationEnvelopes,
                            boolean fmVolumeEnvelopes, boolean ssgEg, CarrierSelection carrierSelection,
                            TargetClock clock, int bankLimitBytes) {
    public record PitchRange(int low, int high) {
        public boolean contains(int pitch) { return pitch >= low && pitch <= high; }
        public int clamp(int pitch) { return Math.clamp(pitch, low, high); }
    }

    public enum EnvelopeGrammar { S3K_CONTROLS, HOLD_80 }
    public enum CarrierSelection { TL_HIGH_BIT, ALGORITHM }

    private static final PitchRange FM = new PitchRange(12, 106);
    private static final PitchRange CLASSIC_PSG = new PitchRange(48, 116);
    public static final TargetProfile S3K = new TargetProfile(TargetGame.S3K, new PitchRange(45, 117), FM,
            DacSample.BASE_CYCLES, EnvelopeGrammar.S3K_CONTROLS, true, true, true, CarrierSelection.TL_HIGH_BIT,
            TargetClock.FRAME_CLOCK, ProjectCompiler.MAX_BANK_BYTES);
    private static final TargetProfile S1 = classic(TargetGame.S1, 301);
    private static final TargetProfile S2 = classic(TargetGame.S2, 295);

    private static TargetProfile classic(TargetGame game, int dacBaseCycles) {
        return new TargetProfile(game, CLASSIC_PSG, FM, dacBaseCycles, EnvelopeGrammar.HOLD_80,
                false, false, false, CarrierSelection.ALGORITHM, new TargetClock(game, 255), ProjectCompiler.MAX_BANK_BYTES);
    }

    public static TargetProfile of(TargetGame game) {
        return switch (game) {
            case S1 -> S1;
            case S2 -> S2;
            case S3K -> S3K;
        };
    }

    public double ticksPerSecond() { return clock.ticksPerSecond(); }

    public TargetProfile withClock(TargetClock clock) {
        return new TargetProfile(game, psg, fm, dacBaseCycles, envelopeGrammar, modulationEnvelopes,
                fmVolumeEnvelopes, ssgEg, carrierSelection, clock, bankLimitBytes);
    }

    public double dacSampleRateHz(int rate) {
        return DacSample.sampleRateHz(rate, dacBaseCycles);
    }
}
