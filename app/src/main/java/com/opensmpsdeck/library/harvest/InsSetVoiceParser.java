package com.opensmpsdeck.library.harvest;

import com.opensmpsdeck.io.FmVoiceLayoutNormalizer;
import com.opensmpsdeck.library.rip.SmpsDriverDefinition;
import com.opensmpsdeck.model.FmVoice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class InsSetVoiceParser {

    private InsSetVoiceParser() {
    }

    public static List<FmVoice> parse(Path file, String extension, SmpsDriverDefinition definition)
            throws IOException {
        String insMode = normalize(definition == null ? "" : definition.insMode());
        if (insMode.isBlank()) {
            insMode = "default";
        }
        if (insMode.equals("custom") || insMode.equals("interleaved")) {
            return List.of();
        }
        if (insMode.equals("hardware") && !isConfirmedHardwareMapping(definition)) {
            return List.of();
        }
        if (!insMode.equals("default") && !insMode.equals("hardware")) {
            return List.of();
        }

        byte[] data = Files.readAllBytes(file);
        List<FmVoice> voices = new ArrayList<>();
        int voiceCount = data.length / FmVoice.VOICE_SIZE;
        for (int i = 0; i < voiceCount; i++) {
            byte[] rawVoice = Arrays.copyOfRange(
                    data, i * FmVoice.VOICE_SIZE, (i + 1) * FmVoice.VOICE_SIZE);
            Optional<byte[]> normalized = FmVoiceLayoutNormalizer.normalizeForExtension(rawVoice, extension);
            if (normalized.isPresent()) {
                voices.add(new FmVoice("Voice " + i, normalized.get()));
            }
        }
        return List.copyOf(voices);
    }

    private static boolean isConfirmedHardwareMapping(SmpsDriverDefinition definition) {
        String insRegs = normalize(definition == null ? "" : definition.insRegs());
        return insRegs.equals("algo") || insRegs.equals("bit7");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
