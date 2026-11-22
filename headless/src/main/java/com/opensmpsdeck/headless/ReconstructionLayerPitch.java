package com.opensmpsdeck.headless;

import com.openggf.audio.synth.PsgChip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.Voice;
import com.opensmpsdeck.project.TargetProfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;

import static com.opensmpsdeck.headless.ReconstructionBendEncoding.Fit;

/** Transfers the rendered FM contour, including its live modulation, to a layer gate. */
final class ReconstructionLayerPitch {
    private static final double FM_HZ_PER_UNIT = 7_670_453.0 / (144 * (1 << 21));
    private static final double PSG_HZ_PER_PERIOD = PsgChip.TICK_RATE_HZ / 2;
    static final double TOLERANCE_CENTS = 15;

    record Copy(Fit fit, ReconstructionLayers.Curve curve) {}
    private record Pitch(int note, int detune) {}

    // A subset of DEF_PSGFREQ_Z80_T2 from the pinned sequencer. These anchors
    // plus signed E1 offsets cover the full 10-bit nonzero period range.
    private static final int[] PSG_NOTES = {84, 72, 60, 54, 49, 45};
    private static final int[] PSG_PERIODS = {107, 214, 427, 604, 806, 1015};

    private ReconstructionLayerPitch() {}

    static Fit fitPsg(ReconstructionStages.Note note, int start, int duration, int detune, TargetProfile profile) {
        var clock = profile.clock();
        var bend = note.bend();
        if (duration < 4 || Math.abs(bend.points().getFirst().time() - clock.secondsAtTick(start)) > .025)
            return null;
        double base = PSG_HZ_PER_PERIOD / ReconstructionLeadAudio.hz(note.pitch()) + detune;
        if (base <= 0) return null;
        var copy = steps(duration, false, tick -> {
            int at = start + tick;
            double midpoint = (clock.frameAtTick(at) + clock.frameAtTick(at + 1)) / 120.0;
            return PSG_HZ_PER_PERIOD / (FM_HZ_PER_UNIT * base) * Math.pow(2, bend.cents(midpoint) / 1200);
        }, tick -> tick == 0);
        return copy.fit().notes().size() > 1 && copy.curve().withinTolerance() ? copy.fit() : null;
    }

    static Copy copy(Fit dry, Voice voice, boolean targetFm, int duration, int sourcePitch, int targetPitch,
                     int detuneOffset) throws IOException {
        return copy(dry, voice, targetFm, duration, sourcePitch, targetPitch, detuneOffset, TargetProfile.S3K);
    }

    static Copy copy(Fit dry, Voice voice, boolean targetFm, int duration, int sourcePitch, int targetPitch,
                     int detuneOffset, TargetProfile profile) throws IOException {
        int transpose = targetPitch - sourcePitch;
        if (voice != null && targetFm && detuneOffset == 0
                && dry.notes().stream().allMatch(n -> n.pitch() + transpose >= 12 && n.pitch() + transpose <= 106)) {
            // Shift the original driver program by whole octaves. The F-numbers,
            // detune values and live F0/FA counters are identical at each block.
            var notes = dry.notes().stream().filter(n -> n.tick() < duration)
                    .map(n -> new NoteEvent(n.id(), n.tick(), Math.min(n.duration(), duration - n.tick()),
                            n.pitch() + transpose)).toList();
            var commands = dry.commands().stream().filter(c -> c.tick() < duration).toList();
            var endingVibrato = dry.endingVibrato();
            // A truncated gate can end in a different modulation state.
            if (duration < dry.notes().getLast().tick() + dry.notes().getLast().duration()
                    && dry.mode() == ReconstructionBendEncoding.Mode.E7) {
                endingVibrato = null;
                for (var command : commands) {
                    if (command.command() == NativeCommand.MODULATION_OFF) endingVibrato = null;
                    if (command.command() == NativeCommand.MODULATION) {
                        var args = command.arguments();
                        endingVibrato = new ReconstructionVoicing.Vibrato(args.get(0), args.get(1),
                                (byte) (int) args.get(2), args.get(3));
                    }
                }
            }
            return new Copy(new Fit(dry.mode(), notes, commands, 0, endingVibrato),
                    ReconstructionLayers.Curve.measured(0));
        }

        // Probe the rendered bend, rather than refitting the source annotation.
        // Each sample is local to the attack, so the copy can outlive the dry gate in project time.
        int fnum = LeadEffectMatcher.fmFnum(sourcePitch);
        double ratio = Math.pow(2, transpose / 12.0) * (fnum + detuneOffset) / fnum;
        var held = new HashSet<Integer>();
        for (var command : dry.commands())
            if (command.command() == NativeCommand.HOLD) held.add(command.tick());
        var attacks = new HashSet<Integer>();
        for (var note : dry.notes())
            if (!held.contains(note.tick())) attacks.add(note.tick());

        if (voice == null) return copyPsg(dry, targetFm, duration, ratio, attacks::contains);
        int[] frequency = ReconstructionBendEncoding.frequencies(dry, voice, duration, profile);
        return steps(duration, targetFm, tick -> ReconstructionBendEncoding.linear(frequency[tick]) * ratio,
                attacks::contains);
    }

