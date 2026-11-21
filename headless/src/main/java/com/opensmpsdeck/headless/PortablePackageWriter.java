package com.opensmpsdeck.headless;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.RetailSongOrigin;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.opensmpsdeck.headless.PackageManifest.*;

final class PortablePackageWriter {
    record Payload(PackageManifest manifest, Map<String, byte[]> entries, List<String> converted) {
        List<String> warnings() { return manifest.provenance().warnings(); }
    }

    private record Selection(Asset asset, AssetOrigin origin) {
        boolean referenced() { return asset.ref() != null; }
    }

    private final TreeSet<String> warnings = new TreeSet<>();
    private final List<String> converted = new ArrayList<>();
    private final List<Asset> assets = new ArrayList<>();
    private final Map<Kind, Map<String, byte[]>> files = new EnumMap<>(Kind.class);
    private final Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
    private final List<Fill> fills = new ArrayList<>();
    private boolean romBytes;

    static Payload build(StudioProject project, PortableOptions options) throws IOException {
        return new PortablePackageWriter().buildPackage(project, options);
    }

    static Payload write(StudioProject project, Path output, PortableOptions options) throws IOException {
        require(output.getFileName().toString().endsWith(".smps.zip"), "Portable package output must end in .smps.zip");
        var payload = build(project, options);
        PortableArchive.write(output, payload.entries(), archiveLimit(payload.manifest()));
        return payload;
    }

    static int archiveLimit(PackageManifest manifest) {
        return manifest.source() == null ? PortableArchive.WITHOUT_SOURCE : PortableArchive.WITH_SOURCE;
    }

