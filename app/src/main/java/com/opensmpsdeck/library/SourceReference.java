package com.opensmpsdeck.library;

public record SourceReference(
        String scanRoot,
        String driverFamily,
        String gameName,
        String variantPath,
        String configExtension,
        String sourceSongFile,
        String sourceCompanionFile,
        String originalIndexOrId,
        String driverSummary) {

    public static SourceReference minimal(String scanRoot, String sourceCompanionFile, String originalIndexOrId) {
        return new SourceReference(scanRoot, "", "", "", "", "", sourceCompanionFile, originalIndexOrId, "");
    }
}
