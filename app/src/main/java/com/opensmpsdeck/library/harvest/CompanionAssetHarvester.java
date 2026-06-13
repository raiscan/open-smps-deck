package com.opensmpsdeck.library.harvest;

import com.opensmpsdeck.io.DacCodec;
import com.opensmpsdeck.io.EnvelopeListParser;
import com.opensmpsdeck.library.AddResult;
import com.opensmpsdeck.library.InstrumentLibrary;
import com.opensmpsdeck.library.InstrumentLibraryEntry;
import com.opensmpsdeck.library.SourceReference;
import com.opensmpsdeck.library.rip.SmpsDriverDefinition;
import com.opensmpsdeck.library.rip.SmpsRipConfig;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.PsgEnvelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class CompanionAssetHarvester {

    private CompanionAssetHarvester() {
    }

    public static HarvestResult harvest(
            InstrumentLibrary library,
            Path scanRoot,
            SmpsRipConfig.Section section,
            SmpsDriverDefinition driver,
            SourceReference baseSource) {
        Counter counter = new Counter();
        Instant now = Instant.now();

        harvestEnvelopes(library, section.resolve("VolEnv"), false,
                scanRoot, section.extension(), driver, baseSource, now, counter);
        harvestEnvelopes(library, section.resolve("ModEnv"), true,
                scanRoot, section.extension(), driver, baseSource, now, counter);
        harvestDac(library, section.resolve("DAC"), scanRoot, section.extension(), driver, baseSource, now, counter);
        harvestInsSet(library, section.resolve("GlobalInsLib"), section.extension(),
                scanRoot, driver, baseSource, now, counter);

        return new HarvestResult(
                counter.addedCount,
                counter.duplicateCount,
                counter.skippedCount,
                List.copyOf(counter.warnings));
    }

    private static void harvestEnvelopes(
            InstrumentLibrary library,
            Path companion,
            boolean modulation,
            Path scanRoot,
            String extension,
            SmpsDriverDefinition driver,
            SourceReference baseSource,
            Instant now,
            Counter counter) {
        if (companion == null) {
            return;
        }
        if (!Files.exists(companion)) {
            skip(counter, companion, "missing envelope companion");
            return;
        }
        try {
            List<PsgEnvelope> envelopes = EnvelopeListParser.parse(Files.readAllBytes(companion));
            if (envelopes.isEmpty()) {
                skip(counter, companion, "no envelopes parsed");
                return;
            }
            for (int i = 0; i < envelopes.size(); i++) {
                PsgEnvelope envelope = envelopes.get(i);
                SourceReference source = source(scanRoot, extension, driver, baseSource, companion, Integer.toString(i));
                InstrumentLibraryEntry entry = modulation
                        ? InstrumentLibraryEntry.modEnvelope(envelope.getName(), envelope.getData(), source)
                        : InstrumentLibraryEntry.psgEnvelope(envelope.getName(), envelope.getData(), source);
                count(library.addOrMerge(entry, now), counter);
            }
        } catch (IOException | RuntimeException e) {
            skip(counter, companion, "failed to harvest envelope companion: " + e.getMessage());
        }
    }

    private static void harvestDac(
            InstrumentLibrary library,
            Path companion,
            Path scanRoot,
            String extension,
            SmpsDriverDefinition driver,
            SourceReference baseSource,
            Instant now,
            Counter counter) {
        if (companion == null) {
            return;
        }
        if (!Files.exists(companion)) {
            skip(counter, companion, "missing DAC companion");
            return;
        }
        try {
            List<DacIniParser.Entry> entries = DacIniParser.parse(companion);
            if (entries.isEmpty()) {
                skip(counter, companion, "no DAC entries parsed");
                return;
            }
            Path configDir = companion.toAbsolutePath().normalize().getParent();
            for (DacIniParser.Entry entry : entries) {
                byte[] sample = loadDacFile(configDir, entry);
                if (sample == null) {
                    skip(counter, companion, "missing DAC sample for " + hexId(entry.id()));
                    continue;
                }
                String displayName = baseName(entry.file());
                SourceReference source = source(scanRoot, extension, driver, baseSource, companion, hexId(entry.id()));
                InstrumentLibraryEntry libraryEntry = InstrumentLibraryEntry.dacSample(
                        displayName, sample, entry.rate(), entry.compressionLabel(),
                        entry.pan(), entry.param1(), entry.param2(), hexId(entry.id()), source);
                count(library.addOrMerge(libraryEntry, now), counter);
            }
        } catch (IOException | RuntimeException e) {
            skip(counter, companion, "failed to harvest DAC companion: " + e.getMessage());
        }
    }

    private static void harvestInsSet(
            InstrumentLibrary library,
            Path companion,
            String extension,
            Path scanRoot,
            SmpsDriverDefinition driver,
            SourceReference baseSource,
            Instant now,
            Counter counter) {
        if (companion == null) {
            return;
        }
        if (!Files.exists(companion)) {
            skip(counter, companion, "missing FM instrument companion");
            return;
        }
        try {
            List<FmVoice> voices = InsSetVoiceParser.parse(companion, extension, driver);
            if (voices.isEmpty()) {
                skip(counter, companion, "no supported FM voices parsed");
                return;
            }
            for (int i = 0; i < voices.size(); i++) {
                FmVoice voice = voices.get(i);
                SourceReference source = source(
                        scanRoot, extension, driver, baseSource, companion, String.format("%02X", i));
                InstrumentLibraryEntry entry = InstrumentLibraryEntry.fmVoice(
                        companion.getFileName() + " #" + String.format("%02X", i),
                        voice.getData(),
                        source);
                count(library.addOrMerge(entry, now), counter);
            }
        } catch (IOException | RuntimeException e) {
            skip(counter, companion, "failed to harvest FM instrument companion: " + e.getMessage());
        }
    }

    private static byte[] loadDacFile(Path configDir, DacIniParser.Entry entry) throws IOException {
        String normalized = entry.file().replace('\\', '/');
        String baseName = Path.of(normalized).getFileName().toString();

        Path uncompressed = configDir.resolve("DAC").resolve("uncompressed").resolve(baseName).normalize();
        if (Files.exists(uncompressed)) {
            return Files.readAllBytes(uncompressed);
        }

        Path direct = resolvePossiblyAbsolute(configDir, normalized);
        if (Files.exists(direct)) {
            byte[] raw = Files.readAllBytes(direct);
            return entry.isDpcm() ? DacCodec.decompressDpcm(raw) : raw;
        }

        Path dacDir = configDir.resolve("DAC").resolve(baseName).normalize();
        if (Files.exists(dacDir)) {
            byte[] raw = Files.readAllBytes(dacDir);
            return entry.isDpcm() ? DacCodec.decompressDpcm(raw) : raw;
        }

        return null;
    }

    private static Path resolvePossiblyAbsolute(Path baseDir, String value) {
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return baseDir.resolve(path).normalize();
    }

    private static void count(AddResult result, Counter counter) {
        if (result.added()) {
            counter.addedCount++;
        } else {
            counter.duplicateCount++;
        }
    }

    private static void skip(Counter counter, Path companion, String reason) {
        counter.skippedCount++;
        counter.warnings.add(companion.getFileName() + ": " + reason);
    }

    private static SourceReference source(
            Path scanRoot,
            String extension,
            SmpsDriverDefinition driver,
            SourceReference base,
            Path companion,
            String originalIndexOrId) {
        String root = scanRoot == null
                ? value(base == null ? null : base.scanRoot())
                : scanRoot.toString();
        return new SourceReference(
                root,
                value(base == null ? null : base.driverFamily()),
                value(base == null ? null : base.gameName()),
                value(base == null ? null : base.variantPath()),
                firstNonBlank(base == null ? null : base.configExtension(), extension),
                value(base == null ? null : base.sourceSongFile()),
                companionSource(scanRoot, base, companion),
                originalIndexOrId,
                driver == null ? value(base == null ? null : base.driverSummary()) : driver.summary());
    }

    private static String companionSource(Path scanRoot, SourceReference base, Path companion) {
        Path root = scanRoot;
        if (root == null && base != null && base.scanRoot() != null && !base.scanRoot().isBlank()) {
            root = Path.of(base.scanRoot());
        }
        Path normalizedCompanion = companion.toAbsolutePath().normalize();
        if (root != null) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (normalizedCompanion.startsWith(normalizedRoot)) {
                return slashPath(normalizedRoot.relativize(normalizedCompanion));
            }
        }
        return companion.getFileName().toString();
    }

    private static String slashPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : value(second);
    }

    private static String hexId(int id) {
        return String.format("%02X", id & 0xFF);
    }

    private static String baseName(String file) {
        String normalized = file.replace('\\', '/');
        String name = Path.of(normalized).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static final class Counter {
        private int addedCount;
        private int duplicateCount;
        private int skippedCount;
        private final List<String> warnings = new ArrayList<>();
    }
}
