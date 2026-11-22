package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

/** Checks project payloads and final package references against the same ROM snapshots. */
final class ExportStockCheck {
    private record Result(String slot, String error) {}

    private final List<? extends StockAssets> sources;
    private final List<Result> results = new ArrayList<>();

    ExportStockCheck(List<? extends StockAssets> sources) {
        this.sources = sources;
    }

    StudioProject resolveProject(StudioProject project) {
        if (sources.isEmpty()) return StockAssets.resolveProject(project, sources);
        var voices = new ArrayList<Voice>();
        for (var voice : project.voices()) {
            String slot = slot("fm/" + voices.size(), voice.id());
            var bytes = checkProjectAsset(slot, voice.origin(), () -> voice.bytes() == null ? null : StockTable.bytes(voice.bytes()));
            voices.add(voice.bytes() == null && bytes != null
                    ? new Voice(voice.id(), voice.name(), StockAssets.unsigned(bytes), voice.origin()) : voice);
        }
        var envelopes = new ArrayList<Envelope>();
        int psg = 0;
        int mod = 0;
        for (var envelope : project.envelopes()) {
            String key = envelope.type().equals(Envelope.PSG_VOLUME) ? "psg/" + psg++ : "mod/" + mod++;
            var bytes = checkProjectAsset(slot(key, envelope.id()), envelope.origin(), () -> envelope.bytes() == null ? null : StockTable.bytes(envelope.bytes()));
            envelopes.add(envelope.bytes() == null && bytes != null
                    ? new Envelope(envelope.id(), envelope.name(), envelope.type(), StockAssets.unsigned(bytes), envelope.origin())
                    : envelope);
        }
        var samples = new ArrayList<DacSample>();
        for (var sample : project.samples()) {
            var bytes = checkProjectAsset(slot("dac/" + samples.size(), sample.id()), sample.origin(),
                    () -> sample.pcmBase64() == null ? null : sample.pcm());
            samples.add(sample.pcmBase64() == null && bytes != null
                    ? DacSample.of(sample.id(), sample.name(), sample.rate(), bytes).withOrigin(sample.origin()) : sample);
        }
        requireMatches();
        return project.withAssets(voices, envelopes, samples);
    }

    private byte[] checkProjectAsset(String slot, AssetOrigin origin, Supplier<byte[]> recordedPayload) {
        if (origin == null || origin.ref() == null) return null;
        return check(slot, () -> {
            var resolved = StockAssets.resolveProjectAsset(slot, origin, sources);
            var recorded = recordedPayload.get();
            if (recorded != null) StockAssets.verifyHash(slot + " project payload", StockTable.hash(recorded), resolved);
            return resolved;
        });
    }

    void verifyManifest(PackageManifest manifest) {
        if (sources.isEmpty()) return;
        for (var asset : manifest.assets()) {
            if (asset.ref() == null) continue;
            String slot = slot(asset.key(), asset.id());
            check(slot, () -> {
                var bytes = StockAssets.resolve(slot, asset.ref(), asset.transforms(), asset.resultSha256(), sources);
                asset.kind().validateLength(slot, bytes.length);
                return bytes;
            });
        }
        requireMatches();
    }

    private byte[] check(String slot, Supplier<byte[]> resolve) {
        try {
            byte[] resolved = resolve.get();
            results.add(new Result(slot, null));
            return resolved;
        } catch (IllegalArgumentException failure) {
            results.add(new Result(slot, failure.getMessage()));
            return null;
        }
    }

    List<String> matches() {
        return results.stream().filter(result -> result.error() == null).map(Result::slot).distinct().toList();
    }

    private void requireMatches() {
        if (results.stream().noneMatch(result -> result.error() != null)) return;
        // When checking the package representation, a failure supersedes the
        // earlier match for the same project slot.
        var lines = new LinkedHashMap<String, String>();
        for (var result : results) {
            lines.put(result.slot(), result.error() == null ? "Matched " + result.slot()
                    : "Mismatch " + result.slot() + " — " + result.error());
        }
        throw new IllegalArgumentException("ROM stock asset verification failed:\n" + String.join("\n", lines.values()));
    }

    private static String slot(String key, String id) {
        return key + " (" + id + ")";
    }
}
