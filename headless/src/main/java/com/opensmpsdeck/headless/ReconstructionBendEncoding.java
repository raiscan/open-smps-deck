package com.opensmpsdeck.headless;

import com.openggf.audio.synth.ChipWriteObserver;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Fits and checks native pitch changes on an existing FM gate. */
final class ReconstructionBendEncoding {
    private static final double CENTS_PER_LOG = 1200 / Math.log(2);
    private static final int MAX_TIED_STEPS = 14;

    private ReconstructionBendEncoding() {}

    enum Mode { F0, E7 }

    record Applied(String id, String lane, int startTick, int endTick, Mode mode, double rmseCents, String reason) {}
    record Fit(Mode mode, List<NoteEvent> notes, List<CommandEvent> commands, double rmseCents) {
        boolean modulatingAtEnd() {
            return mode == Mode.F0 && commands.stream().noneMatch(c -> c.command() == NativeCommand.MODULATION_OFF);
        }
    }

    private record Pitch(int note, int detune) {}
    private record Ramp(int start, int stop, int rate, int delta, double error) {}

    static Fit fit(ReconstructionStages.Note note, int startTick, int duration, int detune, Voice voice)
            throws IOException {
        var bend = note.bend();
        if (bend == null || duration < 4
                || Math.abs(bend.points().getFirst().time() - startTick / 60.0) > .025) return null;
        // Precision beyond the last tracked frame is irrelevant to a shorter compiled gate.
        double[] desired = new double[duration];
        for (int i = 0; i < duration; i++) desired[i] = bend.cents((startTick + i + .5) / 60.0);
        int pitch = note.pitch();
        double base = linear(packed(new Pitch(pitch, detune)));
        double baseline = 0, excursion = 0;
        for (double cents : desired) {
            baseline += cents * cents;
            excursion = Math.max(excursion, Math.abs(cents - desired[0]));
        }
        if (excursion < 60) return null;
        double flatError = Math.sqrt(baseline / duration);

        Fit steps = measure(steps(pitch, detune, base, desired), voice, base, desired);
        Fit ramp = measure(ramp(pitch, detune, base, desired), voice, base, desired);
        // Prefer the more compact ramp when the scores differ by less than 10 cents.
        Fit best = ramp != null && (steps == null || ramp.rmseCents() <= steps.rmseCents() + 10) ? ramp : steps;
        return best != null && best.rmseCents() <= Math.min(90, flatError * .66) ? best : null;
    }

    private static Fit steps(int pitch, int detune, double base, double[] desired) {
        // Keep the gate intact, using detuned equal-tempered notes as staircase steps.
        // Increasing the tolerance first keeps the byte cost bounded on long bends.
        for (int tolerance = 26; tolerance <= 96; tolerance += 10) {
            var notes = new ArrayList<NoteEvent>();
            var commands = new ArrayList<CommandEvent>();
            int start = 0;
            while (start < desired.length && notes.size() < MAX_TIED_STEPS) {
                int end = start + 1;
                while (end < desired.length && Math.abs(desired[end] - desired[start]) <= tolerance) end++;
                double center = start == 0 ? desired[0] : (desired[start] + desired[end - 1]) / 2;
                Pitch step = nearest(pitch, detune, base, center);
                append(notes, commands, start, end, step);
                start = end;
            }
            if (start == desired.length) return new Fit(Mode.E7, notes, commands, 0);
        }
        return null;
    }

