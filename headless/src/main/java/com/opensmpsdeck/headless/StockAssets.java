package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.StockRef;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;
import com.opensmpsdeck.project.ProjectFormat;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/** A loader view of one cartridge. Resolution checks each payload independently. */
@FunctionalInterface
interface StockAssets {
    byte[] payload(StockRef ref);

    default byte[] retailBank(String game, int songId) throws IOException { return null; }

    final class Unavailable extends IllegalArgumentException {
        Unavailable(String message, Throwable cause) { super(message, cause); }
    }

    static byte[] resolve(String asset, StockRef ref, List<AssetOrigin.Transform> transforms,
                          String resultSha256, List<? extends StockAssets> sources) {
        return resolve(asset, ref, transforms, resultSha256, sources, false);
    }

    private static byte[] resolve(String asset, StockRef ref, List<AssetOrigin.Transform> transforms,
                                  String resultSha256, List<? extends StockAssets> sources, boolean editableVoice) {
        byte[] bytes = null;
        var actual = new java.util.LinkedHashSet<String>();
        var problems = new java.util.LinkedHashSet<String>();
        for (var source : sources) {
            byte[] candidate;
            try {
                candidate = source.payload(ref);
            } catch (Unavailable error) {
                problems.add(error.getMessage());
                continue;
            }
            if (candidate == null) continue;
            String hash = StockTable.hash(candidate);
            if (hash.equals(ref.sha256())) {
                bytes = candidate.clone();
                break;
            }
            actual.add(hash);
        }
        if (bytes == null)
            throw new IllegalArgumentException("Stock asset " + asset + " " + ref + ": expected "
                    + ref.sha256() + ", actual " + (actual.isEmpty() ? "missing" : String.join(", ", actual))
                    + (problems.isEmpty() ? "" : " (" + String.join("; ", problems) + ")"));
        if (editableVoice) ClassicPackageDriver.S2.voiceBytes(bytes);
        if (transforms != null) {
            for (var transform : transforms) {
                switch (transform.op()) {
                    case "gain" -> DacSample.applyGain(bytes, Double.parseDouble(transform.factor()));
                    case "trim" -> {
                        if (transform.end() > bytes.length)
                            throw new IllegalArgumentException("Trim exceeds stock asset " + asset);
                        bytes = Arrays.copyOfRange(bytes, transform.start(), transform.end());
                    }
                    case "patch" -> {
                        for (var change : transform.bytes()) {
                            if (change.offset() >= bytes.length)
                                throw new IllegalArgumentException("Patch exceeds stock asset " + asset);
                            bytes[change.offset()] = (byte) change.value();
                        }
                    }
                    default -> throw new AssertionError(transform.op());
                }
            }
        }
        if (resultSha256 != null) verifyHash(asset + " transformed payload", resultSha256, bytes);
        return bytes;
    }

    static void verifyHash(String name, String expected, byte[] bytes) {
        String hash = StockTable.hash(bytes);
        if (!expected.equals(hash))
            throw new IllegalArgumentException(name + ": expected SHA-256 " + expected + ", actual " + hash);
    }

    static StudioProject resolveProject(StudioProject project, List<? extends StockAssets> sources) {
        if (sources.isEmpty()) {
            ProjectFormat.requireResolved(project);
            return project;
        }
        var voices = project.voices().stream().map(voice -> voice.bytes() != null ? voice
                : new Voice(voice.id(), voice.name(),
                        unsigned(resolveProjectAsset(voice.id(), voice.origin(), sources)), voice.origin())).toList();
        var envelopes = project.envelopes().stream().map(envelope -> envelope.bytes() != null ? envelope
                : new Envelope(envelope.id(), envelope.name(), envelope.type(),
                        unsigned(resolveProjectAsset(envelope.id(), envelope.origin(), sources)), envelope.origin())).toList();
        var samples = project.samples().stream().map(sample -> sample.pcmBase64() != null ? sample
                : DacSample.of(sample.id(), sample.name(), sample.rate(),
                        resolveProjectAsset(sample.id(), sample.origin(), sources)).withOrigin(sample.origin())).toList();
        var resolved = project.withAssets(voices, envelopes, samples);
        ProjectFormat.validate(resolved);
        return resolved;
    }

    static byte[] resolveProjectAsset(String id, AssetOrigin origin, List<? extends StockAssets> sources) {
        StockRef ref = origin.ref();
        return resolve(id, ref, origin.transforms(), null, sources,
                ref.game().equals("s2") && ref.kind().equals("FM_VOICE"));
    }

    static List<Integer> unsigned(byte[] bytes) {
        return IntStream.range(0, bytes.length).map(i -> Byte.toUnsignedInt(bytes[i])).boxed().toList();
    }
}
