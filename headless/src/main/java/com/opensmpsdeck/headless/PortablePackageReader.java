package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.Voice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.opensmpsdeck.headless.PackageManifest.*;

final class PortablePackageReader {
    static final Set<String> WARNING_CODES = Set.of("RETAIL_SONG_DATA", "ROM_DERIVED_DATA", "DERIVED_FROM_RETAIL_SONG", "TEMPO_APPROXIMATED",
            "UNDECLARED_STOCK_CONVERTED", "SOURCE_AUDIO", "THIRD_PARTY", "UNKNOWN_ROM_IMAGE", "DPCM_INEXACT");

    record Package(PackageManifest manifest, Map<String, byte[]> entries) {}

    private PortablePackageReader() {}

    static Package read(Path file) throws IOException {
        var entries = PortableArchive.read(file);
        var manifest = PackageManifest.decode(required(entries, "manifest.json"));
        validateStructure(manifest, entries);
        return new Package(manifest, entries);
    }

    static void validateStructure(PackageManifest manifest, Map<String, byte[]> entries) throws IOException {
        require(manifest.schemaVersion() == 4 && manifest.profile().equals("portable"), "Expected portable schema 4");
        String driver = manifest.driver();
        var dialect = PackageDriver.supported(driver);
        Metadata.validate(manifest.packageInfo());
        nonblank(manifest.generator().tool(), "generator.tool");
        nonblank(manifest.generator().version(), "generator.version");
        require(manifest.generator().openggfAudio().matches("[0-9a-f]{40}"), "Invalid generator.openggfAudio revision");
        validateWarnings(manifest.provenance());
        validateDriverOptions(manifest);
        var bank = manifest.bank();
        boolean patch = bank.form().equals("patch");
        require(patch || bank.form().equals("embedded"), "Unsupported bank form: " + bank.form());
        String bankPath = patch ? RetailBankPatch.PATH : "track.bin";
        require(bank.path().equals(bankPath), "bank.path must be " + bankPath);
        if (patch) validatePatchManifest(manifest);
        byte[] stored = required(entries, bank.path());
        validateBank(dialect, bank, stored, patch);
        var global = manifest.globalVoices();
        if (dialect instanceof ClassicPackageDriver) {
            require(bank.headerOffset() == 0, driver + ": bank.headerOffset must be 0");
            require(global == null, driver + ": global FM voices are unsupported");
        }
        byte[] globalBytes = null;
        var globalIndex = new HashMap<String, Integer>();
        if (global != null) {
            require(global.path().equals("voices/global.bin"), "Invalid globalVoices.path");
            range(global.voices().size(), 1, 128, "global voice count");
            globalBytes = required(entries, global.path());
            require(globalBytes.length == 25 * global.voices().size(), "Invalid global voice table length");
            StockAssets.verifyHash(global.path(), global.sha256(), globalBytes);
            for (int i = 0; i < global.voices().size(); i++) {
                require(globalIndex.put(global.voices().get(i), i) == null, "Duplicate global voice asset key");
            }
        }
        var offsets = new HashSet<Integer>();
        var fillKeys = new HashSet<String>();
        for (var fill : bank.fills()) {
            offsets.add(fill.offset());
            fillKeys.add(fill.asset());
        }
        var expectedFiles = new ArrayList<>(List.of("manifest.json", bankPath));
        if (global != null) expectedFiles.add(global.path());
        var paths = new EnumMap<Kind, List<String>>(Kind.class);
        var byKey = new HashMap<String, Asset>();
        var ordinals = new EnumMap<Kind, Integer>(Kind.class);
        var games = new TreeSet<String>();
        if (patch) games.add(manifest.provenance().derivedFromRetailSong().game());
        int dacBytes = 0;
        for (var asset : manifest.assets()) {
            var kind = asset.kind();
            require(kind != null, "Unknown asset kind for " + asset.key());
            require(!(dialect instanceof ClassicPackageDriver) || kind != Kind.MODULATION,
                    driver + ": MODULATION envelopes are unsupported");
            int ordinal = ordinals.merge(kind, 1, Integer::sum) - 1;
            require(asset.key().equals(kind.prefix + "/" + ordinal), "Asset keys must be canonical: " + asset.key());
            byKey.put(asset.key(), asset);
            nonblank(asset.id(), asset.key() + " id");
            nonblank(asset.name(), asset.key() + " name");
            var origin = new AssetOrigin(asset.origin().type(), asset.ref(), asset.transforms(),
                    asset.origin().license(), asset.origin().attribution());
            origin.validateKind(kind.name());
            if (asset.origin().license() != null) Metadata.validateLicense(asset.origin().license());
            Integer globalSlot = globalIndex.get(asset.key());
            if (asset.ref() != null) {
                require(asset.ref().game().equals(driver), asset.key() + " reference game must match package driver");
                if (dialect instanceof ClassicPackageDriver && asset.ref().table().equals("local-fm"))
                    require(dialect.musicIds().contains(asset.ref().songId()), asset.key() + " has unknown " + driver + " music ID");
                if (asset.resultSha256() != null) hash(asset.resultSha256(), asset.key() + " resultSha256");
                games.add(asset.ref().game());
                if (globalSlot != null) {
                    require(kind == Kind.FM_VOICE && allZero(globalBytes, globalSlot * 25, 25),
                            "Global reference must name a zeroed FM voice slot");
                } else if (kind == Kind.FM_VOICE) {
                    require(fillKeys.contains(asset.key()), "Unbound FM reference " + asset.key());
                }
            } else if (asset.bankOffset() != null) {
                require(!patch, "Patch bank assets cannot use bankOffset");
                int offset = asset.bankOffset();
                require(kind == Kind.FM_VOICE && globalSlot == null, "Only local FM voices can use bankOffset");
                range(offset, 0, stored.length - 25, asset.key() + " bankOffset");
                require(offsets.add(offset), "Duplicate bank voice offset " + offset);
            } else {
                require(asset.path().equals(kind.path(ordinal)), "Noncanonical path for asset " + asset.key());
                if (kind == Kind.FM_VOICE) require(globalSlot != null, "Unbound global FM asset " + asset.key());
                else {
                    require(globalSlot == null, "Global voices must refer to FM assets");
                    paths.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(asset.path());
                }
                byte[] payload = embedded(asset, entries, globalIndex, stored);
                kind.validateLength(asset.key(), payload.length);
                if (kind == Kind.PSG_VOLUME && dialect instanceof ClassicPackageDriver classic)
                    ClassicPackageBank.validateEnvelope(classic, new Envelope(asset.id(), asset.name(), kind.name(), StockAssets.unsigned(payload)));
                StockAssets.verifyHash(asset.key(), asset.sha256(), payload);
                if (kind == Kind.DAC_SAMPLE) dacBytes += payload.length;
            }
        }
        range(ordinals.getOrDefault(Kind.PSG_VOLUME, 0), 0, 255, "PSG asset count");
        range(ordinals.getOrDefault(Kind.MODULATION, 0), 0, 127, "modulation asset count");
        range(ordinals.getOrDefault(Kind.DAC_SAMPLE, 0), 0, DacSample.MAX_SAMPLES, "DAC asset count");
        range(dacBytes, 0, DacSample.MAX_TOTAL_PCM_BYTES, "total DAC bytes");
        require(manifest.requires().stockAssets() == !games.isEmpty()
                && manifest.requires().games().equals(List.copyOf(games)), "requires does not match stock references or retail base");
        require(byKey.keySet().containsAll(globalIndex.keySet()), "Undefined global voice asset");
        for (String key : fillKeys) {
            var asset = byKey.get(key);
            require(asset != null && asset.kind() == Kind.FM_VOICE && asset.ref() != null,
                    "Bank fill must refer to an FM_VOICE reference: " + key);
        }
        if (!patch) validateLocalOffsets(dialect, bank, stored, offsets);
        validateBindings(manifest, byKey);
        for (Kind kind : List.of(Kind.PSG_VOLUME, Kind.MODULATION, Kind.DAC_SAMPLE))
            expectedFiles.addAll(paths.getOrDefault(kind, List.of()));
        if (manifest.source() != null) {
            var source = manifest.source();
            require(source.path().equals("source.smps.json"), "Invalid source path");
            var data = required(entries, source.path());
            StockAssets.verifyHash(source.path(), source.sha256(), data);
            var project = ProjectFormat.read(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString());
            if (!source.redacted()) ProjectFormat.requireResolved(project);
            if (source.redacted()) {
                for (var voice : project.voices())
                    verifyRedacted(voice.origin(), voice.bytes() == null, voice.id());
                for (var envelope : project.envelopes())
                    verifyRedacted(envelope.origin(), envelope.bytes() == null, envelope.id());
                for (var sample : project.samples())
                    verifyRedacted(sample.origin(), sample.pcmBase64() == null, sample.id());
            }
            expectedFiles.add(source.path());
        }
        if (entries.containsKey("LICENSE.txt")) {
            require(manifest.packageInfo().license() != null, "LICENSE.txt requires package.license");
            validateLicenseFile(entries.get("LICENSE.txt"), manifest.packageInfo());
            expectedFiles.add("LICENSE.txt");
        }
        require(List.copyOf(entries.keySet()).equals(expectedFiles), "Unexpected, missing or out-of-order package entries");
        require(entries.size() <= PortableArchive.MAX_ENTRIES, "Too many package entries");
        int total = 0;
        for (var entry : entries.entrySet()) {
            range(entry.getValue().length, 1, PortableArchive.entryLimit(entry.getKey()), entry.getKey() + " length");
            total += entry.getValue().length;
        }
        require(total <= PortablePackageWriter.archiveLimit(manifest), "Package exceeds payload limit");
    }

