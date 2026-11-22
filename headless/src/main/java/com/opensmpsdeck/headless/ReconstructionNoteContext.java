package com.opensmpsdeck.headless;

import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static com.opensmpsdeck.headless.NoteHandoff.round;

/** Joins the source grid and saved note trace for clipboard and live queries. */
final class ReconstructionNoteContext {
    static final List<String> FILES = Stream.concat(ReconstructionTrace.FILES.stream(),
            Stream.of("arrangement.smps.json", ReconstructionStemTemplates.FILE)).toList();

    private record Detail(NoteHandoff.Note note, ReconstructionTrace.Row trace, List<JsonObject> intents) {}
    private record Description(NoteHandoff.Project project, boolean stale, NoteHandoff.Context context,
                               List<String> missingIds, List<Detail> selected, List<Detail> neighbors,
                               List<Detail> competitors) {}

    private final Path work;
    private final String song;
    private final String builtRevision;
    private final ReconstructionTimeMap clock;
    private final ReconstructionBeatMap beats;
    private final int beatsPerBar;
    private final List<ReconstructionStructure.Bar> bars;
    private final Map<String, ReconstructionTrace.Row> trace;

    ReconstructionNoteContext(Path work, ReconstructionStages.Score score, String builtRevision,
                              Map<String, ReconstructionTrace.Row> trace) {
        this.work = work.toAbsolutePath().normalize();
        this.song = score.inputs() != null && score.inputs().name() != null ? score.inputs().name() : this.work.getFileName().toString();
        this.builtRevision = builtRevision;
        this.clock = ReconstructionTimeMap.of(score);
        this.beats = score.beatMap();
        this.beatsPerBar = score.tempo() == null ? 4 : score.tempo().selected().beatsPerBar();
        this.bars = score.usesBeatGrid() ? beats.bars(clock.sourceDuration()) : score.bars();
        this.trace = trace;
    }

