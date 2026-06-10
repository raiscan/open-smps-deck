package com.opensmpsdeck.io;

import com.opensmpsdeck.codec.HierarchyDecompiler;
import com.opensmpsdeck.codec.PatternCompiler;
import com.opensmpsdeck.model.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/**
 * Diagnostic sweep: runs every song file under docs/SMPS-rips through the same
 * pipeline the UI uses on import:
 *   1. SmpsImporter.importFile (builds the hierarchical arrangement)
 *   2. pattern-track decompile robustness check (legacy pattern view path)
 *   3. PatternCompiler.compile (hierarchical, as playback would)
 * Each file runs under a watchdog; hangs are reported with the stuck stack trace.
 */
public class RipsSweepHarness {

    private static final long TIMEOUT_SECONDS = 20;

    public static void main(String[] args) throws Exception {
        File ripsRoot = new File("../docs/SMPS-rips");
        if (!ripsRoot.exists()) ripsRoot = new File("docs/SMPS-rips");

        List<File> songs = new ArrayList<>();
        collectSongs(ripsRoot, songs);
        songs.sort(Comparator.comparing(File::getPath));
        System.out.println("Found " + songs.size() + " song files");

        int pass = 0;
        List<String> failures = new ArrayList<>();

        for (File song : songs) {
            String rel = ripsRoot.toPath().relativize(song.toPath()).toString();
            String result = runOne(song);
            if (result == null) {
                pass++;
            } else {
                failures.add(rel + "\n" + result);
                System.out.println("FAIL: " + rel);
            }
        }

        System.out.println();
        System.out.println("==== SWEEP RESULT: " + pass + "/" + songs.size() + " passed ====");
        for (String f : failures) {
            System.out.println("----------------------------------------");
            System.out.println(f);
        }
        System.exit(failures.isEmpty() ? 0 : 1);
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

    /** Returns null on success, or a failure description. */
    private static String runOne(File file) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sweep-worker");
            t.setDaemon(true);
            return t;
        });
        final Thread[] workerRef = new Thread[1];
        Future<String> future = exec.submit(() -> {
            workerRef[0] = Thread.currentThread();
            return runStages(file);
        });
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            StringBuilder sb = new StringBuilder("HANG after " + TIMEOUT_SECONDS + "s");
            Thread worker = workerRef[0];
            if (worker != null) {
                sb.append("; stuck at:\n");
                for (StackTraceElement el : worker.getStackTrace()) {
                    sb.append("    at ").append(el).append('\n');
                }
            }
            future.cancel(true);
            return sb.toString();
        } catch (Exception e) {
            return "HARNESS ERROR: " + e;
        } finally {
            exec.shutdownNow();
        }
    }

    private static String runStages(File file) {
        String stage = "IMPORT";
        try {
            SmpsImporter importer = new SmpsImporter();
            Song song = importer.importFile(file);

            // Robustness stage: pattern-data decompile must not throw/hang
            // (exercised by the legacy pattern view)
            stage = "PREVIEW_DECOMPILE";
            decompileAllChannels(song);

            // The UI imports the arrangement built by SmpsImporter as-is
            stage = "COMPILE";
            PatternCompiler compiler = new PatternCompiler();
            byte[] compiled = compiler.compile(song, song.getSmpsMode());
            if (compiled == null || compiled.length < 6) {
                return stage + ": compiled output too small ("
                        + (compiled == null ? "null" : compiled.length) + " bytes)";
            }
            return null;
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder(stage + ": " + t);
            for (StackTraceElement el : t.getStackTrace()) {
                sb.append("\n    at ").append(el);
                if (sb.length() > 4000) break;
            }
            return sb.toString();
        }
    }

    /** Mirrors ImportPreviewDialog.decompileAllChannels (no JavaFX). */
    private static List<HierarchyDecompiler.DecompileResult> decompileAllChannels(Song song) {
        List<HierarchyDecompiler.DecompileResult> channelResults = new ArrayList<>();
        if (song.getPatterns().isEmpty()) return channelResults;
        Pattern pattern = song.getPatterns().getFirst();
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            byte[] trackData = pattern.getTrackData(ch);
            if (trackData == null || trackData.length == 0) {
                channelResults.add(new HierarchyDecompiler.DecompileResult(
                        List.of(), List.of(), false, -1, 0));
                continue;
            }
            ChannelType type = ChannelType.fromChannelIndex(ch);
            channelResults.add(HierarchyDecompiler.decompileTrack(trackData, type));
        }
        return channelResults;
    }

}