    private static void verifyRedacted(AssetOrigin origin, boolean missing, String id) {
        if (origin != null && origin.ref() != null)
            require(missing, "Redacted source embeds referenced asset " + id);
    }

    private static void validateLicenseFile(byte[] bytes, Metadata.PackageInfo info) throws IOException {
        var lines = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().lines().toList();
        var expected = new ArrayList<String>();
        expected.add(info.license());
        expected.addAll(info.authors());
        require(lines.equals(expected), "LICENSE.txt must contain the SPDX expression followed by the authors");
    }

    private static void validateWarnings(Provenance value) {
        require(WARNING_CODES.containsAll(value.warnings())
                        && value.warnings().equals(List.copyOf(new TreeSet<>(value.warnings()))),
                "provenance.warnings must contain sorted unique known warning codes");
        var retail = value.derivedFromRetailSong();
        if (retail != null) new com.opensmpsdeck.project.RetailSongOrigin("retail-song",
                retail.game(), retail.songId(), retail.bankSha256());
    }

    private static void validatePatchManifest(PackageManifest manifest) {
        require(manifest.driver().equals("s3k"), "Patch banks are supported only for S3K native v5 banks");
        var provenance = manifest.provenance();
        var retail = provenance.derivedFromRetailSong();
        require(retail != null && retail.game().equals(manifest.driver()),
                "Patch bank requires provenance.derivedFromRetailSong for its driver");
        require(!provenance.containsRetailSongData() && !provenance.warnings().contains("RETAIL_SONG_DATA")
                        && provenance.warnings().contains("DERIVED_FROM_RETAIL_SONG"),
                "Patch bank must declare DERIVED_FROM_RETAIL_SONG without RETAIL_SONG_DATA");
        require(manifest.source() == null, "Patch bank must omit source");
        require(manifest.bank().fills().isEmpty(), "Patch bank must have empty fills");
    }