    private static Fit ramp(int pitch, int detune, double base, double[] desired) {
        // The S3K delay decrements before the rate counter, including at note-on.
        // Zero steps prevent reversal for 256 active passes. Stop explicitly with
        // an E7 continuation if a plateau is reached before release.
        Pitch attack = nearest(pitch, detune, base, desired[0]);
        int packed = packed(attack);
        int fnum = packed & 0x7ff;
        int block = packed >> 11;
        double first = CENTS_PER_LOG * Math.log(linear(packed) / base);
        int moving = 0;
        while (moving + 1 < desired.length && Math.abs(desired[moving] - first) < 18) moving++;
        int plateau = desired.length - 1;
        while (plateau > moving && Math.abs(desired[plateau - 1] - desired[desired.length - 1]) < 16) plateau--;
        Ramp best = null;
        for (int start = Math.max(0, moving - 4); start <= Math.min(250, moving + 2); start++) {
            int lastStop = Math.min(Math.min(desired.length, start + 250), plateau + 4);
            for (int stop = Math.max(start + 1, plateau - 3); stop <= lastStop; stop++) {
                for (int rate = 1; rate <= 3 && rate <= start + 1; rate++) {
                    double sum = 0, square = 0;
                    int holdCount = (stop - start + rate - 1) / rate;
                    for (int i = start; i < desired.length; i++) {
                        int count = Math.min(holdCount, 1 + (i - start) / rate);
                        double offset = base * Math.exp(desired[i] / CENTS_PER_LOG) / (1 << block) - fnum;
                        sum += count * offset;
                        square += count * count;
                    }
                    int delta = (int) Math.clamp(Math.round(sum / square), -128, 127);
                    if (delta == 0 || fnum + holdCount * delta <= 0 || fnum + holdCount * delta >= 0x800) continue;
                    double error = 0;
                    for (int i = start; i < desired.length; i++) {
                        int count = Math.min(holdCount, 1 + (i - start) / rate);
                        double cents = CENTS_PER_LOG * Math.log(linear(packed + count * delta) / base);
                        double residual = cents - desired[i];
                        error += residual * residual;
                    }
                    for (int i = 0; i < start; i++) error += Math.pow(first - desired[i], 2);
                    if (best == null || best.error() > error) best = new Ramp(start, stop, rate, delta, error);
                }
            }
        }
        if (best == null) return null;
        var notes = new ArrayList<NoteEvent>();
        var commands = new ArrayList<CommandEvent>();
        append(notes, commands, 0, best.stop(), attack);
        commands.add(new CommandEvent("bend-f0", 0, NativeCommand.MODULATION,
                List.of(best.start() - best.rate() + 2, best.rate(), best.delta() & 255, 0)));
        if (best.stop() < desired.length) {
            int count = (best.stop() - best.start() + best.rate() - 1) / best.rate();
            double cents = CENTS_PER_LOG * Math.log(linear(packed + best.delta() * count) / base);
            commands.add(new CommandEvent("bend-off", best.stop(), NativeCommand.MODULATION_OFF, List.of()));
            append(notes, commands, best.stop(), desired.length, nearest(pitch, detune, base, cents));
        }
        return new Fit(Mode.F0, notes, commands, 0);
    }

    private static Fit measure(Fit fit, Voice voice, double base, double[] desired) throws IOException {
        if (fit == null) return null;
        var sequence = new Sequence("probe", "Bend probe", "FM", desired.length, voice.id(), 100,
                fit.notes(), fit.commands(), "RESET");
        var probe = new StudioProject(4, "Bend probe", "S3K", 30, 0, List.of(voice),
                List.of(sequence), List.of(new Clip("probe", sequence.id(), "FM1", 0, desired.length)));
        var trace = new FrequencyTrace();
        var compiled = ProjectCompiler.compileWithAssets(probe);
        S3kRenderer.render(compiled, desired.length, pcm -> trace.frame(desired, base), trace);
        return new Fit(fit.mode(), fit.notes(), fit.commands(), Math.sqrt(trace.squaredError / desired.length));
    }

    private static final class FrequencyTrace implements ChipWriteObserver {
        int high, packed, tick;
        double squaredError;

        @Override public void onYm2612Write(int port, int register, int value) {
            if (port != 0) return;
            if (register == 0xa4) high = value;
            if (register == 0xa0) packed = (high << 8) | value;
        }

        @Override public void onPsgWrite(int value) {}

        void frame(double[] desired, double base) {
            double cents = CENTS_PER_LOG * Math.log(linear(packed) / base);
            double error = cents - desired[tick++];
            squaredError += error * error;
        }
    }

    private static void append(List<NoteEvent> notes, List<CommandEvent> commands, int start, int end, Pitch pitch) {
        notes.add(new NoteEvent("bend-note-" + start, start, end - start, pitch.note()));
        if (start > 0) commands.add(new CommandEvent("bend-hold-" + start, start, NativeCommand.HOLD, List.of()));
        commands.add(new CommandEvent("bend-detune-" + start, start, NativeCommand.DETUNE, List.of(pitch.detune() & 255)));
    }

    private static Pitch nearest(int pitch, int detune, double base, double cents) {
        if (Math.abs(cents) < 1) return new Pitch(pitch, detune);
        int note = Math.clamp(pitch + (int) Math.round(cents / 100), 12, 106);
        int block = (note - 12) / 12;
        double target = base * Math.exp(cents / CENTS_PER_LOG) / (1 << block);
        int offset = (int) Math.clamp(Math.round(target - LeadEffectMatcher.fmFnum(note)), -128, 127);
        return new Pitch(note, offset);
    }

    private static int packed(Pitch pitch) {
        return (((pitch.note() - 12) / 12 << 11) | LeadEffectMatcher.fmFnum(pitch.note())) + pitch.detune();
    }

    private static double linear(int packed) {
        return (packed & 0x7ff) * (1 << ((packed >> 11) & 7));
    }
}
