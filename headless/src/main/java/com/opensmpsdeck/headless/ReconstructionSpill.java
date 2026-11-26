package com.opensmpsdeck.headless;

import com.openggf.audio.synth.ChipWriteObserver;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;
import com.opensmpsdeck.project.TargetProfile;
import com.opensmpsdeck.project.TargetGame;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Matches the FM amplitude contour using the PSG envelopes the driver can play. */
final class ReconstructionSpill {
    private static final int RELEASE_TICKS = 12;
    private static final int ATTACK_TICKS = 3;

    record Match(String variantId, int volumeOffset) {}
    private record Contour(double[] amplitude, int attackAttenuation) {}
    private record Probe(int pitch, int gate, int fmLevel, int psgLevel, double[] target, ReconstructionHeldNotes.Hold hold, double holdSeconds) {}

    private ReconstructionSpill() {}

    static Match match(InstrumentCatalogue catalogue, ReconstructionVoicing.Lane fm,
                       MidiScore.Part part, List<ReconstructionAllocation.Entry> notes, TargetProfile profile) throws IOException {
        return match(catalogue, fm, part, notes, profile, java.util.Map.of());
    }

    static Match match(InstrumentCatalogue catalogue, ReconstructionVoicing.Lane fm,
                       MidiScore.Part part, List<ReconstructionAllocation.Entry> notes, TargetProfile profile,
                       java.util.Map<String, ReconstructionHeldNotes.Hold> holds) throws IOException {
        if (notes.isEmpty()) return new Match(null, 0);
        var sustained = notes.stream().filter(note -> holds.containsKey(note.note().id()))
                .filter(note -> holds.get(note.note().id()).tailTime() * 60 + 2 < note.endTick())
                .max(Comparator.comparingDouble(note -> holds.get(note.note().id()).tailTime() - note.note().start()));
        boolean centered = sustained.isPresent();
        int quietest = notes.stream().mapToInt(note -> psgLevel(fm, part, note)).min().orElseThrow();
        var sorted = notes.stream().sorted(Comparator.comparingInt(n -> n.endTick() - n.startTick())).toList();
        var probes = new java.util.ArrayList<Probe>();
        var patch = catalogue.match(fm.variantId()).asset();
        var bytes = patch.bytes();
        for (int i : new int[]{0, sorted.size() / 2, sorted.size() - 1}) {
            var note = sorted.get(i);
            int pitch = Math.clamp(note.note().pitch(), profile.psg().low(), profile.fm().high());
            int gate = Math.min(48, S3kReconstruction.voicedDuration(note, fm.articulation()));
            int raw = S3kReconstruction.volume(part, note.note().midi());
            int fmLevel = Math.max(ReconstructionEcho.fmVolumeFloor(bytes, profile), ReconstructionEcho.scaleVolume(raw, fm.gain()));
            int psgLevel = psgLevel(fm, part, note);
            if (probes.stream().anyMatch(p -> p.pitch() == pitch && p.gate() == gate && p.fmLevel() == fmLevel)) continue;
            var target = contour(catalogue, fm.variantId(), pitch, gate, fmLevel, bytes, profile, centered).amplitude();
            probes.add(new Probe(pitch, gate, fmLevel, psgLevel, target, null, 0));
        }
        if (sustained.isPresent()) {
            var entry = sustained.orElseThrow();
            var hold = holds.get(entry.note().id());
            double seconds = Math.min(2.8, hold.tailTime() - entry.note().start());
            int pitch = Math.clamp(entry.note().pitch(), profile.psg().low(), profile.fm().high());
            int gate = Math.min(180, (int) profile.clock().nearestTick((seconds + .05) * 60));
            int raw = S3kReconstruction.volume(part, entry.note().midi());
            int fmLevel = Math.max(ReconstructionEcho.fmVolumeFloor(bytes, profile), ReconstructionEcho.scaleVolume(raw, fm.gain()));
            var target = contour(catalogue, fm.variantId(), pitch, gate, fmLevel, bytes, profile, centered).amplitude();
            probes.add(new Probe(pitch, gate, fmLevel, psgLevel(fm, part, entry), target, hold, seconds));
        }
        var seen = new HashSet<String>();
        double bestError = Double.POSITIVE_INFINITY;
        Match best = new Match(null, 0);
        for (var variant : catalogue.variants().stream().sorted(Comparator.comparing(InstrumentCatalogue.Variant::id)).toList()) {
            var asset = catalogue.asset(variant.assetId());
            if (asset.kind() != InstrumentCatalogue.Kind.PSG_VOLUME || !seen.add(variant.assetId())) continue;
            byte[] envelope = asset.bytes();
            if (!S3kReconstruction.safePsg(envelope, profile.game()) || !ReconstructionStemParts.startsAudibly(envelope)) continue;
            var contours = new java.util.ArrayList<Contour>();
            for (var probe : probes) contours.add(contour(catalogue, variant.id(), probe.pitch(), probe.gate(), 127, null, profile, centered));
            int attack = contours.stream().mapToInt(Contour::attackAttenuation).max().orElseThrow();
            if (attack == 15) continue;
            // Leave room for the attack steps even at the quietest note's quantized level.
            int minOffset = fm.gain() == 0 ? -120 : 8 * (attack + 1) - quietest;
            // Compare only legal PSG steps. A 2 dB step multiplies amplitude by approximately 0.794.
            for (int offset = -120; offset <= 120; offset += 8) {
                if (offset < minOffset) continue;
                double error = 0;
                for (int i = 0; i < probes.size(); i++) {
                    var probe = probes.get(i);
                    var contour = contours.get(i).amplitude();
                    int attenuation = 15 - (Math.clamp(probe.psgLevel() + offset, 0, 127) >> 3);
                    double scale = attenuation == 15 ? 0 : Math.pow(10, -attenuation / 10.0);
                    double energy = 0, distance = 0;
                    for (int tick = 0; tick < contour.length; tick++) {
                        double expected = probe.target()[tick];
                        double difference = expected - scale * contour[tick];
                        energy += expected * expected;
                        distance += difference * difference;
                    }
                    error += distance / Math.max(1e-9, energy);
                    if (probe.hold() != null) {
                        int tail = Math.min(contour.length - 1, (int) Math.round(probe.holdSeconds() * 60));
                        double opening = contour[Math.min(7, contour.length - 1)];
                        double ending = scale * contour[tail];
                        double decay = 20 * Math.log10(Math.max(1e-7, ending)
                                / Math.max(1e-7, scale * opening));
                        error += Math.max(0, probe.hold().minimumDecayDb() - decay) / 3;
                        error += Math.max(0, -55 - 20 * Math.log10(Math.max(1e-7, ending))) / 3;
                    }
                }
                if (error < bestError || error == bestError && Math.abs(offset) < Math.abs(best.volumeOffset())) {
                    bestError = error;
                    best = new Match(variant.id(), offset);
                }
            }
        }
        return best;
    }

