package com.opensmpsdeck.headless;

import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Ranks catalogue playback variants against one isolated reference sound. */
public final class CatalogueMatcher {
    public static final String PROFILE = "s3k-ntsc-match-v1";

    public record Options(Kind kind, String role, String source, int midi, Integer gateTicks, int limit) {
        public Options {
            if (midi < 12 || midi > 117) throw new IllegalArgumentException("MIDI note must be in 12..117");
            if (gateTicks != null && (gateTicks < 1 || gateTicks > 180))
                throw new IllegalArgumentException("Gate must be in 1..180 presentation ticks");
            if (limit < 1 || limit > InstrumentCatalogue.PAGE_LIMIT)
                throw new IllegalArgumentException("Limit must be in 1.." + InstrumentCatalogue.PAGE_LIMIT);
        }
    }

    public record Ranked(String assetId, String variantId, Kind kind, String name, List<String> roles,
                         AudioFingerprint.Distance distance, AudioFingerprint.Summary sound) {}

    public record Skipped(String variantId, String reason) {}

    public record Report(int formatVersion, String featureProfile, int midi, int gateTicks,
                         double referenceSeconds, int eligible, int compared, AudioFingerprint.Summary reference,
                         List<Ranked> matches, List<Skipped> skipped) {
        public Report {
            matches = List.copyOf(matches);
            skipped = List.copyOf(skipped);
        }
    }

    private CatalogueMatcher() {}

    public static Report match(InstrumentCatalogue index, ReferenceAudio audio, Options options) throws IOException {
        float[] samples = audio.samples();
        var reference = AudioFingerprint.extract(samples, audio.sampleRate());
        double seconds = samples.length / (double) audio.sampleRate();
        int referenceFrames = Math.max(1, (int) Math.ceil(seconds * S3kRenderer.PRESENTATIONS_PER_SECOND));
        int gate = options.gateTicks() == null ? referenceFrames : options.gateTicks();
        var ranked = new ArrayList<Ranked>();
        var skipped = new ArrayList<Skipped>();
        int eligible;
        int offset = 0;
        do {
            var query = new InstrumentCatalogue.Query(null, options.kind(), null, options.role(),
                    options.source(), null, offset, InstrumentCatalogue.PAGE_LIMIT);
            var page = index.search(query);
            eligible = page.total();
            for (var hit : page.items()) {
                checkInterrupted();
                var variant = hit.variant();
                try {
                    var probe = CataloguePlayback.matchProbe(index, variant.id(), options.midi(), gate);
                    var fingerprint = describeProbe(probe, referenceFrames);
                    ranked.add(new Ranked(hit.asset().id(), variant.id(), hit.asset().kind(), variant.name(),
                            variant.roles(), reference.distance(fingerprint), fingerprint.summary()));
                } catch (IllegalArgumentException unableToCompare) {
                    skipped.add(new Skipped(variant.id(), unableToCompare.getMessage()));
                }
            }
            offset += page.items().size();
        } while (offset < eligible);

        ranked.sort(Comparator.comparingDouble((Ranked row) -> row.distance().total())
                .thenComparing(Ranked::variantId));
        return new Report(InstrumentCatalogue.FORMAT_VERSION, PROFILE, options.midi(), gate, seconds, eligible,
                ranked.size(), reference.summary(), ranked.subList(0, Math.min(options.limit(), ranked.size())), skipped);
    }

    private static AudioFingerprint describeProbe(StudioProject probe, int referenceFrames) throws IOException {
        int frames = Math.min(referenceFrames, ProjectRenderTiming.presentationCount(probe));
        int samplesPerFrame = S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;
        float[] audio = new float[frames * samplesPerFrame];
        int[] position = {0};
        S3kRenderer.render(ProjectCompiler.compileWithAssets(probe), frames, pcm -> {
            checkInterrupted();
            for (int i = 0; i < samplesPerFrame; i++)
                audio[position[0]++] = (pcm[2 * i] + pcm[2 * i + 1]) / 65536.0f;
        });
        return AudioFingerprint.extract(audio, S3kRenderer.SAMPLE_RATE);
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Catalogue matching interrupted");
    }
}
