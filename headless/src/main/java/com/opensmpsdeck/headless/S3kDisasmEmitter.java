package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.Voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.IntStream;

import static com.opensmpsdeck.headless.PortablePackageWriter.word;

final class S3kDisasmEmitter {
    private record Event(int size, boolean dac) {}
    private record VoiceFill(String asset, int size, byte[] bytes) {}

    private final DisassemblyKit kit;
    private final PackageManifest.Bank layout;
    private final byte[] bank;
    private final int header;
    private final int fm;
    private final int psg;
    private final int voiceStart;
    private final Map<Integer, Event> events = new TreeMap<>();
    private final TreeMap<Integer, Integer> relocations = new TreeMap<>();
    private final TreeSet<Integer> labels = new TreeSet<>();
    private final TreeMap<Integer, VoiceFill> fills = new TreeMap<>();
    private final StringBuilder stock = new StringBuilder();
    private final StringBuilder song = new StringBuilder();
    private final S3kDisasmSamples.Plan dac;
    private boolean traversalComplete;

    private S3kDisasmEmitter(DisassemblyKit kit) throws java.io.IOException {
        this.kit = kit;
        layout = kit.manifest.bank();
        bank = kit.artifact.entries().get(layout.path()).clone();
        header = layout.headerOffset();
        // Containment and channel counts are also enforced for package inputs.
        program();
        fm = at(header + 2);
        psg = at(header + 3);
        voiceStart = layout.localVoiceCount() > 0 ? word(bank, header) - layout.base() : -1;
        if (layout.localVoiceCount() > 0 && (voiceStart < 0 || voiceStart + 25 * layout.localVoiceCount() > bank.length))
            throw new IllegalArgumentException("Local voice table extends outside the S3K bank");
        kit.readme.append("## Music\n\n")
                .append("Copy `Sound/Music/").append(kit.name).append(".asm` (and any stock voice include) into skdisasm. ")
                .append("Include the source inside a music `startBank` / `finishBank` pair. ")
                .append("Use `").append(kit.name).append("` as the song entry in `z80_MusicPointers` ")
                .append("and use the bank label in `z80_MusicBanks` for the same music ID. ")
                .append("Preserve any bytes before the header.\n\n")
                .append("## DAC files and note mapping\n\n");
        dac = S3kDisasmSamples.plan(kit);
    }

    static void emit(DisassemblyKit kit) throws java.io.IOException {
        new S3kDisasmEmitter(kit).write();
    }

    private NativeProgram program() {
        return NativeProgram.of(layout.base(), header, layout.localVoiceCount(), layout.previewFrames(), bank,
                kit.manifest.envelopeBindings().stream().map(slot -> new NativeProgram.EnvelopeSlot(slot.asset(), slot.slot())).toList(),
                kit.manifest.dacBindings().stream().map(slot -> new NativeProgram.DacSlot(slot.note(), slot.asset(), slot.rate())).toList());
    }

