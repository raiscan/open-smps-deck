package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetClock;
import com.opensmpsdeck.project.TargetGame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.TreeMap;

/** Adapts reconstruction PSG programs to the classic drivers' hold-only grammar. */
final class ReconstructionPsgEnvelopes {
    private static final int MAX_STEPS = 254;
    record Translation(List<Integer> bytes, List<String> reasons) {}
    record Adapted(StudioProject project, Report report) {}
    record Report(int customCount, int customBytes, int stockCount, Map<String, List<String>> approximations) {}

    private ReconstructionPsgEnvelopes() {}

    static Translation translate(List<Integer> program, int gate) {
        int steps = Math.clamp(gate, 1, MAX_STEPS);
        var values = new ArrayList<Integer>();
        var reasons = new LinkedHashSet<String>();
        int cursor = 0;
        // Like the pinned sequencer, follow immediate commands within a tick.
        // A non-yielding cycle can never emit another attenuation value.
        int immediate = 0;
        while (values.size() < steps) {
            if (cursor >= program.size() || immediate >= program.size()) {
                reasons.add("Envelope cursor exhausted or entered a loop with no attenuation steps. Held the last level.");
                break;
            }
            int value = program.get(cursor++);
            immediate++;
            if (value < 0x80) {
                values.add(Math.min(value, 15));
                immediate = 0;
                continue;
            }
            if (value == 0x81) break;
            if (value == 0x83) {
                values.add(15);
                break;
            }
            if (value == 0x80) {
                cursor = 0;
            } else if ((value == 0x82 || value == 0x84) && cursor < program.size()) {
                if (value == 0x82) cursor = program.get(cursor);
                else {
                    cursor++;
                    reasons.add("Skipped 84 multiplier operand, matching the pinned PSG volume interpreter.");
                }
            } else {
                reasons.add("Held the last level at unsupported or incomplete control %02X.".formatted(value));
                break;
            }
        }
        if (gate > MAX_STEPS && values.size() == MAX_STEPS) {
            // A hold or silence immediately after the limit may be a no-op.
            if (cursor >= program.size() || program.get(cursor) != 0x81)
                reasons.add("Held the last level after the 254 attenuation-step limit.");
        }
        if (values.isEmpty()) values.add(0);
        values.add(0x80);
        return new Translation(List.copyOf(values), List.copyOf(reasons));
    }

    static Set<String> classicCatalogueEnvelopes(InstrumentCatalogue catalogue, TargetGame target) {
        if (target == TargetGame.S3K) return Set.of();
        return catalogue.forTarget(target).assets().stream()
                .filter(asset -> asset.kind() == InstrumentCatalogue.Kind.PSG_VOLUME)
                .map(InstrumentCatalogue.Asset::id).collect(Collectors.toUnmodifiableSet());
    }

    static Envelope forProbe(Envelope envelope, TargetGame target, int gate) {
        return forProbe(envelope, target, gate, false);
    }

    static Envelope forProbe(Envelope envelope, TargetGame target, int gate, boolean classicSource) {
        if (target == TargetGame.S3K || !envelope.type().equals(Envelope.PSG_VOLUME)) return envelope;
        return withBytes(envelope, translation(envelope, gate, classicSource).bytes());
    }

    private static Translation translation(Envelope envelope, int gate, boolean classicSource) {
        var bytes = envelope.bytes();
        var ref = envelope.origin() == null ? null : envelope.origin().ref();
        boolean knownClassicStock = ref != null ? !ref.game().equals("s3k")
                : StockTable.match(Envelope.PSG_VOLUME, StockTable.bytes(bytes), "s1") != null
                || StockTable.match(Envelope.PSG_VOLUME, StockTable.bytes(bytes), "s2") != null;
        if ((classicSource || knownClassicStock) && bytes.size() >= 2 && bytes.size() <= MAX_STEPS + 1
                && bytes.getLast() == 0x80
                && bytes.subList(0, bytes.size() - 1).stream().allMatch(value -> value < 0x80))
            return new Translation(bytes, List.of());
        return translate(bytes, gate);
    }

