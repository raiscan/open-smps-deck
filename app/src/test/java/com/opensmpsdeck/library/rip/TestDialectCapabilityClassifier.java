package com.opensmpsdeck.library.rip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestDialectCapabilityClassifier {

    @Test
    void beforeParameterizedImportOnlyExistingModesAreFullImport() {
        SmpsDriverDefinition current = new SmpsDriverDefinition("Z80", "S2", "Default", "25", false);
        SmpsDriverDefinition preSmps = new SmpsDriverDefinition("Z80", "S2", "Default", "25", true);

        assertEquals(DialectCapability.FULL_IMPORT,
                DialectCapabilityClassifier.classify(".smp", current, true));
        assertEquals(DialectCapability.FULL_IMPORT,
                DialectCapabilityClassifier.classify(".sm2", current, true));
        assertEquals(DialectCapability.FULL_IMPORT,
                DialectCapabilityClassifier.classify(".s3k", current, true));
        assertEquals(DialectCapability.FULL_IMPORT,
                DialectCapabilityClassifier.classify(".bin", current, true));

        assertEquals(DialectCapability.ASSET_ONLY,
                DialectCapabilityClassifier.classify(".s1", current, true));
        assertEquals(DialectCapability.ASSET_ONLY,
                DialectCapabilityClassifier.classify(".sm2", current, false));
        assertEquals(DialectCapability.UNSUPPORTED,
                DialectCapabilityClassifier.classify(".sm2", preSmps, true));
        assertEquals(DialectCapability.ASSET_ONLY,
                DialectCapabilityClassifier.classify(".sm2", preSmps, false));
        assertEquals(DialectCapability.IGNORED,
                DialectCapabilityClassifier.classify("", current, true));
    }
}