    private void write() {
        for (var fill : layout.fills()) addFill(fill.offset(), fill.asset(), null);
        for (var asset : kit.manifest.assets()) {
            if (asset.bankOffset() == null) continue;
            int offset = asset.bankOffset();
            byte[] bytes = Arrays.copyOfRange(bank, offset, offset + 25);
            if (kit.stock(asset, bytes)) addFill(offset, asset.key(), bytes);
        }
        if (fills.values().stream().allMatch(fill -> fill.bytes() != null))
            StockAssets.verifyHash("Assembled bank", layout.assembledSha256(), bank);
        discoverEvents();
        layout.relocations().forEach(this::addRelocation);
        if (!layout.relocationsComplete() || !traversalComplete) kit.warnings.add("INCOMPLETE_RELOCATIONS");

        for (var binding : kit.manifest.dacBindings()) {
            var note = dac.notes().get(binding.note());
            kit.readme.append("- ").append(hex(binding.note())).append(" → `").append(note.constant())
                    .append("` (").append(hex(note.number())).append("), rate ").append(hex(note.rate())).append(".\n");
        }
        if (dac.notes().isEmpty()) kit.readme.append("No DAC bindings.\n");
        S3kDisasmSamples.emitSetup(kit, dac);
        if (!dac.samples().isEmpty()) {
            kit.readme.append("\nCopy the WAV files into `Sound/DAC`. Apply the three sections of `dac-setup.asm` ")
                    .append("to the Z80 bank selector table, the retail pointer macro, and the 68000 DAC bank area, respectively. ")
                    .append("Run the build script to regenerate the DPCM includes. ")
                    .append("Use the OpenSMPSDeck disassembly-kit guide for driver layout, bank fit, and S3 loader sizing.\n");
        }

        var globals = S3kDisasmResources.globalVoices(kit, stock);
        S3kDisasmResources.envelopes(kit);
        song.append("; S3/S&K SMPS2ASM. Use the song label for the music pointer.\n");
        var constants = dac.notes().values().stream().filter(note -> note.constant().startsWith(kit.name + "_"))
                .distinct().toList();
        for (var note : constants) {
            // S3 and S&K name these two constants differently.
            if (note.constant().endsWith("EchoedClapHit")) {
                String name = note.constant().substring(kit.name.length() + 1);
                song.append("\tif SonicDriverVer=3\n").append(note.constant()).append(" equ ").append(name)
                        .append("_S3\n\telse\n").append(note.constant()).append(" equ ").append(name).append("\n\tendif\n");
            } else {
                song.append(note.constant()).append(" equ ").append(hex(note.number())).append('\n');
            }
        }
        if (!fills.isEmpty() || globals) {
            if (!fills.isEmpty()) song.append("\tinclude \"Sound/Music/").append(kit.name).append("_StockVoices.asm\"\n");
            kit.readme.append("\n`Sound/Music/").append(kit.name)
                    .append("_StockVoices.asm` defines the referenced 25-byte voices. ")
                    .append("Re-export with `--rom PATH` if the include is missing.\n\n");
            if (!kit.roms.isEmpty()) kit.text("Sound/Music/" + kit.name + "_StockVoices.asm", stock);
            else kit.warnings.add("UNMATERIALISED_STOCK");
        }

        var emittedLabels = new TreeSet<Integer>();
        song.append(kit.name).append("_BankStart:\n");
        int offset = 0;
        while (offset < bank.length) {
            if (labels.contains(offset)) {
                song.append(label(offset)).append(":\n");
                emittedLabels.add(offset);
            }
            if (offset == header) {
                offset = emitHeader();
                continue;
            }
            var fill = fills.get(offset);
            if (fill != null) {
                macro(song, voiceMacro(kit, fill.asset()));
                offset += fill.size();
                continue;
            }
            if (voiceStart >= 0 && offset >= voiceStart && offset < voiceStart + 25 * layout.localVoiceCount()
                    && (offset - voiceStart) % 25 == 0 && !insideBoundary(offset, 25)) {
                voice(song, bank, offset);
                offset += 25;
                continue;
            }
            var event = events.get(offset);
            if (event != null && !insideBoundary(offset, event.size()) && emitEvent(offset, event)) {
                offset += event.size();
            } else if (relocations.containsKey(offset)) {
                macro(song, "dc.w", "z80_ptr(" + label(relocations.get(offset)) + ")");
                offset += 2;
            } else {
                int end = offset + 1;
                while (end < bank.length && end < offset + 16 && !isBoundary(end)) end++;
                bytes(song, bank, offset, end);
                offset = end;
            }
        }
        // A pointer can target a byte inside a formatted header entry or
        // voice. An alias preserves that address without splitting the macro.
        for (int target : labels) {
            if (!emittedLabels.contains(target))
                song.append(label(target)).append(" equ ").append(kit.name).append("_BankStart+").append(hex(target)).append('\n');
        }
        kit.text("Sound/Music/" + kit.name + ".asm", song);
    }