    private static void validateDriverOptions(PackageManifest manifest) {
        var options = manifest.driverOptions();
        if (manifest.driver().equals("s3k")) {
            require(options.isEmpty(), "S3K driverOptions must be empty");
        } else {
            require(options.keySet().equals(Set.of("speedUpTempo")), "Expected driverOptions.speedUpTempo");
            var tempo = options.get("speedUpTempo");
            if (!tempo.isJsonNull()) {
                require(tempo.isJsonPrimitive() && tempo.getAsJsonPrimitive().isNumber(), "speedUpTempo must be an integer");
                try {
                    range(tempo.getAsBigDecimal().intValueExact(), 0, 255, "speedUpTempo");
                } catch (ArithmeticException invalid) {
                    throw new IllegalArgumentException("speedUpTempo must be an integer in 0..255", invalid);
                }
            }
        }
    }

    private static void validateBank(PackageDriver driver, Bank bank, byte[] stored, boolean patch) {
        if (!patch) range(stored.length, 1, PortableArchive.MAX_TRACK, "track.bin length");
        hash(bank.sha256(), "bank.sha256");
        hash(bank.assembledSha256(), "bank.assembledSha256");
        StockAssets.verifyHash(bank.path(), bank.sha256(), stored);
        if (!patch && bank.fills().isEmpty())
            require(bank.sha256().equals(bank.assembledSha256()), "A bank without fills must have matching hashes");
        int length = patch ? RetailBankPatch.targetLength(stored) : stored.length;
        range(bank.base(), 0, 65535, "bank.base");
        driver.validateBase(bank.base());
        range(bank.headerOffset(), 0, length - (patch ? 6 : 1), "bank.headerOffset");
        range(bank.localVoiceCount(), 0, 128, "bank.localVoiceCount");
        S3kRenderer.validatePresentations(bank.previewFrames());
        int previous = -1;
        for (int offset : bank.relocations()) {
            range(offset, 0, length - 2, "relocation offset");
            require(offset > previous, "Relocations must be sorted and unique");
            previous = offset;
        }
        int end = 0;
        for (var fill : bank.fills()) {
            require(fill.length() == 25, "A bank fill must be 25 bytes");
            range(fill.offset(), end, stored.length - 25, "fill offset");
            require(allZero(stored, fill.offset(), 25), "Nonzero bytes in bank fill at " + fill.offset());
            end = fill.offset() + 25;
        }
    }

