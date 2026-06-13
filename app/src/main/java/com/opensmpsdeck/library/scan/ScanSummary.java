package com.opensmpsdeck.library.scan;

import com.opensmpsdeck.library.InstrumentAssetKind;

import java.util.List;
import java.util.Map;

public record ScanSummary(
        int filesVisited,
        int configDirectoriesFound,
        int fullSongImportsAttempted,
        int fullSongImportsSucceeded,
        int assetOnlyFoldersHarvested,
        int unsupportedSongDialects,
        int newAssets,
        int duplicateAssets,
        Map<InstrumentAssetKind, Integer> newAssetsByKind,
        Map<InstrumentAssetKind, Integer> duplicateAssetsByKind,
        Map<InstrumentAssetKind, Integer> totalLibraryCountsByKind,
        List<ScanFailure> failures) {
}