    private void addFill(int offset, String key, byte[] embedded) {
        byte[] bytes = kit.materialise(kit.assets.get(key), embedded);
        if (bytes != null) {
            System.arraycopy(bytes, 0, bank, offset, 25);
            stockMacro(kit, stock, key, bytes);
        }
        fills.put(offset, new VoiceFill(key, 25, bytes));
    }

    private void discoverEvents() {
        var envelopes = new ArrayList<Envelope>();
        var samples = new ArrayList<DacSample>();
        var global = new ArrayList<Voice>();
        for (var asset : kit.assets.values()) {
            switch (asset.kind()) {
                case DAC_SAMPLE -> samples.add(DacSample.of(asset.key(), asset.name(), 0, new byte[1]));
                case PSG_VOLUME, MODULATION -> envelopes.add(new Envelope(asset.key(), asset.name(), asset.kind().name(), List.of(0x81)));
                case FM_VOICE -> {}
            }
        }
        if (kit.manifest.globalVoices() != null) {
            for (String key : kit.manifest.globalVoices().voices())
                global.add(new Voice(key, key, java.util.Collections.nCopies(25, 0)));
        }
        try {
            var analysis = NativeBankAnalysis.inspectForEditing(new CompiledProject(bank, envelopes, samples, program(), global));
            for (var event : analysis.events())
                events.put(event.address() - layout.base(), new Event(event.bytes().size(), event.meaning().equals("DAC sample")));
            analysis.relocations().forEach(this::addRelocation);
            traversalComplete = analysis.relocationsComplete();
        } catch (IllegalArgumentException unsupported) {
            traversalComplete = false;
        }
        // Compare channel contexts independently. The playback decoder stores one
        // event per offset, while two tracks can address the same bytes.
        var structural = new TreeMap<Integer, Event>();
        var ambiguous = new TreeSet<Integer>();
        S3kDisasmTraversal.walk(bank, layout.base(), header, voiceStart, layout.localVoiceCount(), fills.keySet(),
                (at, size, isDac) -> {
                    var event = new Event(size, isDac);
                    var previous = structural.putIfAbsent(at, event);
                    if (previous != null && (previous.size() != size || previous.dac() != isDac
                            && dac.notes().containsKey(this.at(at)))) ambiguous.add(at);
                }, at -> {
                    if (events.isEmpty()) addRelocation(at);
                });
        if (events.isEmpty()) events.putAll(structural);
        if (!ambiguous.isEmpty()) {
            traversalComplete = false;
            ambiguous.forEach(events::remove);
        }
        if (voiceStart >= 0) addRelocation(header);
    }

    private void addRelocation(int at) {
        int target = word(bank, at) - layout.base();
        if (target >= 0 && target < bank.length) {
            relocations.put(at, target);
            labels.add(target);
        } else {
            kit.warnings.add("INCOMPLETE_RELOCATIONS");
        }
    }

    private int emitHeader() {
        song.append(kit.name).append(":\n");
        macro(song, "smpsHeaderStartSong", "3", "1");
        if (relocations.containsKey(header)) {
            macro(song, "smpsHeaderVoice", label(relocations.get(header)));
        } else if (word(bank, header) == 0 && (fm == 1 || kit.manifest.globalVoices() == null)) {
            macro(song, "smpsHeaderVoiceNull");
        } else if (word(bank, header) == 0 || word(bank, header) == 0x17D8) {
            macro(song, "smpsHeaderVoiceUVB");
        } else if (kit.manifest.globalVoices() != null && word(bank, header) > 0x17D8
                && (word(bank, header) - 0x17D8) % 25 == 0 && word(bank, header) < 0x8000) {
            macro(song, "dc.w", "little_endian(z80_UniVoiceBank+" + hex(word(bank, header) - 0x17D8) + ")");
        } else {
            bytes(song, bank, header, header + 2);
        }
        macro(song, "smpsHeaderChan", hex(fm), hex(psg));
        macro(song, "smpsHeaderTempo", hex(at(header + 4)), hex(at(header + 5)));
        int offset = header + 6;
        for (int i = 0; i < fm + psg; i++) {
            int size = i < fm ? 4 : 6;
            if ((i == 6 && fm == 7) || !relocations.containsKey(offset)) {
                bytes(song, bank, offset, offset + size);
            } else {
                var args = new ArrayList<String>();
                args.add(label(relocations.get(offset)));
                for (int field = 2; field < size; field++) args.add(hex(at(offset + field)));
                macro(song, i == 0 ? "smpsHeaderDAC" : i < fm ? "smpsHeaderFM" : "smpsHeaderPSG", args.toArray(String[]::new));
            }
            offset += size;
        }
        return offset;
    }

