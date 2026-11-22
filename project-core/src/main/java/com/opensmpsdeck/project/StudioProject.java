package com.opensmpsdeck.project;

import java.util.List;

/** Versioned, linked source document. Use ProjectFormat.validate at trust boundaries. */
public record StudioProject(int formatVersion, String name, String mode, int ticksPerBeat,
                            int tempo, List<Voice> voices, List<Sequence> sequences, List<Clip> clips,
                            List<Envelope> envelopes, List<DacSample> samples, NativeProgram nativeProgram,
                            List<AudioReference> references, List<ReviewNote> notes,
                            int arrangementVersion, List<GroupSequence> groupSequences,
                            List<GroupPlacement> groupPlacements, boolean detachedFromReconstruction, RetailSongOrigin derivedFrom, TargetGame target, Integer nativeTempo) {
    public StudioProject {
        java.util.Objects.requireNonNull(target, "target");
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

    public StudioProject withNativeTempo(Integer nativeTempo) {
        return new StudioProject(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, arrangementVersion, groupSequences,
                groupPlacements, detachedFromReconstruction, derivedFrom, target, nativeTempo);
    }

    public TargetClock clock() {
        return nativeTempo == null ? new TargetClock(TargetGame.S3K, tempo) : new TargetClock(target, nativeTempo);
    }

    public StudioProject withTarget(TargetGame target) {
        return new StudioProject(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, arrangementVersion, groupSequences,
                groupPlacements, detachedFromReconstruction, derivedFrom, target, this.target == target ? nativeTempo : null);
    }

    public StudioProject withAssets(List<Voice> voices, List<Envelope> envelopes, List<DacSample> samples) {
        return new StudioProject(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, arrangementVersion, groupSequences,
                groupPlacements, detachedFromReconstruction, derivedFrom, target, nativeTempo);
    }

    public StudioProject withDerivedFrom(RetailSongOrigin origin) {
        return new StudioProject(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, arrangementVersion, groupSequences,
                groupPlacements, detachedFromReconstruction, origin, target, nativeTempo);
    }

    public StudioProject withDetachedFromReconstruction(boolean detached) {
        return new StudioProject(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, arrangementVersion, groupSequences,
                groupPlacements, detached, derivedFrom, target, nativeTempo);
    }

    StudioProject withArrangement(int version, List<Voice> voices, List<Sequence> sequences,
                                  List<Clip> clips, List<Envelope> envelopes, List<DacSample> samples) {
        return new StudioProject(formatVersion == 6 ? 6 : version, name, mode, ticksPerBeat, tempo,
                voices, sequences, clips, envelopes, samples, nativeProgram, references, notes,
                version, groupSequences, groupPlacements, detachedFromReconstruction, derivedFrom, target, nativeTempo);
    }

    public StudioProject withSettings(String name, int ticksPerBeat, int tempo) {
        return new StudioProject(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, arrangementVersion, groupSequences, groupPlacements, detachedFromReconstruction, derivedFrom, target, this.tempo == tempo ? nativeTempo : null);
    }

    public StudioProject withReview(List<AudioReference> references, List<ReviewNote> notes) {
        return new StudioProject(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, arrangementVersion, groupSequences, groupPlacements, detachedFromReconstruction, derivedFrom, target, nativeTempo);
    }

    public StudioProject(int formatVersion, String name, String mode, int ticksPerBeat,
                            int tempo, List<Voice> voices, List<Sequence> sequences, List<Clip> clips,
                            List<Envelope> envelopes, List<DacSample> samples, NativeProgram nativeProgram,
                            List<AudioReference> references, List<ReviewNote> notes,
                            int arrangementVersion, List<GroupSequence> groupSequences,
                            List<GroupPlacement> groupPlacements, boolean detachedFromReconstruction, TargetGame target, Integer nativeTempo) {
        this(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, arrangementVersion, groupSequences,
                groupPlacements, detachedFromReconstruction, null, target, nativeTempo);
    }

    public StudioProject(int formatVersion, String name, String mode, int ticksPerBeat, int tempo,
                         List<Voice> voices, List<Sequence> sequences, List<Clip> clips,
                         List<Envelope> envelopes, List<DacSample> samples, NativeProgram nativeProgram,
                         List<AudioReference> references, List<ReviewNote> notes, int arrangementVersion,
                         List<GroupSequence> groupSequences, List<GroupPlacement> groupPlacements, TargetGame target, Integer nativeTempo) {
        this(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, arrangementVersion, groupSequences,
                groupPlacements, false, target, nativeTempo);
    }

    public StudioProject(int formatVersion, String name, String mode, int ticksPerBeat, int tempo,
                         List<Voice> voices, List<Sequence> sequences, List<Clip> clips,
                         List<Envelope> envelopes, List<DacSample> samples, NativeProgram nativeProgram,
                         List<AudioReference> references, List<ReviewNote> notes, TargetGame target, Integer nativeTempo) {
        this(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, references, notes, formatVersion, List.of(), List.of(), target, nativeTempo);
    }

    public StudioProject(int formatVersion, String name, String mode, int ticksPerBeat, int tempo,
                         List<Voice> voices, List<Sequence> sequences, List<Clip> clips,
                         List<Envelope> envelopes, List<DacSample> samples, NativeProgram nativeProgram, TargetGame target, Integer nativeTempo) {
        this(formatVersion, name, mode, ticksPerBeat, tempo, voices, sequences, clips,
                envelopes, samples, nativeProgram, List.of(), List.of(), target, nativeTempo);
    }

    public StudioProject(int formatVersion,String name,String mode,int ticksPerBeat,int tempo,
                         List<Voice> voices,List<Sequence> sequences,List<Clip> clips,List<Envelope> envelopes,List<DacSample> samples, TargetGame target, Integer nativeTempo) {
        this(formatVersion,name,mode,ticksPerBeat,tempo,voices,sequences,clips,envelopes,samples,null, target, nativeTempo);
    }

    public StudioProject(int formatVersion,String name,String mode,int ticksPerBeat,int tempo,
                         List<Voice> voices,List<Sequence> sequences,List<Clip> clips,List<Envelope> envelopes, TargetGame target, Integer nativeTempo) {
        this(formatVersion,name,mode,ticksPerBeat,tempo,voices,sequences,clips,envelopes,List.of(), target, nativeTempo);
    }

    public StudioProject(int formatVersion,String name,String mode,int ticksPerBeat,int tempo,
                         List<Voice> voices,List<Sequence> sequences,List<Clip> clips, TargetGame target, Integer nativeTempo) {
        this(formatVersion,name,mode,ticksPerBeat,tempo,voices,sequences,clips,List.of(), target, nativeTempo);
    }
}