    private static Envelope withBytes(Envelope envelope, List<Integer> bytes) {
        if (envelope.bytes().equals(bytes)) return envelope;
        var origin = envelope.origin();
        if (origin != null) origin = origin.withPatch(envelope.bytes(), bytes);
        return new Envelope(envelope.id(), envelope.name(), envelope.type(), bytes, origin);
    }

    static Adapted forAssembly(StudioProject project, Set<String> classicSources) {
        if (project.target() == TargetGame.S3K) return new Adapted(project, null);
        var gates = new HashMap<String, Integer>();
        var byId = new HashMap<String, Sequence>();
        for (var sequence : project.sequences()) byId.put(sequence.id(), sequence);
        int tempo = TargetPlayback.classic(project.target()).projectTempo(project).targetByte();
        var clock = new TargetClock(project.target(), tempo);
        for (var clip : project.clips())
            measureGates(byId.get(clip.sequenceId()), clip.startTick(), clock, gates);
        var stock = project.target() == TargetGame.S2 && !gates.isEmpty() ? stockS2() : Map.<Integer, byte[]>of();
        var byProgram = new LinkedHashMap<List<Integer>, Envelope>();
        var ids = new HashMap<String, String>();
        var reasons = new LinkedHashMap<String, List<String>>();
        var envelopes = new ArrayList<Envelope>();
        int stockCount = 0;
        int customBytes = 0;
        for (var envelope : project.envelopes()) {
            if (!envelope.type().equals(Envelope.PSG_VOLUME)) {
                envelopes.add(envelope);
                continue;
            }
            Integer gate = gates.get(envelope.id());
            if (gate == null) continue;
            var translation = translation(envelope, gate, classicSources.contains(envelope.id()));
            var bytes = trimHold(translation.bytes());
            var notes = new ArrayList<>(translation.reasons());
            if (bytes.equals(List.of(0, 0x80))) {
                if (!notes.isEmpty()) reasons.put(envelope.id(), List.copyOf(notes));
                ids.put(envelope.id(), null); // The driver's envelope-disabled contour is flat zero.
                continue;
            }
            // Preserve exact stock bytes where possible before canonicalizing a held tail.
            var ref = StockTable.match(Envelope.PSG_VOLUME, StockTable.bytes(translation.bytes()), project.target().id());
            if (ref != null) bytes = translation.bytes();
            if (ref == null) ref = StockTable.match(Envelope.PSG_VOLUME, StockTable.bytes(bytes), project.target().id());
            if (ref == null && !stock.isEmpty()) {
                int slot = closeStock(bytes, gate, stock);
                if (slot != 0) {
                    byte[] payload = stock.get(slot);
                    var matched = unsigned(payload);
                    if (!bytes.equals(matched)) notes.add("Reused S2 stock envelope " + slot
                            + " within one attenuation step during the sounding gate.");
                    bytes = matched;
                    ref = StockTable.ref("s2", "psg", null, slot, StockTable.hash(payload));
                }
            }
            if (!notes.isEmpty()) reasons.put(envelope.id(), List.copyOf(notes));
            var existing = byProgram.get(bytes);
            if (existing == null) {
                existing = withBytes(envelope, bytes);
                if (ref != null) {
                    existing = existing.withOrigin(AssetOrigin.stock(ref));
                    stockCount++;
                } else {
                    customBytes += bytes.size();
                }
                byProgram.put(bytes, existing);
            }
            ids.put(envelope.id(), existing.id());
        }
        envelopes.addAll(byProgram.values());
        var sequences = project.sequences().stream().map(s -> remap(s, ids)).toList();
        var adapted = new StudioProject(project.formatVersion(), project.name(), project.mode(), project.ticksPerBeat(),
                project.tempo(), project.voices(), sequences, project.clips(), envelopes, project.samples(),
                project.target(), project.nativeTempo());
        return new Adapted(adapted, new Report(byProgram.size() - stockCount, customBytes, stockCount, reasons));
    }

