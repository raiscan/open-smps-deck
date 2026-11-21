package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionRolePlanTest {
    @TempDir Path work;

    @Test void describesTheScoreAndCollapsesRepeatsWithPartDifferences() throws Exception {
        fixture();
        run("roles", work.toString());
        String text = run("describe", work.toString());
        assertEquals(text, run("describe", work.toString()));
        assertTrue(text.contains("120.000 BPM, 2.000 s/bar"), text);
        assertTrue(text.contains("P0 Keys | program 0 | drums false | 8 notes"), text);
        assertTrue(text.contains("[C4+G4] -2 .13"), text);
        assertTrue(text.contains("2.00 notes/bar poly 2 figure 1b"), text);
        assertTrue(text.contains("S2 bars 3-4 same as S1\nP1"), text);
        assertTrue(text.contains("3 S .15"), text);
        assertFalse(text.contains("D2"), text);

        var fullView = JsonParser.parseString(run("describe", work.toString(), "--json")).getAsJsonObject();
        var sections = fullView.getAsJsonArray("sections");
        assertEquals("[0,2,2,0,2,0]", sections.get(0).getAsJsonObject().get("attacks").toString());
        assertEquals("[2,0]", sections.get(1).getAsJsonObject().get("attacks").toString());

        Path output = work.resolve("view.json");
        assertEquals("", run("describe", work.toString(), "--json", "--section", "S2", "--parts", "0",
                "--bars", "4-4", "--out", output.toString()));
        var view = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertEquals(1, view.getAsJsonArray("parts").size());
        var section = view.getAsJsonArray("sections").get(0).getAsJsonObject();
        assertEquals("S2", section.get("id").getAsString());
        assertFalse(section.has("sameAs"));
        assertEquals(4, section.get("firstBar").getAsInt());
        var part = section.getAsJsonArray("parts").get(0).getAsJsonObject();
        assertEquals(1, part.getAsJsonArray("bars").size());
        assertEquals("[0,1]", section.getAsJsonArray("attacks").toString());
        assertEquals(2, part.getAsJsonObject("roles").entrySet().stream().mapToInt(e -> e.getValue().getAsInt()).sum());
    }

    @Test void usesTheWinningRuleAndTracesOnlyItsCandidates() throws Exception {
        fixture();
        Files.writeString(work.resolve("roleplan.json"), """
                {"version":1,"rules":[
                  {"sections":["*"],"part":0,"role":"harmony"},
                  {"sections":["S1"],"part":0,"role":"melody","voice":"bottom"},
                  {"sections":["S1"],"bars":"2-2","part":0,"role":"drop","pitchMin":67}
                ]}
                """);
        run("roles", work.toString());
        var roles = ReconstructionArtifacts.read(work.resolve("roles.json"), ReconstructionRoles.Result.class);
        assertDecision(roles, "lo0", "melody", "roleplan#1");
        assertDecision(roles, "hi0", "harmony", "roleplan#1");
        assertDecision(roles, "lo1", "melody", "roleplan#1");
        assertDecision(roles, "hi1", "drop", "roleplan#2");
        assertDecision(roles, "lo2", "harmony", "roleplan#0");
        assertDecision(roles, "drum2", "drums", null);
        assertTrue(roles.notes().stream().noneMatch(d -> d.note().id().equals("other0")
                && d.role() == ReconstructionRoles.Role.melody));
    }

    @Test void startsAndEndsAPlanInsideASection() throws Exception {
        fixture();
        Files.writeString(work.resolve("roleplan.json"), """
                {"version":1,"rules":[
                  {"sections":["S1"],"part":0,"role":"bass","voice":"bottom","startSeconds":2,"endSeconds":2.25},
                  {"sections":["S1"],"part":2,"role":"counter","voice":"top","startSeconds":2}
                ]}
                """);
        run("roles", work.toString());
        var roles = ReconstructionArtifacts.read(work.resolve("roles.json"), ReconstructionRoles.Result.class);
        assertDecision(roles, "other0", "melody", null);
        assertDecision(roles, "lo1", "bass", "roleplan#0");
        assertDecision(roles, "hi1", "harmony", "roleplan#0");
        assertDecision(roles, "other1", "counter", "roleplan#1");
        assertDecision(roles, "other2", "melody", null);
    }

    @Test void protectsPlannedLeadRestsAndLetsBackingPlayOutsideTheScope() throws Exception {
        fixture();
        Files.writeString(work.resolve("roleplan.json"), """
                {"version":1,"rules":[
                  {"sections":["S1"],"bars":"2-2","part":0,"role":"melody","pitchMin":100}
                ]}
                """);
        run("roles", work.toString());
        var roles = ReconstructionArtifacts.read(work.resolve("roles.json"), ReconstructionRoles.Result.class);
        assertDecision(roles, "other0", "melody", null);
        assertDecision(roles, "other1", "harmony", "roleplan#0");
        assertDecision(roles, "other2", "melody", null);
    }

    @Test void textureMatchesTheScoreGridAndFiltersBeforeTracing() throws Exception {
        fixture(true);
        String text = run("describe", work.toString(), "--section", "S1");
        assertTrue(text.contains("[C4+G4] G4"), text);
        assertTrue(text.contains("3.00 notes/bar poly 3 figure 1b 2/2"), text);
        assertTrue(text.contains("1.00 notes/bar poly 1 figure 1b 2/0"), text);

        Files.writeString(work.resolve("roleplan.json"), """
                {"version":1,"rules":[
                  {"sections":["S1"],"part":0,"role":"harmony"},
                  {"sections":["S1"],"part":0,"role":"melody","voice":"top","texture":"single","pitchMin":67},
                  {"sections":["S1"],"part":0,"role":"drop","texture":"chord","pitchMax":60},
                  {"sections":["S1"],"part":1,"role":"drop","texture":"chord"}
                ]}
                """);
        run("roles", work.toString());
        var roles = ReconstructionArtifacts.read(work.resolve("roles.json"), ReconstructionRoles.Result.class);
        assertDecision(roles, "single0", "melody", "roleplan#1");
        assertDecision(roles, "single1", "melody", "roleplan#1");
        assertDecision(roles, "hi0", "harmony", "roleplan#0");
        assertDecision(roles, "lo0", "drop", "roleplan#2");
        assertDecision(roles, "drum0", "drums", null);
    }

    @Test void plannedLineClaimsTheTailOfAChordTone() throws Exception {
        var notes = List.of(new Note("low", 0, .1, .7, 60, 85, "midi", 1),
                new Note("held", 0, .1, .99, 68, 85, "midi", 1),
                new Note("before", 0, .48, .60, 70, 90, "midi", 1),
                new Note("after", 0, 1.0, 1.30, 72, 90, "midi", 1));
        var bars = List.of(new ReconstructionStructure.Bar("b0", 0, 2, true));
        var sections = List.of(new ReconstructionStructure.Section("S1", List.of("b0")));
        var parts = List.of(new MidiScore.Part(0, "Keys", 0, false));
        var inputs = new ReconstructionStages.Inputs("test.mid", "test.wav", "catalogue.json", "Synthetic", 30,
                false, false, false, false, Map.of(), null, 0, null, false);
        var score = new ReconstructionStages.Score(1, 2_000_000, 120, 60, inputs, bars,
                new MidiScore(parts, List.of(), List.of()), Map.of(0, 4), notes, List.of(), List.of(), null, null);
        ReconstructionArtifacts.write(work.resolve("score.json"), score);
        ReconstructionArtifacts.write(work.resolve("structure.json"),
                new ReconstructionStructure.Result(1, bars, sections, List.of(), List.of(), notes));
        Path plan = work.resolve("roleplan.json");
        String rules = """
                {"version":1,"rules":[
                  {"sections":["*"],"part":0,"role":"harmony"},
                  {"sections":["*"],"part":0,"role":"melody","texture":"single"}
                ]}
                """;
        Files.writeString(plan, rules);
        run("roles", work.toString());
        Path rolesFile = work.resolve("roles.json");
        var roles = ReconstructionArtifacts.read(rolesFile, ReconstructionRoles.Result.class);
        assertDecision(roles, "held-lead", "melody", "roleplan#1 lead-continuation");
        assertEquals(.6, roles.notes().stream().filter(d -> d.note().id().equals("held"))
                .findFirst().orElseThrow().note().end(), 1e-9);
        run("allocate", work.toString());
        var allocation = ReconstructionArtifacts.read(work.resolve("allocation.json"), ReconstructionAllocation.Result.class);
        assertTrue(allocation.notes().stream().anyMatch(entry -> entry.note().id().equals("held-lead")
                && "FM1".equals(entry.lane()) && entry.startTick() == 36));
        Files.writeString(plan, rules.replace("\"texture\":\"single\"", "\"texture\":\"single\",\"pitchMin\":69"));
        run("roles", work.toString());
        assertTrue(ReconstructionArtifacts.read(rolesFile, ReconstructionRoles.Result.class).notes().stream()
                .noneMatch(decision -> decision.note().id().equals("held-lead")));
    }

    @Test void omittedTextureProducesTheSameRolesAsExplicitAny() throws Exception {
        fixture(true);
        var plan = work.resolve("roleplan.json");
        var roles = work.resolve("roles.json");
        String legacy = """
                {"version":1,"rules":[
                  {"sections":["S1"],"part":0,"role":"melody","voice":"bottom"},
                  {"bars":"2-2","part":0,"role":"drop","pitchMin":67}
                ]}
                """;
        Files.writeString(plan, legacy);
        run("roles", work.toString());
        byte[] original = Files.readAllBytes(roles);
        Files.writeString(plan, legacy.replace("\"part\":0", "\"part\":0,\"texture\":\"any\""));
        run("roles", work.toString());
        assertArrayEquals(original, Files.readAllBytes(roles));
    }

    @Test void rejectsInvalidTextureWithItsRuleIndex() throws Exception {
        fixture();
        for (String invalid : List.of("\"stacked\"", "\"Chord\"", "2", "true", "[]")) {
            Files.writeString(work.resolve("roleplan.json"), """
                    {"version":1,"rules":[
                      {"sections":["S1"],"part":0,"role":"harmony"},
                      {"sections":["S1"],"part":0,"role":"melody","texture":%s}
                    ]}
                    """.formatted(invalid));
            String result = call("roles", work.toString());
            assertTrue(result.contains("roleplan.json.rules[1].texture"), result);
            if (invalid.startsWith("\"")) assertTrue(result.contains("expected any, single or chord"), result);
        }
    }

    @Test void templateIsValidatedAndAnInvalidRuleLeavesRolesIntact() throws Exception {
        fixture();
        run("roles", work.toString());
        Path roles = work.resolve("roles.json");
        byte[] original = Files.readAllBytes(roles);
        var template = work.resolve("roleplan.json");
        var printed = run("plan-template", work.toString());
        assertFalse(Files.exists(template));
        run("plan-template", work.toString(), "--out", template.toString());
        assertEquals(printed.strip(), Files.readString(template).strip());
        assertEquals(6, ReconstructionArtifacts.read(template, ReconstructionRolePlan.class).rules().size());
        assertTrue(call("plan-template", work.toString(), "--out", template.toString()).contains("roleplan.json"));
        for (String bad : List.of(
                "\"sections\":[\"missing\"],\"part\":0",
                "\"sections\":[\"*\"],\"part\":4",
                "\"bars\":\"2-5\",\"part\":0",
                "\"bars\":\"2-2\",\"part\":0,\"startSeconds\":3,\"endSeconds\":2")) {
            Files.writeString(template, "{\"version\":1,\"rules\":[{" + bad + ",\"role\":\"melody\"}]}");
            String error = call("roles", work.toString());
            assertTrue(error.startsWith("Error: ") && error.contains("roleplan.json.rules[0]"), error);
            assertEquals(1, error.lines().count(), error);
            assertArrayEquals(original, Files.readAllBytes(roles));
        }
    }

    @Test void describesAndAppliesRolePlanBoundsInProjectSeconds() throws Exception {
        fixture();
        var scoreFile = work.resolve("score.json");
        var score = ReconstructionStages.readScore(scoreFile);
        var input = score.inputs();
        var inputs = new ReconstructionStages.Inputs(input.midi(), input.wav(), input.catalogue(), input.name(),
                input.ticksPerBeat(), false, false, false, false, Map.of(), null, 7.5,
                new ReconstructionTempo.Options(false, 120.0, null, "60"), false);
        var grid = ReconstructionTempo.override(inputs.grid(), 4);
        var beats = ReconstructionBeatMap.constant(grid.selected(), 8);
        ReconstructionArtifacts.write(scoreFile, score.withInputs(inputs, score.bars(), score.notes(), grid, null, beats));

        run("structure", work.toString());
        var view = JsonParser.parseString(run("describe", work.toString(), "--json")).getAsJsonObject();
        assertEquals("project", view.get("timeBase").getAsString());
        assertEquals(60, view.get("bpm").getAsDouble());
        assertEquals(4, view.get("barSeconds").getAsDouble());
        var sections = view.getAsJsonArray("sections");
        assertEquals(16, sections.get(sections.size() - 1).getAsJsonObject().get("endSeconds").getAsDouble());

        var plan = work.resolve("roleplan.json");
        run("plan-template", work.toString(), "--out", plan.toString());
        assertEquals(ReconstructionTimeMap.TimeBase.source,
                ReconstructionArtifacts.read(plan, ReconstructionRolePlan.class).timeBase());
        String override = """
                {"version":1,"timeBase":"project","rules":[
                  {"sections":["*"],"part":0,"role":"drop","startSeconds":12,"endSeconds":12.25}
                ]}
                """;
        Files.writeString(plan, override);
        run("roles", work.toString());
        var roles = ReconstructionArtifacts.read(work.resolve("roles.json"), ReconstructionRoles.Result.class);
        assertEquals(ReconstructionTimeMap.TimeBase.project, roles.timeBase());
        assertDecision(roles, "lo3", "drop", "roleplan#0");
        assertDecision(roles, "hi3", "drop", "roleplan#0");
        assertFalse(roles.notes().stream().anyMatch(decision -> decision.note().start() < 12
                && "roleplan#0".equals(decision.reason())));
        run("allocate", work.toString());
        var allocation = ReconstructionArtifacts.read(work.resolve("allocation.json"), ReconstructionAllocation.Result.class);
        assertEquals(ReconstructionTimeMap.TimeBase.project, allocation.timeBase());

        var expected = Files.readString(work.resolve("roles.json"));
        Files.writeString(plan, """
                {"version":1,"rules":[
                  {"part":0,"role":"drop","startSeconds":6,"endSeconds":6.125}
                ]}
                """);
        assertTrue(run("roles", work.toString()).contains("ticks [720,735)"));
        assertEquals(expected, Files.readString(work.resolve("roles.json")));

        Files.writeString(plan, override.replace("\"project\"", "\"source\""));
        assertTrue(call("roles", work.toString()).contains("score duration"));
    }

    private void fixture() throws Exception {
        fixture(false);
    }

    private void fixture(boolean mixedTexture) throws Exception {
        List<MidiScore.Part> parts = List.of(new MidiScore.Part(0, "Keys", 0, false),
                new MidiScore.Part(1, "Drums", 0, true), new MidiScore.Part(2, "Upper", 80, false));
        var notes = new ArrayList<Note>();
        var bars = new ArrayList<ReconstructionStructure.Bar>();
        for (int i = 0; i < 4; i++) {
            double start = i * 2;
            bars.add(new ReconstructionStructure.Bar("b" + i, start, start + 2, true));
            notes.add(new Note("lo" + i, 0, start, start + .375, 60, 90, "midi", 1));
            notes.add(new Note("hi" + i, 0, start + (mixedTexture ? .04 : 0), start + .375, 67, 90, "midi", 1));
            notes.add(new Note("drum" + i, 1, start, start + .08, i < 2 ? 36 : 38, 90, "midi", 1));
            notes.add(new Note("other" + i, 2, start, start + .375, 77, 90, "midi", 1));
            if (mixedTexture) notes.add(new Note("single" + i, 0, start + .125, start + .375, 67, 90, "midi", 1));
        }
        notes.sort(java.util.Comparator.comparingDouble(Note::start));
        var inputs = new ReconstructionStages.Inputs("test.mid", "test.wav", "catalogue.json", "Synthetic", 30,
                false, false, false, false, Map.of(), null, 7.5, null, false);
        var score = new ReconstructionStages.Score(1, 8_000_000, 480, 60, inputs, bars,
                new MidiScore(parts, List.of(), List.of()), Map.of(0, mixedTexture ? 12 : 8, 1, 4, 2, 4),
                notes, List.of(), List.of(), null, null);
        var sections = List.of(new ReconstructionStructure.Section("S1", List.of("b0", "b1")),
                new ReconstructionStructure.Section("S2", List.of("b2", "b3")));
        var repeats = List.of(new ReconstructionStructure.Repeat("R1", List.of("b0", "b2")),
                new ReconstructionStructure.Repeat("R2", List.of("b1", "b3")));
        ReconstructionArtifacts.write(work.resolve("score.json"), score);
        ReconstructionArtifacts.write(work.resolve("structure.json"),
                new ReconstructionStructure.Result(1, bars, sections, repeats, List.of(), notes));
    }

    private static void assertDecision(ReconstructionRoles.Result roles, String id, String role, String reason) {
        var decision = roles.notes().stream().filter(d -> d.note().id().equals(id)).findFirst().orElseThrow();
        assertEquals(role, decision.role().name());
        assertEquals(reason, decision.reason());
    }

    private static String run(String... command) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var out = new PrintStream(output)) {
            ReconstructionCli.run(args(command), out);
        }
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String call(String... command) {
        var output = new ByteArrayOutputStream();
        int status = Main.run(args(command), new PrintStream(output), new PrintStream(output));
        assertEquals(2, status, output::toString);
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String[] args(String[] command) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of("reconstruct"),
                java.util.Arrays.stream(command)).toArray(String[]::new);
    }
}
