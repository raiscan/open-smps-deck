package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Envelope;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.opensmpsdeck.headless.S3kDisasmEmitter.hex;

/** Emits the finite classic portable profile in the matching SMPS2ASM dialect. */
final class ClassicDisasmEmitter {
    private static final Map<Integer, String> FLAGS = Map.ofEntries(
            Map.entry(0xE0, "smpsPan"), Map.entry(0xE1, "smpsDetune"), Map.entry(0xE6, "smpsAlterVol"),
            Map.entry(0xE7, "dc.b smpsNoAttack"), Map.entry(0xE8, "smpsNoteFill"),
            Map.entry(0xE9, "smpsChangeTransposition"), Map.entry(0xEC, "smpsPSGAlterVol"),
            Map.entry(0xEF, "smpsFMvoice"), Map.entry(0xF0, "smpsModSet"), Map.entry(0xF2, "smpsStop"),
            Map.entry(0xF3, "smpsPSGform"), Map.entry(0xF4, "smpsModOff"), Map.entry(0xF5, "smpsPSGvoice"));

    private record Unit(int count, boolean dacNote) {}

    private final DisassemblyKit kit;
    private final ClassicPackageDriver driver;
    private final boolean s1;
    private final PackageManifest.Bank layout;
    private final byte[] bank;
    private final StringBuilder song = new StringBuilder();
    private final StringBuilder stock = new StringBuilder();

    private ClassicDisasmEmitter(DisassemblyKit kit, ClassicPackageDriver driver) {
        this.kit = kit;
        this.driver = driver;
        s1 = driver == ClassicPackageDriver.S1;
        layout = kit.manifest.bank();
        bank = kit.artifact.entries().get(layout.path()).clone();
    }

    static void emit(DisassemblyKit kit, ClassicPackageDriver driver) throws IOException {
        new ClassicDisasmEmitter(kit, driver).write();
    }

    static boolean catalogued(PackageManifest.Asset asset) {
        return StockTable.entries().stream().anyMatch(entry ->
                entry.kind().equals(asset.kind().name()) && entry.ref().equals(asset.ref()));
    }

    private void write() throws IOException {
        var units = new HashMap<Integer, Unit>();
        var psgSlots = kit.manifest.envelopeBindings().stream().map(PackageManifest.EnvelopeBinding::slot)
                .collect(Collectors.toSet());
        var dacNotes = kit.manifest.dacBindings().stream().map(PackageManifest.DacBinding::note)
                .collect(Collectors.toSet());
        ClassicPackageBank.walk(driver, bank, layout.base(), layout.localVoiceCount(), psgSlots, dacNotes,
                (at, count, dac) -> {
                    var unit = new Unit(count, dac && at(at) > 0x80 && at(at) < 0xE0);
                    var prior = units.putIfAbsent(at, unit);
                    driver.require(prior == null || prior.equals(unit), "shared DAC and pitched track at " + at);
                });
        int fm = at(2), psg = at(3);
        int headerEnd = 6 + 4 * fm + 6 * psg;
        int voiceStart = layout.localVoiceCount() == 0 ? bank.length : pointer(0);
        var labels = new TreeSet<Integer>();
        for (int channel = 0; channel < fm + psg; channel++) {
            labels.add(pointer(channel < fm ? 6 + 4 * channel : 6 + 4 * fm + 6 * (channel - fm)));
        }
        writeInstructions();
        var dac = ClassicDisasmDac.emit(kit, s1);
        emitEnvelopes();

        song.append("; ").append(driver.id()).append(" SMPS2ASM. Assemble with the target's _smps2asm_inc.asm.\n");
        dac.values().stream().filter(note -> note.constant().startsWith(kit.name + "_")).forEach(note ->
                song.append(note.constant()).append(" equ ").append(hex(note.number())).append('\n'));
        song.append(kit.name).append(":\n\tsmpsHeaderStartSong ").append(s1 ? 1 : 2).append('\n');
        line(layout.localVoiceCount() == 0 ? "smpsHeaderVoiceNull" : "smpsHeaderVoice " + kit.name + "_Voices");
        line("smpsHeaderChan " + literals(2, 2));
        line("smpsHeaderTempo " + literals(4, 2));
        for (int offset = 6; offset < headerEnd; ) {
            String macro = offset == 6 ? "smpsHeaderDAC" : offset < 6 + 4 * fm ? "smpsHeaderFM" : "smpsHeaderPSG";
            int count = macro.equals("smpsHeaderPSG") ? 6 : 4;
            line(macro + " " + label(pointer(offset)) + "," + literals(offset + 2, count - 2));
            offset += count;
        }
        for (int offset = headerEnd; offset < voiceStart; ) {
            if (labels.contains(offset)) song.append(label(offset)).append(":\n");
            var unit = units.get(offset);
            if (unit == null) {
                line("dc.b " + hex(at(offset++)));
                continue;
            }
            int command = at(offset);
            if (unit.dacNote()) {
                line("dc.b " + dac.get(command).constant() + "," + literals(offset + 1, 1));
            } else if (command < 0xE0) {
                line("dc.b " + literals(offset, unit.count()));
            } else if (command == 0xE0) {
                line("smpsPan " + literals(offset + 1, 1) + ",0");
            } else {
                line(FLAGS.get(command) + (unit.count() == 1 ? "" : " " + literals(offset + 1, unit.count() - 1)));
            }
            offset += unit.count();
        }
        emitVoices(voiceStart);
        if (!layout.relocationsComplete()) kit.warnings.add("INCOMPLETE_RELOCATIONS");
        String path = "sound/music/" + kit.name + ".asm";
        kit.text(path, song);
        if (!stock.isEmpty()) kit.text("sound/music/" + kit.name + "_stock.asm", stock);
    }

