package com.opensmpsdeck.library.scan;

import com.opensmpsdeck.io.EnvelopeListParser;
import com.opensmpsdeck.io.SmpsImporter;
import com.opensmpsdeck.library.AddResult;
import com.opensmpsdeck.library.InstrumentAssetKind;
import com.opensmpsdeck.library.InstrumentLibrary;
import com.opensmpsdeck.library.InstrumentLibraryEntry;
import com.opensmpsdeck.library.SourceReference;
import com.opensmpsdeck.library.harvest.CompanionAssetHarvester;
import com.opensmpsdeck.library.harvest.DacIniParser;
import com.opensmpsdeck.library.harvest.HarvestResult;
import com.opensmpsdeck.library.harvest.InsSetVoiceParser;
import com.opensmpsdeck.library.rip.CoordFlagDefinition;
import com.opensmpsdeck.library.rip.DialectCapability;
import com.opensmpsdeck.library.rip.DialectCapabilityClassifier;
import com.opensmpsdeck.library.rip.SmpsDriverDefinition;
import com.opensmpsdeck.library.rip.SmpsRipConfig;
import com.opensmpsdeck.library.rip.SmpsRipConfigParser;
import com.opensmpsdeck.model.DacSample;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmpsdeck.model.Song;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class InstrumentLibraryScanner {

    private static final String CONFIG_INI = "config.ini";

    public ScanSummary scan(Path root, InstrumentLibrary library) throws IOException {
        Path scanRoot = root.toAbsolutePath().normalize();
        Counter counter = new Counter(library);
        Set<Path> visitedDirectories = new HashSet<>();

        Files.walkFileTree(scanRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path realPath;
                try {
                    realPath = dir.toRealPath();
                } catch (IOException e) {
                    counter.failure(dir, "failed to resolve directory: " + message(e));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!visitedDirectories.add(realPath)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                Path config = dir.resolve(CONFIG_INI);
                if (Files.isRegularFile(config)) {
                    counter.configDirectoriesFound++;
                    scanConfigDirectory(scanRoot, config, library, counter);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    counter.filesVisited++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                counter.failure(file, "failed to visit file: " + message(exc));
                return FileVisitResult.CONTINUE;
            }
        });

        return counter.summary(library);
    }

    private void scanConfigDirectory(
            Path scanRoot,
            Path configPath,
            InstrumentLibrary library,
            Counter counter) {
        SmpsRipConfig config;
        try {
            config = SmpsRipConfigParser.parse(configPath);
        } catch (IOException | RuntimeException e) {
            counter.failure(configPath, "failed to parse config.ini: " + message(e));
            return;
        }

        for (SmpsRipConfig.Section section : config.sections().values()) {
            ParsedSection parsed = parseSection(configPath, section, counter);
            if (parsed == null) {
                continue;
            }

            SourceReference baseSource = baseSource(scanRoot, section.directory(), section, parsed.driver());
            harvestSection(scanRoot, configPath, section, parsed.driver(), baseSource, library, counter);
            scanSongs(scanRoot, configPath, section, parsed.driver(), baseSource, library, counter);
        }
    }

    private ParsedSection parseSection(Path configPath, SmpsRipConfig.Section section, Counter counter) {
        SmpsDriverDefinition driver = null;
        Path driverPath = firstResolved(section, "driver", "def");
        if (driverPath != null) {
            try {
                driver = SmpsDriverDefinition.parse(driverPath);
            } catch (IOException | RuntimeException e) {
                counter.failure(driverPath, "failed to parse driver definition: " + message(e));
                return null;
            }
        }

        LinkedHashSet<Path> coordFlagPaths = new LinkedHashSet<>();
        addResolved(coordFlagPaths, section, "commands", "cmds", "cflags", "coordflags");
        for (Path coordFlagPath : coordFlagPaths) {
            try {
                CoordFlagDefinition.parse(coordFlagPath);
            } catch (IOException | RuntimeException e) {
                counter.failure(coordFlagPath, "failed to parse coordination flags: " + message(e));
                return null;
            }
        }

        return new ParsedSection(configPath, driver);
    }

    private void harvestSection(
            Path scanRoot,
            Path configPath,
            SmpsRipConfig.Section section,
            SmpsDriverDefinition driver,
            SourceReference baseSource,
            InstrumentLibrary library,
            Counter counter) {
        Map<InstrumentAssetKind, Integer> before = countsByKind(library);
        Map<InstrumentAssetKind, Integer> companionCounts = companionCountsByKind(section, driver);
        HarvestResult result = CompanionAssetHarvester.harvest(library, scanRoot, section, driver, baseSource);
        Map<InstrumentAssetKind, Integer> after = countsByKind(library);

        counter.assetOnlyFoldersHarvested++;
        counter.newAssets += result.addedCount();
        counter.duplicateAssets += result.duplicateCount();
        addDeltas(counter.newAssetsByKind, before, after);
        addCompanionDuplicateCounts(counter.duplicateAssetsByKind, companionCounts, before, after);
        for (String warning : result.warnings()) {
            counter.failure(configPath, warning);
        }
    }

    private void scanSongs(
            Path scanRoot,
            Path configPath,
            SmpsRipConfig.Section section,
            SmpsDriverDefinition driver,
            SourceReference baseSource,
            InstrumentLibrary library,
            Counter counter) {
        List<Path> songCandidates;
        try {
            songCandidates = songCandidates(section);
        } catch (IOException e) {
            counter.failure(section.directory(), "failed to list song candidates: " + message(e));
            return;
        }

        for (Path songPath : songCandidates) {
            DialectCapability capability = DialectCapabilityClassifier.classify(section.extension(), driver, true);
            if (capability == DialectCapability.UNSUPPORTED) {
                counter.unsupportedSongDialects++;
                continue;
            }
            if (capability != DialectCapability.FULL_IMPORT) {
                continue;
            }

            counter.fullSongImportsAttempted++;
            try {
                Song song = new SmpsImporter().importFile(songPath.toFile());
                SourceReference source = songSource(scanRoot, songPath, baseSource);
                importSongAssets(song, source, library, counter);
                counter.fullSongImportsSucceeded++;
            } catch (IOException | RuntimeException e) {
                counter.failure(songPath, "failed to import song: " + message(e));
            }
        }
    }

    private void importSongAssets(
            Song song,
            SourceReference source,
            InstrumentLibrary library,
            Counter counter) {
        Instant now = Instant.now();
        for (int i = 0; i < song.getVoiceBank().size(); i++) {
            FmVoice voice = song.getVoiceBank().get(i);
            SourceReference indexed = withIndex(source, Integer.toString(i));
            countAdd(library.addOrMerge(
                    InstrumentLibraryEntry.fmVoice(voice.getName(), voice.getData(), indexed), now), counter);
        }
        for (int i = 0; i < song.getPsgEnvelopes().size(); i++) {
            PsgEnvelope envelope = song.getPsgEnvelopes().get(i);
            SourceReference indexed = withIndex(source, Integer.toString(i));
            countAdd(library.addOrMerge(
                    InstrumentLibraryEntry.psgEnvelope(envelope.getName(), envelope.getData(), indexed), now),
                    counter);
        }
        for (int i = 0; i < song.getModEnvelopes().size(); i++) {
            PsgEnvelope envelope = song.getModEnvelopes().get(i);
            SourceReference indexed = withIndex(source, Integer.toString(i));
            countAdd(library.addOrMerge(
                    InstrumentLibraryEntry.modEnvelope(envelope.getName(), envelope.getData(), indexed), now),
                    counter);
        }
        for (int i = 0; i < song.getDacSamples().size(); i++) {
            DacSample sample = song.getDacSamples().get(i);
            SourceReference indexed = withIndex(source, Integer.toString(i));
            countAdd(library.addOrMerge(
                    InstrumentLibraryEntry.dacSample(
                            sample.getName(), sample.getData(), sample.getRate(),
                            "", null, null, null, Integer.toString(i), indexed), now),
                    counter);
        }
    }

    private static List<Path> songCandidates(SmpsRipConfig.Section section) throws IOException {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        addConfiguredSong(section, candidates, "song");
        addConfiguredSong(section, candidates, "file");
        addConfiguredSong(section, candidates, "music");

        String extension = normalizeExtension(section.extension());
        if (extension.equals(".bin")) {
            return List.copyOf(candidates);
        }
        if (!extension.isBlank() && Files.isDirectory(section.directory())) {
            Set<Path> companionPaths = companionPaths(section);
            try (var stream = Files.list(section.directory())) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> extension.equals(normalizeExtension(fileExtension(path))))
                        .filter(path -> !companionPaths.contains(path.toAbsolutePath().normalize()))
                        .filter(path -> !isLikelyInstrumentSet(path))
                        .forEach(path -> candidates.add(path.toAbsolutePath().normalize()));
            }
        }
        return List.copyOf(candidates);
    }

    private static void addConfiguredSong(
            SmpsRipConfig.Section section,
            LinkedHashSet<Path> candidates,
            String key) {
        Path song = section.resolve(key);
        if (song != null && Files.isRegularFile(song)) {
            candidates.add(song.toAbsolutePath().normalize());
        }
    }

    private static Set<Path> companionPaths(SmpsRipConfig.Section section) {
        Set<Path> paths = new HashSet<>();
        for (String key : List.of("volenv", "modenv", "dac", "globalinslib", "driver", "def", "cflags", "coordflags",
                "commands", "cmds")) {
            Path path = section.resolve(key);
            if (path != null) {
                paths.add(path.toAbsolutePath().normalize());
            }
        }
        return paths;
    }

    private static boolean isLikelyInstrumentSet(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.startsWith("insset.") || name.equals("insset.bin");
    }

    private static SourceReference baseSource(
            Path scanRoot,
            Path configDirectory,
            SmpsRipConfig.Section section,
            SmpsDriverDefinition driver) {
        Path relative = relativePath(scanRoot, configDirectory);
        String driverFamily = pathSegment(relative, 0);
        String gameName = pathSegment(relative, 1);
        String variant = variantPath(relative);
        return new SourceReference(
                scanRoot.toString(),
                driverFamily,
                gameName,
                variant,
                normalizeExtension(section.extension()),
                "",
                "",
                "",
                driver == null ? "" : driver.summary());
    }

    private static SourceReference songSource(Path scanRoot, Path songPath, SourceReference base) {
        return new SourceReference(
                base.scanRoot(),
                base.driverFamily(),
                base.gameName(),
                base.variantPath(),
                base.configExtension(),
                slashPath(relativePath(scanRoot, songPath.toAbsolutePath().normalize())),
                "",
                "",
                base.driverSummary());
    }

    private static SourceReference withIndex(SourceReference source, String index) {
        return new SourceReference(
                source.scanRoot(),
                source.driverFamily(),
                source.gameName(),
                source.variantPath(),
                source.configExtension(),
                source.sourceSongFile(),
                source.sourceCompanionFile(),
                index,
                source.driverSummary());
    }

    private static Path relativePath(Path root, Path child) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absoluteChild = child.toAbsolutePath().normalize();
        if (absoluteChild.startsWith(absoluteRoot)) {
            return absoluteRoot.relativize(absoluteChild);
        }
        return absoluteChild.getFileName();
    }

    private static String pathSegment(Path path, int index) {
        return path.getNameCount() > index ? path.getName(index).toString() : "";
    }

    private static String variantPath(Path path) {
        if (path.getNameCount() <= 2) {
            return "";
        }
        Path variant = path.subpath(2, path.getNameCount());
        return slashPath(variant);
    }

    private static String slashPath(Path path) {
        return path == null ? "" : path.toString().replace(File.separatorChar, '/');
    }

    private static Path firstResolved(SmpsRipConfig.Section section, String... keys) {
        for (String key : keys) {
            Path path = section.resolve(key);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private static void addResolved(LinkedHashSet<Path> paths, SmpsRipConfig.Section section, String... keys) {
        for (String key : keys) {
            Path path = section.resolve(key);
            if (path != null) {
                paths.add(path);
            }
        }
    }

    private static String fileExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private static String normalizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return "";
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized : "." + normalized;
    }

    private static void countAdd(AddResult result, Counter counter) {
        InstrumentAssetKind kind = result.entry().kind();
        if (result.added()) {
            counter.newAssets++;
            counter.increment(counter.newAssetsByKind, kind);
        } else {
            counter.duplicateAssets++;
            counter.increment(counter.duplicateAssetsByKind, kind);
        }
    }

    private static Map<InstrumentAssetKind, Integer> countsByKind(InstrumentLibrary library) {
        EnumMap<InstrumentAssetKind, Integer> counts = new EnumMap<>(InstrumentAssetKind.class);
        for (InstrumentAssetKind kind : InstrumentAssetKind.values()) {
            counts.put(kind, library.entries(kind).size());
        }
        return counts;
    }

    private static void addDeltas(
            EnumMap<InstrumentAssetKind, Integer> target,
            Map<InstrumentAssetKind, Integer> before,
            Map<InstrumentAssetKind, Integer> after) {
        for (InstrumentAssetKind kind : InstrumentAssetKind.values()) {
            int delta = after.get(kind) - before.get(kind);
            if (delta > 0) {
                target.merge(kind, delta, Integer::sum);
            }
        }
    }

    private static void addCompanionDuplicateCounts(
            EnumMap<InstrumentAssetKind, Integer> target,
            Map<InstrumentAssetKind, Integer> companionCounts,
            Map<InstrumentAssetKind, Integer> before,
            Map<InstrumentAssetKind, Integer> after) {
        for (InstrumentAssetKind kind : InstrumentAssetKind.values()) {
            int newCount = Math.max(0, after.get(kind) - before.get(kind));
            int duplicateCount = Math.max(0, companionCounts.get(kind) - newCount);
            if (duplicateCount > 0) {
                target.merge(kind, duplicateCount, Integer::sum);
            }
        }
    }

    private static Map<InstrumentAssetKind, Integer> companionCountsByKind(
            SmpsRipConfig.Section section,
            SmpsDriverDefinition driver) {
        EnumMap<InstrumentAssetKind, Integer> counts = new EnumMap<>(InstrumentAssetKind.class);
        for (InstrumentAssetKind kind : InstrumentAssetKind.values()) {
            counts.put(kind, 0);
        }
        counts.put(InstrumentAssetKind.PSG_ENVELOPE, envelopeCount(section.resolve("VolEnv")));
        counts.put(InstrumentAssetKind.MOD_ENVELOPE, envelopeCount(section.resolve("ModEnv")));
        counts.put(InstrumentAssetKind.DAC_SAMPLE, dacCount(section.resolve("DAC")));
        counts.put(InstrumentAssetKind.FM_VOICE, fmVoiceCount(section.resolve("GlobalInsLib"), section.extension(), driver));
        return counts;
    }

    private static int envelopeCount(Path companion) {
        if (companion == null || !Files.exists(companion)) {
            return 0;
        }
        try {
            return EnvelopeListParser.parse(Files.readAllBytes(companion)).size();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static int fmVoiceCount(Path companion, String extension, SmpsDriverDefinition driver) {
        if (companion == null || !Files.exists(companion)) {
            return 0;
        }
        try {
            return InsSetVoiceParser.parse(companion, extension, driver).size();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static int dacCount(Path companion) {
        if (companion == null || !Files.exists(companion)) {
            return 0;
        }
        try {
            Path configDir = companion.toAbsolutePath().normalize().getParent();
            int count = 0;
            for (DacIniParser.Entry entry : DacIniParser.parse(companion)) {
                if (dacSampleExists(configDir, entry)) {
                    count++;
                }
            }
            return count;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static boolean dacSampleExists(Path configDir, DacIniParser.Entry entry) {
        String normalized = entry.file().replace('\\', '/');
        String baseName = Path.of(normalized).getFileName().toString();
        return Files.exists(configDir.resolve("DAC").resolve("uncompressed").resolve(baseName).normalize())
                || Files.exists(resolvePossiblyAbsolute(configDir, normalized))
                || Files.exists(configDir.resolve("DAC").resolve(baseName).normalize());
    }

    private static Path resolvePossiblyAbsolute(Path baseDir, String value) {
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return baseDir.resolve(path).normalize();
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private record ParsedSection(Path configPath, SmpsDriverDefinition driver) {
    }

    private static final class Counter {
        private int filesVisited;
        private int configDirectoriesFound;
        private int fullSongImportsAttempted;
        private int fullSongImportsSucceeded;
        private int assetOnlyFoldersHarvested;
        private int unsupportedSongDialects;
        private int newAssets;
        private int duplicateAssets;
        private final EnumMap<InstrumentAssetKind, Integer> newAssetsByKind =
                new EnumMap<>(InstrumentAssetKind.class);
        private final EnumMap<InstrumentAssetKind, Integer> duplicateAssetsByKind =
                new EnumMap<>(InstrumentAssetKind.class);
        private final List<ScanFailure> failures = new ArrayList<>();

        private Counter(InstrumentLibrary library) {
            for (InstrumentAssetKind kind : InstrumentAssetKind.values()) {
                newAssetsByKind.put(kind, 0);
                duplicateAssetsByKind.put(kind, 0);
            }
        }

        private void increment(EnumMap<InstrumentAssetKind, Integer> counts, InstrumentAssetKind kind) {
            counts.merge(kind, 1, Integer::sum);
        }

        private void failure(Path path, String reason) {
            failures.add(new ScanFailure(path.toAbsolutePath().normalize(), reason));
        }

        private ScanSummary summary(InstrumentLibrary library) {
            return new ScanSummary(
                    filesVisited,
                    configDirectoriesFound,
                    fullSongImportsAttempted,
                    fullSongImportsSucceeded,
                    assetOnlyFoldersHarvested,
                    unsupportedSongDialects,
                    newAssets,
                    duplicateAssets,
                    Map.copyOf(newAssetsByKind),
                    Map.copyOf(duplicateAssetsByKind),
                    Map.copyOf(countsByKind(library)),
                    List.copyOf(failures));
        }
    }
}
