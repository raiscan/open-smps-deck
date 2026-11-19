package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.S3kRenderer;
import com.opensmpsdeck.project.StudioProject;

import java.util.HashSet;
import java.util.Set;

/** Project time and monitor controls shared by the arrangement views. */
final class ReviewTransport {
    enum Mode {
        RENDER("Render"), REFERENCE("Reference"), AB("A/B");
        private final String label;
        Mode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    record Range(double start, double end) {
        Range {
            if (!(start >= 0 && end > start && Double.isFinite(end))) throw new IllegalArgumentException("Invalid loop");
        }
    }

    private record Monitor(Mode mode, boolean referenceSide) {}
    private volatile Monitor monitor = new Monitor(Mode.RENDER, false);
    volatile Range loop;
    double seconds;

    void mode(Mode next) { monitor = new Monitor(next, false); }
    boolean referenceAudible() {
        var current = monitor;
        return current.mode == Mode.REFERENCE || current.mode == Mode.AB && current.referenceSide;
    }
    void ab(boolean referenceSide) { monitor = new Monitor(Mode.AB, referenceSide); }

    static double ticksPerSecond(StudioProject project) {
        return project == null || project.nativeProgram() != null ? 60 : 60 * (256 - project.tempo()) / 256.0;
    }

    static final class Lanes {
        private final Set<String> solo = new HashSet<>();
        private final Set<String> mute = new HashSet<>();

        boolean soloed(String lane) { return solo.contains(lane); }
        boolean muted(String lane) { return mute.contains(lane); }
        void toggle(String lane, boolean selectSolo) {
            Set<String> set = selectSolo ? solo : mute;
            if (!set.remove(lane)) set.add(lane);
        }
        void clear() { solo.clear(); mute.clear(); }

        S3kRenderer.ChannelMask mask() {
            int fm = 0, psg = 0;
            for (String lane : TimelineView.LANES) {
                if (!muted(lane) && (solo.isEmpty() || soloed(lane))) continue;
                if (lane.startsWith("PSG")) {
                    int index = lane.charAt(3) - '1';
                    psg |= index == 2 ? 0b1100 : 1 << index;
                } else {
                    fm |= lane.equals("DAC") ? 1 << 5 : 1 << (lane.charAt(2) - '1');
                }
            }
            return new S3kRenderer.ChannelMask(fm, psg);
        }
    }
}