    private Payload buildPackage(StudioProject project, PortableOptions options) throws IOException {
        var driver = PackageDriver.supported(options.driver());
        var info = Metadata.make(project.name(), options.id(), options.version(), options.authors(), options.license());
        var nativeProgram = project.nativeProgram();
        var arrangement = nativeProgram == null ? ProjectCompiler.compileArrangementLayout(project) : null;
        byte[] original = arrangement == null ? ProjectCompiler.compile(project) : arrangement.bytes();
        var compiled = new CompiledProject(original, project.envelopes(), project.samples(), nativeProgram,
                nativeProgram == null ? List.of() : project.voices());
        if (nativeProgram == null) driver.preflight(compiled);
        int base = nativeProgram == null ? 0 : nativeProgram.bankBase();
        int header = nativeProgram == null ? 0 : nativeProgram.headerOffset();
        int localCount = nativeProgram == null ? project.voices().size() : nativeProgram.localVoiceCount();
        int preview = nativeProgram == null ? ProjectRenderTiming.presentationCount(project) : nativeProgram.previewFrames();
        int localStart = localCount == 0 ? -1 : word(original, header) - base;
        require(localCount == 0 || localStart >= 0 && localStart + 25 * localCount <= original.length,
                "Local FM table extends outside track.bin");

        byte[] track = original.clone();
        var sourceVoices = new ArrayList<Voice>();
        var sourceEnvelopes = new ArrayList<Envelope>();
        var sourceSamples = new ArrayList<DacSample>();
        var envelopeKeys = new HashMap<String, String>();
        var sampleKeys = new HashMap<String, String>();
        byte[] global = nativeProgram == null || project.voices().isEmpty() ? null : new byte[project.voices().size() * 25];
        var globalKeys = new ArrayList<String>();

        for (int i = 0; i < project.voices().size(); i++) {
            var voice = project.voices().get(i);
            byte[] bytes = StockTable.bytes(voice.bytes());
            Integer offset = nativeProgram == null ? localStart + i * 25 : null;
            var selected = select(Kind.FM_VOICE, voice.id(), voice.name(), bytes, voice.origin(), offset);
            if (selected.referenced() && offset != null) fill(track, offset, selected.asset().key());
            if (global != null) {
                globalKeys.add(selected.asset().key());
                if (!selected.referenced()) System.arraycopy(bytes, 0, global, i * 25, 25);
            }
            sourceVoices.add(new Voice(voice.id(), voice.name(), selected.referenced() ? null : voice.bytes(), selected.origin()));
        }

        if (nativeProgram != null) {
            for (int slot = 0; slot < localCount; slot++) {
                int offset = localStart + slot * 25;
                addBankVoice(original, track, offset, "bank-local-" + slot, "Local FM " + slot);
            }
            // A retail bank can also hold voices shared by other headers in the
            // same bank. Only known exact 25-byte matches are replaced.
            for (int offset = 0; offset <= original.length - 25; offset++) {
                if (localCount > 0 && offset < localStart + localCount * 25 && offset + 25 > localStart) continue;
                byte[] bytes = Arrays.copyOfRange(original, offset, offset + 25);
                var ref = StockTable.match("FM_VOICE", bytes);
                if (ref == null) continue;
                addBankVoice(original, track, offset, "bank-voice-" + offset, "Bank FM " + offset);
                offset += 24;
            }
            if (options.includeSource() && !fills.isEmpty()) romBytes = true;
        }

        for (var envelope : project.envelopes()) {
            Kind kind = Kind.valueOf(envelope.type());
            var selected = select(kind, envelope.id(), envelope.name(), StockTable.bytes(envelope.bytes()), envelope.origin(), null);
            envelopeKeys.put(envelope.id(), selected.asset().key());
            sourceEnvelopes.add(new Envelope(envelope.id(), envelope.name(), envelope.type(),
                    selected.referenced() ? null : envelope.bytes(), selected.origin()));
        }
        for (var sample : project.samples()) {
            var selected = select(Kind.DAC_SAMPLE, sample.id(), sample.name(), sample.pcm(), sample.origin(), null);
            sampleKeys.put(sample.id(), selected.asset().key());
            sourceSamples.add(new DacSample(sample.id(), sample.name(), sample.rate(),
                    selected.referenced() ? null : sample.pcmBase64(), selected.origin()));
        }

        var envelopes = new ArrayList<EnvelopeBinding>();
        var dac = new TreeMap<Integer, DacBinding>();
        if (nativeProgram != null) {
            for (var slot : nativeProgram.envelopeSlots()) envelopes.add(new EnvelopeBinding(envelopeKeys.get(slot.assetId()), slot.slot()));
            for (var slot : nativeProgram.dacSlots())
                dac.put(slot.note(), new DacBinding(slot.note(), sampleKeys.get(slot.sampleId()), slot.rate()));
        } else {
            int psg = 0, mod = 0, note = DacSample.FIRST_NOTE;
            for (var envelope : project.envelopes())
                envelopes.add(new EnvelopeBinding(envelopeKeys.get(envelope.id()),
                        envelope.type().equals(Envelope.PSG_VOLUME) ? ++psg : ++mod));
            for (var sample : project.samples()) {
                dac.put(note, new DacBinding(note, sampleKeys.get(sample.id()), sample.rate()));
                note++;
            }
        }

        var retail = ProjectProvenance.retailOrigin(project);
        if (retail != null) {
            romBytes = true;
            warnings.add("RETAIL_SONG_DATA");
        }
        if (romBytes) warnings.add("ROM_DERIVED_DATA");
        var games = assets.stream().filter(a -> a.ref() != null).map(a -> a.ref().game()).distinct().sorted().toList();
        byte[] sourceBytes = options.includeSource()
                ? ProjectFiles.encodeSource(project.withAssets(sourceVoices, sourceEnvelopes, sourceSamples)) : null;
        var source = sourceBytes == null ? null : new Source("source.smps.json", StockTable.hash(sourceBytes), true);
        var globalVoices = global == null ? null : new GlobalVoices("voices/global.bin", StockTable.hash(global), globalKeys);

        List<Integer> relocations;
        boolean relocationsComplete;
        if (arrangement != null) {
            relocations = arrangement.relocations();
            relocationsComplete = true;
        } else {
            try {
                var traversal = NativeBankAnalysis.inspectForEditing(compiled);
                relocations = traversal.relocations();
                relocationsComplete = traversal.relocationsComplete();
            } catch (IllegalArgumentException unsupported) {
                relocations = headerRelocations(original, base, header);
                relocationsComplete = false;
            }
        }
        fills.sort(java.util.Comparator.comparingInt(Fill::offset));
        var bank = new Bank("embedded", "track.bin", StockTable.hash(track), StockTable.hash(original),
                base, header, localCount, preview, relocations, relocationsComplete, List.copyOf(fills));
        var manifest = new PackageManifest(4, "portable", driver.id(), info, generator(),
                new Provenance(romBytes, retail != null, retail(retail), List.copyOf(warnings)), new Requirements(!games.isEmpty(), games),
                new JsonObject(), bank, List.copyOf(assets), envelopes, List.copyOf(dac.values()), globalVoices, source);
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("manifest.json", manifest.encode());
        entries.put("track.bin", track);
        if (global != null) entries.put("voices/global.bin", global);
        for (Kind kind : List.of(Kind.PSG_VOLUME, Kind.MODULATION, Kind.DAC_SAMPLE))
            entries.putAll(files.getOrDefault(kind, Map.of()));
        if (sourceBytes != null) entries.put("source.smps.json", sourceBytes);
        PortablePackageReader.validateStructure(manifest, entries);
        return new Payload(manifest, entries, List.copyOf(converted));
    }

