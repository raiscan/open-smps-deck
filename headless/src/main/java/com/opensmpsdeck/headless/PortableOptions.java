package com.opensmpsdeck.headless;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

record PortableOptions(String driver, boolean includeSource, String license, String id,
                       String version, List<String> authors, List<Integer> replacements) {
    PortableOptions {
        authors = List.copyOf(authors);
        replacements = List.copyOf(replacements);
        if (!List.of("s1", "s2", "s3k").contains(driver))
            throw new IllegalArgumentException("Unknown package driver " + driver);
    }
    static final PortableOptions DEFAULT = new PortableOptions("s3k", true, null, null, null, List.of(), List.of());

    static final class Cli {
        private static final Set<String> OPTIONS = Set.of("--portable", "--no-source", "--driver", "--license",
                "--package-id", "--package-version", "--author", "--replace", "--rom");
        private final Set<String> seen = new HashSet<>();
        private final List<String> authors = new ArrayList<>();
        private final List<Integer> replacements = new ArrayList<>();
        private final List<Path> roms = new ArrayList<>();
        private String driver = "s3k";
        private String license, id, version;

        boolean recognizes(String option) { return OPTIONS.contains(option); }
        boolean portable() { return seen.contains("--portable"); }
        List<Path> roms() { return List.copyOf(roms); }
        boolean hasPackageOptions() { return seen.stream().anyMatch(key -> !key.equals("--rom")); }

        int read(String[] args, int at) {
            String option = args[at++];
            boolean repeatable = List.of("--author", "--replace", "--rom").contains(option);
            if (!seen.add(option) && !repeatable) throw new IllegalArgumentException("Duplicate option " + option);
            if (option.equals("--portable") || option.equals("--no-source")) return at;
            if (at == args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[at++];
            switch (option) {
                case "--driver" -> driver = value;
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

        PortableOptions options() {
            return new PortableOptions(driver, !seen.contains("--no-source"), license, id, version, authors, replacements);
        }
    }
}
