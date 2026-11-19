package com.opensmpsdeck.headless;

import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ranks catalogue playback variants against one isolated reference sound. */
public final class CatalogueMatcher {
    public static final String PROFILE = "s3k-ntsc-match-v1";
    static final String ASSET_PROFILE = "s3k-ntsc-match-assets-v2";
    static final String LOCKED_ON_S3K = "sha256:fba0677fde9f76df93f3e98d6310d8af68b9847bde16e253d73cd4dd8134ed23";

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

    private record EmittedAsset(String id, Integer dacRate) {}

    private CatalogueMatcher() {}

    public static Report match(InstrumentCatalogue index, ReferenceAudio audio, Options options) throws IOException {
        return match(index, audio, options, false, Set.of());
    }

    static Report matchAssets(InstrumentCatalogue index, ReferenceAudio audio, Options options) throws IOException {
        return matchAssets(index, audio, options, Set.of());
    }

    static Report matchAssets(InstrumentCatalogue index, ReferenceAudio audio, Options options,
                              Set<String> variants) throws IOException {
        return match(index, audio, options, true, variants);
    }

    private static Report match(InstrumentCatalogue index, ReferenceAudio audio, Options options,
                                boolean rawAssets, Set<String> variants) throws IOException {
        float[] samples = audio.samples();
        var reference = AudioFingerprint.extract(samples, audio.sampleRate());
        double seconds = samples.length / (double) audio.sampleRate();
        int referenceFrames = Math.max(1, (int) Math.ceil(seconds * S3kRenderer.PRESENTATIONS_PER_SECOND));
        int gate = options.gateTicks() == null ? referenceFrames : options.gateTicks();
        var ranked = new ArrayList<Ranked>();
        var skipped = new ArrayList<Skipped>();
        var emitted = new HashSet<EmittedAsset>();
        Set<String> trackData = rawAssets && options.kind() == Kind.FM_VOICE ? inferredAzureLakeSlots(index) : Set.of();
        int total;
        int eligible = 0;
        int offset = 0;
        do {
            var query = new InstrumentCatalogue.Query(null, options.kind(), null, options.role(),
                    options.source(), null, offset, InstrumentCatalogue.PAGE_LIMIT);
            var page = index.search(query);
            total = page.total();
            for (var hit : page.items()) {
                checkInterrupted();
                var variant = hit.variant();
                if (!variants.isEmpty() && !variants.contains(variant.id())) continue;
                eligible++;
                if (rawAssets && !emitted.add(new EmittedAsset(variant.assetId(), variant.recipe().rate()))) continue;
                if (trackData.contains(variant.assetId())) {
                    skipped.add(new Skipped(variant.id(), "Azure Lake reads track data past its five local FM voices"));
                    continue;
                }
                try {
                    var probe = CataloguePlayback.matchProbe(index, variant.id(), options.midi(), gate, rawAssets);
                    var fingerprint = describeProbe(probe, referenceFrames);
                    ranked.add(new Ranked(hit.asset().id(), variant.id(), hit.asset().kind(), variant.name(),
                            variant.roles(), reference.distance(fingerprint), fingerprint.summary()));
                } catch (IllegalArgumentException unableToCompare) {
                    skipped.add(new Skipped(variant.id(), unableToCompare.getMessage()));
                }
            }
            offset += page.items().size();
        } while (offset < total);

        ranked.sort(Comparator.comparingDouble((Ranked row) -> row.distance().total())
                .thenComparing(Ranked::variantId));
        return new Report(InstrumentCatalogue.FORMAT_VERSION, rawAssets ? ASSET_PROFILE : PROFILE, options.midi(), gate, seconds, eligible,
                ranked.size(), reference.summary(), ranked.subList(0, Math.min(options.limit(), ranked.size())), skipped);
    }

    private static Set<String> inferredAzureLakeSlots(InstrumentCatalogue index) {
        Set<String> inferred = new HashSet<>();
        Set<String> supported = new HashSet<>();
        for (var variant : index.variants()) {
            if (variant.occurrences().isEmpty() || variant.occurrences().stream().anyMatch(occurrence ->
                    !(LOCKED_ON_S3K.equals(occurrence.revision()) && Integer.valueOf(32).equals(occurrence.songId())
                            && "local-fm".equals(occurrence.table()) && occurrence.slot() != null && occurrence.slot() >= 5))) {
                supported.add(variant.assetId());
            } else {
                inferred.add(variant.assetId());
            }
        }
        inferred.removeAll(supported);
        return inferred;
    }

    static double assetDistance(InstrumentCatalogue index, AudioFingerprint reference, String variant,
                                int midi, int gateTicks, int frames) throws IOException {
        checkInterrupted();
        return reference.distance(describeProbe(
                CataloguePlayback.matchProbe(index, variant, midi, gateTicks, true), frames)).total();
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
