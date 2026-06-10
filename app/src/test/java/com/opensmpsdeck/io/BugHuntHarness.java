package com.opensmpsdeck.io;

import com.opensmpsdeck.codec.HierarchyDecompiler;
import com.opensmpsdeck.codec.PatternCompiler;
import com.opensmpsdeck.codec.SmpsDecoder;
import com.opensmpsdeck.model.ChannelType;
import com.opensmpsdeck.model.SmpsMode;
import com.opensmpsdeck.model.Song;
import com.opensmps.smps.SmpsCoordFlags;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/**
 * Invariant-based bug hunt over all rips:
 *   1. IDEMPOTENCE: compile(import(file)) == compile(import(compile(import(file))))
 *   2. PROJECT ROUND TRIP: compile(load(save(import(file)))) == compile(import(file))
 *   3. FUZZ: random bytecode through decoder/decompiler in every dialect must
 *      neither throw nor hang.
 */
public class BugHuntHarness {

    public static void main(String[] args) throws Exception {
        File ripsRoot = new File("../docs/SMPS-rips");
        if (!ripsRoot.exists()) ripsRoot = new File("docs/SMPS-rips");

        List<File> songs = new ArrayList<>();
        collectSongs(ripsRoot, songs);
        songs.sort(Comparator.comparing(File::getPath));
        System.out.println("Hunting across " + songs.size() + " songs");

        List<String> findings = new ArrayList<>();
        File tmp = Files.createTempDirectory("bughunt").toFile();

        int idemFail = 0, projFail = 0, errors = 0;
        for (File f : songs) {
            String rel = ripsRoot.toPath().relativize(f.toPath()).toString();
            try {
                SmpsImporter importer = new SmpsImporter();
                Song song1 = importer.importFile(f);
                byte[] compile1 = new PatternCompiler().compile(song1);

                // 1. Idempotence: re-import the compiled binary as the same mode
                Song song2 = new SmpsImporter().importData(
                        compile1, song1.getName(), 0, song1.getSmpsMode());
                song2.setSmpsMode(song1.getSmpsMode());
                song2.setDacChannelFm6(song1.isDacChannelFm6());
                byte[] compile2 = new PatternCompiler().compile(song2);
                if (!Arrays.equals(compile1, compile2)) {
                    idemFail++;
                    findings.add("IDEMPOTENCE: " + rel + " (" + compile1.length
                            + " vs " + compile2.length + " bytes, firstDiff="
                            + firstDiff(compile1, compile2) + ")");
                }

                // 2. Project round trip
                File proj = new File(tmp, "t.osmpsd");
                ProjectFile.save(song1, proj);
                Song loaded = ProjectFile.load(proj);
                byte[] compile3 = new PatternCompiler().compile(loaded);
                if (!Arrays.equals(compile1, compile3)) {
                    projFail++;
                    findings.add("PROJECT-RT: " + rel + " (" + compile1.length
                            + " vs " + compile3.length + " bytes, firstDiff="
                            + firstDiff(compile1, compile3) + ")");
                }
            } catch (Throwable t) {
                errors++;
                findings.add("ERROR: " + rel + ": " + t);
            }
        }
        System.out.printf("Idempotence failures: %d, project round-trip failures: %d, errors: %d%n",
                idemFail, projFail, errors);

        // 3. Fuzz decoder + decompiler with deterministic pseudo-random bytecode
        int fuzzFindings = fuzz(findings);
        System.out.println("Fuzz findings: " + fuzzFindings);

        System.out.println("==== FINDINGS: " + findings.size() + " ====");
        for (String s : findings) System.out.println(s);
        System.exit(findings.isEmpty() ? 0 : 1);
    }

    private static int fuzz(List<String> findings) throws Exception {
        Random rng = new Random(0xC0FFEE);
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fuzz");
            t.setDaemon(true);
            return t;
        });
        int before = findings.size();
        for (int round = 0; round < 3000; round++) {
            byte[] data = new byte[rng.nextInt(120) + 1];
            rng.nextBytes(data);
            for (SmpsCoordFlags.Dialect d : SmpsCoordFlags.Dialect.values()) {
                final byte[] input = data;
                final SmpsCoordFlags.Dialect dialect = d;
                final int r = round;
                Future<?> fut = exec.submit(() -> {
                    SmpsDecoder.decode(input);
                    HierarchyDecompiler.decompileTrack(input, ChannelType.FM, dialect);
                    // compile what came out: must not throw either
                    var res = HierarchyDecompiler.decompileTrack(input, ChannelType.PSG_TONE, dialect);
                    var lib = new com.opensmpsdeck.model.PhraseLibrary();
                    var chain = new com.opensmpsdeck.model.Chain(0);
                    Map<Integer, Integer> idMap = new HashMap<>();
                    for (var p : res.phrases()) {
                        var np = lib.createPhrase(p.getName(), p.getChannelType());
                        np.setData(p.getDataDirect());
                        idMap.put(p.getId(), np.getId());
                    }
                    for (var e : res.chainEntries()) {
                        Integer ni = idMap.get(e.getPhraseId());
                        if (ni != null) chain.getEntries().add(new com.opensmpsdeck.model.ChainEntry(ni));
                    }
                    com.opensmpsdeck.codec.HierarchyCompiler.compileChainDetailed(chain, lib, dialect);
                });
                try {
                    fut.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    fut.cancel(true);
                    findings.add("FUZZ-HANG: round=" + r + " dialect=" + dialect
                            + " data=" + hex(input));
                    exec.shutdownNow();
                    return findings.size() - before; // executor thread is stuck
                } catch (ExecutionException e) {
                    findings.add("FUZZ-THROW: round=" + r + " dialect=" + dialect
                            + " " + e.getCause() + " data=" + hex(input));
                }
            }
        }
        exec.shutdownNow();
        return findings.size() - before;
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private static int firstDiff(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) return i;
        }
        return a.length == b.length ? -1 : n;
    }

    private static void collectSongs(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (f.isDirectory()) {
                if (name.equals("dac") || name.startsWith("psg") || name.contains("sfx")) continue;
                collectSongs(f, out);
            } else if (name.startsWith("insset")) {
                continue;
            } else if (name.endsWith(".sm2") || name.endsWith(".s3k") || name.endsWith(".smp")
                    || name.matches(".*\\.[0-9a-f]{4}\\.bin$")) {
                out.add(f);
            }
        }
    }
}
