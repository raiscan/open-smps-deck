package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.List;

/** Streaming stereo loudness power at 48 kHz, per ITU-R BS.1770 Annex 1. */
final class KWeightedLevel {
    private static final int HOP = 4_800;
    private static final int HOPS_PER_BLOCK = 4;
    static final double ABSOLUTE_GATE = Math.pow(10, (-70 + .691) / 10);

    private final Channel left = new Channel();
    private final Channel right = new Channel();
    private final double[] hops = new double[HOPS_PER_BLOCK];
    private final List<Double> blocks = new ArrayList<>();
    private int frames;
    private int hop;
    private double energy;

    void add(double l, double r) {
        energy += left.power(l) + right.power(r);
        if (++frames == HOP) {
            hops[hop % HOPS_PER_BLOCK] = energy;
            if (++hop >= HOPS_PER_BLOCK) {
                blocks.add((hops[0] + hops[1] + hops[2] + hops[3]) / (HOP * HOPS_PER_BLOCK));
            }
            energy = 0;
            frames = 0;
        }
    }

    double power() {
        double active = meanAbove(ABSOLUTE_GATE);
        return meanAbove(Math.max(ABSOLUTE_GATE, active * .1));
    }

    private double meanAbove(double gate) {
        double total = 0;
        int count = 0;
        for (double block : blocks) {
            if (block <= gate) continue;
            total += block;
            count++;
        }
        return count == 0 ? 0 : total / count;
    }

    private static final class Channel {
        // BS.1770 Tables 1 and 2, showing the two stages at 48 kHz.
        private final Biquad shelf = new Biquad(1.53512485958697, -2.69169618940638, 1.19839281085285,
                -1.69065929318241, .73248077421585);
        private final Biquad highPass = new Biquad(1, -2, 1, -1.99004745483398, .99007225036621);

        double power(double value) {
            double filtered = highPass.filter(shelf.filter(value));
            return filtered * filtered;
        }
    }

    private static final class Biquad {
        private final double b0, b1, b2, a1, a2;
        private double s1, s2;

        Biquad(double b0, double b1, double b2, double a1, double a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        double filter(double sample) {
            double output = b0 * sample + s1;
            s1 = b1 * sample - a1 * output + s2;
            s2 = b2 * sample - a2 * output;
            return output;
        }
    }
}
