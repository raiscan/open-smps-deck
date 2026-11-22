package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Locale;

import static com.opensmpsdeck.headless.S3kRenderer.PRESENTATIONS_PER_SECOND;

/** Independent held-note patch probes. The result is only report evidence. */
final class ReconstructionSustain {
    private static final double MIN_DIP_DB = 6;

    record Probe(int notesProbed, int notesOverHalfSecond, int totalNotes, Double worstDipDb, String warning) {}

    private ReconstructionSustain() {}

    static String check(ReconstructionStages.Score score, InstrumentCatalogue catalogue,
                        List<ReconstructionAllocation.Entry> notes, ReconstructionVoicing.Lane lane) throws IOException {
        return inspect(score, catalogue, notes, lane).warning();
    }

    static Probe inspect(ReconstructionStages.Score score, InstrumentCatalogue catalogue,
                         List<ReconstructionAllocation.Entry> notes, ReconstructionVoicing.Lane lane) throws IOException {
        if (notes == null || lane == null || lane.variantId() == null || lane.gain() == 0)
            return new Probe(0, 0, notes == null ? 0 : notes.size(), null, null);
        int held = (int) notes.stream().filter(note -> heldTicks(note, lane) > PRESENTATIONS_PER_SECOND / 2).count();
        var longest = notes.stream()
                .filter(entry -> heldTicks(entry, lane) >= PRESENTATIONS_PER_SECOND)
                .sorted(Comparator.comparingInt((ReconstructionAllocation.Entry entry) -> heldTicks(entry, lane)).reversed())
                .limit(3).toList();
        if (longest.isEmpty()) return new Probe(0, held, notes.size(), null, null);

        var selected = catalogue.match(lane.variantId()).asset();
        double worst = Double.NEGATIVE_INFINITY;
        ReconstructionAllocation.Entry dipped = null;
        for (var note : longest) {
            double dip = midDip(probe(score, note, lane, selected));
            if (dip > worst) {
                worst = dip;
                dipped = note;
            }
        }
        if (worst <= MIN_DIP_DB) return new Probe(longest.size(), held, notes.size(), worst, null);

        String alternative = null;
        double flattest = Double.POSITIVE_INFINITY;
        for (var patch : lane.shortlist()) {
            if (patch.variantId().equals(lane.variantId())) continue;
            var match = catalogue.match(patch.variantId());
            if (match.asset().kind() != InstrumentCatalogue.Kind.FM_VOICE) continue;
            var rms = probe(score, dipped, lane, match.asset());
            double spread = rms.stream().anyMatch(value -> value <= 1e-7) ? Double.POSITIVE_INFINITY
                    : dbRatio(rms.stream().max(Double::compare).orElseThrow(), rms.stream().min(Double::compare).orElseThrow());
            if (spread < flattest) {
                flattest = spread;
                alternative = match.variant().name() + " [" + patch.variantId() + "]";
            }
        }
        String warning = String.format(Locale.ROOT, "FM1 patch %s dips %.1f dB mid-note on %s (project %.2f s). %s",
                lane.variantId(), worst, ReconstructionDescribe.pitch(dipped.note().pitch()),
                score.profile().clock().secondsAtTick(dipped.startTick()),
                alternative == null ? "No audible shortlist alternative."
                        : String.format(Locale.ROOT, "Flattest shortlist alternative is %s (%.1f dB RMS spread).",
                                alternative, flattest));
        return new Probe(longest.size(), held, notes.size(), worst, warning);
    }

