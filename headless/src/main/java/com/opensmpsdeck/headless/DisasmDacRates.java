package com.opensmpsdeck.headless;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Retail playback rate bytes keyed by native DAC note. */
final class DisasmDacRates {
    private DisasmDacRates() {}

    static Map<Integer, Integer> read(String driver) throws IOException {
        var path = "/stock/" + driver + "-dac-rates.txt";
        try (var stream = DisasmDacRates.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing retail DAC rate table " + path);
            var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.US_ASCII));
            var rates = new HashMap<Integer, Integer>();
            for (String line; (line = reader.readLine()) != null; ) {
                var parts = line.split(" ");
                rates.put(Integer.parseInt(parts[0], 16), Integer.parseInt(parts[1], 16));
            }
            return rates;
        }
    }
}
