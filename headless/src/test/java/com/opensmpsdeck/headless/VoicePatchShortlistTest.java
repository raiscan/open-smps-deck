package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import java.util.ArrayList;
import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoicePatchShortlistTest {
    @Test void probesComeFromEveryStretchOfAPartWhoseBestAttacksClusterAtTheStart() {
        var ordered = new ArrayList<ReconstructionAllocation.Entry>();
        // Quality order: five long opening notes, then forty short notes from the rest of the part.
        for (int i = 0; i < 5; i++) ordered.add(entry(i * 30, 18));
        for (int i = 0; i < 40; i++) ordered.add(entry(200 + i * 6, 5));

        var stretches = VoicePatchShortlist.stretches(ordered, 5);

        assertEquals(5, stretches.size());
        assertEquals(List.of(0, 224, 278, 332, 386),
                stretches.stream().map(stretch -> stretch.getFirst().startTick()).toList());
        assertEquals(45, stretches.stream().mapToInt(List::size).sum());
    }

    @Test void aQuietAttackIsComparedWithItsPatchAtTheMatchingVolume() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        // Three carriers under one modulator. Their sum clips the channel at full volume.
        byte[] bytes = {0x05, 1, 1, 1, 1, 31, 31, 31, 31, 0, 0, 0, 0, 0, 0, 0, 0, 15, 15, 15, 15,
                0x18, (byte) 0x80, (byte) 0x80, (byte) 0x80};
        String asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Carriers", bytes);
        String id = builder.variant(asset, "Carriers", InstrumentCatalogue.Recipe.plain(), null);
        var catalogue = builder.build();
        var shape = new VoiceAttackShape();
        var quiet = shape.measure(render(catalogue, id, 103));
        var full = shape.measure(render(catalogue, id, 127));
        var shortlist = new VoicePatchShortlist(catalogue, TargetGame.S3K);
        var variant = catalogue.match(id).variant();

        int volume = shortlist.attackMatchedVolume(variant, 72, quiet.peak());

        assertEquals(103, volume);
        assertEquals(127, shortlist.attackMatchedVolume(variant, 72, 4 * full.peak()));
        assertTrue(quiet.distance(full) > .05, "the full-volume template misrepresents the quiet part");
        assertEquals(0, quiet.distance(shape.measure(render(catalogue, id, volume))), 1e-9);
    }

    @Test void aChallengerFromTheTypicalAttacksCannotChangeWhatTheClearestAttacksSupport(@TempDir Path directory)
            throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var ids = new ArrayList<String>();
        // Twenty-one audibly different two-carrier patches, three more than the shortlist holds.
        for (int first = 1; first <= 7; first++) {
            for (int second = 1; second <= 3; second++) {
                String name = "Pair " + first + "-" + second;
                ids.add(builder.variant(builder.asset(InstrumentCatalogue.Kind.FM_VOICE, name, pair(first, second)),
                        name, InstrumentCatalogue.Recipe.plain(), null));
            }
        }
        var catalogue = builder.build();
        var screening = new VoicePatchShortlist(catalogue, TargetGame.S3K);

        int frames = 1200, perFrame = S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;
        var audio = new short[frames * perFrame * 2];
        var longNotes = new ArrayList<ReconstructionAllocation.Entry>();
        // Long notes that no patch explains: a tone off the written pitch. Eight of
        // them leave five clear attacks outside the held-out windows.
        for (int i = 0; i < 8; i++) {
            int start = 10 + i * 36, pitch = 60 + 2 * i, first = start * perFrame, length = 30 * perFrame;
            double hz = 1.37 * 440 * Math.pow(2, (pitch - 69) / 12.0);
            for (int at = 0; at < length; at++) {
                double fade = .5 - .5 * Math.cos(Math.PI * Math.min(1, Math.min(at, length - at) / 1440.0));
                audio[2 * (first + at)] = audio[2 * (first + at) + 1] = (short) (6000 * fade
                        * Math.sin(Math.TAU * hz * (first + at) / S3kRenderer.SAMPLE_RATE));
            }
            longNotes.add(entry(start, 30, pitch));
        }
        var alone = directory.resolve("long.wav");
        write(alone, audio, frames);
        var before = screening.rank(longNotes, longNotes, directory.resolve("mix.wav"), Map.of(0, alone),
                new VoiceWindows(frames, Map.of("FM2", longNotes), null), null, true, null, null, true);
        assertEquals(18, before.clearest().size());
        String left = ids.stream().filter(id -> before.clearest().stream().noneMatch(patch -> patch.id().equals(id)))
                .findFirst().orElseThrow();

        // Forty short notes played with a patch the long notes left out fill the rest of the part.
        var entries = new ArrayList<>(longNotes);
        for (int i = 0; i < 40; i++) {
            int start = 320 + i * 20, pitch = 55 + i % 20;
            int[] at = {start * perFrame * 2};
            TargetPlayback.prepare(CataloguePlayback.matchProbe(catalogue, left, pitch, 6, true, TargetGame.S3K))
                    .render(8, pcm -> {
                        System.arraycopy(pcm, 0, audio, at[0], pcm.length);
                        at[0] += pcm.length;
                    });
            entries.add(entry(start, 6, pitch));
        }
        var stem = directory.resolve("stem.wav");
        write(stem, audio, frames);

        var ranking = screening.rank(entries, entries, directory.resolve("mix.wav"), Map.of(0, stem),
                new VoiceWindows(frames, Map.of("FM2", longNotes), null), null, true, null, null, true);

        // The short notes name the missing patch, but only as a challenger scored on the long notes.
        assertEquals(before.clearest(), ranking.clearest());
        assertEquals(before.clearestMatch(), ranking.clearestMatch());
        var challenger = ranking.typical().stream().filter(patch -> patch.id().equals(left)).findFirst().orElseThrow();
        assertTrue(ranking.clearest().stream().allMatch(patch -> patch.score() >= challenger.score()));
    }

    private static void write(Path file, short[] audio, int frames) throws Exception {
        int width = S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 2;
        WaveFile.write(file, frames, sink -> {
            for (int frame = 0; frame < frames; frame++)
                sink.accept(java.util.Arrays.copyOfRange(audio, frame * width, (frame + 1) * width));
        });
    }

    private static byte[] pair(int first, int second) {
        byte[] bytes = new byte[25];
        bytes[0] = 7;
        for (int op = 0; op < 4; op++) {
            bytes[1 + op] = (byte) (op == 0 ? first : op == 1 ? second : 1);
            bytes[5 + op] = 31;
            bytes[17 + op] = 15;
            bytes[21 + op] = (byte) (op < 2 ? 0x80 : 0xff);
        }
        return bytes;
    }

    private static ReconstructionAllocation.Entry entry(int start, int length, int pitch) {
        var note = new ReconstructionStages.Note("n" + start, 0, start / 60.0, (start + length) / 60.0, pitch, 96, "test", 1);
        return new ReconstructionAllocation.Entry(note, Role.melody, "FM2", start, start + length,
                ReconstructionAllocation.Outcome.kept, null, null);
    }

    private static float[] render(InstrumentCatalogue catalogue, String id, int volume) throws Exception {
        return VoiceWindows.renderMono(CataloguePlayback.leveledProbe(catalogue, id, 72, 7, TargetGame.S3K, volume), 7);
    }

    private static ReconstructionAllocation.Entry entry(int start, int length) {
        var note = new ReconstructionStages.Note("n" + start, 0, start / 60.0, (start + length) / 60.0, 60, 96, "test", 1);
        return new ReconstructionAllocation.Entry(note, Role.harmony, "FM2", start, start + length,
                ReconstructionAllocation.Outcome.kept, null, null);
    }
}
