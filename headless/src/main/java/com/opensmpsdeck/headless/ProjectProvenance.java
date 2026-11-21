package com.opensmpsdeck.headless;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.RetailSongOrigin;
import com.opensmpsdeck.project.StudioProject;

import java.io.PrintStream;
import java.util.List;
import java.util.TreeSet;

/** Detection shared by project exports, reconstruction, and Studio warnings. */
public record ProjectProvenance(boolean containsRomDerivedData, boolean containsRetailSongData,
                                RetailSongOrigin derivedFromRetailSong, List<String> warnings) {
    public ProjectProvenance { warnings = List.copyOf(warnings); }

    public static ProjectProvenance detect(StudioProject project) {
        var warnings = new TreeSet<String>();
        var retail = retailOrigin(project);
        if (retail != null) {
            warnings.add("RETAIL_SONG_DATA");
            warnings.add("ROM_DERIVED_DATA");
        }
        for (var voice : project.voices())
            check(warnings, "FM_VOICE", StockTable.bytes(voice.bytes()), voice.origin());
        for (var envelope : project.envelopes())
            check(warnings, envelope.type(), StockTable.bytes(envelope.bytes()), envelope.origin());
        for (var sample : project.samples())
            check(warnings, "DAC_SAMPLE", sample.pcm(), sample.origin());

        var nativeProgram = project.nativeProgram();
        if (nativeProgram != null && nativeProgram.localVoiceCount() > 0 && !warnings.contains("ROM_DERIVED_DATA")) {
            byte[] bank = nativeProgram.bank();
            int header = nativeProgram.headerOffset();
            int start = ((bank[header] & 255) | (bank[header + 1] & 255) << 8) - nativeProgram.bankBase();
            int end = start + 25 * nativeProgram.localVoiceCount();
            if (start >= 0 && end <= bank.length) {
                for (int offset = start; offset < end; offset += 25)
                    check(warnings, "FM_VOICE", java.util.Arrays.copyOfRange(bank, offset, offset + 25), null);
            }
        }
        return new ProjectProvenance(warnings.contains("ROM_DERIVED_DATA"), retail != null, retail, List.copyOf(warnings));
    }

    static RetailSongOrigin retailOrigin(StudioProject project) {
        if (project.derivedFrom() != null) return project.derivedFrom();
        return project.nativeProgram() == null ? null : StockTable.matchBank(project.nativeProgram().bank());
    }

    private static void check(TreeSet<String> warnings, String kind, byte[] bytes, AssetOrigin origin) {
        if (origin == null) {
            if (StockTable.match(kind, bytes) != null) warnings.add("ROM_DERIVED_DATA");
        } else {
            switch (origin.type()) {
                case "stock" -> warnings.add("ROM_DERIVED_DATA");
                case "source-audio" -> warnings.add("SOURCE_AUDIO");
                case "third-party" -> warnings.add("THIRD_PARTY");
                default -> {}
            }
        }
    }

    JsonObject json() {
        var json = new JsonObject();
        json.addProperty("containsRomDerivedData", containsRomDerivedData);
        json.addProperty("containsRetailSongData", containsRetailSongData);
        if (derivedFromRetailSong == null) {
            json.add("derivedFromRetailSong", JsonNull.INSTANCE);
        } else {
            var retail = new JsonObject();
            retail.addProperty("game", derivedFromRetailSong.game());
            retail.addProperty("songId", derivedFromRetailSong.songId());
            retail.addProperty("bankSha256", derivedFromRetailSong.bankSha256());
            json.add("derivedFromRetailSong", retail);
        }
        var codes = new JsonArray();
        warnings.forEach(codes::add);
        json.add("warnings", codes);
        return json;
    }

    public static String warningMessage(String code) {
        return switch (code) {
            case "RETAIL_SONG_DATA" -> "This package contains copyrighted song data from the retail game. "
                    + "It is for personal use and must not be redistributed. A shareable portable mode is planned.";
            case "ROM_DERIVED_DATA" -> "This package contains assets derived from the game ROM. "
                    + "It is for personal use and must not be redistributed. A shareable portable mode is planned.";
            case "SOURCE_AUDIO" -> "This package contains audio derived from an input recording. Check that you have the rights to it.";
            case "THIRD_PARTY" -> "This package contains third-party material. Check its license.";
            default -> code;
        };
    }

    public static void printWarnings(List<String> warnings, PrintStream err) {
        for (String code : warnings) err.println("Warning [" + code + "]: " + warningMessage(code));
    }
}
