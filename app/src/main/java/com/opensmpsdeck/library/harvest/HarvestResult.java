package com.opensmpsdeck.library.harvest;

import java.util.List;

public record HarvestResult(
        int addedCount,
        int duplicateCount,
        int skippedCount,
        List<String> warnings) {
}
