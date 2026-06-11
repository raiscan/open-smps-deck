package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.model.DacSample;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.SmpsMode;

import java.util.List;
import java.util.Map;

/** Everything MidiSongBuilder needs, produced by MidiImportDialog. */
public record MidiImportSpec(
        String songName,
        SmpsMode mode,
        int tempoByte,
        int dividingTiming,
        int unitsPerSixteenth,
        int stepsPerBar,
        int barsPerPhrase,
        boolean loopWholeSong,
        int ppq,
        List<LineAssignment> lines,
        List<GmDrumMapper.DrumHit> dacHits,
        List<GmDrumMapper.DrumHit> noiseHits,
        GmDrumMapper.Mapping drumMapping,
        Map<Integer, DacSample> dacSampleOverrides) {  // slot → real sample (Phase 2 fills this)

    /**
     * @param targetChannel model channel index 0-8 (FM1-5, DAC handled separately, PSG1-3)
     * @param voice         FM voice for FM channels (null for PSG)
     * @param psgEnvelopeId 1-based envelope id for PSG channels, -1 = none
     * @param ppq           pulses per quarter of the stem this line came from
     */
    public record LineAssignment(String stemName, VoiceSeparator.SeparatedLine line,
                                 int targetChannel, int octaveShift,
                                 FmVoice voice, int psgEnvelopeId, int ppq) {}
}
