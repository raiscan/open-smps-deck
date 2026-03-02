# Glossary

Quick reference for terms used throughout the OpenSMPSDeck user guide.

## Song Structure

**Song** -- The top-level container for everything in a project: arrangement, instruments, tempo, and SMPS mode. Each open song lives in its own tab.

**Chain** -- An ordered list of phrase references for a single channel. Each of the 10 channels has exactly one chain. Chains define the playback sequence and can transpose, repeat, and loop their entries. Compiles to a single SMPS track.

**Chain Entry** -- One slot in a chain. References a phrase by ID and optionally applies a semitone transpose and a repeat count.

**Phrase** -- A named block of SMPS track data (notes, rests, ties, instrument changes, coordination flags) for a single channel. Phrases live in a shared library and can be referenced by multiple chain entries across any channel.

**Phrase Library** -- The shared pool of all phrases in a song. Any chain can reference any phrase from the library. Phrases referenced by two or more chain entries compile as SMPS subroutines.

**Hierarchical Arrangement** -- The LSDJ-style Song -> Chain -> Phrase structure that organises all 10 channels. This is the arrangement model used by OpenSMPSDeck.

**Loop Point** -- The chain entry that playback jumps back to after reaching the end of the chain. Creates a looping section. If no loop point is set, the channel stops when the chain ends.

## Channels

**FM Channel** -- One of six frequency modulation channels on the YM2612 chip (FM1--FM5 plus DAC/FM6). FM channels use voices for their timbre.

**PSG Channel** -- One of four channels on the SN76489 chip: three tone channels (PSG1--PSG3) producing square waves, and one noise channel. PSG channels use envelopes for their volume shape.

**DAC Channel** -- Channel 6 (index 5), which replaces YM2612 FM channel 6 with direct sample playback. Plays one sample at a time with no polyphony.

**Channel Type** -- The category a channel belongs to: FM, DAC, PSG Tone, or PSG Noise. Determines which instruments and note entry behaviour apply.

## Instruments

**FM Voice** -- A 25-byte instrument patch for the YM2612 defining the algorithm, feedback, and all four operator parameters. Controls the timbre of FM channels.

**Voice Bank** -- The collection of FM voices in a song, listed in the Instrument Panel. Voices are referenced by hex index from the tracker grid.

**PSG Envelope** -- A volume step sequence for PSG channels. Each step sets a volume level (0 = loudest, 7 = quietest) that plays for one driver frame. Shapes how PSG notes decay over time.

**DAC Sample** -- A raw unsigned 8-bit mono PCM audio clip used on the DAC channel. Has a name and a playback rate that controls pitch and speed.

**Operator** -- One of the four sine-wave oscillators inside the YM2612 that make up an FM voice. An operator is either a carrier (produces audible output) or a modulator (shapes another operator's timbre).

**Carrier** -- An operator whose output is heard directly. Its TL parameter controls volume.

**Modulator** -- An operator that feeds into another operator instead of producing direct output. Its TL parameter controls modulation depth (timbral intensity).

**Algorithm** -- A value 0--7 that defines how the four operators in an FM voice are wired together. Determines which operators are carriers and which are modulators.

**Feedback** -- Self-modulation on operator 1 (0--7). Turns its pure sine wave into a richer waveform without needing another operator.

## Tracker Grid

**Row** -- One horizontal line in the tracker grid representing a single event: a note, rest, tie, or coordination flag.

**Sub-column** -- Each channel in the grid has three sub-columns: Note, Instrument, and Effect.

**Rest** -- A silence event (`---`). In SMPS, every frame must have data, so empty space is represented by rests.

**Tie** -- A sustain event (`===`) that extends the previous note without re-triggering the instrument envelope.

**Duration** -- The frame count following a note byte in SMPS bytecode. Controls how long a note, rest, or tie lasts. Not shown as a visible column.

**Octave** -- The pitch register for note entry (0--8). Set with F1--F8 or adjusted with Page Up/Page Down. Affects which octave new notes are entered at.

**Mute** -- Silences a channel during playback. Click the channel header to toggle. Respected by both real-time playback and WAV export.

**Solo** -- Isolates a single channel by muting all others. Ctrl+Click the channel header. Only one channel can be soloed at a time.

## SMPS

**SMPS** -- Sample Music Playback System. The sound driver used by Sonic the Hedgehog games on the Sega Mega Drive. OpenSMPSDeck's internal model is SMPS bytecode.

**Bytecode** -- The raw binary instructions that the SMPS sequencer interprets: note values, durations, and coordination flags. The tracker grid is a decoded view over this data.

**Coordination Flag** -- A multi-byte command in SMPS bytecode that controls playback behaviour beyond simple notes: pan, modulation, transpose, voice changes, tempo, loops, calls, and jumps.

**SMPS Mode** -- The target Sonic game driver variant: S1 (Sonic 1, 68k), S2 (Sonic 2, Z80), or S3K (Sonic 3 & Knuckles, Z80). Affects tempo interpretation, note offsets, and binary format.

**Tempo** -- A value 1--255 that controls playback speed. Interpreted differently depending on the SMPS mode (TIMEOUT for S1, OVERFLOW2 for S2, OVERFLOW for S3K).

**Dividing Timing** -- A value 1--8 that subdivides the tempo tick for finer rhythmic control.

## Hardware

**YM2612** -- The FM synthesis chip in the Sega Mega Drive. Provides six FM channels (or five FM + one DAC). Generates sound through four-operator frequency modulation.

**SN76489** -- The PSG (Programmable Sound Generator) chip in the Sega Mega Drive. Provides three square-wave tone channels and one noise channel.

**Mega Drive / Genesis** -- Sega's 16-bit console (1988). Called Mega Drive in most regions and Genesis in North America. The target hardware for SMPS music.

## Interface

**Transport Bar** -- The control strip below the menu bar with Play/Stop/Pause buttons and spinners for tempo, dividing timing, and SMPS mode.

**Song View** -- The left panel showing all 10 channels and their chains at a glance. Click a channel to select it.

**Chain Strip** -- The horizontal row of clickable phrase cells above the tracker grid, showing the active channel's chain entries.

**Breadcrumb Bar** -- The navigation path above the chain strip (e.g., "Song > FM1 > Phrase 03"). Click any level to navigate back up the hierarchy.

**Instrument Panel** -- The right panel containing the voice bank list, PSG envelope list, and DAC sample list.

## File Formats

**.osmpsd** -- OpenSMPSDeck project file (JSON). Stores the complete song including arrangement, instruments, and settings.

**.bin** -- Raw SMPS binary export. Ready for ROM injection or playback by SMPSPlay / OpenGGF.

**.wav** -- Standard audio file (44.1 kHz, 16-bit stereo). Rendered offline from the SMPS sequencer.

**.ovm** -- OpenSMPSDeck voice bank file (JSON). Contains FM voices and PSG envelopes for sharing between projects.

**.rym2612** -- RYM2612 VSTi FM patch file (XML). Import-only; converted to SMPS voice format on load.
