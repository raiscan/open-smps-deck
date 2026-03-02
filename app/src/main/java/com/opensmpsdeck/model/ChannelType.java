package com.opensmpsdeck.model;

public enum ChannelType {
    FM, DAC, PSG_TONE, PSG_NOISE;

    public static ChannelType fromChannelIndex(int ch) {
        return switch (ch) {
            case 0, 1, 2, 3, 4 -> FM;
            case 5 -> DAC;
            case 6, 7, 8 -> PSG_TONE;
            case 9 -> PSG_NOISE;
            default -> throw new IllegalArgumentException("Invalid channel index: " + ch);
        };
    }
}
