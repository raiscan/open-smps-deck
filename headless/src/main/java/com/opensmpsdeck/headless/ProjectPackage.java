package com.opensmpsdeck.headless;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.StudioProject;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Portable native bank, source and declared resources; does not read archives or ROMs. */
public final class ProjectPackage {
    private ProjectPackage() {}

    public static void write(StudioProject project, Path output) throws IOException {
        var compiled = ProjectCompiler.compileWithAssets(project);
        byte[] song = S3kBinary.validateAndCopy(compiled);
        byte[] source = ProjectFormat.write(project).getBytes(StandardCharsets.UTF_8);
        if (source.length > ProjectFiles.MAX_FILE_BYTES)
            throw new IllegalArgumentException("Project exceeds the " + ProjectFiles.MAX_FILE_BYTES + " byte source limit");

        JsonObject manifest = new JsonObject();
        manifest.addProperty("schemaVersion", compiled.samples().isEmpty() ? 1 : 2);
        manifest.addProperty("mode", "S3K");
        manifest.addProperty("bankBase", 0);
        manifest.add("track", reference("track.bin", song));
        manifest.add("source", reference("source.smps.json", source));
        JsonArray voices = new JsonArray();
        for (int index = 0; index < project.voices().size(); index++) {
            var voice = project.voices().get(index);
            JsonObject item = new JsonObject();
            item.addProperty("id", voice.id()); item.addProperty("name", voice.name()); item.addProperty("index", index);
            voices.add(item);
        }
        manifest.add("voices", voices);
        List<Entry> resources = new ArrayList<>();
        JsonArray envelopes = new JsonArray();
        int psgIndex = 0, modulationIndex = 0;
        for (Envelope envelope : compiled.envelopes()) {
            boolean psg = envelope.type().equals("PSG_VOLUME");
            int index = psg ? ++psgIndex : ++modulationIndex;
            // IDs and display names never become paths. Native indices have
            // independent namespaces and stable authored order within each kind.
            String path = String.format(Locale.ROOT, "%s/%03d.bin", psg ? "psg" : "mod", index);
            byte[] bytes = bytes(envelope.bytes());
            resources.add(new Entry(path, bytes));
            JsonObject item = reference(path, bytes);
            item.addProperty("kind", envelope.type()); item.addProperty("id", envelope.id());
            item.addProperty("name", envelope.name()); item.addProperty("index", index);
            envelopes.add(item);
        }
        manifest.add("envelopes", envelopes);
        if (!compiled.samples().isEmpty()) {
            JsonObject dac = new JsonObject();
            dac.addProperty("baseCycles", DacSample.BASE_CYCLES);
            JsonArray samples = new JsonArray();
            for (int index = 0; index < compiled.samples().size(); index++) {
                DacSample sample = compiled.samples().get(index);
                String path = String.format(Locale.ROOT, "dac/%03d.pcm", index);
                byte[] pcm = sample.pcm();
                resources.add(new Entry(path, pcm));
                JsonObject item = reference(path, pcm);
                item.addProperty("id", sample.id()); item.addProperty("name", sample.name());
                item.addProperty("sampleId", index); item.addProperty("note", DacSample.FIRST_NOTE + index);
                item.addProperty("rate", sample.rate());
                samples.add(item);
            }
            dac.add("samples", samples); manifest.add("dac", dac);
        }
        byte[] manifestBytes = (new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(manifest) + "\n")
                .getBytes(StandardCharsets.UTF_8);

        // All validation and serialization precede publication. CREATE_NEW
        // preserves existing files, including symlinks and concurrent creators.
        var created = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        boolean complete = false;
        try {
            try (var archive = new ZipOutputStream(new BufferedOutputStream(created), StandardCharsets.UTF_8)) {
                writeEntry(archive, new Entry("track.bin", song));
                writeEntry(archive, new Entry("source.smps.json", source));
                writeEntry(archive, new Entry("manifest.json", manifestBytes));
                for (Entry resource : resources) writeEntry(archive, resource);
            }
            complete = true;
        } finally {
            if (!complete) Files.deleteIfExists(output);
        }
    }

    private static void writeEntry(ZipOutputStream archive, Entry value) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Package export cancelled");
        ZipEntry entry = new ZipEntry(value.path());
        entry.setTime(0L);
        // Stored entries make bytes reproducible without depending on a
        // compressor's implementation or tuning; these resources are bounded.
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(value.bytes().length);
        CRC32 crc = new CRC32(); crc.update(value.bytes()); entry.setCrc(crc.getValue());
        archive.putNextEntry(entry);
        archive.write(value.bytes());
        archive.closeEntry();
    }

    private static JsonObject reference(String path, byte[] bytes) {
        JsonObject reference = new JsonObject();
        reference.addProperty("path", path);
        try {
            reference.addProperty("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException exception) { throw new AssertionError("SHA-256 is required", exception); }
        return reference;
    }

    private static byte[] bytes(List<Integer> values) {
        byte[] bytes = new byte[values.size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (int) values.get(i);
        return bytes;
    }

    private record Entry(String path, byte[] bytes) {}
}
