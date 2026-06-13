package com.opensmpsdeck.io;

import com.opensmps.smps.SmpsCoordFlags;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.SmpsMode;

import java.util.Locale;
import java.util.Optional;

public final class FmVoiceLayoutNormalizer {

    private FmVoiceLayoutNormalizer() {
    }

    public static byte[] normalizeForMode(byte[] voiceData, SmpsMode mode) {
        byte[] copy = voiceData.clone();
        return mode.dialect() == SmpsCoordFlags.Dialect.S2
                ? copy
                : FmVoice.swapMiddleOperators(copy);
    }

    public static Optional<byte[]> normalizeForExtension(byte[] voiceData, String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        SmpsMode mode = switch (ext) {
            case ".smp" -> SmpsMode.S1;
            case ".s3k" -> SmpsMode.S3K;
            case ".sm2", ".bin" -> SmpsMode.S2;
            default -> null;
        };
        return mode == null ? Optional.empty() : Optional.of(normalizeForMode(voiceData, mode));
    }
}
