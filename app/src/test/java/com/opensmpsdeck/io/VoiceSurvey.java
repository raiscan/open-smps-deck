package com.opensmpsdeck.io;

import com.opensmpsdeck.model.Song;
import java.io.File;
import java.util.*;

public class VoiceSurvey {
    public static void main(String[] args) throws Exception {
        File root = new File("../docs/SMPS-rips");
        if (!root.exists()) root = new File("docs/SMPS-rips");
        List<File> songs = new ArrayList<>();
        collect(root, songs);
        songs.sort(Comparator.comparing(File::getPath));
        int empty = 0;
        for (File f : songs) {
            Song song = new SmpsImporter().importFile(f);
            if (song.getVoiceBank().isEmpty()) {
                empty++;
                System.out.println("NO VOICES: " + root.toPath().relativize(f.toPath()));
            }
        }
        System.out.println("=== " + empty + "/" + songs.size() + " songs import with empty voice bank ===");
    }

    private static void collect(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (f.isDirectory()) {
                if (name.equals("dac") || name.startsWith("psg") || name.contains("sfx")) continue;
                collect(f, out);
            } else if (name.startsWith("insset")) {
                continue;
            } else if (name.endsWith(".sm2") || name.endsWith(".s3k") || name.endsWith(".smp")
                    || name.matches(".*\\.[0-9a-f]{4}\\.bin$")) {
                out.add(f);
            }
        }
    }
}
