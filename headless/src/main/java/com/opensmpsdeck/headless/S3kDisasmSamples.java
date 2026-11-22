package com.opensmpsdeck.headless;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.opensmpsdeck.headless.S3kDisasmEmitter.hex;

/** Plans note bindings, one WAV per sample, and self-contained DAC banks. */
final class S3kDisasmSamples {
    private static final int FIRST_RETAIL = 0x81, LAST_RETAIL = 0xC4, LAST_NOTE = 0xDF;
    private static final int BANK_BYTES = 0x8000;
    private static final List<String> RETAIL_NOTES = List.of(("""
            dSnareS3 dHighTom dMidTomS3 dLowTomS3 dFloorTomS3 dKickS3 dMuffledSnare
            dCrashCymbal dRideCymbal dLowMetalHit dMetalHit dHighMetalHit
            dHigherMetalHit dMidMetalHit dClapS3 dElectricHighTom
            dElectricMidTom dElectricLowTom dElectricFloorTom
            dTightSnare dMidpitchSnare dLooseSnare dLooserSnare
            dHiTimpaniS3 dLowTimpaniS3 dMidTimpaniS3 dQuickLooseSnare
            dClick dPowerKick dQuickGlassCrash
            dGlassCrashSnare dGlassCrash dGlassCrashKick dQuietGlassCrash
            dOddSnareKick dKickExtraBass dComeOn dDanceSnare dLooseKick
            dModLooseKick dWoo dGo dSnareGo dPowerTom dHiWoodBlock dLowWoodBlock
            dHiHitDrum dLowHitDrum dMetalCrashHit dEchoedClapHit dLowerEchoedClapHit
            dHipHopHitKick dHipHopHitPowerKick dBassHey dDanceStyleKick dHipHopHitKick2 dHipHopHitKick3
            dReverseFadingWind dScratchS3 dLooseSnareNoise dPowerKick2
            dCrashingNoiseWoo dQuickHit dKickHey dPowerKickHit dLowPowerKickHit dLowerPowerKickHit dLowestPowerKickHit
            """).strip().split("\\s+"));

    record Note(int number, String constant, int rate) {}
    record Sample(String asset, List<Note> notes, byte[] pcm, int startRate) {
        Note first() { return notes.getFirst(); }
        int bytes() { return 5 * notes.size() + (pcm == null ? 32768 : pcm.length / 2); }
    }
    record Plan(Map<Integer, Note> notes, List<Sample> samples, int lastNote) {}

    private S3kDisasmSamples() {}

    static Plan plan(DisassemblyKit kit) throws IOException {
        var notes = new LinkedHashMap<Integer, Note>();
        var custom = new ArrayList<PackageManifest.DacBinding>();
        var usedRetail = new HashSet<Integer>();
        var rates = kit.manifest.dacBindings().isEmpty() ? Map.<Integer, Integer>of() : DisasmDacRates.read("s3k");
        for (var binding : kit.manifest.dacBindings()) {
            var asset = kit.assets.get(binding.asset());
            var ref = asset.ref();
            boolean nativeSample = ref != null && asset.transforms().isEmpty()
                    && rates.getOrDefault(ref.note(), -1) == binding.rate()
                    && StockTable.entries().stream().anyMatch(entry ->
                            entry.kind().equals("DAC_SAMPLE") && entry.ref().equals(ref));
            if (nativeSample) {
                if (!kit.roms.isEmpty()) kit.materialise(asset, null);
                if (usedRetail.add(ref.note()) && (ref.note() == 0xB2 || ref.note() == 0xB3))
                    kit.readme.append("- Retail ").append(hex(ref.note())).append(" uses the S&K version of the sample ")
                            .append("(`B2-B3 (Sonic & Knuckles)` in skdisasm). Select that variant in a Sonic 3 build.\n");
                notes.put(binding.note(), new Note(ref.note(), retailName(ref.note(), kit.name), binding.rate()));
            } else {
                custom.add(binding);
            }
        }

        var replaced = new HashSet<Integer>();
        for (int note : kit.replacements) {
            if (note < FIRST_RETAIL || note > LAST_RETAIL || usedRetail.contains(note) || !replaced.add(note))
                throw new IllegalArgumentException("DAC replacement must be a unique unused retail note $81–$C4: " + hex(note));
        }
        if (kit.replacements.size() > custom.size())
            throw new IllegalArgumentException("More --replace-dac slots than custom DAC bindings");
        if (!replaced.isEmpty()) kit.warnings.add("DAC_REPLACEMENT");
        if (custom.size() - replaced.size() > LAST_NOTE - LAST_RETAIL)
            throw new IllegalArgumentException("S3K supports 27 new DAC notes ($C5–$DF). Supply unused retail slots with --replace-dac");

        var groups = new LinkedHashMap<String, List<Note>>();
        int last = LAST_RETAIL;
        for (int i = 0; i < custom.size(); i++) {
            var binding = custom.get(i);
            int number = i < kit.replacements.size() ? kit.replacements.get(i) : ++last;
            var note = new Note(number, kit.name + "_d" + suffix(number), binding.rate());
            notes.put(binding.note(), note);
            groups.computeIfAbsent(binding.asset(), key -> new ArrayList<>()).add(note);
        }

        var samples = new ArrayList<Sample>();
        for (var group : groups.entrySet()) {
            var asset = kit.assets.get(group.getKey());
            byte[] pcm = kit.materialise(asset, kit.artifact.entries().get(asset.path()));
            var first = group.getValue().getFirst();
            int rate = S3kDisasmDac.sampleRate(first.rate());
            String path = "Sound/DAC/" + suffix(first.number()) + ".wav";
            kit.readme.append("- `").append(path).append("` (`").append(asset.key()).append("`) ");
            if (pcm != null) {
                byte[] decoded = S3kDisasmDac.quantize(pcm);
                boolean exact = Arrays.equals(pcm, decoded);
                if (!exact) kit.warnings.add("DPCM_INEXACT");
                kit.readme.append(exact ? "round-trips through DPCM.\n" : "is DPCM_INEXACT against the package PCM.\n");
                pcm = kit.closed ? decoded : S3kDisasmDac.padded(pcm);
                kit.files.put(path, S3kDisasmDac.wav(pcm, rate));
            } else {
                kit.readme.append("requires ROM materialisation.\n");
                kit.warnings.add("UNMATERIALISED_STOCK");
            }
            samples.add(new Sample(asset.key(), group.getValue(), pcm, rate));
        }
        if (kit.closed) kit.warnings.add("DPCM_CLOSED");
        return new Plan(notes, samples, last);
    }

