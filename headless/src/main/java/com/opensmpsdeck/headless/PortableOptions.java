package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

record PortableOptions(String driver, boolean includeSource, String license, String id,
                       String version, List<String> authors, List<Integer> replacements, Integer speedUpTempo,
                       boolean strictTempo, boolean retailPatch, List<Path> roms) {
    PortableOptions(String driver, boolean includeSource, String license, String id,
                    String version, List<String> authors, List<Integer> replacements, Integer speedUpTempo,
                    boolean strictTempo) {
        this(driver, includeSource, license, id, version, authors, replacements, speedUpTempo, strictTempo, false, List.of());
    }
    PortableOptions(String driver, boolean includeSource, String license, String id,
                    String version, List<String> authors, List<Integer> replacements, Integer speedUpTempo) {
        this(driver, includeSource, license, id, version, authors, replacements, speedUpTempo, false);
    }
    PortableOptions(String driver, boolean includeSource, String license, String id,
                    String version, List<String> authors, List<Integer> replacements) {
        this(driver, includeSource, license, id, version, authors, replacements, null);
    }
    PortableOptions {
        authors = List.copyOf(authors);
        replacements = List.copyOf(replacements);
        roms = List.copyOf(roms);
        PackageDriver.supported(driver);
        if (speedUpTempo != null && (PackageDriver.supported(driver) instanceof S3kPackageDriver || speedUpTempo < 0 || speedUpTempo > 255))
            throw new IllegalArgumentException("speedUpTempo requires driver s1 or s2 and an S3K tempo byte in 0..255");
    }
    static final PortableOptions DEFAULT = defaults(TargetGame.S3K);

    static PortableOptions defaults(TargetGame target) {
        return new PortableOptions(target.id(), true, null, null, null, List.of(), List.of());
    }

    com.google.gson.JsonObject driverOptions(Integer nativeSpeedUpTempo) {
        var options = new com.google.gson.JsonObject();
        if (!(PackageDriver.supported(driver) instanceof S3kPackageDriver)) options.addProperty("speedUpTempo", nativeSpeedUpTempo);
        return options;
    }

    static final class Cli {
        private static final Set<String> OPTIONS = Set.of("--portable", "--no-source", "--driver", "--license",
                "--package-id", "--package-version", "--author", "--replace", "--rom", "--speed-up-tempo", "--strict-tempo", "--retail-patch");
        private final Set<String> seen = new HashSet<>();
        private final List<String> authors = new ArrayList<>();
        private final List<Integer> replacements = new ArrayList<>();
        private final List<Path> roms = new ArrayList<>();
        private String driver;
        private String license, id, version;
        private Integer speedUpTempo;

        boolean recognizes(String option) { return OPTIONS.contains(option); }
        boolean portable() { return seen.contains("--portable"); }
        List<Path> roms() { return List.copyOf(roms); }
        boolean hasPackageOptions() { return seen.stream().anyMatch(key -> !key.equals("--rom")); }

        int read(String[] args, int at) {
            String option = args[at++];
            boolean repeatable = List.of("--author", "--replace", "--rom").contains(option);
            if (!seen.add(option) && !repeatable) throw new IllegalArgumentException("Duplicate option " + option);
            if (List.of("--portable", "--no-source", "--strict-tempo", "--retail-patch").contains(option)) return at;
            if (at == args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[at++];
            switch (option) {
                case "--driver" -> driver = value;
                case "--speed-up-tempo" -> speedUpTempo = Integer.valueOf(value);
                case "--license" -> license = value;
                case "--package-id" -> id = value;
                case "--package-version" -> version = value;
                case "--author" -> authors.add(value);
                case "--replace" -> {
                    if (!value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("--replace requires a decimal music ID");
                    replacements.add(Integer.parseInt(value));
                }
                case "--rom" -> roms.add(Path.of(value));
                default -> throw new IllegalArgumentException("Unknown option " + option);
            }
            return at;
        }

        PortableOptions options() { return options(TargetGame.S3K); }

        PortableOptions options(TargetGame fallback) {
            return new PortableOptions(driver == null ? fallback.id() : driver, !seen.contains("--no-source"), license, id, version, authors, replacements,
                    speedUpTempo, seen.contains("--strict-tempo"), seen.contains("--retail-patch"), roms);
        }
    }
}