    private static Map<Integer, byte[]> stockS2() {
        String path = System.getenv("OPENSMPSDECK_S2_ROM");
        if (path == null || path.isBlank()) return Map.of();
        try {
            return Sonic2RomEnvelopes.extract(Files.readAllBytes(Path.of(path)));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot load S2 PSG envelopes from OPENSMPSDECK_S2_ROM: "
                    + failure.getMessage(), failure);
        }
    }

    private static List<Integer> unsigned(byte[] bytes) {
        var values = new ArrayList<Integer>(bytes.length);
        for (byte value : bytes) values.add(Byte.toUnsignedInt(value));
        return List.copyOf(values);
    }

    static int closeStock(List<Integer> bytes, int gate, Map<Integer, byte[]> stock) {
        int best = 0;
        int bestError = Integer.MAX_VALUE;
        for (var entry : new TreeMap<>(stock).entrySet()) {
            byte[] candidate = entry.getValue();
            int error = 0;
            int ticks = Math.min(gate, Math.max(bytes.size(), candidate.length) - 1);
            for (int tick = 0; tick < ticks; tick++) {
                int expected = bytes.get(Math.min(tick, bytes.size() - 2));
                int actual = Math.min(15, Byte.toUnsignedInt(candidate[Math.min(tick, candidate.length - 2)]));
                int difference = Math.abs(expected - actual);
                if (difference > 1 || (expected == 15) != (actual == 15)) {
                    error = Integer.MAX_VALUE;
                    break;
                }
                error += difference;
            }
            if (error < bestError) {
                best = entry.getKey();
                bestError = error;
            }
        }
        return best;
    }

    private static List<Integer> trimHold(List<Integer> bytes) {
        int count = bytes.size() - 1;
        while (count > 1 && bytes.get(count - 1).equals(bytes.get(count - 2))) count--;
        if (count == bytes.size() - 1) return bytes;
        var trimmed = new ArrayList<>(bytes.subList(0, count));
        trimmed.add(0x80);
        return List.copyOf(trimmed);
    }

    private static void measureGates(Sequence sequence, int clipStart, TargetClock clock, Map<String, Integer> gates) {
        if (!sequence.type().equals("PSG")) return;
        var changes = new TreeMap<Integer, String>();
        String asset = sequence.psgEnvelopeId();
        if (asset != null) gates.putIfAbsent(asset, 1);
        for (var command : sequence.commands()) {
            if (command.command() != NativeCommand.PSG_ENVELOPE) continue;
            changes.put(command.tick(), command.assetId());
            if (command.assetId() != null) gates.putIfAbsent(command.assetId(), 1);
        }
        var holds = sequence.commands().stream().filter(c -> c.command() == NativeCommand.HOLD)
                .map(CommandEvent::tick).collect(java.util.stream.Collectors.toSet());
        var notes = sequence.notes().stream().sorted(Comparator.comparingInt(NoteEvent::tick)).toList();
        for (int i = 0; i < notes.size(); i++) {
            int start = notes.get(i).tick();
            int end = start + notes.get(i).duration();
            while (i + 1 < notes.size() && notes.get(i + 1).tick() == end && holds.contains(end))
                end += notes.get(++i).duration();
            var selection = changes.floorEntry(start);
            if (selection != null) asset = selection.getValue();
            for (var change : changes.subMap(start, false, end, false).entrySet()) {
                recordGate(gates, asset, clipStart + start, clipStart + change.getKey(), clock);
                start = change.getKey();
                asset = change.getValue();
            }
            recordGate(gates, asset, clipStart + start, clipStart + end, clock);
        }
    }

    private static void recordGate(Map<String, Integer> gates, String asset, int start, int end, TargetClock clock) {
        if (asset == null) return;
        // The sequencer can advance an envelope while the tempo service
        // holds the note duration, so count the actual sounding passes.
        int frames = Math.toIntExact(clock.frameAtTick(end) - clock.frameAtTick(start));
        gates.merge(asset, frames, Math::max);
    }

    private static Sequence remap(Sequence sequence, Map<String, String> ids) {
        if (!sequence.type().equals("PSG")) return sequence;
        var commands = sequence.commands().stream().map(command -> command.command() == NativeCommand.PSG_ENVELOPE
                ? new CommandEvent(command.id(), command.tick(), command.command(), command.arguments(), ids.get(command.assetId()))
                : command).toList();
        return new Sequence(sequence.id(), sequence.name(), sequence.type(), sequence.lengthTicks(),
                sequence.voiceId(), sequence.volume(), sequence.notes(), commands, sequence.repeatState(),
                ids.get(sequence.psgEnvelopeId()), sequence.modulationEnvelopeId(), sequence.triggers());
    }
}
