package com.opensmpsdeck.headless;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.S3kDisasmEmitter.hex;

/** Maps portable virtual DAC notes onto the retail S1/S2 players. */
final class ClassicDisasmDac {
    private static final int CLOCK = 53693175 / 15;
    private static final int FIRST = 0x81;
    private static final int S2_LAST = 0x91;
    private static final List<String> S1_NAMES = List.of("dKick", "dSnare", "dTimpani",
            "dHiTimpani", "dMidTimpani", "dLowTimpani", "dVLowTimpani");
    private static final List<String> S2_NAMES = List.of("dKick", "dSnare", "dClap", "dScratch", "dTimpani",
            "dHiTom", "dVLowClap", "dHiTimpani", "dMidTimpani", "dLowTimpani", "dVLowTimpani",
            "dMidTom", "dLowTom", "dFloorTom", "dHiClap", "dMidClap", "dLowClap");

    private static final List<String> S2_SAMPLE_LABELS = List.of("SndDAC_Kick", "SndDAC_Snare",
            "SndDAC_Clap", "SndDAC_Scratch", "SndDAC_Timpani", "SndDAC_Tom", "SndDAC_Bongo");

    record Note(int number, String constant, int rate) {}

    private ClassicDisasmDac() {}

    static String z80Hex(int value) {
        return "0" + S3kDisasmSamples.suffix(value) + "h";
    }

    private static int intercept(boolean s1) {
        return (s1 ? 301 : 295) / 2 - 13 / 2;
    }

    static int sampleRate(boolean s1, int rate) {
        // ASL's integer divisions truncate toward zero. Pick a clock quotient
        // in the middle of the interval yielding the requested byte.
        int cycles = intercept(s1) + (rate == 0 ? -19 : (rate - 1) * 13 + 6);
        return (int) Math.round((double) CLOCK / cycles);
    }

    private static String retailName(boolean s1, int note) {
        if (!s1) return note >= FIRST && note <= S2_LAST ? S2_NAMES.get(note - FIRST) : null;
        if (note >= FIRST && note <= 0x83) return S1_NAMES.get(note - FIRST);
        return note >= 0x88 && note <= 0x8B ? S1_NAMES.get(note - 0x88 + 3) : null;
    }

    private static String s2StockSample(int note) {
        if (note <= 0x87) return S2_SAMPLE_LABELS.get(note - FIRST);
        if (note <= 0x8B) return "SndDAC_Timpani";
        if (note <= 0x8E) return "SndDAC_Tom";
        return "SndDAC_Bongo";
    }

