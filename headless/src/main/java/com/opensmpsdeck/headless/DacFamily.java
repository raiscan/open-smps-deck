package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;

import java.util.Set;

/** Drum families identified by the native driver's DAC note assignments. */
enum DacFamily {
    KICK, SNARE, CLAP, TOM, OTHER;

    // The S3K shortlist uses the subset historically used to corroborate mix kicks.
    private static final Set<Integer> S3K_MIX_KICKS = Set.of(0x86, 0x9d, 0xa4, 0xa7, 0xa8, 0xbd);

    static DacFamily slot(TargetGame target, int slot) {
        if (target.s3kRules()) return valueOf(S3kDacFamily.slot(slot).name());
        return switch (stockName(target, slot)) {
            case "kick" -> KICK;
            case "snare" -> SNARE;
            case "clap" -> CLAP;
            case "timpani", "tom", "bongo" -> TOM;
            default -> OTHER;
        };
    }

    static String stockName(TargetGame target, int slot) {
        if (slot == 0x81) return "kick";
        if (slot == 0x82) return "snare";
        if (target == TargetGame.S1) {
            return slot == 0x83 || slot >= 0x88 && slot <= 0x8d ? "timpani" : "other";
        }
        return switch (slot) {
            case 0x83 -> "clap";
            case 0x84 -> "scratch";
            case 0x85, 0x88, 0x89, 0x8a, 0x8b -> "timpani";
            case 0x86, 0x8c, 0x8d, 0x8e -> "tom";
            case 0x87, 0x8f, 0x90, 0x91 -> "bongo";
            default -> "other";
        };
    }

    static DacFamily occurrence(TargetGame target, InstrumentCatalogue.Occurrence usage) {
        if (usage.slot() == null || !"dac".equals(usage.table())) return OTHER;
        if (target.s3kRules()) {
            return CatalogueMatcher.LOCKED_ON_S3K.equals(usage.revision()) ? slot(target, usage.slot()) : OTHER;
        }
        return target.name().equalsIgnoreCase(usage.driver()) ? slot(target, usage.slot()) : OTHER;
    }

    static boolean corroboratedKick(TargetGame target, InstrumentCatalogue.Occurrence usage) {
        if (!target.s3kRules()) return occurrence(target, usage) == KICK;
        return CatalogueMatcher.LOCKED_ON_S3K.equals(usage.revision()) && "S3K".equals(usage.driver())
                && "dac".equals(usage.table()) && usage.slot() != null && S3K_MIX_KICKS.contains(usage.slot());
    }
}
