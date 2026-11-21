package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.opensmpsdeck.headless.S3kRenderer.PRESENTATIONS_PER_SECOND;

/** Independent held-note patch probes. The result is only report evidence. */
final class ReconstructionSustain {
    private static final double MIN_DIP_DB = 6;

    private ReconstructionSustain() {}

    static String check(ReconstructionStages.Score score, InstrumentCatalogue catalogue,
                        List<ReconstructionAllocation.Entry> notes, ReconstructionVoicing.Lane lane) throws IOException {
        if (notes == null || lane == null || lane.variantId() == null || lane.gain() == 0) return null;
        var longest = notes.stream()
                .filter(entry -> heldTicks(entry, lane) >= PRESENTATIONS_PER_SECOND)
                .sorted(Comparator.comparingInt((ReconstructionAllocation.Entry entry) -> heldTicks(entry, lane)).reversed())
                .limit(3).toList();
        if (longest.isEmpty()) return null;

        var selected = catalogue.match(lane.variantId()).asset();
        double worst = MIN_DIP_DB;
        ReconstructionAllocation.Entry dipped = null;
        for (var note : longest) {
            double dip = midDip(probe(score, note, lane, selected));
            if (dip > worst) {
                worst = dip;
                dipped = note;
            }
        }
        if (dipped == null) return null;

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
        return String.format(Locale.ROOT, "FM1 patch %s dips %.1f dB mid-note on %s (project %.2f s). %s",
                lane.variantId(), worst, ReconstructionDescribe.pitch(dipped.note().pitch()),
                dipped.startTick() / (double) PRESENTATIONS_PER_SECOND,
                alternative == null ? "No audible shortlist alternative."
                        : String.format(Locale.ROOT, "Flattest shortlist alternative is %s (%.1f dB RMS spread).",
                                alternative, flattest));
    }

    private static int heldTicks(ReconstructionAllocation.Entry note, ReconstructionVoicing.Lane lane) {
        return S3kReconstruction.voicedDuration(note, lane.articulation());
    }

    private static List<Double> probe(ReconstructionStages.Score score, ReconstructionAllocation.Entry entry,
                                      ReconstructionVoicing.Lane lane, InstrumentCatalogue.Asset asset) throws IOException {
        // Bound the diagnostic to the first three seconds of the gate.
        int ticks = Math.min(3 * PRESENTATIONS_PER_SECOND, heldTicks(entry, lane));
        var note = entry.note();
        var part = score.source().parts().stream().filter(p -> p.id() == note.part()).findFirst().orElseThrow();
        int volume = Math.max(ReconstructionEcho.fmVolumeFloor(asset.bytes()),
                ReconstructionEcho.scaleVolume(S3kReconstruction.volume(part, note.midi()), lane.gainFor(note.part())));
        byte[] bytes = ReconstructionVoicing.patchBytes(asset, lane.articulation());
        var patch = new java.util.ArrayList<Integer>(bytes.length);
        for (byte value : bytes) patch.add(Byte.toUnsignedInt(value));
        var voice = new Voice("probe", "Sustain", patch);
        var sequence = new Sequence("probe", "Sustain", "FM", ticks, voice.id(), volume,
                List.of(new NoteEvent("probe", 0, ticks, note.pitch())));
        var project = new StudioProject(4, "Sustain probe", "S3K", 15, 0, List.of(voice),
                List.of(sequence), List.of(new Clip("probe", sequence.id(), "FM1", 0, ticks)));
        var pcm = new float[ticks * ReconstructionAudition.FRAME_SAMPLES];
        int[] offset = {0};
        S3kRenderer.render(ProjectCompiler.compileWithAssets(project), ticks, samples -> {
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
