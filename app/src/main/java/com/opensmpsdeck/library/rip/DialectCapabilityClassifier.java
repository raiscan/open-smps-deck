package com.opensmpsdeck.library.rip;

import java.util.Locale;
import java.util.Set;

public final class DialectCapabilityClassifier {

    private static final Set<String> CURRENT_FULL_IMPORT_EXTENSIONS = Set.of(".smp", ".sm2", ".s3k", ".bin");

    private DialectCapabilityClassifier() {
    }

    public static DialectCapability classify(String extension, SmpsDriverDefinition definition, boolean songCandidate) {
        String normalizedExtension = normalizeExtension(extension);
        if (normalizedExtension.isEmpty()) {
            return DialectCapability.IGNORED;
        }
        if (songCandidate && definition == null) {
            return DialectCapability.UNSUPPORTED;
        }
        if (songCandidate && definition != null && definition.hasPreSmpsTrackHeader()) {
            return DialectCapability.UNSUPPORTED;
        }
        if (songCandidate && CURRENT_FULL_IMPORT_EXTENSIONS.contains(normalizedExtension)) {
            return DialectCapability.FULL_IMPORT;
        }
        return DialectCapability.ASSET_ONLY;
    }

    private static String normalizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return "";
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(".")) {
            normalized = "." + normalized;
        }
        return normalized;
    }
}
