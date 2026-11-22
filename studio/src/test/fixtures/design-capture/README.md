# Copper Kites capture fixture

Copper Kites is an original eight-second synthetic reconstruction at 120 BPM.
The source WAVs are generated harmonic tones for a vocal-like melody, bass,
and keys, plus swept-sine and seeded-noise drum hits. The source MIDI encodes
the same notes and drum attacks for the import mapping checks. The instrument
catalogue uses the public demo FM voices and a short synthetic PSG envelope.

The staged artifacts come from the audio-only reconstruction pipeline with
deterministic transcription snapshots for the three tonal stems. The vocal
snapshot has synthetic pitch-contour and syllable evidence with two deliberate
pitch disagreements. The CLI's `vocal-review --propose` generated the two
pending vocal proposals. The journal also has a built bass velocity edit and a
pending keys proposal. Paths in the capture artifacts are relative. The mix
and stems are relative to the work directory and the catalogue is relative to
the repository root.

This directory retains the artifacts Studio reads for inspection. It is a
capture fixture with no project history, separator model, or ROM dependency.
