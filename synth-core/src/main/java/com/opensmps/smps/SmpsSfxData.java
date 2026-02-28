package com.opensmps.smps;

import java.util.List;

/**
 * Interface for SMPS sound effect data.
 *
 * <p>Sound effects use a simplified header format compared to music:
 * a tick multiplier and a list of track entries, each specifying the
 * target channel, data pointer, transpose, and volume offset.
 */
public interface SmpsSfxData {
    int getTickMultiplier();

    List<? extends SmpsSfxTrack> getTrackEntries();

    interface SmpsSfxTrack {
        int channelMask();

        int pointer();

        int transpose();

        int volume();
    }
}
