package com.opensmpsdeck.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.ReconstructionBranches;
import com.opensmpsdeck.headless.ReconstructionInspection;
import com.opensmpsdeck.headless.ReconstructionStages;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.TargetGame;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Short, self-contained MIDI reconstruction shared by branch layout and window tests. */
final class StudioBranchFixture {
    private StudioBranchFixture() {}

    static Path create(Path root) throws Exception {
        Files.createDirectories(root);
        var midi = root.resolve("song.mid");
        var mix = root.resolve("mix.wav");
        var catalogue = root.resolve("catalogue.json");
        var work = root.resolve("Paper Lanterns");
        var sequence = new Sequence(Sequence.PPQ, 480);
        for (int channel = 0; channel < 3; channel++) {
            var track = sequence.createTrack();
            byte[] name = List.of("Lead", "Bass", "Keys").get(channel).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            track.add(new MidiEvent(new MetaMessage(3, name, name.length), 0));
            for (int i = 0; i < 12; i++) {
                int start = i * 420 + channel * 60;
                int pitch = 72 - channel * 12 + i % 4;
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, channel, pitch, 90), start));
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, channel, pitch, 0), start + 270));
            }
        }
        MidiSystem.write(sequence, 1, midi.toFile());
        int rate = 12_000;
        int[] samples = new int[rate * 6];
        for (int i = 0; i < samples.length; i++) {
            double time = (double) i / rate;
            double tone = 0;
            for (int channel = 0; channel < 3; channel++) {
                double notes = (time - channel / 16.0) * 960 / 420;
                if (notes < 0 || notes - Math.floor(notes) >= 270.0 / 420) continue;
                int pitch = 72 - channel * 12 + (int) notes % 4;
                tone += Math.sin(Math.TAU * 440 * Math.pow(2, (pitch - 69) / 12.0) * time);
            }
            samples[i] = (int) (4500 * tone);
        }
        ReferenceWaveTest.wav(mix, 16, 1, rate, samples);
        var voice = DemoProject.create().voices().getFirst();
        var builder = new InstrumentCatalogue.Builder();
        byte[] bytes = new byte[voice.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = voice.bytes().get(i).byteValue();
        var asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, voice.name(), bytes);
        var occurrence = new InstrumentCatalogue.Occurrence("fixture", TargetGame.S3K.id(), "test",
                "Synthetic branch preview", 1, null, null, null, false);
        builder.variant(asset, voice.name(), InstrumentCatalogue.Recipe.plain(), occurrence);
        builder.build().save(catalogue);
        ReconstructionStages.runForReview("run", new String[]{midi.toString(), mix.toString(),
                catalogue.toString(), work.toString(), "--seconds", "6"}, (done, total, message) -> {});
        return work;
    }

    static void branches(Path work) throws Exception {
        var score = ReconstructionInspection.open(work);
        var parts = score.parts();
        int lead = parts.getFirst().id(), bass = parts.get(1).id(), keys = parts.get(2).id();
        var melody = attacks(score, lead);
        var lower = attacks(score, bass);
        var chords = attacks(score, keys);
        branch(work, "astra", "Astra · agent", List.of(
                pitch("astra-bass", lower.getFirst(), -2, "bass-tail"),
                pitch("astra-lead", melody.getFirst(), 2, "bass-tail"),
                pitch("astra-response", lower.get(3), -1, "bass-tail")),
                new ReconstructionBranches.Group("bass-tail", "Bass response", "Step down into the first turnaround."));
        branch(work, "fable", "Fable · agent", List.of(
                pitch("fable-opening", melody.getFirst(), -2, "lead-start"),
                pitch("fable-lead", melody.get(3), 1, "lead-pocket"),
                pitch("fable-keys", chords.get(6), -1, "lead-start")),
                new ReconstructionBranches.Group("lead-start", "Opening phrase", "Keep the opening below the answer."),
                new ReconstructionBranches.Group("lead-pocket", "Lead lift", "Lift the third attack into the response."));
    }

    private static List<ReconstructionInspection.Overlay> attacks(ReconstructionInspection score, int part) {
        return score.overlays().stream().filter(note -> note.part() == part && note.sounds()).toList();
    }

    private static JsonObject pitch(String id, ReconstructionInspection.Overlay note, int delta, String group) {
        var edit = new JsonObject();
        edit.addProperty("id", id);
        edit.addProperty("op", "setNote");
        edit.addProperty("pitch", note.pitch() + delta);
        edit.addProperty("reason", "Synthetic branch pitch choice");
        edit.addProperty("group", group);
        edit.addProperty("noteId", note.id());
        edit.addProperty("author", "agent");
        return edit;
    }

    private static void branch(Path work, String name, String author, List<JsonObject> edits,
                               ReconstructionBranches.Group... groups) throws Exception {
        ReconstructionBranches.create(work, name, author);
        var journal = new JsonArray();
        edits.forEach(journal::add);
        ReconstructionBranches.edit(work, name, journal, List.of(groups), Set.of());
    }
}
