package com.opensmpsdeck.headless;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionEditsTest {
    @Test void retriggerKeepsTheFirstIdAndReplaysStableIndependentAttacks() {
        var original = note("held", .10, .55, 68);
        var score = new ReconstructionStages.Score(1, 1_000_000, 60, 60, inputs(), List.of(),
                new MidiScore(List.of(new MidiScore.Part(0, "Synth", 0, false)), List.of(), List.of()),
                Map.of(), List.of(original), List.of(), List.of(), null, null);
        var edits = new ReconstructionEdits(List.of(intent("""
                {"op":"retrigger","id":"pulse","reason":"repeated attacks","noteId":"held",
                 "attacks":[{"offset":0,"gate":0.05},{"offset":0.1,"gate":0.06,"velocity":82}]}
                """)));
        var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
        var rebuilt = edits.applyScore(score, outcomes);
        assertEquals(List.of("held", "edit-pulse-1"), outcomes.getFirst().noteIds());
        assertEquals(List.of(original), outcomes.getFirst().removedNotes());
        assertEquals(rebuilt.notes(), outcomes.getFirst().resultNotes());
        assertEquals(82, rebuilt.notes().getLast().velocity());
        assertEquals(68, rebuilt.notes().getLast().pitch());
        assertEquals(.20, rebuilt.notes().getLast().start(), 1e-9);
        assertEquals(rebuilt, edits.applyScore(score, new ArrayList<>()));
        assertThrows(IllegalArgumentException.class, () -> intent("""
                {"op":"retrigger","id":"pulse","reason":"invalid","noteId":"held",
                 "attacks":[{"offset":0,"gate":0.12},{"offset":0.1,"gate":0.06}]}
                """));
    }

    @Test void selectiveLayerOffAcceptsOnlyNamedPolicies() {
        assertDoesNotThrow(() -> intent("""
                {"op":"layer","id":"mask","reason":"keep octave","noteIds":["held"],
                 "mode":"off","policies":["fmDouble"]}
                """));
        assertThrows(IllegalArgumentException.class, () -> intent("""
                {"op":"layer","id":"mask","reason":"keep octave","noteIds":["held"],
                 "mode":"off","policies":["unknown"]}
                """));
    }

    @Test void rejectsAmbiguousSelectorsAndUnorderedBendPoints() {
        var ambiguous = json("""
                {"id":"bad","reason":"evidence","op":"setRole","noteIds":["n"],
                 "span":[1,2],"part":0,"role":"melody"}
                """);
        var error = assertThrows(IllegalArgumentException.class, () -> ReconstructionEdits.parse(ambiguous, "edits[0]"));
        assertTrue(error.getMessage().contains("noteIds"));

        var unordered = json("""
                {"id":"bad","reason":"evidence","op":"bend","noteId":"n","points":[[0.2,0],[0.1,2]]}
                """);
        error = assertThrows(IllegalArgumentException.class, () -> ReconstructionEdits.parse(unordered, "edits[0]"));
        assertTrue(error.getMessage().contains("points[1]"));
    }

    @Test void unmergeRestoresTheEntireChainAndCanSelectByPitchAndSourceSpan() {
        var anchor = note("anchor", .10, .20, 60);
        var attack = note("attack", .20, .28, 60);
        var last = note("last", .28, .32, 60);
        var unrelated = note("unrelated", .25, .35, 64);
        var parts = List.of(new MidiScore.Part(0, "Synth", 0, false));
        var bars = List.of(new ReconstructionStructure.Bar("b0", 0, 1, false));
        var written = List.of(anchor, attack, last, unrelated);
        var score = new ReconstructionStages.Score(1, 1_000_000, 60, 60, inputs(), bars,
                new MidiScore(parts, List.of(), List.of()), Map.of(), written, List.of(), List.of(), null, null);
        var structure = new ReconstructionStructure.Result(1, bars, List.of(), List.of(), List.of(), written);
        var roles = new ReconstructionRoles.Result(1, 7.5, List.of(
                new ReconstructionRoles.Decision(anchor.withTiming(.10, .32), Role.harmony, "sustained-inner-tone", null),
                new ReconstructionRoles.Decision(attack, Role.drop, "rapid-inner-tone-reattack", "anchor"),
                new ReconstructionRoles.Decision(last, Role.drop, "rapid-inner-tone-reattack", "anchor"),
                new ReconstructionRoles.Decision(unrelated, Role.counter, null, null)), null);
        var edit = intent("""
                {"op":"unmerge","id":"attacks","reason":"separate transients","part":0,"span":[0.19,0.26],
                 "pitchMin":60,"pitchMax":60}
                """);
        var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
        var actual = new ReconstructionEdits(List.of(edit)).applyRoles(score, structure, roles, List.of(), outcomes);
        assertEquals(List.of("anchor", "attack", "last"), outcomes.getFirst().noteIds());
        for (var decision : actual.notes()) {
            var original = written.stream().filter(n -> n.id().equals(decision.note().id())).findFirst().orElseThrow();
            assertEquals(original, decision.note());
            assertNull(decision.mergedInto());
            if (original != unrelated) {
                assertEquals(Role.harmony, decision.role());
                assertEquals("edit:attacks", decision.reason());
            }
        }
    }

    @Test void roleFiltersUseEarlierEditsAndDeletionCanTargetGeneratedNotes() {
        var counter = note("counter", .1, .2, 69);
        var melody = note("melody", .1, .2, 65);
        var harmony = note("harmony", .16, .27, 60);
        var generated = note("lead-audio-0-400000-70", .4, .6, 70);
        var written = List.of(counter, melody, harmony);
        var bars = List.of(new ReconstructionStructure.Bar("b0", 0, 1, false));
        var score = new ReconstructionStages.Score(1, 1_000_000, 60, 60, inputs(), bars,
                new MidiScore(List.of(new MidiScore.Part(0, "Synth", 0, false)), List.of(), List.of()),
                Map.of(), written, List.of(), List.of(), null, null);
        var structure = new ReconstructionStructure.Result(1, bars, List.of(), List.of(), List.of(), written);
        var roles = new ReconstructionRoles.Result(1, 7.5, List.of(
                new ReconstructionRoles.Decision(counter, Role.counter, null, null),
                new ReconstructionRoles.Decision(melody, Role.melody, null, null),
                new ReconstructionRoles.Decision(harmony, Role.harmony, null, null),
                new ReconstructionRoles.Decision(generated, Role.melody, "lead-audio-added", null)), null);
        var edits = new ReconstructionEdits(List.of(
                intent("""
                        {"id":"swap","reason":"lead handoff","op":"swapRoles",
                         "part":0,"span":[0.1,0.3],"from":"counter","to":"melody"}
                        """),
                intent("""
                        {"id":"filter","reason":"alternate role","op":"setRole","role":"counter",
                         "selector":{"span":[0.1,0.3],"part":0,"pitchMin":68,"fromRole":"melody"}}
                        """),
                intent("""
                        {"id":"delete","reason":"no such attack in the stem","op":"deleteNote",
                         "selector":{"at":0.41,"pitch":70,"part":0}}
                        """)));
        var scoreOutcomes = new ArrayList<ReconstructionEdits.Outcome>();
        assertSame(score, edits.applyScore(score, scoreOutcomes));
        assertTrue(scoreOutcomes.isEmpty());
        var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
        var result = edits.applyRoles(score, structure, roles, scoreOutcomes, outcomes);
        var assigned = new java.util.HashMap<String, Role>();
        result.notes().forEach(decision -> assigned.put(decision.note().id(), decision.role()));
        assertEquals(Map.of("counter", Role.counter, "melody", Role.harmony, "harmony", Role.harmony,
                generated.id(), Role.drop), assigned);
        assertEquals(List.of("counter", "melody"), outcomes.getFirst().noteIds());
        assertEquals(List.of("counter"), outcomes.get(1).noteIds());
        assertEquals(List.of(generated.id()), outcomes.getLast().noteIds());
    }

    @Test void aBatchIsValidatedBeforePublishingAnyEdit(@TempDir Path work) throws Exception {
        var batch = new com.google.gson.JsonArray();
        for (int i = 0; i < 20; i++) batch.add(json("""
                {"id":"level-%d","reason":"level correction","op":"setVelocity","span":[0,1],"part":0,"velocity":30}
                """.formatted(i)));
        var file = work.resolve("batch.json");
        Files.writeString(file, batch.toString());
        var result = json(cli("reconstruct", "edit", work.toString(), "add", "--file", file.toString()));
        assertEquals(20, result.getAsJsonArray("ids").size());
        assertEquals("transcribe", result.get("from").getAsString());
        String saved = Files.readString(work.resolve(ReconstructionEditCommand.FILE));
        var invalid = new com.google.gson.JsonArray();
        var first = batch.get(0).getAsJsonObject().deepCopy();
        first.addProperty("id", "new");
        invalid.add(first);
        invalid.add(batch.get(0));
        assertThrows(IllegalArgumentException.class, () -> ReconstructionEditCommand.run(work, "add", invalid, null,
                false, (a, b, c) -> {}));
        assertEquals(saved, Files.readString(work.resolve(ReconstructionEditCommand.FILE)));
    }

    @Test void pointSelectorsRejectBothAmbiguityAndMissingMatches() {
        var notes = List.of(note("a", .10, .20, 60), note("b", .13, .26, 60));
        var score = new ReconstructionStages.Score(1, 1_000_000, 60, 60, inputs(), List.of(),
                new MidiScore(List.of(new MidiScore.Part(0, "Synth", 0, false)), List.of(), List.of()),
                Map.of(), notes, List.of(), List.of(), null, null);
        var edits = new ReconstructionEdits(List.of(
                intent("""
                        {"id":"tie","reason":"same pitch twice","op":"setNote","velocity":30,
                         "selector":{"at":0.115,"pitch":60,"part":0}}
                        """),
                intent("""
                        {"id":"absent","reason":"missing attack","op":"setNote","velocity":30,
                         "selector":{"at":0.3,"pitch":60,"part":0}}
                        """),
                intent("""
                        {"id":"unique","reason":"first attack","op":"setNote","velocity":30,
                         "selector":{"at":0.101,"pitch":60,"part":0}}
                        """)));
        var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
        var result = edits.applyScore(score, outcomes);
        assertEquals(List.of(ReconstructionEdits.Status.invalid, ReconstructionEdits.Status.invalid,
                ReconstructionEdits.Status.applied), outcomes.stream().map(ReconstructionEdits.Outcome::status).toList());
        assertEquals(List.of("a", "b"), outcomes.getFirst().noteIds());
        assertEquals(List.of(30, 90), result.notes().stream().map(Note::velocity).toList());
    }

    @Test void proposalsResolveAndPreviewWithoutChangingBuiltState(@TempDir Path dir) throws Exception {
        var midi = dir.resolve("fixture.mid");
        var wav = dir.resolve("mix.wav");
        var catalogue = dir.resolve("index.json");
        writeMidi(midi, true);
        writeAudio(catalogue, wav);
        var work = dir.resolve("work");
        cli("reconstruct", "run", midi.toString(), wav.toString(), catalogue.toString(), work.toString(), "--seconds", "2");
        var score = Files.readAllBytes(work.resolve("score.json"));
        var render = Files.readAllBytes(work.resolve("render.wav"));
        var batch = dir.resolve("batch.json");
        Files.writeString(batch, """
                [{"id":"keep","group":"phrase","reason":"Missed attack","op":"insertNote",
                  "part":0,"start":0.85,"end":1.2,"pitch":68},
                 {"id":"reject","group":"phrase","reason":"Try a second attack","op":"insertNote",
                  "part":0,"start":1.25,"end":1.45,"pitch":71}]
                """);
        cli("reconstruct", "edit", "propose", work.toString(), "--file", batch.toString());
        var listed = json(cli("reconstruct", "proposals", "list", work.toString(), "--group", "phrase"));
        assertEquals(2, listed.getAsJsonArray("proposals").size());
        var proposal = listed.getAsJsonArray("proposals").get(0).getAsJsonObject();
        assertEquals("agent", proposal.getAsJsonObject("edit").get("author").getAsString());
        assertEquals("proposed", proposal.getAsJsonObject("edit").get("status").getAsString());
        assertEquals("applied", proposal.getAsJsonArray("outcomes").get(0).getAsJsonObject().get("status").getAsString());
        assertThrows(IllegalArgumentException.class, () -> ReconstructionEditCommand.run(work, "propose", json("""
                {"id":"invalid-evidence","reason":"Missing note","op":"insertNote",
                 "part":0,"start":0.85,"end":1.2,"pitch":68,
                 "evidence":{"text":"Outside work directory","path":"../source.wav"}}
                """), null, false, (a, b, c) -> {}));

        cli("reconstruct", "run", "--from", "transcribe", work.toString());
        assertArrayEquals(score, Files.readAllBytes(work.resolve("score.json")));
        assertArrayEquals(render, Files.readAllBytes(work.resolve("render.wav")));
        var journal = Files.readAllBytes(work.resolve("edits.json"));
        var preview = json(cli("reconstruct", "run", work.toString(), "--with-proposals", "--id", "keep"));
        var shadow = Path.of(preview.get("workDir").getAsString());
        assertTrue(ReconstructionTrace.read(shadow).containsKey("edit-keep"));
        assertFalse(ReconstructionTrace.read(shadow).containsKey("edit-reject"));
        assertTrue(Files.isRegularFile(Path.of(preview.getAsJsonArray("comparisons").get(0).getAsJsonObject()
                .get("output").getAsString())));
        assertArrayEquals(journal, Files.readAllBytes(work.resolve("edits.json")));
        assertArrayEquals(render, Files.readAllBytes(work.resolve("render.wav")));

        cli("reconstruct", "proposals", "revise", work.toString(), "keep", "--file", reviseFile(dir, """
                {"reason":"move the approved attack","op":"insertNote","part":0,"start":0.65,"end":0.8,"pitch":69}
                """).toString());
        assertEquals(.65, ReconstructionProposals.results(work).stream().filter(row -> row.id().equals("keep"))
                .findFirst().orElseThrow().edit().get("start").getAsDouble());
        cli("reconstruct", "edit", work.toString(), "propose", """
                {"id":"second","reason":"second approved attack","op":"insertNote","part":0,"start":1.25,"end":1.4,"pitch":71}
                """);
        var ids = dir.resolve("accept.ids");
        Files.writeString(ids, "[\"keep\",\"second\"]");
        var accepted = json(cli("reconstruct", "proposals", "accept", work.toString(), "--file", ids.toString()));
        assertEquals("accepted", accepted.get("status").getAsString());
        assertEquals(2, accepted.getAsJsonArray("ids").size());
        assertFalse(accepted.has("proposals"));
        cli("reconstruct", "proposals", "reject", work.toString(), "--group", "phrase", "--op", "insertNote");
        var history = json(cli("reconstruct", "proposals", "list", work.toString(), "--show-decided"));
        assertEquals(3, history.getAsJsonArray("decided").size());
        assertTrue(history.getAsJsonArray("proposals").isEmpty());
        assertArrayEquals(score, Files.readAllBytes(work.resolve("score.json")));
        cli("reconstruct", "run", "--from", "transcribe", work.toString());
        assertTrue(ReconstructionTrace.read(work).containsKey("edit-keep"));
        assertFalse(ReconstructionTrace.read(work).containsKey("edit-reject"));
    }

    private static Path reviseFile(Path work, String intent) throws Exception {
        var path = work.resolve("revision.json");
        Files.writeString(path, intent);
        return path;
    }

    @Test void authoredLayersReplayAndSharePlacementEvidenceWithDryRunsAndProposals(@TempDir Path dir) throws Exception {
        var midi = dir.resolve("fixture.mid");
        var wav = dir.resolve("mix.wav");
        var catalogue = dir.resolve("index.json");
        writeMidi(midi, true);
        writeAudio(catalogue, wav, true);
        var work = dir.resolve("work");
        cli("reconstruct", "run", midi.toString(), wav.toString(), catalogue.toString(), work.toString(), "--seconds", "2");
        byte[] render = Files.readAllBytes(work.resolve("render.wav"));
        var echo = json("""
                {"op":"layer","id":"echo","author":"human","reason":"Echo the opening note","mode":"echo","laneType":"FM",
                 "delayFrames":10,"levelDb":-9,"selectors":[{"at":0,"pitch":60,"part":0},{"at":0.7,"pitch":111,"part":0}]}
                """);
        var dryRun = ReconstructionEditCommand.run(work, "add", echo, null, false, true, (a, b, c) -> {});
        assertEquals("assemble", dryRun.get("from").getAsString());
        var outcomes = dryRun.getAsJsonArray("outcomes");
        assertEquals("invalid", outcomes.get(0).getAsJsonObject().get("status").getAsString());
        var dryLayer = outcomes.get(1).getAsJsonObject().getAsJsonObject("layer");
        assertEquals("applied", dryLayer.get("status").getAsString());
        assertEquals(12, dryLayer.get("attenuation").getAsInt());
        assertEquals(10, dryLayer.get("startTick").getAsInt());
        assertFalse(Files.exists(work.resolve("edits.json")));

        var chorus = json("""
                {"op":"layer","id":"chorus","reason":"Double a counter note","mode":"chorus","laneType":"PSG",
                 "detune":-4,"span":[0.49,0.55],"part":0,"pitchMin":64,"pitchMax":64}
                """);
        var proposed = ReconstructionEditCommand.run(work, "propose", chorus, null, false, (a, b, c) -> {});
        var proposalOutcomes = proposed.getAsJsonArray("proposals").get(0).getAsJsonObject().getAsJsonArray("outcomes");
        assertEquals("applied", proposalOutcomes.get(0).getAsJsonObject().getAsJsonObject("layer")
                .get("status").getAsString());
        assertArrayEquals(render, Files.readAllBytes(work.resolve("render.wav")));

        var preview = ReconstructionReview.previewLayer(work, echo, ReconstructionReview.revision(work), (a, b, c) -> {});
        assertEquals(1, preview.comparisons().size());
        assertTrue(Files.isRegularFile(Path.of(preview.comparisons().getFirst().output())));
        assertArrayEquals(render, Files.readAllBytes(work.resolve("render.wav")));
        ReconstructionEditCommand.run(work, "add", echo, null, true, (a, b, c) -> {});
        cli("reconstruct", "proposals", "accept", work.toString(), "chorus");
        cli("reconstruct", "run", "--from", "transcribe", work.toString());
        var trace = ReconstructionTrace.read(work);
        var echoResult = trace.get("n-0-0-60").layers().getFirst();
        assertEquals(dryLayer, new com.google.gson.Gson().toJsonTree(echoResult));
        assertEquals("PSG1", trace.get("n-0-500000-64").layers().getFirst().lane());
        assertFalse(java.util.Arrays.equals(render, Files.readAllBytes(work.resolve("render.wav"))));
        var inspection = ReconstructionInspection.open(work);
        assertEquals(2, inspection.layers().size());
        var focused = json(cli("reconstruct", "trace", work.toString(), "--lane", echoResult.lane(), "--json"));
        assertTrue(focused.getAsJsonArray("notes").asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject).anyMatch(row -> row.has("layers")));
    }

    @Test void studioApplyPublishesOnlyOnSuccessAndSharesTheCliJournal(@TempDir Path dir) throws Exception {
        Path midi = dir.resolve("fixture.mid");
        Path wav = dir.resolve("mix.wav");
        Path catalogue = dir.resolve("index.json");
        writeMidi(midi, true);
        writeAudio(catalogue, wav);
        Path work = dir.resolve("work");
        cli("reconstruct", "run", midi.toString(), wav.toString(), catalogue.toString(), work.toString(), "--seconds", "2");
        var revision = ReconstructionReview.revision(work);
        byte[] score = Files.readAllBytes(work.resolve("score.json"));
        byte[] render = Files.readAllBytes(work.resolve("render.wav"));
        var insert = new com.google.gson.JsonArray();
        insert.add(json("""
                {"id":"human-answer","author":"human","reason":"Missed attack","op":"insertNote",
                 "part":0,"start":0.85,"end":1.2,"pitch":68,"velocity":87}
                """));

        assertThrows(java.util.concurrent.CancellationException.class, () ->
                ReconstructionReview.apply(work, revision, insert, (done, total, message) -> {
                    if (done == 2) throw new java.util.concurrent.CancellationException();
                }));
        assertFalse(Files.exists(work.resolve("edits.json")));
        assertArrayEquals(score, Files.readAllBytes(work.resolve("score.json")));
        assertArrayEquals(render, Files.readAllBytes(work.resolve("render.wav")));

        var applied = ReconstructionReview.apply(work, revision, insert, (done, total, message) -> {});
        assertEquals("transcribe", applied.get("from").getAsString());
        assertTrue(applied.has("lanePermutations"));
        assertTrue(applied.has("compiledTimeline"));
        assertTrue(applied.has("renderedPcm"));
        assertEquals(insert, ReconstructionReview.revision(work).entries());
        assertEquals("applied", ReconstructionReview.outcomes(work).get("human-answer").getFirst().status());

        var role = insert.deepCopy();
        role.add(json("""
                {"id":"human-role","author":"human","reason":"Lead","op":"setRole",
                 "noteIds":["edit-human-answer"],"role":"melody"}
                """));
        var roleDiff = ReconstructionReview.apply(work, ReconstructionReview.revision(work), role, (a, b, c) -> {});
        assertEquals("roles", roleDiff.get("from").getAsString());
        cli("reconstruct", "run", "--from", "transcribe", work.toString());
        assertEquals(role, ReconstructionReview.revision(work).entries());
        var rebuilt = ReconstructionTrace.read(work).get("edit-human-answer");
        assertEquals(68, rebuilt.score().note().pitch());
        assertEquals(Role.melody, rebuilt.roles().role());
    }

    @Test void studioVoiceAndNoteScopesReplayWithScoreEdits(@TempDir Path dir) throws Exception {
        Path midi = dir.resolve("fixture.mid");
        Path wav = dir.resolve("mix.wav");
        Path catalogue = dir.resolve("index.json");
        writeMidi(midi, true);
        writeAudio(catalogue, wav, true);
        Path work = dir.resolve("work");
        cli("reconstruct", "run", midi.toString(), wav.toString(), catalogue.toString(), work.toString(), "--seconds", "2");
        var asset = InstrumentCatalogue.open(catalogue);
        String fm = asset.variants().stream().filter(v -> asset.asset(v.assetId()).kind() == InstrumentCatalogue.Kind.FM_VOICE)
                .findFirst().orElseThrow().id();
        var batch = JsonParser.parseString("""
                [
                  {"id":"lead-voice","author":"human","reason":"Identify lead","op":"voiceEdit",
                   "voiceEdit":{"op":"create","voiceId":"lead","name":"Lead instrument 1","kind":"tonal"}},
                  {"id":"lead-members","author":"human","reason":"Identify lead","op":"voiceEdit",
                   "voiceEdit":{"op":"move","voiceId":"lead","noteIds":["n-0-0-60"]}},
                  {"id":"lead-patch","author":"human","reason":"Set instrument","op":"voiceEdit",
                   "voiceEdit":{"op":"assign","voiceId":"lead","assignment":{"laneType":"FM","instrument":"%s"}}},
                  {"id":"level","author":"human","reason":"Scale attacks","op":"setVelocity",
                   "noteIds":["n-0-0-60","n-0-1250000-62"],"scale":0.5}
                ]
                """.formatted(fm)).getAsJsonArray();
        ReconstructionReview.apply(work, ReconstructionReview.revision(work), batch, (a, b, c) -> {});
        var voice = ReconstructionVoicePlan.load(work).voices().stream().filter(v -> v.id().equals("lead")).findFirst().orElseThrow();
        assertEquals(List.of("n-0-0-60"), voice.noteIds());
        assertEquals(fm, voice.assignment().instrument());
        assertEquals(45, ReconstructionTrace.read(work).get("n-0-0-60").score().note().velocity());

        var clip = ReconstructionEditing.audition(work, java.util.Set.of("n-0-0-60"),
                new ReconstructionEditing.Instrument(ReconstructionLaneType.FM, fm, "Test"));
        assertEquals(1, clip.project().clips().size());
        assertEquals(0, clip.start());
        byte[] before = Files.readAllBytes(work.resolve("render.wav"));
        batch.add(json("""
                {"id":"motion","author":"human","reason":"Add vibrato","op":"noteVoicing","noteIds":["n-0-0-60"],
                 "vibrato":{"delay":1,"rate":1,"delta":8,"steps":6}}
                """));
        var changed = ReconstructionReview.apply(work, ReconstructionReview.revision(work), batch, (a, b, c) -> {});
        assertEquals("assemble", changed.get("from").getAsString());
        byte[] after = Files.readAllBytes(work.resolve("render.wav"));
        assertFalse(java.util.Arrays.equals(before, after));
        cli("reconstruct", "run", "--from", "transcribe", work.toString(), "--keep-voicing");
        assertEquals(batch, ReconstructionReview.revision(work).entries());
        for (var results : ReconstructionReview.outcomes(work).values())
            assertEquals("applied", results.getFirst().status(), results.toString());
        assertArrayEquals(after, Files.readAllBytes(work.resolve("render.wav")));
    }

    @Test void intentsSurviveTranscriptionAndManifestReplayAndReportOrphans(@TempDir Path dir) throws Exception {
        Path midi = dir.resolve("theme.mid");
        writeMidi(midi, true);
        Path catalogue = dir.resolve("catalogue.json");
        Path wav = dir.resolve("mix.wav");
        writeAudio(catalogue, wav);
        Path work = dir.resolve("work");
        cli("reconstruct", "run", midi.toString(), wav.toString(), catalogue.toString(), work.toString(), "--seconds", "2");

        Path plan = work.resolve("roleplan.json");
        java.nio.file.Files.writeString(plan, """
                {"version":1,"rules":[{"bars":"1-1","part":0,"role":"melody","voice":"top","pitchMax":60}]}
                """);
        String kept = "n-0-500000-64";
        String removed = "n-0-1250000-62";
        String competitor = "n-0-500000-67";
        cli("reconstruct", "edit", work.toString(), "add", """
                {"id":"answer","reason":"answer attack","op":"insertNote","part":0,"start":0.85,"end":1.2,
                 "pitch":68,"velocity":87,"role":"melody","lane":"FM1"}
                """);
        add(work, """
                {"id":"timing","reason":"lead phrasing","op":"setNote","noteId":"%s","start":0.51,"end":0.83,"pitch":65}
                """.formatted(kept), false);
        add(work, """
                {"id":"deletion","reason":"false detection","op":"deleteNote","noteId":"%s"}
                """.formatted(removed), false);
        add(work, """
                {"id":"melody","reason":"lead phrase","op":"setRole","span":[0.5,0.6],"part":0,
                 "pitchMin":65,"pitchMax":65,"role":"melody","lane":"FM1"}
                """, false);
        add(work, """
                {"id":"opponent","reason":"alternate placement","op":"pinLane","noteIds":["%s"],"lane":"FM1"}
                """.formatted(competitor), false);
        // These two onsets quantize to nearby presentations. The earlier pin occupies the lane through the retime.
        var rerun = add(work, """
                {"id":"scoop","reason":"pitch contour","op":"bend","noteId":"edit-answer",
                 "points":[[0.85,-2],[0.92,0],[1.2,1],[1.21,0]]}
                """, true);
        assertEquals("transcribe", rerun.get("from").getAsString());
        assertTrue(rerun.getAsJsonObject("traceDiff").get("changedNotes").getAsInt() >= 4);
        assertTrue(rerun.getAsJsonObject("traceDiff").get("outsideSpanChanges").getAsInt() > 0);

        var trace = ReconstructionTrace.read(work);
        var answer = trace.get("edit-answer");
        assertEquals("edit", answer.source().provenance());
        assertEquals(87, answer.score().note().velocity());
        assertEquals("FM1", answer.allocation().lane());
        assertEquals("edit:scoop", answer.voiced().getFirst().bend().reason());
        assertEquals(new ReconstructionBends.Point(1.2, 0), answer.score().note().bend().points().getLast());
        assertEquals("edit:deletion", trace.get(removed).reason());
        assertNull(trace.get(removed).score());
        var changed = trace.get(kept);
        assertEquals(.5, changed.timing().sourceStart());
        assertNotEquals(changed.timing(), changed.effectiveTiming());
        assertEquals(answer.voiced().getFirst().gate(), answer.effectiveTiming());
        assertEquals(.51, changed.score().note().start());
        assertEquals(.83, changed.roles().effective().note().end());
        assertNull(changed.roles().preLegatoEnd());
        assertEquals(65, changed.score().note().pitch());
        assertEquals(Role.melody, changed.roles().role());
        assertEquals(competitor, changed.allocation().competitorNoteId());
        assertTrue(changed.editReasons().contains("edit:melody"));
        assertTrue(trace.containsKey("n-0-0-60"));

        Path replay = dir.resolve("replay");
        cli("reconstruct", "rerun", work.toString(), replay.toString());
        assertEquals(-1, java.nio.file.Files.mismatch(plan, replay.resolve("roleplan.json")));
        for (Path path : List.of(work, replay)) {
            var manifest = ReconstructionArtifacts.read(path.resolve("manifest.json"), ReconstructionManifest.Manifest.class);
            assertEquals(ReconstructionCli.audioHash(plan), manifest.rolePlanSha256());
        }
        var replayTrace = ReconstructionTrace.diff(work, replay, null);
        assertEquals(0, replayTrace.getAsJsonObject("compiledTimeline").get("changedEvents").getAsInt());
        assertEquals(0, replayTrace.getAsJsonArray("changes").size(), replayTrace.toString());
        cli("reconstruct", "run", "--from", "transcribe", work.toString());
        assertEquals(0, ReconstructionTrace.diff(work, replay, null).getAsJsonArray("changes").size());

        writeMidi(midi, false);
        var stderr = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"reconstruct", "run", "--from", "transcribe", work.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(stderr)), stderr.toString());
        assertTrue(stderr.toString().contains("edit:deletion orphaned"), stderr::toString);
        var report = ReconstructionArtifacts.readTree(work.resolve("report.json")).getAsJsonObject();
        assertTrue(report.has("sustainProbe"));
        cli("reconstruct", "assemble", work.toString());
        assertTrue(ReconstructionArtifacts.readTree(work.resolve("report.json")).getAsJsonObject().has("sustainProbe"));
        assertTrue(report.getAsJsonArray("editResults").asList().stream().map(e -> e.getAsJsonObject()).anyMatch(
                e -> e.get("id").getAsString().equals("deletion") && e.get("status").getAsString().equals("orphaned")));

        var cleared = ReconstructionEditCommand.run(work, "remove", null, "answer", true, (a, b, c) -> {});
        assertFalse(ReconstructionTrace.read(work).containsKey("edit-answer"));
        assertTrue(cleared.toString().contains("\"id\":\"scoop\",\"op\":\"bend\",\"status\":\"orphaned\""));
    }

    @Test void registerSelectionPreviewsPriorRolesAndGlideSurvivesReplay(@TempDir Path dir) throws Exception {
        Path midi = dir.resolve("theme.mid"), catalogue = dir.resolve("catalogue.json"), wav = dir.resolve("mix.wav");
        writeMidi(midi, true);
        writeAudio(catalogue, wav);
        Path work = dir.resolve("work");
        cli("reconstruct", "run", midi.toString(), wav.toString(), catalogue.toString(), work.toString(),
                "--seconds", "2", "--tempo", "120", "--steady-tempo", "80");

        String from = "n-0-0-60", top = "n-0-500000-67", bottom = "n-0-500000-64";
        add(work, """
                {"id":"landing","reason":"separate melody attacks","op":"setRole","role":"melody","lane":"FM1",
                 "selectors":[{"at":0,"pitch":60,"part":0},{"at":0.5,"pitch":67,"part":0}]}
                """, false);
        String saved = Files.readString(work.resolve("edits.json"));
        String register = """
                {"id":"register","reason":"bring out the chord top","op":"setRole","part":0,"span":[0.7,0.8],
                 "timeBase":"project","voice":"top","fromRole":"counter","role":"melody","others":"harmony"}
                """;
        var preview = json(cli("reconstruct", "edit", work.toString(), "add", "--dry-run", "--verbose", register));
        assertEquals(saved, Files.readString(work.resolve("edits.json")));
        assertFalse(Files.exists(work.resolve("edit-results.json")));
        var results = preview.getAsJsonArray("notes").asList().stream().map(row -> row.getAsJsonObject()).toList();
        assertEquals(List.of(top, bottom), results.stream().map(row -> row.get("noteId").getAsString()).toList());
        assertTrue(results.getFirst().get("satisfied").getAsBoolean());
        assertEquals("melody", results.getFirst().get("incomingRole").getAsString());
        assertEquals("harmony", results.getLast().get("resultingRole").getAsString());
        assertTrue(preview.toString().contains("\"status\":\"satisfied\""));
        assertFalse(preview.toString().contains("orphaned"));

        add(work, register, false);
        add(work, """
                {"id":"slide","reason":"connect two lead attacks","op":"glide","fromNoteId":"%s",
                 "toNoteId":"%s","shape":"smooth","seconds":0.15}
                """.formatted(from, top), true);
        var trace = ReconstructionTrace.read(work);
        var departure = trace.get(from);
        var landing = trace.get(top);
        assertEquals(.5, departure.score().note().end(), 1e-9);
        assertEquals(700, departure.score().note().bend().points().getLast().cents());
        assertEquals(landing.effectiveTiming().startTick(), departure.effectiveTiming().endTick());
        assertEquals("edit:slide", departure.voiced().getFirst().bend().reason());
        Path replay = dir.resolve("replay");
        cli("reconstruct", "rerun", work.toString(), replay.toString());
        assertEquals(0, ReconstructionTrace.diff(work, replay, null).getAsJsonArray("changes").size());
    }

    @Test void splitAndMergePreviewScoreGatesAndSurviveTranscribeReplay(@TempDir Path dir) throws Exception {
        Path midi = dir.resolve("theme.mid"), catalogue = dir.resolve("catalogue.json"), wav = dir.resolve("mix.wav");
        writeMidi(midi, true);
        writeAudio(catalogue, wav);
        Path work = dir.resolve("work");
        cli("reconstruct", "run", midi.toString(), wav.toString(), catalogue.toString(), work.toString(),
                "--seconds", "2", "--tempo", "120", "--steady-tempo", "80");
        String split = """
                {"id":"cut","op":"splitNote","reason":"re-attack","selector":{"at":0,"pitch":60,"part":0},
                 "at":0.3,"timeBase":"project","pitch":61}
                """;
        var preview = json(cli("reconstruct", "edit", work.toString(), "add", "--dry-run", "--verbose", split));
        assertFalse(Files.exists(work.resolve("edits.json")));
        assertFalse(Files.exists(work.resolve("edit-results.json")));
        var result = preview.getAsJsonArray("outcomes").get(0).getAsJsonObject().getAsJsonArray("resultNotes");
        assertEquals(2, result.size());
        assertEquals(.2, result.get(1).getAsJsonObject().get("start").getAsDouble(), 1e-9);
        assertEquals(61, result.get(1).getAsJsonObject().get("pitch").getAsInt());

        add(work, split, false);
        add(work, """
                {"id":"join","op":"mergeNotes","reason":"sustained slide","glide":true,
                 "selectors":[{"at":1.25,"pitch":62,"part":0},{"at":0.5,"pitch":67,"part":0}]}
                """, false);
        add(work, """
                {"id":"lead","op":"setRole","reason":"keep both attacks and the slide on FM",
                 "noteIds":["edit-cut-left","edit-cut-right","n-0-500000-67"],"role":"melody","lane":"FM1"}
                """, false);
        cli("reconstruct", "run", "--from", "transcribe", work.toString());
        var trace = ReconstructionTrace.read(work);
        var left = trace.get("edit-cut-left");
        var right = trace.get("edit-cut-right");
        assertEquals("edit:cut", trace.get("n-0-0-60").reason());
        assertEquals("edit:cut", right.editReasons().getFirst());
        assertEquals("FM1", left.allocation().lane());
        assertEquals("FM1", right.allocation().lane());
        assertEquals(left.effectiveTiming().endTick(), right.effectiveTiming().startTick());
        var landing = trace.get("n-0-1250000-62");
        assertEquals("edit:join", landing.reason());
        assertEquals("n-0-500000-67", landing.scoreDecision().successorId());
        var glide = trace.get("n-0-500000-67");
        assertEquals(-500, glide.score().note().bend().points().getLast().cents());
        assertEquals("edit:join", glide.voiced().getFirst().bend().reason());
        cli("reconstruct", "run", "--from", "transcribe", work.toString());
        assertEquals(trace, ReconstructionTrace.read(work));
    }

    @Test void spanMergeKeepsTheFirstAttackAndRejectsConflictsAtomically() {
        var notes = List.of(note("first", .10, .22, 60), note("repeat", .25, .32, 60),
                note("last", .37, .64, 60), note("neighbor", .25, .45, 64),
                new Note("different-part", 1, .25, .64, 60, 90, "midi", 1));
        var score = new ReconstructionStages.Score(1, 1_000_000, 60, 60, inputs(), List.of(),
                new MidiScore(List.of(new MidiScore.Part(0, "Synth", 0, false),
                        new MidiScore.Part(1, "Other synth", 0, false)), List.of(), List.of()), Map.of(),
                notes, List.of(), List.of(), null, null);
        var merge = intent("""
                {"id":"sustain","op":"mergeNotes","reason":"collapse re-attacks","part":0,"pitch":60,"span":[0.1,0.4]}
                """);
        var delete = intent("""
                {"id":"delete","op":"deleteNote","reason":"unwanted repeat","noteId":"repeat"}
                """);
        for (var intents : List.of(List.of(delete, merge), List.of(merge, delete))) {
            var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
            var result = new ReconstructionEdits(intents).applyScore(score, outcomes);
            assertEquals(.22, result.notes().stream().filter(n -> n.id().equals("first")).findFirst().orElseThrow().end());
            var conflict = outcomes.stream().filter(o -> o.id().equals("sustain")).findFirst().orElseThrow();
            assertEquals(ReconstructionEdits.Status.conflict, conflict.status());
            assertTrue(conflict.detail().contains("edit:delete"));
        }

        var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
        var result = new ReconstructionEdits(List.of(merge)).applyScore(score, outcomes);
        assertEquals(List.of("first", "neighbor", "different-part"), result.notes().stream().map(Note::id).toList());
        assertEquals(.64, result.notes().getFirst().end());
        assertEquals(List.of("repeat", "last"), outcomes.getFirst().removedNotes().stream().map(Note::id).toList());

        for (var pair : List.of(List.of("first", "different-part"), List.of("first", "neighbor"),
                List.of("first", "missing"))) {
            outcomes.clear();
            var invalid = intent("""
                    {"id":"invalid","op":"mergeNotes","reason":"invalid pair","noteIds":["%s","%s"]}
                    """.formatted(pair.getFirst(), pair.getLast()));
            assertSame(score, new ReconstructionEdits(List.of(invalid)).applyScore(score, outcomes));
            assertTrue(List.of(ReconstructionEdits.Status.invalid, ReconstructionEdits.Status.orphaned)
                    .contains(outcomes.getFirst().status()));
        }
    }

    @Test void laneTypesRespectAffinityAndDoNotSpillWhenFull() {
        var type = ReconstructionLaneType.PSG;
        var pins = new java.util.LinkedHashMap<String, ReconstructionEdits.Pin>();
        var decisions = new ArrayList<ReconstructionRoles.Decision>();
        for (int i = 0; i < 4; i++) {
            Note note = note("backing-" + i, .1, .4, 60 + i);
            pins.put(note.id(), new ReconstructionEdits.Pin(null, "psg", i + 1, type));
            decisions.add(new ReconstructionRoles.Decision(note, Role.harmony, null, null));
        }
        var roles = new ReconstructionRoles.Result(1, 7.5, decisions, null, null, pins);
        var parts = List.of(new MidiScore.Part(0, "Synth", 0, false));
        var allocation = ReconstructionAllocation.allocateWithAffinity(roles, parts, 60, false, false,
                Map.of("backing-0", "PSG2", "backing-1", "FM4"));
        var notes = allocation.notes();
        assertEquals("PSG2", notes.getFirst().lane());
        assertEquals(java.util.Set.of("PSG1", "PSG2", "PSG3"),
                java.util.Set.copyOf(notes.subList(0, 3).stream().map(ReconstructionAllocation.Entry::lane).toList()));
        assertEquals(ReconstructionAllocation.Outcome.dropped, notes.getLast().outcome());
        assertEquals(type, notes.getLast().laneType());
        assertNull(notes.getLast().lane());
        assertTrue(notes.getLast().reason().contains("edit:psg"));

        assertNull(ReconstructionEdits.laneTypeIssue(note("hat", .1, .2, 42), true,
                ReconstructionLaneType.NOISE, false));
        assertTrue(ReconstructionEdits.laneTypeIssue(note("hat", .1, .2, 42), true, type, false)
                .contains("cannot voice MIDI drums"));
        assertTrue(ReconstructionEdits.laneTypeIssue(note("hat", .1, .2, 42), true,
                ReconstructionLaneType.FM, false).contains("FM percussion voicing"));
    }

    @Test void chordEditsSurviveReplayAndDescribeTheirAssembledGates(@TempDir Path dir) throws Exception {
        Path midi = dir.resolve("chords.mid"), catalogue = dir.resolve("catalogue.json"), wav = dir.resolve("mix.wav");
        var source = new Sequence(Sequence.PPQ, 480);
        var track = source.createTrack();
        for (int[] event : new int[][]{{60, 0, 420}, {64, 0, 420}, {67, 0, 420},
                {60, 480, 930}, {63, 480, 930}, {67, 480, 930}, {58, 480, 930},
                {60, 960, 1350}, {63, 960, 1350}, {67, 960, 1350}}) {
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, event[0], 90), event[1]));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, event[0], 0), event[2]));
        }
        MidiSystem.write(source, 0, midi.toFile());
        writeAudio(catalogue, wav, true);
        Path work = dir.resolve("work");
        cli("reconstruct", "run", midi.toString(), wav.toString(), catalogue.toString(), work.toString(),
                "--seconds", "2");
        cli("reconstruct", "sections", "add", work.toString(), """
                {"id":"opening","level":"section","name":"Opening","sourceStart":0,"sourceEnd":0.45,
                 "author":"human","reason":"first chord"}
                """);
        var arp = add(work, """
                {"id":"cycle","reason":"free two lanes","op":"arpeggio","part":0,
                 "span":{"section":"opening"},"rate":2,"order":"up-down"}
                """, true);
        assertEquals("allocate", arp.get("from").getAsString());
        add(work, """
                {"id":"seventh","reason":"test refusal","op":"chordInstrument","part":0,"span":[0.5,0.9]}
                """, true);
        add(work, """
                {"id":"minor","reason":"use three carriers","op":"chordInstrument",
                 "noteIds":["n-0-1000000-60","n-0-1000000-63","n-0-1000000-67"]}
                """, true);

        var trace = ReconstructionTrace.read(work);
        var root = trace.get("n-0-0-60").voiced();
        assertEquals(List.of(0L, 8L, 16L, 24L), root.stream().map(gate -> gate.gate().startTick()).toList());
        assertEquals(2, trace.get("n-0-0-64").voiced().getFirst().gate().startTick());
        var minor = trace.get("n-0-1000000-63");
        assertTrue(minor.voiced().getFirst().evidence().contains("chord instrument"));
        assertEquals(60, minor.voiced().getFirst().gate().startTick());
        assertTrue(ReconstructionInspection.open(work).overlays().stream().anyMatch(note ->
                note.id().equals(minor.id()) && note.sounds() && note.reason().equals("absorbed-into-chord-instrument")));
        assertTrue(ReconstructionEdits.Journal.read(work).all().stream()
                .anyMatch(row -> row.id().equals("seventh") && row.status() == ReconstructionEdits.Status.invalid));
        var report = ReconstructionArtifacts.readTree(work.resolve("report.json")).getAsJsonObject();
        assertEquals(24, report.get("arpeggioExtraNoteBytes").getAsInt());
        Path replay = dir.resolve("replay");
        cli("reconstruct", "rerun", work.toString(), replay.toString());
        assertEquals(0, ReconstructionTrace.diff(work, replay, null).getAsJsonArray("changes").size());

        cli("reconstruct", "edit", work.toString(), "remove", "minor");
        Path batch = dir.resolve("combined-edits.json");
        Files.writeString(batch, """
                [
                  {"id":"split-tone","reason":"reattack after the chord","op":"splitNote","noteId":"n-0-0-64","at":0.3},
                  {"id":"merge-tone","reason":"hold the middle tone","op":"mergeNotes",
                   "noteIds":["n-0-500000-63","n-0-1000000-63"]},
                  {"id":"noise-arp","reason":"route the opening chord to noise","op":"pinLane",
                   "noteIds":["edit-split-tone-left","n-0-0-67"],"laneType":"NOISE"},
                  {"id":"fm-chord","reason":"voice the edited minor triad","op":"chordInstrument",
                   "noteIds":["n-0-500000-60","n-0-500000-63","n-0-500000-67"]},
                  {"id":"fm-pin","reason":"keep the merged tone on FM","op":"pinLane",
                   "noteIds":["n-0-500000-63"],"laneType":"FM"}
                ]
                """);
        var compact = json(cli("reconstruct", "edit", work.toString(), "add", "--dry-run", "--file", batch.toString()));
        assertEquals(5, compact.getAsJsonArray("results").size());
        assertFalse(compact.has("notes"));
        assertFalse(compact.has("outcomes"));
        var preview = json(cli("reconstruct", "edit", work.toString(), "add", "--dry-run", "--verbose", "--file", batch.toString()));
        assertEquals("transcribe", preview.get("from").getAsString());
        var previewRows = preview.getAsJsonArray("outcomes").asList().stream().map(row -> row.getAsJsonObject()).toList();
        for (String id : List.of("split-tone", "merge-tone", "fm-chord")) {
            assertTrue(previewRows.stream().anyMatch(row -> row.get("id").getAsString().equals(id)
                    && row.get("status").getAsString().equals("applied")), id);
        }
        assertEquals(2, previewRows.stream().filter(row -> row.get("id").getAsString().equals("noise-arp")
                && row.has("lane") && row.get("lane").getAsString().equals("PSG3")).count());
        var added = json(cli("reconstruct", "edit", work.toString(), "add", "--file", batch.toString()));
        assertTrue(added.get("journalSaved").getAsBoolean());
        assertEquals("applied", added.getAsJsonArray("results").get(0).getAsJsonObject().get("status").getAsString());
        var invalid = dir.resolve("invalid-batch.json");
        Files.writeString(invalid, """
                [{"id":"bad","reason":"missing selector","op":"deleteNote"}, {"id":"malformed"}]
                """);
        var rejected = json(cli("reconstruct", "edit", work.toString(), "add", "--file", invalid.toString()));
        assertFalse(rejected.get("journalSaved").getAsBoolean());
        assertEquals(2, rejected.getAsJsonArray("results").size());
        assertTrue(rejected.getAsJsonArray("results").asList().stream().allMatch(row ->
                row.getAsJsonObject().get("status").getAsString().equals("rejected")));
        cli("reconstruct", "run", "--from", "transcribe", work.toString());

        var editedTrace = ReconstructionTrace.read(work);
        for (String id : List.of("n-0-0-60", "edit-split-tone-left", "n-0-0-67")) {
            assertTrue(editedTrace.get(id).voiced().stream()
                    .anyMatch(gate -> gate.lane().equals("PSG3") && gate.evidence().contains("arpeggio")), id);
        }
        assertTrue(editedTrace.get("n-0-500000-63").voiced().getFirst().evidence().contains("chord instrument"));
        var contradictoryPin = """
                {"id":"conflicting-type","reason":"different chip type","op":"pinLane",
                 "noteIds":["n-0-0-60"],"laneType":"FM"}
                """;
        add(work, contradictoryPin, true);
        assertTrue(ReconstructionEdits.Journal.read(work).all().stream().anyMatch(row -> row.id().equals("cycle")
                && row.status() == ReconstructionEdits.Status.conflict && row.detail().contains("different lane types")));
        var fallback = ReconstructionTrace.read(work);
        assertEquals("PSG3", fallback.get("edit-split-tone-left").allocation().lane());
        assertNull(fallback.get("n-0-0-67").allocation().lane());
        assertTrue(fallback.get("n-0-0-67").allocation().reason().contains("NOISE lanes busy"));
    }

    private static Note note(String id, double start, double end, int pitch) {
        return new Note(id, 0, start, end, pitch, 90, "midi", 1);
    }

    private static ReconstructionStages.Inputs inputs() {
        return new ReconstructionStages.Inputs("theme.mid", "mix.wav", "catalogue.json", "Test", 30,
                false, false, false, false, Map.of(), null, 7.5, null, null);
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    private static ReconstructionEdits.Intent intent(String value) {
        return ReconstructionEdits.parse(json(value), "edit");
    }

    private static JsonObject add(Path work, String value, boolean rerun) throws Exception {
        return ReconstructionEditCommand.run(work, "add", json(value), null, rerun, (a, b, c) -> {});
    }

    private static String cli(String... args) throws Exception {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        try (var out = new PrintStream(stdout); var err = new PrintStream(stderr)) {
            assertEquals(0, Main.run(args, out, err), stderr.toString());
        }
        return stdout.toString();
    }

    private static void writeMidi(Path midi, boolean includeRemoved) throws Exception {
        var source = new Sequence(Sequence.PPQ, 480);
        var track = source.createTrack();
        for (int[] event : new int[][]{{60, 0, 420}, {64, 480, 720}, {67, 480, 770}, {62, 1200, 1450}}) {
            if (event[0] == 62 && !includeRemoved) continue;
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, event[0], 90), event[1]));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, event[0], 0), event[2]));
        }
        MidiSystem.write(source, 0, midi.toFile());
    }

    private static void writeAudio(Path index, Path wav) throws Exception {
        writeAudio(index, wav, false);
    }

    private static void writeAudio(Path index, Path wav, boolean psg) throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var patch = DemoProject.create().voices().getFirst();
        var bytes = new byte[patch.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = patch.bytes().get(i).byteValue();
        String asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, patch.name(), bytes);
        String variant = builder.variant(asset, patch.name(), InstrumentCatalogue.Recipe.plain(), null);
        if (psg) {
            String envelope = builder.asset(InstrumentCatalogue.Kind.PSG_VOLUME, "Chord envelope",
                    new byte[]{0, (byte) 0x80});
            builder.variant(envelope, "Chord envelope", InstrumentCatalogue.Recipe.plain(), null);
        }
        var catalogue = builder.build();
        catalogue.save(index);
        WaveFile.render(ProjectCompiler.compileWithAssets(CataloguePlayback.matchProbe(catalogue, variant, 68, 24)), wav, 120);
    }
}