    private static void validateLocalOffsets(PackageDriver driver, Bank bank, byte[] stored, Set<Integer> offsets) {
        if (driver instanceof ClassicPackageDriver)
            require(offsets.size() == bank.localVoiceCount(), driver.id() + ": FM assets must be local table slots");
        if (bank.localVoiceCount() > 0) {
            int start = localVoiceStart(driver, bank, stored);
            for (int i = 0; i < bank.localVoiceCount(); i++)
                require(offsets.contains(start + i * 25), "Missing local FM asset at " + (start + i * 25));
        }
        int previous = -25;
        for (int offset : new TreeSet<>(offsets)) {
            require(offset >= previous + 25, "Overlapping bank FM assets");
            previous = offset;
        }
    }

    private static int localVoiceStart(PackageDriver driver, Bank bank, byte[] track) {
        require(bank.headerOffset() + 1 < track.length, "Missing voice pointer");
        int start = driver.word(track, bank.headerOffset()) - bank.base();
        range(start, 0, track.length - 25 * bank.localVoiceCount(), "local voice table offset");
        return start;
    }

    private static void validateBindings(PackageManifest manifest, Map<String, Asset> byKey) {
        var psg = new HashSet<Integer>();
        var mod = new HashSet<Integer>();
        for (var binding : manifest.envelopeBindings()) {
            var asset = byKey.get(binding.asset());
            require(asset != null && (asset.kind() == Kind.PSG_VOLUME || asset.kind() == Kind.MODULATION),
                    "Invalid envelope binding asset " + binding.asset());
            boolean volume = asset.kind() == Kind.PSG_VOLUME;
            range(binding.slot(), 1, volume ? 255 : 127, "envelope slot");
            require((volume ? psg : mod).add(binding.slot()), "Duplicate envelope slot " + binding.slot());
        }
        int previous = 0x80;
        for (var binding : manifest.dacBindings()) {
            var asset = byKey.get(binding.asset());
            require(asset != null && asset.kind() == Kind.DAC_SAMPLE, "Invalid DAC binding asset " + binding.asset());
            range(binding.note(), previous + 1, 0xDF, "DAC binding note (sorted and unique)");
            range(binding.rate(), 0, 255, "DAC rate");
            previous = binding.note();
        }
    }

