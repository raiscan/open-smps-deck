package com.opensmpsdeck.headless;

import com.opensmpsdeck.headless.InstrumentCatalogue.Label;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Community names for retail S3K stock assets, keyed by exact payload hash.
 * Universal Voice Bank names come from the {@code z80_UniVoiceBank} comments in
 * skdisasm's {@code Sound/Z80 Sound Driver.asm}. DAC names are the S&K note
 * constants from skdisasm's {@code Sound/_smps2asm_inc.asm}. Both were chosen
 * by ear and are not Sega's names.
 */
final class StockNames {
    static final String SOURCE = "skdisasm";

    /** Slots 00h–22h. Slots 11h and 19h share a name in the source. */
    static final List<String> UNIVERSAL_VOICES = List.of(
            "Synth Bass 2", "Trumpet 1", "Slap Bass 2", "Synth Bass 1", "Bell Synth 1", "Bell Synth 2",
            "Synth Brass 1", "Synth like Bassoon", "Bell Horn type thing", "Synth Bass 3", "Synth Trumpet",
            "Wood Block", "Tubular Bell", "Strike Bass", "Elec Piano", "Bright Piano", "Church Bell",
            "Synth Brass 2", "Bell Piano", "Wet Wood Bass", "Silent Bass", "Picked Bass", "Xylophone",
            "Sine Flute", "Pipe Organ", "Synth Brass 2", "Harpsichord", "Metallic Bass",
            "Alternate Metallic Bass", "Backdropped Metallic Bass", "Sine like Bell",
            "Synth like Metallic with Small Bell", "Nice Synth like lead", "Rock Organ", "Strike like Slap Bass");

    private record Named(Label label, Integer rate) {}

    private static final Map<String, List<Named>> BY_HASH = load();
    private static final Map<String, List<Label>> CACHE = new ConcurrentHashMap<>();

    private StockNames() {}

    /**
     * Labels for an S3K asset payload. A DAC rate narrows the labels to the
     * notes that play this PCM at that rate. Without a match, all notes apply.
     */
    static List<Label> labels(InstrumentCatalogue.Kind kind, byte[] bytes, Integer rate) {
        String hash = StockTable.hash(bytes);
        var named = BY_HASH.getOrDefault(kind.name() + ":" + hash, List.of());
        if (named.isEmpty()) return List.of();
        return CACHE.computeIfAbsent(kind + ":" + hash + ":" + rate, ignored -> {
            var exact = named.stream().filter(item -> rate != null && rate.equals(item.rate()))
                    .map(Named::label).toList();
            return exact.isEmpty() ? named.stream().map(Named::label).toList() : exact;
        });
    }

    private static Map<String, List<Named>> load() {
        Map<Integer, Integer> rates;
        try {
            rates = DisasmDacRates.read("s3k");
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
        var notes = S3kDisasmSamples.retailNoteNames();
        var result = new HashMap<String, List<Named>>();
        for (var entry : StockTable.entries()) {
            if (!entry.game().equals("s3k") || entry.table() == null) continue;
            Named named = null;
            if (entry.table().equals("global-fm") && entry.slot() != null
                    && entry.slot() < UNIVERSAL_VOICES.size()) {
                named = new Named(new Label(UNIVERSAL_VOICES.get(entry.slot()), SOURCE,
                        "UVB %02Xh".formatted(entry.slot())), null);
            } else if (entry.table().equals("dac") && entry.note() != null && entry.note() >= 0x81
                    && entry.note() - 0x81 < notes.size()) {
                named = new Named(new Label(notes.get(entry.note() - 0x81), SOURCE,
                        "DAC %02Xh".formatted(entry.note())), rates.get(entry.note()));
            }
            if (named != null)
                result.computeIfAbsent(entry.kind() + ":" + entry.sha256(), ignored -> new ArrayList<>()).add(named);
        }
        result.replaceAll((key, value) -> List.copyOf(value));
        return Map.copyOf(result);
    }
}
