package com.opensmpsdeck.headless;

import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.headless.rom.VerifiedRom;
import com.opensmpsdeck.project.TargetGame;

import com.google.gson.JsonParser;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.SampleTrigger;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.ShortMessage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role.drums;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconstructionDrumsTest {
    @TempDir Path directory;
    @Test void tonalEnvelopeCannotOpenTheNoiseChannelInADrumSolo() throws Exception {
        byte[] raw = {0x40, (byte) 0x80};
        var mask = new S3kRenderer.ChannelMask(0x3f, 0x7);
        assertFalse(noiseIsSilent(List.of(0x40, 0x80), mask), "the fixture must reproduce the leakage");
        assertTrue(noiseIsSilent(S3kReconstruction.psgAttenuation(raw), mask));
    }

    @Test void attenuationLimitPreservesLoopOffsetAndMultiplierOperand() {
        assertEquals(List.of(15, 0x82, 31, 0x84, 64, 15, 0x80),
                S3kReconstruction.psgAttenuation(new byte[] {64, (byte) 0x82, 31, (byte) 0x84, 64, 31, (byte) 0x80}));
    }

    @Test void stagedRunUsesTheFittedRateOrAnEditableStemSample() throws Exception {
        int nativeRate = 12;
        double pitch = 90;
        byte[] pcm = new byte[2_000];
        for (int i = 0; i < pcm.length; i++)
            pcm[i] = (byte) (128 + Math.round(110 * kick(i / DacSample.sampleRateHz(nativeRate), pitch)));
        var builder = new InstrumentCatalogue.Builder();
        String sample = builder.asset(InstrumentCatalogue.Kind.DAC_SAMPLE, "Kick", pcm);
        builder.variant(sample, "Kick", new InstrumentCatalogue.Recipe(nativeRate, null, 127, 60,
                null, null, List.of(), List.of()), null);
        // This mislabeled-for-the-hit candidate has the exact resonance of the
        // reference. Known catalogue class evidence should keep it out of the kick role.
        byte[] distractor = new byte[pcm.length];
        int fastRate = 8;
        for (int i = 0; i < distractor.length; i++)
            distractor[i] = (byte) (128 + Math.round(110 * kick(i / DacSample.sampleRateHz(fastRate), 113)));
        String snare = builder.asset(InstrumentCatalogue.Kind.DAC_SAMPLE, "Snare", distractor);
        builder.variant(snare, "Snare", new InstrumentCatalogue.Recipe(fastRate, null, 127, 60,
                null, null, List.of(), List.of()), null);
        Path index = directory.resolve("catalogue.json");
        builder.build().save(index);

        int[] starts = {9_600, 24_000, 38_400};
        Path stem = directory.resolve("drums.wav");
        WaveFile.write(stem, 72, sink -> {
            short[] frame = new short[1_600];
            for (int f = 0; f < 72; f++) {
                for (int i = 0; i < 800; i++) {
                    double value = 0;
                    for (int attack : starts) {
                        int elapsed = f * 800 + i - attack;
                        if (elapsed >= 0 && elapsed < 9_000) value += kick(elapsed / 48_000.0, 113);
                    }
                    frame[2 * i] = frame[2 * i + 1] = (short) Math.round(12_000 * value);
                }
                sink.accept(frame);
            }
        });
        Path midi = directory.resolve("drums.mid");
        var sequence = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 480);
        var track = sequence.createTrack();
        for (int start : starts) {
            int tick = start / 50;
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), tick));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 9, 36, 0), tick + 12));
        }
        MidiSystem.write(sequence, 0, midi.toFile());

        var catalogue = reconstruct(midi, stem, index, "catalogue", true);
        assertTrue(catalogue.id().startsWith(sample));
        assertTrue(catalogue.rate() < nativeRate, "the higher reference pitch should choose faster DAC playback");
        var slice = reconstruct(midi, stem, index, "sliced", true);
        assertTrue(slice.id().startsWith("stem-"));
        assertEquals("source-audio", slice.origin().type());
        assertFalse(reconstruct(midi, stem, index, "auto", false).id().startsWith("stem-"));
    }

    @Test void tonalDacIsExcludedFromHatFitButStillAvailableAsAnOverride() throws Exception {
        int rate = 10;
        byte[] pcm = new byte[2_500];
        for (int i = 0; i < pcm.length; i++)
            pcm[i] = (byte) (128 + Math.round(100 * Math.sin(2 * Math.PI * 300 * i / DacSample.sampleRateHz(rate))));
        var builder = new InstrumentCatalogue.Builder();
        String asset = builder.asset(InstrumentCatalogue.Kind.DAC_SAMPLE, "One shot", pcm);
        String variant = builder.variant(asset, "One shot", new InstrumentCatalogue.Recipe(rate, null, 127, 60,
                null, null, List.of(), List.of()), null);
        byte[] noise = new byte[2_500];
        new java.util.Random(5).nextBytes(noise);
        String curated = builder.asset(InstrumentCatalogue.Kind.DAC_SAMPLE, "Bright hit", noise);
        builder.variant(curated, "Bright hit", new InstrumentCatalogue.Recipe(rate, null, 127, 60,
                null, null, List.of(), List.of()), null);
        var catalogue = builder.build();
        assertEquals(DacAcoustics.SampleClass.NOISE_LIKE, new DacAcoustics(catalogue).asset(curated).sampleClass());
        Path index = directory.resolve("catalogue.json");
        catalogue.save(index);
        var indexJson = ReconstructionArtifacts.readTree(index).getAsJsonObject();
        indexJson.getAsJsonArray("assets").asList().stream().map(item -> item.getAsJsonObject())
                .filter(item -> item.get("id").getAsString().equals(curated)).findFirst().orElseThrow()
                .addProperty("excludeFromDrumFit", true);
        Files.writeString(index, indexJson.toString());
        Path wav = directory.resolve("mix.wav");
        WaveFile.write(wav, 60, sink -> {
            var frame = new short[1600];
            for (int tick = 0; tick < 60; tick++) {
                for (int i = 0; i < 800; i++) {
                    double t = (tick * 800 + i) / 48_000.0 - .2;
                    frame[2 * i] = frame[2 * i + 1] =
                            (short) (t >= 0 && t < .18 ? 12_000 * Math.sin(2 * Math.PI * 300 * t) : 0);
                }
                sink.accept(frame);
            }
        });
        Path midi = directory.resolve("drums.mid");
        var sequence = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 480);
        midiNote(sequence.createTrack(), 9, 42, 192);
        MidiSystem.write(sequence, 0, midi.toFile());

        Path work = directory.resolve("work");
        command("reconstruct", "run", midi.toString(), wav.toString(), index.toString(), work.toString(),
                "--steady-tempo", "off");
        Path arrangement = work.resolve("arrangement.smps.json");
        var project = ProjectFiles.load(arrangement).project();
        assertTrue(project.samples().isEmpty());
        assertTrue(project.clips().stream().anyMatch(clip -> clip.lane().equals("PSG3")));
        ProjectCompiler.compile(project);

        Path voicing = work.resolve("voicing.json");
        var json = ReconstructionArtifacts.readTree(voicing).getAsJsonObject();
        var settings = json.getAsJsonObject("lanes").getAsJsonObject("DAC")
                .getAsJsonArray("instruments").get(0).getAsJsonObject();
        var evidence = settings.getAsJsonObject("drumSelection").getAsJsonObject("evidence");
        assertEquals("HAT", evidence.get("drumClass").getAsString());
        assertTrue(evidence.getAsJsonArray("exclusions").asList().stream()
                .anyMatch(item -> item.getAsJsonObject().get("reason").getAsString().equals("pitched-tonal")));
        assertTrue(evidence.getAsJsonArray("exclusions").asList().stream()
                .anyMatch(item -> item.getAsJsonObject().get("reason").getAsString().equals("excludeFromDrumFit")));
        settings.remove("drumSelection");
        settings.addProperty("variantId", variant);
        Files.writeString(voicing, json.toString());
        command("reconstruct", "assemble", work.toString());
        var overridden = ProjectFiles.load(arrangement).project();
        assertEquals(rate, overridden.samples().getFirst().rate());
        assertTrue(overridden.samples().getFirst().id().startsWith(asset));

        // Auto may consider a stem slice. A tonal slice must not take the
        // place of the tonal catalogue sample that was just rejected.
        Path auto = directory.resolve("auto-fit");
        command("reconstruct", "run", midi.toString(), wav.toString(), index.toString(), auto.toString(),
                "--steady-tempo", "off", "--stem", "0=" + wav, "--drum-source", "auto");
        var autoProject = ProjectFiles.load(auto.resolve("arrangement.smps.json")).project();
        assertTrue(autoProject.samples().isEmpty());
        assertTrue(autoProject.clips().stream().anyMatch(clip -> clip.lane().equals("PSG3")));
    }

    @Test void dacCollisionUsesPsgNoiseAndProtectsTheKickTail() {
        var parts = List.of(new MidiScore.Part(0, "Drums", 0, true));
        var roles = new ReconstructionRoles.Result(1, 0, List.of(
                attack("kick", 0, 36), attack("snare", 0, 38), attack("hat", 0, 42),
                attack("tail-hat", .05, 42), attack("free-hat", .2, 42)), null);

        var allocated = ReconstructionAllocation.allocate(roles, parts, 60, false);
        var notes = allocated.notes().stream().collect(java.util.stream.Collectors.toMap(n -> n.note().id(), n -> n));
        assertEquals("DAC", notes.get("kick").lane());
        assertEquals("simultaneous-dac-hit", notes.get("snare").reason());
        assertEquals("PSG3", notes.get("hat").lane());
        assertEquals(ReconstructionCymbals.COLLISION, notes.get("tail-hat").reason());
        assertEquals("DAC", notes.get("free-hat").lane());

        var quiet = ReconstructionAllocation.allocate(roles, parts, 60, false, true, null,
                Map.of(new ReconstructionVoices.Key(0, 42), .001));
        assertEquals(ReconstructionCymbals.QUIET, quiet.notes().getLast().reason());
        var kit = new java.util.ArrayList<>(roles.notes());
        kit.add(attack("unmeasured-crash", 0, 49));
        var louder = ReconstructionAllocation.allocate(new ReconstructionRoles.Result(1, 0, kit, null),
                parts, 60, false, true, null, Map.of(new ReconstructionVoices.Key(0, 42), .01));
        assertEquals("simultaneous-dac-hit", louder.notes().get(2).reason());
        assertTrue(louder.notes().stream().noneMatch(n -> "PSG3".equals(n.lane())));
        var fmOnly = ReconstructionAllocation.allocate(roles, parts, 60, true, true, null,
                Map.of(new ReconstructionVoices.Key(0, 42), .001));
        assertEquals("DAC", fmOnly.notes().getLast().lane());
        assertTrue(fmOnly.notes().stream().noneMatch(n -> "PSG3".equals(n.lane())));
    }

    @Test void aSustainedSpillKeepsTheLastToneLane() {
        var parts = List.of(new MidiScore.Part(0, "Drums", 0, true),
                new MidiScore.Part(1, "Backing", 0, false));
        var attacks = new java.util.ArrayList<ReconstructionRoles.Decision>();
        for (int pitch = 55; pitch < 60; pitch++) {
            var held = new ReconstructionStages.Note("tone-" + pitch, 1, 0, .3, pitch, 100, "midi", 1);
            attacks.add(new ReconstructionRoles.Decision(held, ReconstructionRoles.Role.harmony, null, null));
        }
        attacks.add(attack("kick", .1, 36));
        attacks.add(attack("hat", .1, 42));
        var sustained = new ReconstructionStages.Note("sustained", 1, .1, .3, 60, 100, "midi", 1);
        attacks.add(new ReconstructionRoles.Decision(sustained, ReconstructionRoles.Role.harmony, null, null));
        var roles = new ReconstructionRoles.Result(1, 0, attacks, null);

        var allocated = ReconstructionAllocation.allocate(roles, parts, 60, false);
        var notes = allocated.notes().stream().collect(java.util.stream.Collectors.toMap(n -> n.note().id(), n -> n));
        assertEquals("PSG3", notes.get("sustained").lane());
        assertEquals("psg-noise-lane-busy", notes.get("hat").reason());
        assertEquals("sustained", notes.get("hat").competitorNoteId());
    }

    private static ReconstructionRoles.Decision attack(String id, double start, int pitch) {
        var note = new ReconstructionStages.Note(id, 0, start, start + .2, pitch, 100, "midi", 1);
        return new ReconstructionRoles.Decision(note, drums, null, null);
    }

    @Test void drumGainsScaleDacPcmAndPsgNoiseIndependently() throws Exception {
        byte[] pcm = {0, 64, (byte) 128, (byte) 192, (byte) 254};
        var builder = new InstrumentCatalogue.Builder();
        String sample = builder.asset(InstrumentCatalogue.Kind.DAC_SAMPLE, "Snare", pcm);
        String variant = builder.variant(sample, "Snare", new InstrumentCatalogue.Recipe(12, null, 127, 60,
                null, null, List.of(), List.of()), null);
        var catalogue = builder.build();
        var snare = new ReconstructionStages.Note("snare", 0, 0, .15, 38, 100, "midi", 1);
        var hat = new ReconstructionStages.Note("hat", 0, 0, .15, 42, 100, "midi", 1);
        var allocation = new ReconstructionAllocation.Result(1, List.of(
                new ReconstructionAllocation.Entry(snare, drums, "DAC", 0, 9, ReconstructionAllocation.Outcome.kept, null, null),
                new ReconstructionAllocation.Entry(hat, drums, "PSG3", 0, 9, ReconstructionAllocation.Outcome.kept, null, null)));
        var score = new S3kReconstruction.Score(List.of(new MidiScore.Part(0, "Drums", 0, true)), List.of(), List.of());
        var choice = new ReconstructionVoices.Choice(sample, variant, "Snare", 12, "matched", List.of(), List.of());
        var matches = Map.of("DAC", Map.of(new ReconstructionVoices.Key(0, 38), choice));
        var unedited = S3kReconstruction.arrangeAllocated("Drums", 30, 60, score, allocation, null,
                catalogue, matches, Map.of()).project();
        var voiced = S3kReconstruction.arrangeAllocated("Drums", 30, 60, score, allocation, null,
                catalogue, matches, Map.of(), Map.of("DAC", drumLane(38, .5), "PSG3", drumLane(42, .1)), false).project();
        assertArrayEquals(pcm, unedited.samples().getFirst().pcm());
        assertArrayEquals(new byte[]{64, 96, (byte) 128, (byte) 160, (byte) 191}, voiced.samples().getFirst().pcm());
        var uneditedPsg = unedited.sequences().stream().filter(seq -> seq.type().equals("PSG")).findFirst().orElseThrow();
        var voicedPsg = voiced.sequences().stream().filter(seq -> seq.type().equals("PSG")).findFirst().orElseThrow();
        assertEquals(10, (uneditedPsg.volume() >> 3) - (voicedPsg.volume() >> 3));
        assertEquals(uneditedPsg.notes(), voicedPsg.notes());
    }

    @Test void pinsHatsToNoiseAndScopesDacGainWithoutMovingTonalNotes() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        byte[] sample = new byte[2000];
        for (int i = 0; i < sample.length; i++)
            sample[i] = (byte) (128 + Math.round(110 * kick(i / DacSample.sampleRateHz(12), 90)));
        sample[0] = 0;
        var asset = builder.asset(InstrumentCatalogue.Kind.DAC_SAMPLE, "Kick", sample);
        builder.variant(asset, "Kick", new InstrumentCatalogue.Recipe(12, null, 127, 60,
                null, null, List.of(), List.of()), null);
        var env = builder.asset(InstrumentCatalogue.Kind.PSG_VOLUME, "Test envelope", new byte[]{0, (byte) 0x80});
        builder.variant(env, "Test envelope", InstrumentCatalogue.Recipe.plain(), null);
        var voice = DemoProject.create().voices().getFirst();
        var patch = new byte[25];
        for (int i = 0; i < patch.length; i++) patch[i] = voice.bytes().get(i).byteValue();
        var fm = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Tone", patch);
        builder.variant(fm, "Tone", InstrumentCatalogue.Recipe.plain(), null);
        Path index = directory.resolve("catalogue.json");
        builder.build().save(index);

        Path wav = directory.resolve("mix.wav");
        WaveFile.write(wav, 72, sink -> {
            var frame = new short[1600];
            for (int tick = 0; tick < 72; tick++) {
                for (int i = 0; i < 800; i++) {
                    double time = (tick * 800 + i) / 48_000.0;
                    double level = 0;
                    for (double onset : new double[]{.2, .5, .8}) {
                        if (time >= onset && time < onset + .18) level += kick(time - onset, 90);
                    }
                    frame[2 * i] = frame[2 * i + 1] = (short) Math.round(12_000 * level);
                }
                sink.accept(frame);
            }
        });
        Path midi = directory.resolve("notes.mid");
        var sequence = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 480);
        var track = sequence.createTrack();
        midiNote(track, 9, 36, 192);
        midiNote(track, 9, 42, 192);
        midiNote(track, 9, 36, 480);
        midiNote(track, 9, 36, 768);
        midiNote(track, 0, 69, 192);
        MidiSystem.write(sequence, 0, midi.toFile());

        Path work = directory.resolve("work");
        command("reconstruct", "run", midi.toString(), wav.toString(), index.toString(), work.toString(),
                "--steady-tempo", "off", "--drum-source", "catalogue");
        var trace = ReconstructionTrace.read(work).values();
        var hat = trace.stream().filter(row -> row.pitch() == 42).findFirst().orElseThrow();
        var tone = trace.stream().filter(row -> row.pitch() == 69).findFirst().orElseThrow();
        var kick = trace.stream().filter(row -> row.pitch() == 36).findFirst().orElseThrow();
        assertEquals("PSG3", hat.allocation().lane());
        var initialVoice = ReconstructionArtifacts.readTree(work.resolve("voicing.json")).getAsJsonObject();
        var percussion = initialVoice.getAsJsonObject("lanes").getAsJsonObject("PSG3").getAsJsonObject("psgPercussion");
        assertEquals(231, percussion.get("noiseByte").getAsInt());
        assertEquals(8, percussion.get("hatAttackPeriod").getAsInt());
        assertEquals(16, percussion.get("hatDecayPeriod").getAsInt());
        assertEquals(1, percussion.get("hatSwitchTick").getAsInt());
        var initialProject = ProjectFiles.load(work.resolve("arrangement.smps.json")).project();
        var automatic = initialProject.sequences().stream().filter(source -> source.id().startsWith("PSG3"))
                .findFirst().orElseThrow();
        assertEquals(ReconstructionCymbals.envelope(42).id(), automatic.psgEnvelopeId());
        assertEquals(231, automatic.commands().getFirst().arguments().getFirst());
        assertEquals(96, automatic.volume());
        assertEquals(List.of(1, 2), automatic.notes().stream().map(NoteEvent::duration).toList());
        int attack = automatic.notes().getFirst().tick();
        assertEquals(List.of(NativeCommand.DETUNE, NativeCommand.HOLD),
                automatic.commands().stream().filter(event -> event.tick() == attack + 1)
                        .map(event -> event.command()).toList());
        assertEquals(List.of(List.of(248), List.of(0)), automatic.commands().stream()
                .filter(event -> event.command() == NativeCommand.DETUNE).map(event -> event.arguments()).toList());

        Path voiceFile = work.resolve("voicing.json");
        percussion.addProperty("hatAttackPeriod", 10);
        percussion.addProperty("hatDecayPeriod", 20);
        percussion.addProperty("hatSwitchTick", 2);
        initialVoice.getAsJsonObject("lanes").getAsJsonObject("PSG3")
                .add("drumGains", JsonParser.parseString("{\"42\":0}"));
        ReconstructionArtifacts.write(voiceFile, initialVoice);
        command("reconstruct", "run", "--from", "roles", work.toString(), "--keep-voicing");
        var custom = ProjectFiles.load(work.resolve("arrangement.smps.json")).project().sequences().stream()
                .filter(source -> source.id().startsWith("PSG3")).findFirst().orElseThrow();
        assertEquals(0, custom.volume());
        assertEquals(List.of(2, 1), custom.notes().stream().map(NoteEvent::duration).toList());
        assertEquals(List.of(List.of(250), List.of(4)), custom.commands().stream()
                .filter(event -> event.command() == NativeCommand.DETUNE).map(event -> event.arguments()).toList());
        var batch = directory.resolve("edits.json");
        Files.writeString(batch, """
                [{"id":"keep-tone","reason":"Keep tonal spill away from noise","op":"pinLane",
                  "noteIds":["%s"],"lane":"PSG2"},
                 {"id":"move-hat","reason":"Use a fixed PSG3 noise gate","op":"setRole",
                  "noteIds":["%s"],"role":"drums","lane":"PSG3"}]
                """.formatted(tone.id(), hat.id()));
        command("reconstruct", "edit", work.toString(), "add", "--file", batch.toString());
        command("reconstruct", "run", "--from", "roles", work.toString(), "--keep-voicing");
        var moved = ReconstructionTrace.read(work);
        assertEquals("PSG2", moved.get(tone.id()).allocation().lane());
        assertEquals("PSG3", moved.get(hat.id()).allocation().lane());

        var voicing = ReconstructionArtifacts.readTree(voiceFile).getAsJsonObject();
        var lanes = voicing.getAsJsonObject("lanes");
        assertEquals(231, lanes.getAsJsonObject("PSG3").getAsJsonObject("psgPercussion").get("noiseByte").getAsInt());
        assertEquals(0, lanes.getAsJsonObject("PSG3").getAsJsonObject("psgPercussion").get("envelopeSlot").getAsInt());
        lanes.getAsJsonObject("PSG3").add("drumGains", JsonParser.parseString("{\"42\": 2}"));
        lanes.getAsJsonObject("DAC").add("drumGainOverrides", JsonParser.parseString("""
                [{"hitIds":["%s"],"gain":0.5},{"span":[0.49,0.51],"pitch":36,"gain":0.25}]
                """.formatted(kick.id())));
        ReconstructionArtifacts.write(voiceFile, voicing);
        command("reconstruct", "assemble", work.toString());
        var project = ProjectFiles.load(work.resolve("arrangement.smps.json")).project();
        var noise = project.sequences().stream().filter(source -> source.id().startsWith("PSG3")).findFirst().orElseThrow();
        assertEquals(automatic.id(), noise.id());
        assertNull(noise.psgEnvelopeId());
        assertEquals(List.of(81), noise.notes().stream().map(NoteEvent::pitch).toList());
        assertEquals(231, noise.commands().getFirst().arguments().getFirst());
        assertEquals(3, project.samples().size());
        assertTrue(project.samples().stream().anyMatch(dac -> java.util.Arrays.equals(sample, dac.pcm())));
        assertTrue(project.samples().stream().anyMatch(dac -> Byte.toUnsignedInt(dac.pcm()[0]) == 64));
        assertTrue(project.samples().stream().anyMatch(dac -> Byte.toUnsignedInt(dac.pcm()[0]) == 96));
    }

    @ParameterizedTest
    @ValueSource(strings = {"s1", "s2"})
    void classicStockKitSurvivesPortableExportAndRomPatch(String target) throws Exception {
        String configured = System.getenv("OPENSMPSDECK_" + target.toUpperCase(java.util.Locale.ROOT) + "_ROM");
        assumeTrue(configured != null && !configured.isBlank());
        var set = isolatedCommand("rom", "set", target, configured);
        assertEquals(0, set.status(), set.output());
        var fixture = classicFixture(true);
        var run = isolatedCommand("reconstruct", "run", fixture[0], fixture[1], fixture[2],
                directory.resolve("work").toString(), "--steady-tempo", "off", "--target", target);
        assertEquals(0, run.status(), run.output());
        var work = directory.resolve("work");
        var project = ProjectFiles.load(work.resolve("arrangement.smps.json")).project();
        assertFalse(project.samples().isEmpty());
        for (var sample : project.samples()) {
            assertEquals(target, sample.origin().ref().game());
            assertEquals(StockTable.hash(sample.pcm()), sample.origin().ref().sha256());
        }
        assertTrue(project.samples().stream().anyMatch(s -> s.origin().ref().note() == 0x81));
        assertTrue(project.samples().stream().anyMatch(s ->
                DacFamily.slot(TargetGame.parse(target), s.origin().ref().note()) == DacFamily.TOM));

        var portable = work.resolve("arrangement.smps.zip");
        try (var zip = new java.util.zip.ZipFile(portable.toFile())) {
            var manifest = JsonParser.parseReader(new java.io.InputStreamReader(
                    zip.getInputStream(zip.getEntry("manifest.json")), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertTrue(manifest.getAsJsonObject("requires").get("stockAssets").getAsBoolean());
            assertTrue(zip.stream().noneMatch(entry -> entry.getName().endsWith(".pcm")));
        }
        for (Path input : List.of(work.resolve("arrangement.smps.json"), portable)) {
            var patch = isolatedCommand("rom-patch", input.toString(), "--driver", target, "--bps",
                    directory.resolve(input.getFileName() + ".bps").toString());
            assertEquals(0, patch.status(), patch.output());
            var result = JsonParser.parseString(patch.output()).getAsJsonObject();
            var report = result.getAsJsonObject("report");
            if (report.has("approximations")) assertTrue(report.getAsJsonArray("approximations").isEmpty());
            assertTrue(result.getAsJsonArray("warnings").asList().stream()
                    .noneMatch(item -> item.getAsString().contains("substitutes stock DAC")));
            var mappings = report.getAsJsonArray("dacNoteMap");
            assertEquals(project.samples().size(), mappings.size());
            for (var item : mappings) {
                var binding = item.getAsJsonObject();
                assertTrue(binding.get("exact").getAsBoolean(), binding.toString());
                assertEquals(0.0, binding.get("distance").getAsDouble());
                int slot = binding.get("note").getAsInt() - DacSample.FIRST_NOTE;
                assertEquals(project.samples().get(slot).origin().ref().note().intValue(),
                        binding.get("mappedNote").getAsInt());
            }
        }
    }

    @Test void everySonic2StockRateMatchesTheDecompressedDriver() throws Exception {
        String configured = System.getenv("OPENSMPSDECK_S2_ROM");
        assumeTrue(configured != null && !configured.isBlank());
        Path rom = Path.of(configured);
        var samples = List.copyOf(ReconstructionStockDrums.load(VerifiedRom.read(RomGame.S2, rom)).values());
        assertEquals(17, samples.size());
        var triggers = new ArrayList<SampleTrigger>();
        for (int i = 0; i < samples.size(); i++)
            triggers.add(new SampleTrigger("hit-" + i, i * 60, samples.get(i).id()));
        int length = 60 * samples.size();
        var seq = new Sequence("drums", "Drums", "DAC", length, null, 127, List.of(), List.of(),
                "RESET", null, null, triggers);
        var project = new StudioProject(4, "Stock rate coverage", "S3K", 30, 0, List.of(),
                List.of(seq), List.of(new Clip("clip", "drums", "DAC", 0, length)),
                List.of(), samples, TargetGame.S2, 255);
        Path portable = directory.resolve("stock.smps.zip");
        PortablePackageWriter.write(project, portable, PortableOptions.defaults(TargetGame.S2));
        var result = TestInGame.export(new RomLibrary(directory.resolve("roms.json")), portable, rom,
                new TestInGame.Outputs(null, directory.resolve("stock.bps"), false), RomGame.S2,
                new TestInGame.Options(null, false, false, false));
        var mappings = result.getAsJsonObject("report").getAsJsonArray("dacNoteMap");
        assertEquals(samples.size(), mappings.size());
        for (var item : mappings) {
            var binding = item.getAsJsonObject();
            int slot = binding.get("note").getAsInt() - DacSample.FIRST_NOTE;
            assertEquals(samples.get(slot).origin().ref().note().intValue(), binding.get("mappedNote").getAsInt());
            assertTrue(binding.get("exact").getAsBoolean(), binding.toString());
            assertEquals(0.0, binding.get("distance").getAsDouble());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"s1", "s2"})
    void classicStockNeedsRomAndCustomSlicesUseTargetRate(String target) throws Exception {
        var fixture = classicFixture(false);
        var missing = isolatedCommand("reconstruct", "run", fixture[0], fixture[1], fixture[2],
                directory.resolve("missing").toString(), "--target", target);
        assertEquals(2, missing.status(), missing.output());
        assertTrue(missing.output().contains("rom set " + target), missing.output());

        Path custom = directory.resolve("custom");
        var run = isolatedCommand("reconstruct", "run", fixture[0], fixture[1], fixture[2], custom.toString(),
                "--steady-tempo", "off", "--target", target, "--drum-kit", "custom",
                "--drum-source", "sliced", "--stem", "0=" + fixture[1]);
        assertEquals(0, run.status(), run.output());
        assertTrue(run.output().contains("Test in game may approximate"), run.output());
        var project = ProjectFiles.load(custom.resolve("arrangement.smps.json")).project();
        var kick = project.samples().getFirst();
        assertEquals("source-audio", kick.origin().type());
        assertEquals(2, kick.rate());
        var evidence = ReconstructionArtifacts.readTree(custom.resolve("voicing.json")).getAsJsonObject()
                .getAsJsonObject("lanes").getAsJsonObject("DAC").getAsJsonArray("instruments")
                .get(0).getAsJsonObject().getAsJsonObject("drumSelection").getAsJsonObject("evidence");
        double base = target.equals("s1") ? 301 : 295;
        double hz = (2 * 53_693_175.0 / 15) / (base + 26);
        double clipSeconds = evidence.getAsJsonArray("hits").get(0).getAsJsonObject().get("length").getAsDouble();
        assertEquals((int) Math.ceil(clipSeconds * hz), kick.pcm().length);
    }

    private String[] classicFixture(boolean tuned) throws Exception {
        var index = directory.resolve("index.json");
        var catalogue = new InstrumentCatalogue.Builder();
        catalogue.asset(InstrumentCatalogue.Kind.DAC_SAMPLE, "Synthetic placeholder", new byte[]{(byte) 128});
        catalogue.build().save(index);
        var midi = directory.resolve("drums.mid");
        var sequence = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 480);
        var drums = sequence.createTrack();
        for (int i = 0; i < 6; i++) midiNote(drums, 9, tuned && i % 2 == 1 ? 45 : 36, 192 + 480 * i);
        MidiSystem.write(sequence, 0, midi.toFile());
        var wav = directory.resolve("drums.wav");
        WaveFile.write(wav, 192, sink -> {
            var frame = new short[1600];
            for (int tick = 0; tick < 192; tick++) {
                for (int i = 0; i < 800; i++) {
                    double t = (tick * 800 + i) / 48_000.0 - .2;
                    double pitch = tuned && (int) (t / .5) % 2 == 1 ? 180 : 75;
                    frame[2 * i] = frame[2 * i + 1] = (short) (t < 0 ? 0 : 16_000 * kick(t % .5, pitch));
                }
                sink.accept(frame);
            }
        });
        return new String[]{midi.toString(), wav.toString(), index.toString()};
    }

    private record CommandResult(int status, String output) {}

    private CommandResult isolatedCommand(String... args) throws Exception {
        var argv = new java.util.ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"), Main.class.getName()));
        argv.addAll(List.of(args));
        var stdout = directory.resolve("stdout.txt");
        var stderr = directory.resolve("stderr.txt");
        var command = new ProcessBuilder(argv).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        command.environment().put("XDG_CONFIG_HOME", directory.resolve("config").toString());
        var process = command.start();
        int status = process.waitFor();
        return new CommandResult(status, Files.readString(stdout) + (status == 0 ? "" : Files.readString(stderr)));
    }

    private static void midiNote(javax.sound.midi.Track track, int channel, int pitch, int tick) throws Exception {
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, channel, pitch, 100), tick));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, channel, pitch, 0), tick + 48));
    }

    private static void command(String... args) {
        var diagnostics = new ByteArrayOutputStream();
        var out = new PrintStream(diagnostics);
        assertEquals(0, Main.run(args, out, out), diagnostics.toString());
    }

    private static ReconstructionVoicing.Lane drumLane(int pitch, double gain) {
        return new ReconstructionVoicing.Lane(drums, null, List.of(), 1, ReconstructionVoicing.WRITTEN,
                ReconstructionVoicing.DRY, "", "", "", List.of(), null, null, Map.of(pitch, gain), null, null);
    }

    private static double kick(double seconds, double pitch) {
        return Math.sin(2 * Math.PI * pitch * seconds) * Math.exp(-25 * seconds);
    }

    private DacSample reconstruct(Path midi, Path stem, Path index, String mode, boolean withStem) throws Exception {
        Path work = directory.resolve(mode);
        var args = new java.util.ArrayList<>(List.of("reconstruct", "run", midi.toString(), stem.toString(),
                index.toString(), work.toString(), "--steady-tempo", "off", "--drum-source", mode));
        if (withStem) args.addAll(List.of("--stem", "0=" + stem));
        var stderr = new ByteArrayOutputStream();
        try (var diagnostics = new PrintStream(stderr)) {
            assertEquals(0, Main.run(args.toArray(String[]::new), diagnostics, diagnostics), stderr.toString());
        }
        var report = JsonParser.parseString(Files.readString(work.resolve("report.json"))).getAsJsonObject();
        assertEquals(mode, report.get("drumSource").getAsString());
        assertEquals(3, report.get("dacHits").getAsInt());
        return ProjectFiles.load(work.resolve("arrangement.smps.json")).project().samples().getFirst();
    }

    private static boolean noiseIsSilent(List<Integer> values, S3kRenderer.ChannelMask mask) throws Exception {
        var envelope = new Envelope("env", "Volume", Envelope.PSG_VOLUME, values);
        var tone = new Sequence("tone", "Tone", "PSG", 16, null, 95,
                List.of(new NoteEvent("note", 0, 16, 69)), List.of(), "RESET", envelope.id(), null);
        var project = new StudioProject(4, "Envelope isolation", "S3K", 30, 0, List.of(), List.of(tone),
                List.of(new Clip("tone-clip", tone.id(), "PSG2", 0, 16)), List.of(envelope), TargetGame.S3K, null);
        // Compare each presentation's settled waveform against the constant
        // hardware resting level rather than assuming silence is digital zero.
        boolean[] silent = {true};
        S3kRenderer.render(ProjectCompiler.compileWithAssets(project), 12, mask, pcm -> {
            for (int i = 64; i < pcm.length; i++) {
                if (pcm[i] != pcm[64]) silent[0] = false;
            }
        });
        return silent[0];
    }
}
