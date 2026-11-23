package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;

final class ReconstructionCatalogueFixture {
    private ReconstructionCatalogueFixture() {}

    static InstrumentCatalogue.Occurrence stock() {
        return stock(TargetGame.S3K);
    }

    static InstrumentCatalogue.Occurrence stock(TargetGame target) {
        return new InstrumentCatalogue.Occurrence("fixture", target.id(), "test",
                "Synthetic test song", 1, null, null, null, false);
    }
}