    private boolean emitEvent(int offset, Event event) {
        int op = at(offset), size = event.size();
        if (op < 0xE0) {
            if (event.dac() && size == 1 && dac.notes().containsKey(op)) {
                macro(song, "dc.b", dac.notes().get(op).constant());
                return true;
            }
            return false;
        }
        if (op == 0xEA && at(offset + 1) < 0x80) {
            var mapped = dac.notes().get(at(offset + 1) | 0x80);
            if (mapped != null) {
                macro(song, "smpsPlayDACSample", mapped.constant());
                return true;
            }
        }
        int branch = switch (op) {
            case 0xEB -> 2;
            case 0xF6, 0xF8, 0xFC -> 1;
            case 0xF7 -> 3;
            default -> 0;
        };
        if (branch != 0) {
            Integer target = relocations.get(offset + branch);
            if (target == null) return false;
            var args = new ArrayList<String>();
            for (int i = 1; i < branch; i++) args.add(hex(at(offset + i)));
            args.add(label(target));
            macro(song, switch (op) {
                case 0xEB -> "smpsConditionalJump";
                case 0xF6 -> "smpsJump";
                case 0xF7 -> "smpsLoop";
                case 0xF8 -> "smpsCall";
                default -> "smpsContinuousLoop";
            }, args.toArray(String[]::new));
            return true;
        }

        String command = switch (op) {
            case 0xE0 -> "smpsPan";
            case 0xE1 -> "smpsDetune";
            case 0xE2 -> "smpsFade";
            case 0xE3 -> "smpsStopFM";
            case 0xE4 -> "smpsSetVol";
            case 0xE5, 0xE6 -> "smpsFMAlterVol";
            case 0xE7 -> "smpsNoAttack";
            case 0xE8 -> "smpsNoteFill";
            case 0xE9 -> "smpsSpindashRev";
            case 0xEC -> "smpsPSGAlterVol";
            case 0xED -> "smpsSetNote";
            case 0xEE -> "smpsFMICommand";
            case 0xEF -> size == 2 ? "smpsFMvoice" : null;
            case 0xF0 -> "smpsModSet";
            case 0xF1 -> "smpsModChange2";
            case 0xF2 -> "smpsStop";
            case 0xF3 -> "smpsPSGform";
            case 0xF4 -> "smpsModChange";
            case 0xF5 -> "smpsPSGvoice";
            case 0xF9 -> "smpsReturn";
            case 0xFA -> "smpsModOff";
            case 0xFB -> "smpsChangeTransposition";
            case 0xFD -> "smpsAlternateSMPS";
            case 0xFE -> "smpsFM3SpecialMode";
            default -> null;
        };
        if (op == 0xFF && size >= 2) {
            emitMeta(offset, size);
        } else if (command == null) {
            bytes(song, bank, offset, offset + size);
        } else if (op == 0xE7) {
            macro(song, "dc.b", command);
        } else if (op == 0xE0) {
            macro(song, command, hex(at(offset + 1) & 0xC0), hex(at(offset + 1) & 0x3F));
        } else {
            macro(song, command, IntStream.range(1, size).mapToObj(i -> hex(at(offset + i))).toArray(String[]::new));
        }
        return true;
    }

