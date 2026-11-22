package com.opensmpsdeck.headless;

import java.util.Arrays;
import java.util.Locale;

import static com.opensmpsdeck.headless.S3kDisasmEmitter.stockMacro;
import static com.opensmpsdeck.headless.S3kDisasmEmitter.voice;
import static com.opensmpsdeck.headless.S3kDisasmEmitter.voiceMacro;

/** Optional driver-resident assets. Package slot numbers remain unchanged. */
final class S3kDisasmResources {
    private S3kDisasmResources() {}

    static boolean globalVoices(DisassemblyKit kit, StringBuilder stock) {
        var global = kit.manifest.globalVoices();
        if (global == null) return false;
        byte[] data = kit.artifact.entries().get(global.path());
        var out = new StringBuilder("; Install these voices at their existing slots in z80_UniVoiceBank.\n")
                .append("SourceDriver set 3\nSourceSMPS2ASM set 1\n");
        boolean stockInclude = false;
        boolean changes = false;
        for (int slot = 0; slot < global.voices().size(); slot++) {
            var key = global.voices().get(slot);
            var asset = kit.assets.get(key);
            var ref = asset.ref();
            byte[] embedded = ref == null ? Arrays.copyOfRange(data, slot * 25, slot * 25 + 25) : null;
            byte[] materialised = kit.materialise(asset, embedded);
            if (ref != null && ref.table().equals("global-fm") && ref.slot() == slot && asset.transforms().isEmpty()
                    && StockTable.entries().stream().anyMatch(entry -> entry.kind().equals("FM_VOICE") && entry.ref().equals(ref))) {
                out.append("; Keep native slot ").append(slot).append(".\n");
                continue;
            }
            changes = true;
            out.append("; Replace or add slot ").append(slot).append(".\n");
            if (kit.stock(asset, embedded)) {
                stockInclude = true;
                if (materialised != null) stockMacro(kit, stock, key, materialised);
                out.append('\t').append(voiceMacro(kit, key)).append('\n');
            } else {
                voice(out, materialised, 0);
            }
        }
        if (changes) {
            if (stockInclude) out.insert(0, "\tinclude \"Sound/Music/" + kit.name + "_StockVoices.asm\"\n");
            kit.text("global-voices.asm", out);
            kit.readme.append("\n## Global voice table\n\nApply the slot edits in `global-voices.asm` to ")
                    .append("`z80_UniVoiceBank`. These driver-wide slots can affect other songs. ")
                    .append("Preserve the omitted native slots. Set the SMPS2ASM source mode as shown for the kit voices ")
                    .append("and restore the original mode for other voices.\n");
        }
        return stockInclude;
    }

    private static boolean hasModulationOperand(byte[] data) {
        for (byte value : data) {
            if (value == (byte) 0x82 || value == (byte) 0x84) return true;
        }
        return false;
    }

    static void envelopes(DisassemblyKit kit) {
        if (kit.manifest.envelopeBindings().isEmpty()) return;
        var out = new StringBuilder("; Apply these pointer and payload edits to the Z80 driver.\n");
        int byteCount = 0;
        int psgEnd = 39, modEnd = 8;
        boolean complete = true;
        for (var binding : kit.manifest.envelopeBindings()) {
            var asset = kit.assets.get(binding.asset());
            var ref = asset.ref();
            boolean mod = asset.kind() == PackageManifest.Kind.MODULATION;
            int slot = binding.slot();
            byte[] data = kit.materialise(asset, ref == null ? kit.artifact.entries().get(asset.path()) : null);
            boolean nativeEnvelope = ref != null && asset.transforms().isEmpty() && StockTable.entries().stream().anyMatch(entry ->
                    entry.kind().equals(asset.kind().name()) && entry.ref().equals(ref));
            if (mod) modEnd = Math.max(modEnd, slot);
            else psgEnd = Math.max(psgEnd, slot);
            String table = mod ? "z80_ModEnvPointers" : "z80_VolEnvPointers";
            out.append("; ").append(table).append(" slot ").append(slot).append(" (one based)\n");
            if (nativeEnvelope) {
                String label = (mod ? "ModEnv_" : "VolEnv_") + String.format(Locale.ROOT, "%02X", ref.index() - 1);
                if (ref.index() == slot) out.append("; Keep native pointer to ").append(label).append(".\n");
                else out.append("\tdw ").append(label).append('\n');
            } else {
                String label = kit.name + (mod ? "_Mod" : "_Vol") + slot;
                out.append("\tdw ").append(label).append('\n');
                if (data == null) {
                    out.append("; Missing payload. Re-export with --rom.\n");
                    kit.warnings.add("UNMATERIALISED_STOCK");
                    complete = false;
                } else {
                    out.append("; Payload (in the Z80 data region):\n").append(label).append(":\n");
                    for (int at = 0; at < data.length; at += 16) {
                        out.append("\tdb\t");
                        for (int i = at; i < Math.min(at + 16, data.length); i++) {
                            if (i != at) out.append(", ");
                            // ASL in Z80 mode uses a trailing h for hexadecimal.
                            out.append(String.format(Locale.ROOT, "0%02Xh", data[i] & 255));
                        }
                        out.append('\n');
                    }
                    byteCount += data.length;
                    if (mod && hasModulationOperand(data)) kit.warnings.add("CHECK_MODULATION_DRIVER");
                }
            }
        }
        kit.text("envelope-setup.asm", out);
        kit.readme.append("\n## Envelope tables\n\nApply `envelope-setup.asm` to the driver. Slots are one based. ")
                .append("Replace pointers at existing slots and pad any new gaps with pointers to an inert envelope. ")
                .append("Changes to existing slots affect other songs. The listed additions need ")
                .append(byteCount + 2 * (psgEnd - 39 + modEnd - 8)).append(complete ? " bytes" : " or more bytes")
                .append(" before removing any replaced payloads. See the integration guide for modulation operand handling.\n");
    }
}
