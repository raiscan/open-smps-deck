package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionEchoTest {
    @TempDir Path directory;

    @Test void findsRepeatedQuietAttacksButLeavesDryNotesAlone() throws Exception {
        var notes = IntStream.range(0, 8).mapToObj(i -> new MidiScore.Note(
                0, 69 + i % 3, 85, 200_000 + i * 700_000L, 300_000 + i * 700_000L)).toList();
        var score = new MidiScore(List.of(new MidiScore.Part(0, "Lead", 81, false)), notes, List.of());
        Path audio = fixture(notes, "echo");
        var estimate = ReconstructionEcho.detect(score, audio, Map.of(0, audio), 6_000_000).get(0);
        assertEquals("repeat-supported", estimate.status());
        assertEquals(15, estimate.delayTicks(), 1);
        assertTrue(estimate.fitSupport() >= 3 && estimate.checkSupport() >= 2);
        assertTrue(estimate.amplitudeRatio() > .2 && estimate.amplitudeRatio() < .5);

        Path dry = fixture(notes, "dry");
        var without = ReconstructionEcho.detect(score, dry, Map.of(), 6_000_000).get(0);
        assertNull(without.delayTicks());
        Path tail = fixture(notes, "tail");
        var unresolved = ReconstructionEcho.detect(score, tail, Map.of(), 6_000_000).get(0);
        assertEquals("tail-unresolved", unresolved.status());
        assertNull(unresolved.delayTicks());
    }

    @Test void schedulesAttenuatedEchoesWithoutMovingDryNotes() {
        var builder = new InstrumentCatalogue.Builder();
        var voice = com.opensmpsdeck.project.DemoProject.create().voices().getFirst();
        byte[] raw = new byte[voice.bytes().size()];
        for (int i = 0; i < raw.length; i++) raw[i] = voice.bytes().get(i).byteValue();
        String asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, voice.name(), raw);
        String variant = builder.variant(asset, voice.name(), InstrumentCatalogue.Recipe.plain(), null);
        var index = builder.build();
        var score = new MidiScore(List.of(new MidiScore.Part(0, "Lead", 81, false)),
                List.of(new MidiScore.Note(0, 69, 92, 200_000, 300_000)), List.of());
        var prepared = S3kReconstruction.prepare(score, 1_000_000, false);
        var choices = Map.of(new ReconstructionVoices.Key(0, null), new ReconstructionVoices.Choice(
                asset, variant, voice.name(), null, "matched", List.of(), List.of()));
        var estimate = new ReconstructionEcho.Estimate(0, true, "repeat-supported", 8, 0, 15, .33, 4, 4);
        var dry = S3kReconstruction.arrange("Demo", 15, 60, prepared, index, choices, Map.of());
        var wet = S3kReconstruction.arrange("Demo", 15, 60, prepared, index, choices, Map.of(), Map.of(0, estimate));
        assertEquals(1, wet.fmNotes());
        assertEquals(1, wet.echoNotes());
        assertEquals(dry.project().clips().getFirst(), wet.project().clips().getFirst());
        assertEquals(dry.project().sequences().getFirst(), wet.project().sequences().getFirst());
        assertEquals("FM5", wet.project().clips().getLast().lane());
        assertEquals(15, wet.project().clips().getLast().startTick() - wet.project().clips().getFirst().startTick());
        assertTrue(wet.project().sequences().getLast().volume() < dry.project().sequences().getFirst().volume());
        assertDoesNotThrow(() -> com.opensmpsdeck.project.ProjectCompiler.compile(wet.project()));
    }

    @Test void audioRefinementWritesEchoEvidenceAndNativeOutputThroughTheCli() throws Exception {
        var notes = IntStream.range(0, 8).mapToObj(i -> new MidiScore.Note(
                0, 69 + i % 3, 85, 200_000 + i * 700_000L, 300_000 + i * 700_000L)).toList();
        Path audio = fixture(notes, "echo");
        var midi = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 1_000);
        var track = midi.createTrack();
        track.add(new javax.sound.midi.MidiEvent(new javax.sound.midi.ShortMessage(0xC0, 81, 0), 0));
        for (var note : notes) {
            track.add(new javax.sound.midi.MidiEvent(
                    new javax.sound.midi.ShortMessage(0x90, note.pitch(), note.velocity()), note.startUs() / 500));
            track.add(new javax.sound.midi.MidiEvent(
                    new javax.sound.midi.ShortMessage(0x80, note.pitch(), 0), note.endUs() / 500));
        }
        Path input = directory.resolve("score.mid");
        javax.sound.midi.MidiSystem.write(midi, 0, input.toFile());
        var builder = new InstrumentCatalogue.Builder();
        var voice = com.opensmpsdeck.project.DemoProject.create().voices().getFirst();
        byte[] raw = new byte[voice.bytes().size()];
        for (int i = 0; i < raw.length; i++) raw[i] = voice.bytes().get(i).byteValue();
        String asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, voice.name(), raw);
        builder.variant(asset, voice.name(), InstrumentCatalogue.Recipe.plain(), null);
        Path index = directory.resolve("index.json");
        builder.build().save(index);
        Path output = directory.resolve("result");
        var errors = new java.io.ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"reconstruct",input.toString(),audio.toString(),index.toString(),
                        output.toString(),"--audio-refine"}, System.out, new java.io.PrintStream(errors)), errors.toString());
        var report = com.google.gson.JsonParser.parseString(Files.readString(output.resolve("report.json"))).getAsJsonObject();
        assertTrue(report.get("audioRefine").getAsBoolean());
        assertEquals(8, report.get("fmNotes").getAsInt());
        assertEquals(8, report.get("echoNotes").getAsInt());
        assertTrue(report.getAsJsonObject("vibratoEstimates").isEmpty());
        assertEquals(1, report.getAsJsonArray("reverbEstimates").size());
        assertEquals("repeat-supported", report.getAsJsonArray("echoEstimates").get(0).getAsJsonObject()
                .get("status").getAsString());
        assertTrue(Files.isRegularFile(output.resolve("arrangement.smps.zip")));
    }

    private Path fixture(List<MidiScore.Note> notes, String effect) throws Exception {
        int rate = 12_000;
        var samples = new double[rate * 6];
        for (var note : notes) {
            int start = Math.toIntExact(note.startUs() * rate / 1_000_000);
            double hz = 440 * Math.pow(2, (note.pitch() - 69) / 12.0);
            for (int i = 0; i < rate / 10; i++) {
                double value = .3 * Math.sin(2 * Math.PI * i * hz / rate);
                samples[start + i] += value;
                if (effect.equals("echo")) samples[start + i + rate / 4] += value * .33;
                if (effect.equals("tail")) samples[start + i + rate / 10] += value * .48 * Math.exp(-i * 3.0 / (rate / 10));
            }
        }
        int bytes = samples.length * 2;
        var buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R','I','F','F'}).putInt(bytes + 36);
        buffer.put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16);
        buffer.putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        buffer.put(new byte[]{'d','a','t','a'}).putInt(bytes);
        for (double x : samples) buffer.putShort((short) Math.round(x * 32767));
        return Files.write(directory.resolve(effect + ".wav"), buffer.array());
    }
}