    private void writeInstructions() {
        var speed = kit.manifest.driverOptions().get("speedUpTempo");
        String tempo = speed.isJsonNull() ? hex(at(5)) : hex(speed.getAsInt());
        kit.readme.append("## Music\n\nCopy `sound/music/").append(kit.name).append(".asm` into ")
                .append(driver.id()).append("disasm. ");
        if (s1) {
            kit.readme.append("Include it after the SMPS2ASM macro include and point the selected `MusicIndex` row at `")
                    .append(kit.name).append("`. Set the matching `SpeedUpIndex` entry to ").append(tempo).append(".\n\n");
        } else {
            kit.readme.append("Replace the selected song's generated include, keeping its `Mus_*` label and `music_ptr` row. ")
                    .append("See the disassembly-kit guide for the compression list, ROM banks and buffer limit. ")
                    .append("Set the matching `zSpedUpTempoTable` entry to ").append(tempo).append(".\n\n");
        }
    }

    private void emitVoices(int start) {
        if (start == bank.length) return;
        song.append(kit.name).append("_Voices:\n");
        var byOffset = new HashMap<Integer, PackageManifest.Asset>();
        for (var asset : kit.manifest.assets())
            if (asset.bankOffset() != null) byOffset.put(asset.bankOffset(), asset);
        for (var fill : layout.fills()) byOffset.put(fill.offset(), kit.assets.get(fill.asset()));
        boolean complete = true;
        for (int offset = start; offset < bank.length; offset += 25) {
            byte[] embedded = Arrays.copyOfRange(bank, offset, offset + 25);
            var asset = byOffset.get(offset);
            driver.require(asset != null, "missing local FM asset at " + offset);
            if (kit.stock(asset, embedded)) {
                String label = kit.name + "_Voice" + (offset - start) / 25;
                String include = "sound/music/" + kit.name + "_stock.asm";
                if (stock.isEmpty()) line("include \"" + include + "\"");
                // Define the materialised bytes as a macro at the point of use,
                // so the actual voice table remains in the same order.
                byte[] bytes = kit.materialise(asset, embedded);
                stock.append(label).append(" macro\n");
                if (bytes == null) {
                    stock.append("\tfatal \"Re-export with --rom to materialise ").append(asset.key()).append("\"\n");
                    kit.warnings.add("UNMATERIALISED_STOCK");
                    complete = false;
                } else {
                    System.arraycopy(bytes, 0, bank, offset, 25);
                    stock.append("\tdc.b ").append(literals(offset, 25)).append('\n');
                }
                stock.append("\tendm\n");
                line(label);
            } else {
                line("dc.b " + literals(offset, 25));
            }
        }
        if (complete) StockAssets.verifyHash("Assembled bank", layout.assembledSha256(), bank);
        if (!stock.isEmpty()) kit.readme.append("Copy the companion `_stock.asm` alongside the music source.\n\n");
    }