    private void emitMeta(int offset, int size) {
        int subcommand = at(offset + 1);
        switch (subcommand) {
            case 0x00 -> macro(song, "smpsSetTempoMod", hex(at(offset + 2)));
            case 0x01 -> macro(song, "smpsPlaySound", hex(at(offset + 2)));
            case 0x02 -> macro(song, "smpsHaltMusic", hex(at(offset + 2)));
            case 0x04 -> macro(song, "smpsSetTempoDiv", hex(at(offset + 2)));
            case 0x05 -> macro(song, "smpsSSGEG", hex(at(offset + 2)), hex(at(offset + 4)),
                    hex(at(offset + 3)), hex(at(offset + 5)));
            case 0x06 -> macro(song, "smpsFMVolEnv", hex(at(offset + 2)), hex(at(offset + 3)));
            case 0x07 -> macro(song, "smpsResetSpindashRev");
            default -> bytes(song, bank, offset, offset + size);
        }
    }

    private boolean isBoundary(int offset) {
        return labels.contains(offset) || events.containsKey(offset) || fills.containsKey(offset)
                || relocations.containsKey(offset) || offset == header || voiceStart >= 0
                && offset >= voiceStart && offset < voiceStart + 25 * layout.localVoiceCount() && (offset - voiceStart) % 25 == 0;
    }

    private boolean insideBoundary(int offset, int size) {
        for (int i = offset + 1; i < offset + size; i++)
            if (labels.contains(i) || fills.containsKey(i) || i == header) return true;
        return false;
    }

    private int at(int offset) { return bank[offset] & 255; }

    private String label(int offset) { return kit.name + "_" + String.format(Locale.ROOT, "%04X", offset); }

    static String hex(int value) { return String.format(Locale.ROOT, "$%02X", value); }

    static void macro(StringBuilder out, String name, String... args) {
        out.append('\t').append(name);
        if (args.length > 0) out.append('\t').append(String.join(", ", args));
        out.append('\n');
    }

    static void bytes(StringBuilder out, byte[] data, int start, int end) {
        macro(out, "dc.b", IntStream.range(start, end).mapToObj(i -> hex(data[i] & 255)).toArray(String[]::new));
    }

    static String voiceMacro(DisassemblyKit kit, String key) { return kit.name + "_" + key.replace('/', '_'); }

    static void stockMacro(DisassemblyKit kit, StringBuilder stock, String key, byte[] voice) {
        stock.append(voiceMacro(kit, key)).append(" macro\n");
        voice(stock, voice, 0);
        stock.append("\tendm\n");
    }

    static void voice(StringBuilder out, byte[] data, int offset) {
        // Local voices assemble in 68000 mode and universal voices assemble
        // in Z80 mode. Decimal operands work in both ASL numeral dialects.
        int first = data[offset] & 255;
        macro(out, "smpsVcAlgorithm", Integer.toString(first & 7));
        macro(out, "smpsVcFeedback", Integer.toString((first >> 3) & 7));
        var unused = operators(data, offset + 9, 5, 3);
        macro(out, "smpsVcUnusedBits", Integer.toString(first >> 6), unused[0], unused[1], unused[2], unused[3]);
        macro(out, "smpsVcDetune", operators(data, offset + 1, 4, 15));
        macro(out, "smpsVcCoarseFreq", operators(data, offset + 1, 0, 15));
        macro(out, "smpsVcRateScale", operators(data, offset + 5, 6, 3));
        macro(out, "smpsVcAttackRate", operators(data, offset + 5, 0, 63));
        macro(out, "smpsVcAmpMod", operators(data, offset + 9, 7, 1));
        macro(out, "smpsVcDecayRate1", operators(data, offset + 9, 0, 31));
        macro(out, "smpsVcDecayRate2", operators(data, offset + 13, 0, 255));
        macro(out, "smpsVcDecayLevel", operators(data, offset + 17, 4, 15));
        macro(out, "smpsVcReleaseRate", operators(data, offset + 17, 0, 15));
        macro(out, "smpsVcTotalLevel", operators(data, offset + 21, 0, 255));
    }

    private static String[] operators(byte[] data, int offset, int shift, int mask) {
        return IntStream.range(0, 4).mapToObj(i -> Integer.toString((data[offset + 3 - i] >> shift) & mask)).toArray(String[]::new);
    }
}
