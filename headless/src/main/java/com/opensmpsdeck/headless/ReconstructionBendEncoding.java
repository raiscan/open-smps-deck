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
    static final int MAX_CENTS = (106 - 12) * 100;

    private ReconstructionBendEncoding() {}

    enum Mode { F0, E7 }

    record Applied(String id, String lane, int startTick, int endTick, Mode mode, double rmseCents, String reason) {}
    record Fit(Mode mode, List<NoteEvent> notes, List<CommandEvent> commands, double rmseCents,
               ReconstructionVoicing.Vibrato endingVibrato) {
        Fit(Mode mode, List<NoteEvent> notes, List<CommandEvent> commands, double rmseCents) {
            this(mode, notes, commands, rmseCents, null);
        }
        boolean modulatingAtEnd() {
            return mode == Mode.F0 && commands.stream().noneMatch(c -> c.command() == NativeCommand.MODULATION_OFF);
        }
    }

    private record Pitch(int note, int detune) {}
    private record Ramp(int start, int stop, int rate, int delta, double error) {}

    static Fit fit(ReconstructionStages.Note note, int startTick, int duration, int detune, Voice voice)
            throws IOException {
        return fit(note, startTick, duration, detune, voice, null);
    }

    static Fit fit(ReconstructionStages.Note note, int startTick, int duration, int detune, Voice voice,
                   ReconstructionVoicing.Vibrato vibrato) throws IOException {
        return fit(note, startTick, duration, detune, voice, vibrato, java.util.Map.of());
    }

    static Fit fit(ReconstructionStages.Note note, int startTick, int duration, int detune, Voice voice,
                   ReconstructionVoicing.Vibrato vibrato, java.util.Map<Integer, ReconstructionVoicing.Vibrato> changes)
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
        if (bend.editReason() == null && excursion < 60) return null;
        double flatError = Math.sqrt(baseline / duration);

        int limit = Math.max(14, (int) Math.ceil(excursion / 80) + 2);
        Fit steps = measure(steps(pitch, detune, base, desired, limit), voice, base, desired);
        boolean modulated = vibrato != null || !changes.isEmpty();
        Fit ramp = !modulated ? measure(ramp(pitch, detune, base, desired), voice, base, desired) : null;
        // Prefer the more compact ramp when the scores differ by less than 10 cents.
        Fit best = ramp != null && (steps == null || ramp.rmseCents() <= steps.rmseCents() + 10) ? ramp : steps;
        if (best == null || best.rmseCents() > Math.min(90, flatError * .66)) return null;
        return modulated ? withVibrato(best, desired, vibrato, changes) : best;
    }

    private static Fit steps(int pitch, int detune, double base, double[] desired, int limit) {
        // Keep the gate intact, using detuned equal-tempered notes as staircase steps.
        // Increasing the tolerance first keeps the byte cost bounded on long bends.
        for (int tolerance = 26; tolerance <= 96; tolerance += 10) {
            var notes = new ArrayList<NoteEvent>();
            var commands = new ArrayList<CommandEvent>();
            int start = 0;
            while (start < desired.length && notes.size() < limit) {
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

    private static Fit withVibrato(Fit steps, double[] desired, ReconstructionVoicing.Vibrato vibrato,
                                  java.util.Map<Integer, ReconstructionVoicing.Vibrato> changes) {
        var commands = new ArrayList<>(steps.commands());
        var notes = new ArrayList<>(steps.notes());
        // F0 on the attack prepares vibrato. FA pauses it without discarding the live
        // counters. Loading F0 on a tied plateau resumes them, including the old phase.
        int moving = notes.size() > 1 ? notes.get(1).tick() : desired.length;
        int plateau = desired.length - 1;
        while (plateau > moving && Math.abs(desired[plateau - 1] - desired[desired.length - 1]) < 16) plateau--;
        if (plateau == desired.length - 1) plateau = desired.length;
        var boundaries = new java.util.TreeSet<>(changes.keySet());
        boundaries.add(0);
        boundaries.add(moving);
        boundaries.add(plateau);
        ReconstructionVoicing.Vibrato active = null, prepared = null;
        for (int tick : boundaries.headSet(desired.length)) {
            if (changes.containsKey(tick)) vibrato = changes.get(tick);
            var next = tick >= moving && tick < plateau ? null : vibrato;
            if (java.util.Objects.equals(active, next)) continue;
            if (next == null) {
                commands.add(new CommandEvent("bend-pause-" + tick, tick, NativeCommand.MODULATION_OFF, List.of()));
            } else {
                if (tick != 0 && !next.equals(prepared)) reattack(notes, commands, tick);
                commands.add(new CommandEvent("bend-vibrato-" + tick, tick, NativeCommand.MODULATION, next.bytes()));
                prepared = next;
            }
            active = next;
        }
        return new Fit(Mode.E7, notes, commands, steps.rmseCents(), active);
    }

    private static void reattack(List<NoteEvent> notes, List<CommandEvent> commands, int tick) {
        for (int i = 0; i < notes.size(); i++) {
            var step = notes.get(i);
            if (tick == step.tick()) {
                commands.removeIf(c -> c.tick() == tick && c.command() == NativeCommand.HOLD);
                break;
            }
            if (tick > step.tick() && tick < step.tick() + step.duration()) {
                notes.set(i, new NoteEvent(step.id(), step.tick(), tick - step.tick(), step.pitch()));
                notes.add(i + 1, new NoteEvent("bend-reattack-" + tick, tick,
                        step.tick() + step.duration() - tick, step.pitch()));
                break;
            }
        }
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
        int[] packed = frequencies(fit, voice, desired.length);
        double squaredError = 0;
        for (int tick = 0; tick < desired.length; tick++) {
            double cents = CENTS_PER_LOG * Math.log(linear(packed[tick]) / base);
            double error = cents - desired[tick];
            squaredError += error * error;
        }
        return new Fit(fit.mode(), fit.notes(), fit.commands(), Math.sqrt(squaredError / desired.length));
    }

    static int[] frequencies(Fit fit, Voice voice, int duration) throws IOException {
        var sequence = new Sequence("probe", "Bend probe", "FM",
                fit.notes().getLast().tick() + fit.notes().getLast().duration(), voice.id(), 100,
                fit.notes(), fit.commands(), "RESET");
        var probe = new StudioProject(4, "Bend probe", "S3K", 30, 0, List.of(voice),
                List.of(sequence), List.of(new Clip("probe", sequence.id(), "FM1", 0, duration)));
        var trace = new FrequencyTrace();
        var compiled = ProjectCompiler.compileWithAssets(probe);
        int[] packed = new int[duration];
        S3kRenderer.render(compiled, duration, pcm -> packed[trace.tick++] = trace.packed, trace);
        return packed;
    }

    private static final class FrequencyTrace implements ChipWriteObserver {
        int high, packed, tick;

        @Override public void onYm2612Write(int port, int register, int value) {
            if (port != 0) return;
            if (register == 0xa4) high = value;
            if (register == 0xa0) packed = (high << 8) | value;
        }

        @Override public void onPsgWrite(int value) {}
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

    static double linear(int packed) {
        return (packed & 0x7ff) * (1 << ((packed >> 11) & 7));
    }
}
