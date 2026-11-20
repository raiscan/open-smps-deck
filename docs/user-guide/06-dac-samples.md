# DAC Samples

The DAC channel plays audio samples through the YM2612's sixth FM channel, used for drums and sound effects.

## Overview

The DAC (Digital-to-Analog Converter) replaces FM channel 6 with direct sample playback. Only one sample can play at a time -- there is no polyphony. All samples are stored as 8-bit unsigned mono PCM, which is the native format for the Mega Drive Z80 DAC driver.

## Importing Samples

Click the **+** button in the **DAC Samples** section of the instrument panel. A file chooser opens with these supported formats:

- **WAV files** (`.wav`): Automatically converted to unsigned 8-bit mono PCM. The importer handles 8-bit and 16-bit sources, signed and unsigned encodings, and stereo-to-mono downmix (channels are averaged).
- **Raw PCM files** (`.pcm`, `.bin`): Read verbatim as unsigned 8-bit PCM data. No header parsing is performed.

Imported samples are assigned a default playback rate of `0x0C`. The sample name is derived from the filename (minus the extension).

## Editing Samples

Select a sample in the list and click **Edit**, or double-click the entry. The editor dialog contains:

- **Name** -- editable text field for the sample name.
- **Rate** -- spinner from `0` to `255` controlling the Z80 DAC driver playback rate.
- **Size** -- read-only label showing the sample data length in bytes.

Click **OK** to apply changes or **Cancel** to discard them.

## Duplicating and Deleting

- **Dup** -- creates a copy of the selected sample with " (copy)" appended to the name. The copy retains the same data and rate, and is appended to the end of the sample list.
- **Del** -- removes the selected sample from the list. Any DAC notes referencing that index will become invalid.

## Entering DAC Notes

On the DAC channel (channel 6, index 5), note entry keys map to sample indices instead of pitches. Each key triggers the sample at the corresponding index:

**Lower row:**

| Key | `Z` | `S` | `X` | `D` | `C` | `V` | `G` | `B` | `H` | `N` | `J` | `M` |
|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|
| Index | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 |

**Upper row:**

| Key | `Q` | `W` | `E` | `R` | `T` | `Y` | `U` |
|-----|-----|-----|-----|-----|-----|-----|-----|
| Index | 12 | 13 | 14 | 15 | 16 | 17 | 18 |

Up to 19 samples total can be addressed from the keyboard. The tracker grid displays the sample name for each DAC note instead of a pitch value.

## Limitations

- **No polyphony** -- only one sample plays at a time. A new DAC note cuts the previous sample immediately.
- **Mono only** -- stereo WAV files are downmixed to mono on import. The DAC output is mono.
- **8-bit only** -- all samples are stored as unsigned 8-bit PCM regardless of the source bit depth.
- **Rate controls both pitch and speed** -- the playback rate byte affects pitch and duration together. There is no independent pitch or time-stretch control.

## Tips

- Keep samples short. Long samples consume ROM space quickly and the Mega Drive has limited storage for DAC data.
- Higher rate values produce faster, higher-pitched playback. Lower rate values produce slower, lower-pitched playback. Experiment with the **Rate** spinner to find the right balance.
- Use the **Dup** button to create pitch variants of the same sample with different rates. This lets you build a drum kit with multiple tunings from a single source recording.

## Drum sources in headless reconstruction

Use `reconstruct run … --stem 2=drums.wav --drum-source MODE` to fit the kit
against an isolated drum stem. Replace `2` with the MIDI percussion part ID.
The stem and mix must share a source clock. With steady tempo enabled, the
matcher stretches the drum reference with the mix's alignment and measures
the hits on the project clock.

- `catalogue` is the default. It compares several hits per MIDI drum class
  using spectral bands, onset and decay envelopes, noisiness, and low drum
  pitch when measurable. It searches nearby native DAC rates for each sound
  and favors close alternatives for classes that would share the same sample.
  Known kick and snare families provide a preference when another drum is
  audible in the probe. When a mixed reference has insufficient isolated
  hits, the report records use of the legacy source-clock vote.
- `sliced` extracts a one-shot from the best isolated hit on the supplied stem.
  It trims the tail and converts the sample to unsigned 8-bit PCM at an S3K
  DAC rate. It uses the catalogue when a class has no usable stem excerpt.
- `auto` uses a slice when the catalogue fit is weak and a stem slice is
  available. The report records the chosen source, catalogue distance,
  compared hits, and alternative samples per class.

Each choice plays through the existing single DAC lane. Simultaneous-hit and
tail-protection limits apply in all three modes.
