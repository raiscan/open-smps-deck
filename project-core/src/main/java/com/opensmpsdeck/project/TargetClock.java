package com.opensmpsdeck.project;

/** NTSC duration clock, including the tempo service on the load frame. */
public record TargetClock(TargetGame game, int tempo) {
    public static final int FRAMES_PER_SECOND = 60;
    public static final TargetClock FRAME_CLOCK = new TargetClock(TargetGame.S3K, 0);

    public TargetClock {
        int minimum = switch (game) {
            case S1 -> 2;
            case S2 -> 1;
            case S3K -> 0;
        };
        if (tempo < minimum || tempo > 255)
            throw new IllegalArgumentException(game.id() + " tempo must be in " + minimum + "..255");
    }

    public double ticksPerSecond() {
        return switch (game) {
            case S1 -> FRAMES_PER_SECOND * (tempo - 1.0) / tempo;
            case S2 -> FRAMES_PER_SECOND * tempo / 256.0;
            case S3K -> FRAMES_PER_SECOND * (256 - tempo) / 256.0;
        };
    }

    /** Number of duration updates in the first frames presentations. */
    public long ticksAfterFrames(long frames) {
        if (frames <= 0) return 0;
        return switch (game) {
            case S1 -> frames - frames / tempo;
            case S2 -> (frames + 1) * tempo / 256;
            case S3K -> frames - frames * tempo / 256;
        };
    }

    /** Zero-based presentation in which the zero-based arrangement tick is serviced. */
    public long frameAtTick(long tick) {
        return switch (game) {
            case S1 -> tick + tick / (tempo - 1);
            case S2 -> ((tick + 1) * 256 + tempo - 1) / tempo - 2;
            case S3K -> tick * 256 / (256 - tempo);
        };
    }

    /** Closest update to a source frame position. Ties go to the later tick. */
    public long nearestTick(double frame) {
        if (equals(FRAME_CLOCK)) return Math.round(frame);
        long next = ticksAfterFrames((long) Math.floor(frame) + 1);
        long prior = Math.max(0, next - 1);
        return frame - frameAtTick(prior) < frameAtTick(next) - frame ? prior : next;
    }

    public double secondsAtTick(long tick) {
        return frameAtTick(tick) / (double) FRAMES_PER_SECOND;
    }
}