    static CompiledProject assemble(Package artifact, List<? extends StockAssets> roms) {
        var manifest = artifact.manifest();
        var driver = PackageDriver.supported(manifest.driver());
        var entries = artifact.entries();
        var bank = manifest.bank();
        byte[] stored = entries.get(bank.path());
        boolean patch = bank.form().equals("patch");
        byte[] track = patch
                ? RetailBankPatch.apply(stored, RetailBankPatch.resolveBase(manifest.provenance().derivedFromRetailSong(), roms))
                : stored.clone();
        if (patch) {
            StockAssets.verifyHash("Assembled bank", bank.assembledSha256(), track);
            if (bank.localVoiceCount() > 0) localVoiceStart(driver, bank, track);
        }
        var global = manifest.globalVoices();
        var globalIndex = globalIndex(manifest);
        var payloads = new HashMap<String, byte[]>();
        var envelopes = new ArrayList<Envelope>();
        var samples = new ArrayList<DacSample>();
        for (var asset : manifest.assets()) {
            var kind = asset.kind();
            byte[] bytes = asset.ref() == null ? embedded(asset, entries, globalIndex, stored)
                    : StockAssets.resolve(asset.key() + " (" + asset.id() + ")", asset.ref(), asset.transforms(), asset.resultSha256(), roms);
            kind.validateLength(asset.key(), bytes.length);
            payloads.put(asset.key(), bytes);
            switch (kind) {
                case MODULATION, PSG_VOLUME -> envelopes.add(new Envelope(asset.key(), asset.name(), kind.name(), StockAssets.unsigned(bytes)));
                case DAC_SAMPLE -> samples.add(DacSample.of(asset.key(), asset.name(), 0, bytes));
                case FM_VOICE -> {}
            }
        }
        DacSample.validateAll(samples);
        for (var fill : bank.fills()) System.arraycopy(payloads.get(fill.asset()), 0, track, fill.offset(), 25);
        if (!patch) StockAssets.verifyHash("Assembled bank", bank.assembledSha256(), track);
        var globalVoices = new ArrayList<Voice>();
        if (global != null) {
            for (String key : global.voices()) globalVoices.add(new Voice(key, key, StockAssets.unsigned(payloads.get(key))));
        }

        var nativeBank = NativeProgram.of(bank.base(), bank.headerOffset(), bank.localVoiceCount(), bank.previewFrames(),
                track, manifest.envelopeBindings().stream().map(binding -> new NativeProgram.EnvelopeSlot(binding.asset(), binding.slot())).toList(),
                manifest.dacBindings().stream().map(binding -> new NativeProgram.DacSlot(binding.note(), binding.asset(), binding.rate())).toList());
        return new CompiledProject(track, envelopes, samples, nativeBank, globalVoices);
    }

    static TreeSet<String> warnings(Package artifact) throws IOException {
        var manifest = artifact.manifest();
        var provenance = manifest.provenance();
        var warnings = new TreeSet<>(provenance.warnings());
        boolean patch = manifest.bank().form().equals("patch");
        var bank = artifact.entries().get(manifest.bank().path());
        boolean retail = !patch && (provenance.containsRetailSongData() || provenance.derivedFromRetailSong() != null
                || StockTable.matchBank(bank) != null || StockTable.matchBankHash(manifest.bank().assembledSha256()) != null);
        boolean stock = provenance.containsRomDerivedData();
        var globalIndex = globalIndex(manifest);
        for (var asset : manifest.assets()) {
            switch (asset.origin().type()) {
                case "stock" -> stock |= asset.ref() == null;
                case "source-audio" -> warnings.add("SOURCE_AUDIO");
                case "third-party" -> warnings.add("THIRD_PARTY");
                default -> {
                    if (asset.ref() == null) stock |= StockTable.match(asset.kind().name(),
                            embedded(asset, artifact.entries(), globalIndex, bank)) != null;
                }
            }
        }
        if (manifest.source() != null) {
            var source = ProjectFormat.read(StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(artifact.entries().get("source.smps.json"))).toString());
            retail |= ProjectProvenance.retailOrigin(source) != null;
            var project = source.withAssets(source.voices().stream().filter(v -> v.bytes() != null).toList(),
                    source.envelopes().stream().filter(e -> e.bytes() != null).toList(),
                    source.samples().stream().filter(s -> s.pcmBase64() != null).toList());
            warnings.addAll(ProjectProvenance.detect(project).warnings());
        }
        if (retail) warnings.add("RETAIL_SONG_DATA");
        if (stock || retail) warnings.add("ROM_DERIVED_DATA");
        return warnings;
    }

    private static Map<String, Integer> globalIndex(PackageManifest manifest) {
        var slots = new HashMap<String, Integer>();
        if (manifest.globalVoices() != null) {
            var voices = manifest.globalVoices().voices();
            for (int i = 0; i < voices.size(); i++) slots.put(voices.get(i), i);
        }
        return slots;
    }

    private static byte[] embedded(Asset asset, Map<String, byte[]> entries, Map<String, Integer> globalIndex, byte[] bank) {
        if (asset.bankOffset() != null) return Arrays.copyOfRange(bank, asset.bankOffset(), asset.bankOffset() + 25);
        var file = required(entries, asset.path());
        if (asset.kind() != Kind.FM_VOICE) return file;
        int start = globalIndex.get(asset.key()) * 25;
        return Arrays.copyOfRange(file, start, start + 25);
    }

    private static boolean allZero(byte[] bytes, int start, int length) {
        for (int i = start; i < start + length; i++) if (bytes[i] != 0) return false;
        return true;
    }

    private static byte[] required(Map<String, byte[]> entries, String path) {
        byte[] bytes = entries.get(path);
        require(bytes != null, "Missing package entry " + path);
        return bytes;
    }
}