    private static List<Integer> headerRelocations(byte[] bytes, int base, int header) {
        var result = new ArrayList<Integer>();
        if (word(bytes, header) != 0 && word(bytes, header) >= base) result.add(header);
        int fm = Byte.toUnsignedInt(bytes[header + 2]);
        int psg = Byte.toUnsignedInt(bytes[header + 3]);
        for (int i = 0; i < Math.min(6, fm); i++) result.add(header + 6 + 4 * i);
        for (int i = 0; i < psg; i++) result.add(header + 6 + 4 * fm + 6 * i);
        return result;
    }

    static int word(byte[] data, int at) {
        return (data[at] & 255) | (data[at + 1] & 255) << 8;
    }

    private void addBankVoice(byte[] original, byte[] track, int offset, String id, String name) {
        byte[] bytes = Arrays.copyOfRange(original, offset, offset + 25);
        var selected = select(Kind.FM_VOICE, id, name, bytes, null, offset);
        if (selected.referenced()) fill(track, offset, selected.asset().key());
    }

    private void fill(byte[] track, int offset, String key) {
        Arrays.fill(track, offset, offset + 25, (byte) 0);
        fills.add(new Fill(offset, 25, key));
    }

    private Selection select(Kind kind, String id, String name, byte[] bytes, AssetOrigin declared, Integer bankOffset) {
        int ordinal = counts.merge(kind, 1, Integer::sum) - 1;
        String key = kind.prefix + "/" + ordinal;
        var origin = declared;
        if (declared == null) {
            var ref = StockTable.match(kind.name(), bytes);
            if (ref != null) {
                origin = AssetOrigin.stock(ref);
                warnings.add("UNDECLARED_STOCK_CONVERTED");
                converted.add(id);
            }
        }
        if (origin != null && origin.type().equals("stock") && origin.ref() != null) {
            var transforms = origin.transforms() == null ? List.<AssetOrigin.Transform>of() : origin.transforms();
            if (origin.ref().game().equals("s3k") && (!transforms.isEmpty() || origin.ref().sha256().equals(StockTable.hash(bytes)))) {
                var asset = new Asset(key, kind, id, name, Origin.of(origin), origin.ref(), transforms,
                        transforms.isEmpty() ? null : StockTable.hash(bytes), null, null, null);
                assets.add(asset);
                return new Selection(asset, origin);
            }
        }
        if (origin != null) {
            if (origin.ref() != null) origin = AssetOrigin.stock(null);
            switch (origin.type()) {
                case "stock" -> romBytes = true;
                case "source-audio" -> warnings.add("SOURCE_AUDIO");
                case "third-party" -> warnings.add("THIRD_PARTY");
                default -> {}
            }
        }
        String path = bankOffset == null ? kind.path(ordinal) : null;
        if (path != null && kind != Kind.FM_VOICE) files.computeIfAbsent(kind, keyKind -> new LinkedHashMap<>()).put(path, bytes);
        var asset = new Asset(key, kind, id, name, Origin.of(origin), null, null,
                null, path, path == null ? null : StockTable.hash(bytes), bankOffset);
        assets.add(asset);
        return new Selection(asset, origin);
    }

    private static Retail retail(RetailSongOrigin origin) {
        return origin == null ? null : new Retail(origin.game(), origin.songId(), origin.bankSha256());
    }

    private static Generator generator() throws IOException {
        try (var resource = PortablePackageWriter.class.getResourceAsStream("/openggf-audio.lock.json")) {
            if (resource == null) throw new IOException("Missing pinned audio revision");
            var lock = JsonParser.parseReader(new InputStreamReader(resource, StandardCharsets.UTF_8)).getAsJsonObject();
            return new Generator("OpenSMPSDeck", "0.1.0-SNAPSHOT", lock.get("revision").getAsString());
        }
    }
}
