package com.opensmpsdeck.project;

import java.util.List;

/** Versioned, linked source document. Use ProjectFormat.validate at trust boundaries. */
public record StudioProject(int formatVersion, String name, String mode, int ticksPerBeat,
                            int tempo, List<Voice> voices, List<Sequence> sequences, List<Clip> clips,
                            List<Envelope> envelopes, List<DacSample> samples) {
    public StudioProject {
        voices = List.copyOf(voices);
        sequences = List.copyOf(sequences);
        clips = List.copyOf(clips);
        envelopes = List.copyOf(envelopes);
        samples = List.copyOf(samples);
    }
    public StudioProject(int formatVersion,String name,String mode,int ticksPerBeat,int tempo,
                         List<Voice> voices,List<Sequence> sequences,List<Clip> clips,List<Envelope> envelopes) {
        this(formatVersion,name,mode,ticksPerBeat,tempo,voices,sequences,clips,envelopes,List.of());
    }
    public StudioProject(int formatVersion,String name,String mode,int ticksPerBeat,int tempo,
                         List<Voice> voices,List<Sequence> sequences,List<Clip> clips) {
        this(formatVersion,name,mode,ticksPerBeat,tempo,voices,sequences,clips,List.of());
    }
}