    static String builtRevision(Path work) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var fileDigest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[65536];
            for (String name : FILES) {
                digest.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try (var input = Files.newInputStream(work.resolve(name))) {
                    for (int n; (n = input.read(buffer)) != -1;) fileDigest.update(buffer, 0, n);
                    digest.update(fileDigest.digest());
                } catch (java.nio.file.NoSuchFileException missing) {
                    digest.update((byte) 0);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    NoteHandoff.Block block(List<Overlay> selection, boolean roles, boolean draft, ReconstructionReview.Revision revision,
                            Map<String, String> draftVoices, Map<String, Integer> draftVelocities,
                            Map<String, List<JsonObject>> intents, NoteHandoff.ViewRange range) {
        var notes = selection.stream().map(overlay -> note(trace.get(overlay.id()), overlay,
                        draftVoices.get(overlay.id()), draftVelocities.get(overlay.id()), intents.get(overlay.id())))
                .sorted(Comparator.comparingLong(NoteHandoff.Note::tick).thenComparing(NoteHandoff.Note::id)).toList();
        var sample = NoteHandoff.sample(notes);
        var project = project(roles ? "reconstruction/roles" : "reconstruction/detail", draft, revision);
        var shown = range == null ? sample : notes;
        var context = context(notes, 1, notes.size() - shown.size());
        String fetch = notes.isEmpty() ? null : "opensmpsdeck reconstruct describe " + NoteHandoff.quote(project.path())
                + " --notes " + NoteHandoff.noteArgument(sample) + " --context 1bar --revision " + project.revision();
        return new NoteHandoff.Block(project, context, shown, fetch);
    }

    NoteHandoff.Area area(NoteHandoff.ViewRange range) {
        int first = barIndex(range.from()), last = barIndex(Math.max(range.from(), Math.nextDown(range.to())));
        return new NoteHandoff.Area("source", round(range.from()), round(range.to()), clock.tick(clock.project(range.from())),
                clock.tick(clock.project(range.to())), first < 0 ? null : first + 1, last < 0 ? null : last + 1,
                range.minMidi(), range.maxMidi(), range.viewState());
    }

    static String describe(Path work, List<String> ids, int nextBars, String expected, boolean json) throws IOException {
        String builtRevision = builtRevision(work);
        var revision = ReconstructionReview.revision(work);
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var context = new ReconstructionNoteContext(work, score, builtRevision, ReconstructionTrace.read(work, score));
        var scopes = ReconstructionReview.outcomes(work);
        var proposals = ReconstructionProposals.results(work);
        Map<String, Overlay> rendered = Map.of();
        if (ids.stream().anyMatch(id -> !context.trace.containsKey(id) && id.contains("/") && id.contains("@"))
                && Files.isRegularFile(work.resolve("arrangement.smps.json"))) {
            rendered = ReconstructionInspection.open(work).overlays().stream()
                    .filter(note -> note.fate().equals("assembly-only") && ids.contains(note.id()))
                    .collect(java.util.stream.Collectors.toMap(Overlay::id, note -> note, (first, next) -> first));
        }
        var result = context.describe(ids, nextBars, expected, revision, touching(revision.entries(), scopes, proposals), rendered);
        if (!builtRevision.equals(builtRevision(work)) || !revision.equals(ReconstructionReview.revision(work)))
            throw new ReconstructionReview.Changed();
        return json ? NoteHandoff.JSON.toJson(result) + "\n" : text(result);
    }

    private Description describe(List<String> ids, int nextBars, String expected, ReconstructionReview.Revision revision,
                                 Map<String, List<JsonObject>> intents, Map<String, Overlay> rendered) {
        var selected = new ArrayList<Detail>();
        var missing = new ArrayList<String>();
        for (String id : ids) {
            var row = trace.get(id);
            if (row != null) selected.add(detail(row, intents));
            else if (rendered.containsKey(id))
                selected.add(new Detail(note(null, rendered.get(id), null, null, null), null, List.of()));
            else missing.add(id);
        }
        var context = context(selected.stream().map(Detail::note).toList(), nextBars, 0);
        var parts = selected.stream().filter(note -> note.trace != null).map(note -> note.trace.part()).collect(java.util.stream.Collectors.toSet());
        var neighbors = new ArrayList<Detail>();
        if (context.firstBar() != null) {
            double start = bars.get(context.firstBar() - 1).start(), end = bars.get(context.throughBar() - 1).end();
            for (var row : trace.values()) {
                if (!parts.contains(row.part()) || ids.contains(row.id())) continue;
                var time = timing(row);
                if (overlaps(time, start, end) || overlaps(row.timing(), start, end))
                    neighbors.add(detail(row, intents));
            }
        }
        var shown = Stream.concat(selected.stream(), neighbors.stream()).map(item -> item.note.id()).collect(java.util.stream.Collectors.toSet());
        var competitors = Stream.concat(selected.stream(), neighbors.stream()).flatMap(item -> item.note.competitors().stream())
                .distinct().filter(id -> !shown.contains(id) && trace.containsKey(id)).map(id -> detail(trace.get(id), intents)).toList();
        var project = project("reconstruction", false, revision);
        return new Description(project, expected != null && !expected.equals(project.revision()), context,
                missing, selected, neighbors, competitors);
    }

    private Detail detail(ReconstructionTrace.Row row, Map<String, List<JsonObject>> intents) {
        var entries = intents.getOrDefault(row.id(), List.of());
        return new Detail(note(row, null, null, null, entries), row, entries);
    }

    private NoteHandoff.Project project(String view, boolean draft, ReconstructionReview.Revision revision) {
        return new NoteHandoff.Project(work.toString(), song, view,
                ReconstructionArtifacts.fingerprint(List.of(builtRevision, revision)), draft, null, null);
    }

    private static String text(Description result) {
        var out = new StringBuilder().append("project: ").append(NoteHandoff.JSON.toJson(result.project)).append('\n');
        if (result.stale) out.append("stale: the work directory has changed since this revision\n");
        out.append("context: ").append(NoteHandoff.JSON.toJson(result.context)).append('\n');
        if (!result.missingIds.isEmpty()) out.append("missingIds: ").append(NoteHandoff.JSON.toJson(result.missingIds)).append('\n');
        for (var group : List.of(Map.entry("selected", result.selected), Map.entry("neighbors", result.neighbors),
                Map.entry("competitors", result.competitors))) {
            out.append(group.getKey()).append(" (").append(group.getValue().size()).append(")\n");
            for (var row : group.getValue()) out.append(NoteHandoff.noteText(row.note));
        }
        return out.toString();
    }

    private NoteHandoff.Context context(List<NoteHandoff.Note> notes, int nextBars, int omitted) {
        var located = notes.stream().map(NoteHandoff.Note::bar).filter(Objects::nonNull).toList();
        Integer first = located.stream().min(Integer::compare).orElse(null);
        Integer last = located.stream().max(Integer::compare).orElse(null);
        return new NoteHandoff.Context(first, last, last == null ? null : Math.min(last + nextBars, bars.size()),
                nextBars, notes.size(), omitted);
    }

    private int barIndex(double seconds) {
        for (int i = 0; i < bars.size(); i++) if (seconds < bars.get(i).end()) return i;
        return bars.size() - 1;
    }

    private double beat(int bar, double seconds) {
        var window = bars.get(bar);
        double relative = beats == null ? beatsPerBar * (seconds - window.start()) / (window.end() - window.start())
                : beats.index(seconds) - beats.index(window.start());
        return round(1 + Math.max(0, relative));
    }

    private NoteHandoff.Note note(ReconstructionTrace.Row row, Overlay overlay, String draftVoice, Integer draftVelocity,
                                  List<JsonObject> intents) {
        var time = row == null ? null : timing(row);
        double start = overlay == null ? time.sourceStart() : overlay.interval().sourceStart();
        double end = overlay == null ? time.sourceEnd() : overlay.interval().sourceEnd();
        double projectStart = overlay == null ? time.projectStart() : overlay.interval().projectStart();
        long tick = overlay == null ? time.startTick() : clock.tick(projectStart);
        long endTick = overlay == null ? time.endTick() : clock.tick(overlay.interval().projectEnd());
        var authored = row == null ? null : authored(row);
        var allocation = row == null ? null : row.allocation();
        var gate = row == null ? null : gate(row, overlay);
        var status = overlay == null ? status(row, gate) : switch (overlay.fate()) {
            case "shortened" -> "truncated";
            case "dropped", "merged" -> overlay.sounds() ? "played" : "dropped";
            case "assembly-only" -> "played";
            case "kept", "moved" -> status(row, gate);
            default -> overlay.fate();
        };
        String reason = overlay != null ? overlay.reason() : row.fateDetail() == null ? row.reason() : row.fateDetail();
        String allocationReason = allocation == null || Objects.equals(allocation.reason(), reason) ? null : allocation.reason();
        if (gate != null && !status.equals("dropped"))
            reason = reason == null ? gate.evidence() : reason + " [" + gate.evidence() + "]";
        var competitors = new LinkedHashSet<String>();
        if (overlay != null) competitors.addAll(overlay.competitors());
        if (allocation != null) {
            if (allocation.competitorNoteId() != null) competitors.add(allocation.competitorNoteId());
            if (allocation.blockingNoteIds() != null) competitors.addAll(allocation.blockingNoteIds());
            if (allocation.mergedInto() != null) competitors.add(allocation.mergedInto());
        }
        if (row != null && row.roles() != null && row.roles().mergedInto() != null) competitors.add(row.roles().mergedInto());
        if (row != null && row.scoreDecision() != null && row.scoreDecision().successorId() != null)
            competitors.add(row.scoreDecision().successorId());
        var edits = new LinkedHashSet<String>();
        if (row != null && row.editReasons() != null) edits.addAll(row.editReasons());
        if (intents != null) for (var edit : intents) {
            String id = edit.get("id").getAsString();
            if (edits.contains("edit:" + id) && !edit.has("status")) continue;
            edits.add((edit.has("status") ? edit.get("status").getAsString() : "accepted") + ":" + id);
        }
        String voice = draftVoice != null ? draftVoice : row != null && row.voice() != null ? row.voice().id() : null;
        if (overlay != null && overlay.patch() != null)
            voice = voice == null ? overlay.patch() : voice + " / " + overlay.patch();
        int bar = barIndex(start);
        var role = allocation != null && allocation.role() != null ? allocation.role()
                : row != null && row.roles() != null ? row.roles().role() : null;
        int pitch = overlay != null ? overlay.pitch() : gate != null ? gate.pitch() : authored.note().pitch();
        int part = overlay == null ? row.part() : overlay.part();
        String partName = overlay == null ? row.partName() : overlay.partName();
        Integer velocity = authored == null ? null : Integer.valueOf(authored.note().velocity());
        return new NoteHandoff.Note(overlay == null ? row.id() : overlay.id(),
                overlay != null && !overlay.eventId().equals(overlay.id()) ? overlay.eventId() : null,
                part < 0 ? null : part + " " + partName,
                overlay == null ? role == null ? null : role.name() : overlay.role().name().toLowerCase(java.util.Locale.ROOT),
                voice, overlay != null ? overlay.lane() : gate != null ? gate.lane() : allocation == null ? null : allocation.lane(),
                bar < 0 ? null : bar + 1, bar < 0 ? null : beat(bar, start), round(start), round(projectStart),
                tick, null, Math.max(0, endTick - tick), round(end - start), ReconstructionDescribe.pitch(pitch), pitch,
                draftVelocity != null ? draftVelocity : velocity,
                authored == null ? null : authored.note().source(), authored == null ? null : round(authored.note().confidence()),
                status, reason, allocationReason, allocation == null ? null : allocation.relocatedFrom(),
                overlay == null ? List.copyOf(competitors) : limited(competitors),
                overlay == null ? List.copyOf(edits) : limited(edits), bend(authored, overlay, gate), vibrato(authored, gate, intents));
    }

    private static List<String> limited(LinkedHashSet<String> values) {
        var result = new ArrayList<>(values.stream().limit(4).toList());
        if (values.size() > 4) result.add("+" + (values.size() - 4) + " more");
        return result;
    }

    private static ReconstructionTrace.Voiced gate(ReconstructionTrace.Row row, Overlay overlay) {
        return overlay == null ? row.voiced().stream().findFirst().orElse(null)
                : row.voiced().stream().filter(gate -> gate.id().equals(overlay.eventId())).findFirst().orElse(null);
    }

    private static String status(ReconstructionTrace.Row row, ReconstructionTrace.Voiced gate) {
        return switch (row.status()) {
            case dropped, merged -> gate != null && ReconstructionChords.isLabel(gate.evidence()) ? "played" : "dropped";
            case shortened -> "truncated";
            default -> gate == null ? "pending" : "played";
        };
    }

    private static boolean overlaps(ReconstructionTrace.Timing time, double start, double end) {
        return time.sourceStart() < end && (time.sourceStart() >= start || time.sourceEnd() > start);
    }

    private static ReconstructionTrace.Timing timing(ReconstructionTrace.Row row) {
        return Objects.requireNonNullElse(row.effectiveTiming(), row.timing());
    }

    private static ReconstructionTrace.NoteAt authored(ReconstructionTrace.Row row) {
        if (row.allocation() != null && row.allocation().effective() != null) return row.allocation().effective();
        if (row.roles() != null && row.roles().effective() != null) return row.roles().effective();
        if (row.structure() != null && row.structure().effective() != null) return row.structure().effective();
        return row.score() != null ? row.score() : row.source().note();
    }

    private static String bend(ReconstructionTrace.NoteAt authored, Overlay overlay, ReconstructionTrace.Voiced gate) {
        if (overlay != null && overlay.fate().startsWith("Pending") && !overlay.bend().isEmpty())
            return overlay.bend().size() + " draft points";
        if (gate != null && gate.bend() != null) return gate.bend().mode().name();
        if (authored != null && authored.note().bend() != null) return authored.note().bend().kind().name();
        return overlay != null && !overlay.bend().isEmpty() ? overlay.bend().size() + " points" : null;
    }

    private static String vibrato(ReconstructionTrace.NoteAt authored, ReconstructionTrace.Voiced gate, List<JsonObject> intents) {
        if (gate != null && gate.vibratoEnvelope() != null) {
            var segments = gate.vibratoEnvelope();
            return segments.size() == 1 ? round(segments.getFirst().rateHz()) + " Hz / "
                    + round(segments.getFirst().depthCents()) + " cents" : segments.size() + " segments";
        }
        if (intents != null) {
            for (var edit : intents.reversed()) {
                if (!"noteVoicing".equals(edit.get("op").getAsString()) || ReconstructionProposals.isInactive(edit)) continue;
                if (edit.has("vibratoEnvelope")) return edit.getAsJsonArray("vibratoEnvelope").size() + " intent segments";
                if (edit.has("vibrato")) return edit.get("vibrato") + " intent";
                if (edit.has("modulationOff") && edit.get("modulationOff").getAsBoolean()) return "off intent";
            }
        }
        var motion = authored == null ? null : authored.note().motion();
        return motion == null || motion.vibrato() == 0 ? null : round(motion.vibrato()) + " source evidence";
    }

    static Map<String, List<JsonObject>> touching(com.google.gson.JsonArray entries,
            Map<String, List<ReconstructionReview.EditResult>> outcomes, List<ReconstructionProposals.Result> proposals) {
        var byId = new java.util.HashMap<String, List<JsonObject>>();
        var resolved = new java.util.HashMap<>(outcomes);
        for (var proposal : proposals) resolved.put(proposal.id(), proposal.outcomes());
        for (var element : entries) {
            var edit = element.getAsJsonObject();
            String id = edit.get("id").getAsString();
            var ids = new LinkedHashSet<String>();
            var selector = edit.has("voiceEdit") && edit.get("voiceEdit").isJsonObject()
                    ? edit.getAsJsonObject("voiceEdit") : edit;
            if (selector.has("noteIds")) selector.getAsJsonArray("noteIds").forEach(note -> ids.add(note.getAsString()));
            if (selector.has("noteId")) ids.add(selector.get("noteId").getAsString());
            if ("insertNote".equals(edit.get("op").getAsString())) ids.add("edit-" + id);
            for (var result : resolved.getOrDefault(id, List.of())) ids.addAll(result.noteIds());
            for (String note : ids) byId.computeIfAbsent(note, ignored -> new ArrayList<>()).add(edit);
        }
        return byId;
    }
}