    static void emitSetup(DisassemblyKit kit, Plan plan) {
        if (plan.samples().isEmpty()) return;
        var source = new StringBuilder("; Apply these sections at their indicated locations. See README.md.\n");
        var labels = new HashMap<Integer, String>();
        for (var sample : plan.samples()) {
            for (var note : sample.notes()) labels.put(note.number(), dataLabel(kit, sample));
        }
        source.append("\n; 1. In DAC_Banks (Z80 source): replace chosen rows and append new rows.\n");
        labels.keySet().stream().sorted().forEach(note -> source.append("\tzmake68kBanks ")
                .append(labels.get(note)).append("\t; ").append(hex(note)).append('\n'));

        source.append("\n; 2. In startDACBank, immediately after the $C4 pointer and before the first DAC_Setup.\n");
        for (int note = LAST_RETAIL + 1; note <= plan.lastNote(); note++)
            source.append("\tdc.w $0000\t; ").append(hex(note)).append(" (only present in its custom bank)\n");

        source.append("\n; 3. After the retail DAC banks in the 68000 source.\n");
        int table = 2 * (plan.lastNote() - FIRST_RETAIL + 1);
        var banks = new ArrayList<List<Sample>>();
        var pending = new ArrayList<Sample>();
        int size = table;
        for (var sample : plan.samples()) {
            if (sample.pcm() != null && table + sample.bytes() > BANK_BYTES)
                throw new IllegalArgumentException("DAC sample " + sample.asset() + " needs " + (table + sample.bytes())
                        + " bytes including setup and pointers. S3K bank limit is $8000");
            if (!pending.isEmpty() && size + sample.bytes() > BANK_BYTES) {
                banks.add(pending);
                pending = new ArrayList<>();
                size = table;
            }
            pending.add(sample);
            size += sample.bytes();
        }
        if (!pending.isEmpty()) banks.add(pending);
        for (int i = 0; i < banks.size(); i++) emitBank(kit, source, banks.get(i), i + 1, plan.lastNote(), table);
        kit.text("dac-setup.asm", source);
    }

    private static void emitBank(DisassemblyKit kit, StringBuilder out, List<Sample> samples, int index, int lastNote, int table) {
        var inBank = new HashMap<Integer, Note>();
        for (var sample : samples) for (var note : sample.notes()) inBank.put(note.number(), note);
        out.append(kit.name).append("_DacBank").append(index).append(": startBank\n");
        for (int number = FIRST_RETAIL; number <= lastNote; number++) {
            var note = inBank.get(number);
            if (note == null) out.append("\tdc.w $0000\n");
            else out.append("\toffsetBankTableEntry.w ").append(note.constant()).append("_Setup\n");
        }
        for (var sample : samples) {
            String label = dataLabel(kit, sample);
            for (var note : sample.notes()) {
                out.append(note.constant()).append("_Setup:\n\tDAC_Setup ").append(label);
                if (note.rate() != sample.first().rate())
                    out.append(',').append(S3kDisasmDac.scale(sample.startRate(), note.rate()));
                out.append('\n');
            }
            out.append(label).append(":\n\tinclude \"Sound/DAC/generated/").append(suffix(sample.first().number()))
                    .append(".inc\"\n");
        }
        out.append("\tfinishBank\n\n");
        boolean known = samples.stream().allMatch(sample -> sample.pcm() != null);
        kit.readme.append("- Custom DAC bank ").append(index).append(" uses ")
                .append(known ? (table + samples.stream().mapToInt(Sample::bytes).sum()) + " of 32768 bytes"
                        : "a separate bank pending a local size check (32768-byte limit)")
                .append(", including pointers and setup.\n");
    }

    static String suffix(int note) {
        return String.format(Locale.ROOT, "%02X", note);
    }

    private static String dataLabel(DisassemblyKit kit, Sample sample) {
        return kit.name + "_Dac" + suffix(sample.first().number());
    }

    private static String retailName(int number, String prefix) {
        String name = RETAIL_NOTES.get(number - FIRST_RETAIL);
        return name.endsWith("EchoedClapHit") ? prefix + "_" + name : name;
    }
}
