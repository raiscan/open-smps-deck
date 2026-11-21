package com.opensmpsdeck.project;

import java.util.List;

/** Versioned, linked source document. Use ProjectFormat.validate at trust boundaries. */
public record StudioProject(int formatVersion, String name, String mode, int ticksPerBeat,
                            int tempo, List<Voice> voices, List<Sequence> sequences, List<Clip> clips,
                            List<Envelope> envelopes, List<DacSample> samples, NativeProgram nativeProgram,
                            List<AudioReference> references, List<ReviewNote> notes,
                            int arrangementVersion, List<GroupSequence> groupSequences,
                            List<GroupPlacement> groupPlacements, boolean detachedFromReconstruction) {
    public StudioProject {
        voices = List.copyOf(voices);
        sequences = List.copyOf(sequences);
        clips = List.copyOf(clips);
        envelopes = List.copyOf(envelopes);
        samples = List.copyOf(samples);
        references = List.copyOf(references);
        notes = List.copyOf(notes);
        groupSequences = List.copyOf(groupSequences);
        groupPlacements = List.copyOf(groupPlacements);
    }

    public StudioProject(int formatVersion, String name, String mode, int ticksPerBeat, int tempo,
                         List<Voice> voices, List<Sequence> sequences, List<Clip> clips,
                         List<Envelope> envelopes, List<DacSample> samples, NativeProgram nativeProgram,
                         List<AudioReference> references, List<ReviewNote> notes, int arrangementVersion,
                         List<GroupSequence> groupSequences, List<GroupPlacement> groupPlacements) {
        this(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, arrangementVersion, groupSequences,
                groupPlacements, false);
    }

    public StudioProject withDetachedFromReconstruction(boolean detached) {
        return new StudioProject(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, arrangementVersion, groupSequences,
                groupPlacements, detached);
    }

    public StudioProject(int formatVersion, String name, String mode, int ticksPerBeat, int tempo,
                         List<Voice> voices, List<Sequence> sequences, List<Clip> clips,
                         List<Envelope> envelopes, List<DacSample> samples, NativeProgram nativeProgram,
                         List<AudioReference> references, List<ReviewNote> notes) {
        this(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, formatVersion, List.of(), List.of());
    }

    StudioProject withArrangement(int version, List<Voice> voices, List<Sequence> sequences,
                                  List<Clip> clips, List<Envelope> envelopes, List<DacSample> samples) {
        return new StudioProject(formatVersion == 6 ? 6 : version, name, mode, ticksPerBeat, tempo,
                voices, sequences, clips, envelopes, samples, nativeProgram, references, notes,
                version, groupSequences, groupPlacements, detachedFromReconstruction);
    }

    public StudioProject withSettings(String name, int ticksPerBeat, int tempo) {
        return new StudioProject(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, arrangementVersion, groupSequences, groupPlacements, detachedFromReconstruction);
    }

    public StudioProject(int formatVersion, String name, String mode, int ticksPerBeat, int tempo,
                         List<Voice> voices, List<Sequence> sequences, List<Clip> clips,
                         List<Envelope> envelopes, List<DacSample> samples, NativeProgram nativeProgram) {
        this(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, List.of(), List.of());
    }

    public StudioProject withReview(List<AudioReference> references, List<ReviewNote> notes) {
        return new StudioProject(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, arrangementVersion, groupSequences, groupPlacements, detachedFromReconstruction);
    }
    public StudioProject(int formatVersion,String name,String mode,int ticksPerBeat,int tempo,
                         List<Voice> voices,List<Sequence> sequences,List<Clip> clips,List<Envelope> envelopes,List<DacSample> samples) {
        this(formatVersion,name,mode,ticksPerBeat,tempo,voices,sequences,clips,envelopes,samples,null);
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