    private void emitEnvelopes() {
        int retailEnd = s1 ? 9 : Sonic2RomEnvelopes.COUNT;
        int end = retailEnd;
        var rows = new StringBuilder();
        var payloads = new StringBuilder();
        var extensions = new HashMap<Integer, String>();
        String pointer = s1 ? "dc.l" : "dw";
        for (var binding : kit.manifest.envelopeBindings()) {
            int slot = binding.slot();
            driver.require(s1 || slot <= 128, "PSG envelope slot " + slot + " exceeds the S2 pointer index");
            var asset = kit.assets.get(binding.asset());
            var ref = asset.ref();
            boolean identity = ref != null && ref.index() == slot && slot <= retailEnd
                    && asset.transforms().isEmpty() && catalogued(asset);
            byte[] bytes = identity && kit.roms.isEmpty() ? null
                    : kit.materialise(asset, kit.artifact.entries().get(asset.path()));
            if (bytes != null) ClassicPackageBank.validateEnvelope(driver,
                    new Envelope(asset.key(), asset.name(), Envelope.PSG_VOLUME, StockAssets.unsigned(bytes)));
            if (identity) continue;
            String label = kit.name + "_Envelope" + slot;
            if (slot <= retailEnd) rows.append('\t').append(pointer).append(' ').append(label)
                    .append("\t; replace slot ").append(slot).append('\n');
            else extensions.put(slot, label);
            end = Math.max(slot, end);
            payloads.append(label).append(":\n");
            if (bytes == null) {
                payloads.append("\tfatal \"Re-export with --rom to materialise ").append(asset.key()).append("\"\n");
                kit.warnings.add("UNMATERIALISED_STOCK");
            } else {
                for (int start = 0; start < bytes.length; start += 16) {
                    payloads.append(s1 ? "\tdc.b " : "\tdb ");
                    for (int i = start; i < Math.min(start + 16, bytes.length); i++) {
                        if (i != start) payloads.append(',');
                        int value = bytes[i] & 255;
                        payloads.append(s1 ? hex(value) : ClassicDisasmDac.z80Hex(value));
                    }
                    payloads.append('\n');
                }
            }
        }
        for (int slot = retailEnd + 1; slot <= end; slot++)
            rows.append('\t').append(pointer).append(' ').append(extensions.getOrDefault(slot, s1 ? "PSG1" : "zPSG_Env1"))
                    .append("\t; append slot ").append(slot).append('\n');
        if (!rows.isEmpty()) {
            String table = s1 ? "PSG_Index" : "zPSG_EnvTbl";
            kit.text("envelope-setup.asm", "; Apply the pointer rows inside " + table
                    + " at the indicated slots.\n" + rows + "\n; Add the payloads outside the pointer table.\n" + payloads);
            kit.readme.append("\nApply `envelope-setup.asm` to `").append(table)
                    .append("`. Check other music and SFX that use any replaced slot.\n");
        }
    }

    private int at(int offset) { return bank[offset] & 255; }
    private int pointer(int offset) { return driver.word(bank, offset) - layout.base(); }
    private String label(int offset) { return kit.name + "_Track" + Integer.toHexString(offset); }
    private void line(String line) { song.append('\t').append(line).append('\n'); }

    private String literals(int offset, int count) {
        var out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i != 0) out.append(',');
            out.append(hex(at(offset + i)));
        }
        return out.toString();
    }
}
