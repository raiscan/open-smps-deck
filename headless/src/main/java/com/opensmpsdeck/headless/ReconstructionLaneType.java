package com.opensmpsdeck.headless;

import java.util.List;

/** Hardware constraints shared by voice assignments and per-note allocation. */
public enum ReconstructionLaneType {
    FM, PSG, NOISE, DAC;

    boolean isDrum() { return this == NOISE || this == DAC; }

    List<String> lanes() {
        return switch (this) {
            case FM -> ReconstructionAllocation.FM;
            case PSG -> ReconstructionAllocation.PSG;
            case NOISE -> List.of("PSG3");
            case DAC -> List.of("DAC");
        };
    }

    boolean accepts(String lane, boolean drums) {
        return isDrum() == drums && lanes().contains(lane);
    }
}