    private static int psgLevel(ReconstructionVoicing.Lane fm, MidiScore.Part part,
                                ReconstructionAllocation.Entry note) {
        int raw = S3kReconstruction.volume(part, note.note().midi());
        return ReconstructionEcho.scaleVolume(Math.max(1, raw - 24), fm.gainFor(part.id()));
    }

    private static Contour contour(InstrumentCatalogue catalogue, String variant, int pitch, int gate,
                                    int volume, byte[] fmPatch, TargetProfile profile, boolean centered) throws IOException {
        var raw = CataloguePlayback.matchProbe(catalogue, variant, pitch, gate, true, profile.game());
        var setup = raw.sequences().getFirst();
        var envelopes = raw.envelopes();
        var sequence = new Sequence(setup.id(), setup.name(), setup.type(), setup.lengthTicks(), setup.voiceId(),
                volume, setup.notes(), setup.commands(), setup.repeatState(), setup.psgEnvelopeId(),
                setup.modulationEnvelopeId());
        var voices = raw.voices();
        if (fmPatch != null) {
            var original = voices.getFirst();
            var bytes = new java.util.ArrayList<Integer>();
            for (byte b : fmPatch) bytes.add(b & 255);
            voices = List.of(new Voice(original.id(), original.name(), bytes));
        }
        var project = new StudioProject(4, raw.name(), "S3K", raw.ticksPerBeat(), raw.tempo(),
                voices, List.of(sequence), raw.clips(), envelopes, raw.samples(), profile.game(),
                profile.clock().game().s3kRules() ? null : profile.clock().tempo());
        double[] result = new double[gate + RELEASE_TICKS];
        int[] tick = {0};
        int[] attenuation = {15, 0};
        ChipWriteObserver observer = fmPatch != null ? null : new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {}

            @Override public void onPsgWrite(int value) {
                if ((value & 0xf0) == 0xd0) attenuation[0] = value & 0xf;
            }
        };
        S3kRenderer.PcmSink sink = pcm -> {
            double power = 0, sum = 0;
            for (short sample : pcm) {
                power += (double) sample * sample;
                sum += sample;
            }
            // YM2612's DAC bias is present even in a PSG-only probe.
            // Exclude it when checking whether a candidate actually sustains.
            if (centered) power -= sum * sum / pcm.length;
            if (observer != null && tick[0] < Math.min(gate, ATTACK_TICKS))
                attenuation[1] = Math.max(attenuation[1], attenuation[0]);
            result[tick[0]++] = Math.sqrt(Math.max(0, power) / pcm.length) / 32768;
        };
        TargetPlayback.prepare(project).render(result.length, S3kRenderer.ChannelMask.NONE, sink,
                observer == null ? ChipWriteObserver.NONE : observer);
        return new Contour(result, attenuation[1]);
    }
}