    // The legacy entry point chooses instruments by section instead of fitting a lane-wide patch.
    static Probe inspect(StudioProject project) throws IOException {
        record Held(int ticks, int pitch, int start, int volume, Voice voice) {}
        var voices = project.voices().stream().collect(Collectors.toMap(Voice::id, Function.identity()));
        var sequences = project.sequences().stream().collect(Collectors.toMap(Sequence::id, Function.identity()));
        var held = new ArrayList<Held>();
        int total = 0;
        for (var clip : project.clips()) {
            if (!"FM1".equals(clip.lane())) continue;
            var sequence = sequences.get(clip.sequenceId());
            var commands = sequence.commands().stream().sorted(Comparator.comparingInt(CommandEvent::tick)).toList();
            var voice = voices.get(sequence.voiceId());
            int volume = sequence.volume(), command = 0;
            for (var note : sequence.notes()) {
                total++;
                if (note.duration() <= PRESENTATIONS_PER_SECOND / 2) continue;
                while (command < commands.size() && commands.get(command).tick() <= note.tick()) {
                    var next = commands.get(command++);
                    if (next.command() == NativeCommand.FM_VOICE) voice = voices.get(next.assetId());
                    if (next.command() == NativeCommand.VOLUME) volume = next.arguments().getFirst();
                }
                held.add(new Held(note.duration(), note.pitch(), clip.startTick() + note.tick(), volume, voice));
            }
        }
        var longest = held.stream().filter(note -> note.ticks >= PRESENTATIONS_PER_SECOND)
                .sorted(Comparator.comparingInt(Held::ticks).reversed()).limit(3).toList();
        Double worst = null;
        String warning = null;
        for (var note : longest) {
            var rms = probe(note.ticks, note.pitch, note.volume, note.voice.bytes(), project.target(), project.nativeTempo());
            double dip = midDip(rms);
            if (worst != null && dip <= worst) continue;
            worst = dip;
            if (dip > MIN_DIP_DB) warning = String.format(Locale.ROOT,
                    "FM1 patch %s dips %.1f dB mid-note on %s (project %.2f s).",
                    note.voice.id(), dip, ReconstructionDescribe.pitch(note.pitch),
                    project.clock().secondsAtTick(note.start));
        }
        return new Probe(longest.size(), held.size(), total, worst, warning);
    }

    private static int heldTicks(ReconstructionAllocation.Entry note, ReconstructionVoicing.Lane lane) {
        return S3kReconstruction.voicedDuration(note, lane.articulation());
    }

    private static List<Double> probe(ReconstructionStages.Score score, ReconstructionAllocation.Entry entry,
                                      ReconstructionVoicing.Lane lane, InstrumentCatalogue.Asset asset) throws IOException {
        var note = entry.note();
        var part = score.source().parts().stream().filter(p -> p.id() == note.part()).findFirst().orElseThrow();
        int volume = Math.max(ReconstructionEcho.fmVolumeFloor(asset.bytes(), score.inputs().profile()),
                ReconstructionEcho.scaleVolume(S3kReconstruction.volume(part, note.midi()), lane.gainFor(note.part())));
        byte[] bytes = ReconstructionVoicing.patchBytes(asset, lane.articulation());
        var patch = new ArrayList<Integer>(bytes.length);
        for (byte value : bytes) patch.add(Byte.toUnsignedInt(value));
        var profile = score.profile();
        return probe(heldTicks(entry, lane), note.pitch(), volume, patch, profile.game(),
                profile.clock().game() == TargetGame.S3K ? null : profile.clock().tempo());
    }

    private static List<Double> probe(int duration, int pitch, int volume, List<Integer> patch, TargetGame target, Integer nativeTempo) throws IOException {
        int ticks = Math.min(3 * PRESENTATIONS_PER_SECOND, duration);
        var voice = new Voice("probe", "Sustain", patch);
        var sequence = new Sequence("probe", "Sustain", "FM", ticks, voice.id(), volume,
                List.of(new NoteEvent("probe", 0, ticks, pitch)));
        var project = new StudioProject(4, "Sustain probe", "S3K", 15, 0, List.of(voice),
                List.of(sequence), List.of(new Clip("probe", sequence.id(), "FM1", 0, ticks)), target, nativeTempo);
        int frames = (int) project.clock().frameAtTick(ticks);
        var pcm = new float[frames * ReconstructionAudition.FRAME_SAMPLES];
        int[] offset = {0};
        TargetPlayback.prepare(project).render(frames, samples -> {
            for (short sample : samples) pcm[offset[0]++] = sample / 32768f;
        });
        return ReconstructionAudition.envelope(pcm);
    }

    private static double midDip(List<Double> rms) {
        double opening = Math.max(rms.get(0), rms.get(1));
        double middle = Math.min(rms.get(2), Math.min(rms.get(3), rms.get(4)));
        return dbRatio(opening, middle);
    }

    private static double dbRatio(double high, double low) {
        return 20 * Math.log10(Math.max(1e-7, high) / Math.max(1e-7, low));
    }
}
