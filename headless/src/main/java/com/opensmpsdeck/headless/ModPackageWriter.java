package com.opensmpsdeck.headless;

import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

final class ModPackageWriter {
    private ModPackageWriter() {}

    static void write(PortablePackageWriter.Payload payload, Path output, List<Integer> replacements) throws IOException {
        var manifest = payload.manifest();
        var info = manifest.packageInfo();
        var ids = new TreeSet<>(replacements);
        if (ids.isEmpty() || ids.size() != replacements.size())
            throw new IllegalArgumentException("Supply one or more distinct --replace music IDs");
        var valid = PackageDriver.supported(manifest.driver()).musicIds();
        if (!valid.containsAll(ids)) throw new IllegalArgumentException("--replace requires a stock " + manifest.driver() + " music ID (decimal)");
        String prefix = "audio/smps/" + info.id() + "/";
        var mod = new StringBuilder("formatVersion: 1\n")
                .append("id: ").append(yaml(info.id())).append('\n')
                .append("name: ").append(yaml(info.name())).append('\n')
                .append("version: ").append(yaml(info.version())).append('\n')
                .append("authors:\n");
        for (String author : info.authors().isEmpty() ? List.of("Unknown") : info.authors())
            mod.append("  - ").append(yaml(author)).append('\n');
        mod.append("description: ").append(yaml("SMPS music replacement: " + info.name())).append('\n')
                .append("engineApiRange: \">=0.7.0 <0.8.0\"\ntype: patch\nbaseGame: ").append(manifest.driver())
                .append("\ndependencies: []\naudioOverrides:\n");
        for (int id : ids) mod.append("  ").append(id).append(": ").append(yaml(info.id())).append('\n');
        mod.append("artOverrides: {}\n");
        String audio = "formatVersion: 2\ntracks:\n  - id: " + yaml(info.id())
                + "\n    smpsPackage: " + yaml(prefix) + "\n    gain: 1.0\n    tempoEffects: true\nsfx: []\n";
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("META-INF/openggf-mod.yaml", mod.toString().getBytes(StandardCharsets.UTF_8));
        entries.put("audio/audio-manifest.yaml", audio.getBytes(StandardCharsets.UTF_8));
        payload.entries().forEach((name, bytes) -> entries.put(prefix + name, bytes));
        PortableArchive.write(output, entries, null);
    }

    private static String yaml(String text) { return new JsonPrimitive(text).toString(); }
}