    static Map<Integer, Note> emit(DisassemblyKit kit, boolean s1) throws IOException {
        var rates = kit.manifest.dacBindings().isEmpty()
                ? Map.<Integer, Integer>of() : DisasmDacRates.read(kit.manifest.driver());
        var notes = new LinkedHashMap<Integer, Note>();
        var custom = new ArrayList<PackageManifest.DacBinding>();
        var retail = new HashSet<Integer>();
        var stockPointers = new LinkedHashMap<String, String>();
        for (var binding : kit.manifest.dacBindings()) {
            var asset = kit.assets.get(binding.asset());
            var ref = asset.ref();
            String constant = ref == null ? null : retailName(s1, ref.note());
            boolean stock = constant != null && asset.transforms().isEmpty() && ClassicDisasmEmitter.catalogued(asset);
            if (stock && binding.rate() == rates.getOrDefault(ref.note(), -1)) {
                if (!kit.roms.isEmpty()) kit.materialise(asset, null);
                notes.put(binding.note(), new Note(ref.note(), constant, binding.rate()));
                retail.add(ref.note());
                continue;
            }
            if (stock && !s1) {
                if (!kit.roms.isEmpty()) kit.materialise(asset, null);
                stockPointers.put(asset.key(), s2StockSample(ref.note()));
            }
            custom.add(binding);
        }

        var replacements = new HashSet<Integer>();
        int lastReplaceable = s1 ? 0x82 : S2_LAST;
        for (int note : kit.replacements) {
            if (note < FIRST || note > lastReplaceable || retail.contains(note) || !replacements.add(note))
                throw new IllegalArgumentException("DAC replacement must be a unique unused retail note $81–"
                        + hex(lastReplaceable) + ": " + hex(note));
        }
        if (kit.replacements.size() > custom.size())
            throw new IllegalArgumentException("More --replace-dac slots than custom DAC bindings");
        if (!replacements.isEmpty()) kit.warnings.add("DAC_REPLACEMENT");
        int newNotes = custom.size() - replacements.size();
        if (s1 && newNotes > 0)
            throw new IllegalArgumentException("S1 custom DAC requires one explicit --replace-dac 81 or 82 per binding "
                    + "(kick/snare). More slots require a DAC driver patch");
        if (!s1 && newNotes > 0xDF - S2_LAST)
            throw new IllegalArgumentException("S2 DAC extensions end at $DF. Supply unused retail slots with --replace-dac");
        if (!s1 && custom.stream().map(PackageManifest.DacBinding::asset)
                .filter(asset -> !stockPointers.containsKey(asset)).distinct().count() > 57)
            throw new IllegalArgumentException("S2 supports at most 57 custom DAC samples in addition to the seven retail samples");

        kit.readme.append("## DAC notes\n\n");
        if (s1 && retail.contains(0x83))
            kit.readme.append("- Direct `dTimpani` uses mutable pitch state. Apply the direct-timpani pitch reset ")
                    .append("described in the install guide if any song uses pitch variants.\n");
        var playlist = new StringBuilder();
        var pointers = new StringBuilder();
        var data = new StringBuilder();
        var waveLabels = new LinkedHashMap<String, String>();
        long sampleBytes = 0;
        for (int i = 0; i < custom.size(); i++) {
            var binding = custom.get(i);
            int number = i < kit.replacements.size() ? kit.replacements.get(i) : S2_LAST + i + 1 - replacements.size();
            notes.put(binding.note(), new Note(number, kit.name + "_d" + S3kDisasmSamples.suffix(number), binding.rate()));
            if (s1) {
                emitWave(kit, binding, true, binding.rate());
                String label = number == FIRST ? "zDAC_Kick" : "zDAC_Snare";
                data.append(label).append(":\tinclude \"sound/dac/dpcm/generated/")
                        .append(waveStem(kit, binding)).append(".inc\"\n");
            } else {
                String label = stockPointers.getOrDefault(binding.asset(), waveLabels.get(binding.asset()));
                if (label == null) {
                    sampleBytes += emitWave(kit, binding, false, binding.rate());
                    label = kit.name + "_Sample" + waveLabels.size();
                    waveLabels.put(binding.asset(), label);
                    data.append(label).append(":\tinclude \"sound/DAC/generated/").append(waveStem(kit, binding))
                            .append(".inc\"\n");
                    pointers.append("\tdac_sample_pointer ").append(label).append('\n');
                }
                playlist.append("\tdb id(").append(label).append(".pointer),").append(z80Hex(binding.rate()))
                        .append("\t; ").append(hex(number)).append(i < replacements.size() ? " replace\n" : " append\n");
            }
        }
        if (!s1 && sampleBytes >= 0x8000)
            throw new IllegalArgumentException("Custom samples fill or exceed the S2 DAC bank before adding retail samples");
        for (var binding : kit.manifest.dacBindings()) {
            var note = notes.get(binding.note());
            kit.readme.append("- ").append(hex(binding.note())).append(" → `").append(note.constant()).append("` (")
                    .append(hex(note.number())).append("), rate ").append(hex(note.rate())).append(".\n");
        }
        if (notes.isEmpty()) kit.readme.append("No DAC bindings.\n");
        if (!custom.isEmpty()) {
            kit.readme.append("\nCopy the WAVs to their matching paths and apply `dac-setup.asm` using the disassembly-kit guide.\n");
            if (s1) {
                kit.text("dac-setup.asm", "; In sound/z80.asm replace the corresponding sample include lines.\n" + data);
            } else {
                kit.text("dac-setup.asm", "; In s2.asm before SndDAC_End.\n" + data
                        + "\n; Append to zDACPtrTbl. Set its ensure1byteoffset size to "
                        + 4 * (7 + waveLabels.size()) + ".\n" + pointers
                        + "\n; Apply to zDACMasterPlaylist as marked, retaining note order. Set its ensure1byteoffset size to "
                        + 2 * (S2_LAST - FIRST + 1 + newNotes) + ".\n" + playlist);
            }
        }
        if (kit.closed) kit.warnings.add("DPCM_CLOSED");
        return notes;
    }

    private static String waveStem(DisassemblyKit kit, PackageManifest.DacBinding binding) {
        return kit.name + "_" + S3kDisasmSamples.suffix(binding.note());
    }

    private static int emitWave(DisassemblyKit kit, PackageManifest.DacBinding binding, boolean s1, int rate) {
        var asset = kit.assets.get(binding.asset());
        byte[] pcm = kit.materialise(asset, kit.artifact.entries().get(asset.path()));
        String path = (s1 ? "sound/dac/dpcm/" : "sound/DAC/") + waveStem(kit, binding) + ".wav";
        kit.readme.append("- `").append(path).append("` (`").append(asset.key()).append("`) ");
        if (pcm == null) {
            kit.warnings.add("UNMATERIALISED_STOCK");
            kit.readme.append("requires ROM materialisation.\n");
            return 0;
        }
        byte[] decoded = S3kDisasmDac.quantize(pcm);
        boolean exact = Arrays.equals(pcm, decoded);
        if (!exact) kit.warnings.add("DPCM_INEXACT");
        kit.readme.append(exact ? "round-trips through DPCM.\n" : "is DPCM_INEXACT against the package PCM.\n");
        int capacity = s1 ? 0x2000 : 0x8000;
        if (decoded.length / 2 >= capacity)
            throw new IllegalArgumentException("DAC sample " + asset.key() + " fills or exceeds the target DAC region");
        kit.files.put(path, S3kDisasmDac.wav(kit.closed ? decoded : S3kDisasmDac.padded(pcm), sampleRate(s1, rate)));
        return decoded.length / 2;
    }
}