    private static Copy copyPsg(Fit dry, boolean targetFm, int duration, double ratio, IntPredicate attacks) {
        var detuneChanges = new java.util.HashMap<Integer, Integer>();
        for (var command : dry.commands()) if (command.command() == NativeCommand.DETUNE)
            detuneChanges.put(command.tick(), (int) (byte) (int) command.arguments().getFirst());
        var frequency = new double[duration];
        for (int i = 0, detune = 0; i < dry.notes().size(); i++) {
            var note = dry.notes().get(i);
            detune = detuneChanges.getOrDefault(note.tick(), detune);
            double value = PSG_HZ_PER_PERIOD / (FM_HZ_PER_UNIT * psgPeriod(new Pitch(note.pitch(), detune))) * ratio;
            int end = i + 1 == dry.notes().size() ? duration : Math.min(duration, note.tick() + note.duration());
            if (note.tick() >= end) break;
            java.util.Arrays.fill(frequency, note.tick(), end, value);
        }
        return steps(duration, targetFm, tick -> frequency[tick], attacks);
    }

    private static Copy steps(int duration, boolean targetFm, IntToDoubleFunction frequency, IntPredicate attacks) {
        var notes = new ArrayList<NoteEvent>();
        var commands = new ArrayList<CommandEvent>();
        // The samples already include vibrato. Clear any live modulation left
        // on the destination lane by a preceding note.
        commands.add(new CommandEvent("layer-mod-off", 0, NativeCommand.MODULATION_OFF, List.of()));
        Pitch current = null;
        int start = 0;
        double squaredError = 0;
        for (int tick = 0; tick < duration; tick++) {
            double target = frequency.applyAsDouble(tick);
            Pitch next = targetFm ? fm(target, current) : psg(PSG_HZ_PER_PERIOD / (FM_HZ_PER_UNIT * target), current);
            if (!next.equals(current) || attacks.test(tick)) {
                if (current != null)
                    notes.add(new NoteEvent("layer-pitch-" + start, start, tick - start, current.note()));
                if (tick == 0 || current.detune() != next.detune())
                    commands.add(new CommandEvent("layer-detune-" + tick, tick, NativeCommand.DETUNE,
                            List.of(next.detune() & 255)));
                if (!attacks.test(tick))
                    commands.add(new CommandEvent("layer-hold-" + tick, tick, NativeCommand.HOLD, List.of()));
                start = tick;
                current = next;
            }
            double actual = targetFm ? fmFrequency(next)
                    : PSG_HZ_PER_PERIOD / (FM_HZ_PER_UNIT * psgPeriod(next));
            double error = 1200 * Math.log(actual / target) / Math.log(2);
            squaredError += error * error;
        }
        notes.add(new NoteEvent("layer-pitch-" + start, start, duration - start, current.note()));
        var curve = ReconstructionLayers.Curve.measured(Math.sqrt(squaredError / duration));
        return new Copy(new Fit(ReconstructionBendEncoding.Mode.E7, notes, commands, curve.rmseCents()), curve);
    }

    private static Pitch fm(double target, Pitch previous) {
        Pitch best = previous;
        double error = previous == null ? Double.POSITIVE_INFINITY : Math.abs(fmFrequency(previous) - target);
        for (int note = 12; note <= 106; note++) {
            int block = (note - 12) / 12;
            int offset = detune(Math.scalb(target, -block) - LeadEffectMatcher.fmFnum(note));
            var candidate = new Pitch(note, offset);
            double nextError = Math.abs(fmFrequency(candidate) - target);
            if (nextError < error) {
                best = candidate;
                error = nextError;
            }
        }
        return best;
    }

    private static double fmFrequency(Pitch pitch) {
        return Math.scalb(LeadEffectMatcher.fmFnum(pitch.note()) + pitch.detune(), (pitch.note() - 12) / 12);
    }

    private static Pitch psg(double desired, Pitch previous) {
        int period = (int) Math.clamp(Math.round(desired), 1, 1023);
        if (previous != null) {
            int offset = previous.detune() + period - psgPeriod(previous);
            if (offset >= -128 && offset <= 127) return new Pitch(previous.note(), offset);
        }
        int closest = 0;
        for (int i = 1; i < PSG_PERIODS.length; i++)
            if (Math.abs(PSG_PERIODS[i] - period) < Math.abs(PSG_PERIODS[closest] - period)) closest = i;
        return new Pitch(PSG_NOTES[closest], period - PSG_PERIODS[closest]);
    }

    private static int psgPeriod(Pitch pitch) {
        for (int i = 0; i < PSG_NOTES.length; i++)
            if (pitch.note() == PSG_NOTES[i]) return PSG_PERIODS[i] + pitch.detune();
        throw new IllegalArgumentException("Missing PSG period anchor for " + pitch.note());
    }

    private static int detune(double offset) {
        return (int) Math.clamp(Math.round(offset), -128, 127);
    }
}
