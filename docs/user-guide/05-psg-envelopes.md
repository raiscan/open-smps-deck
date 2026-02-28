# PSG Envelopes

The PSG envelope editor shapes the volume curve for the three PSG tone channels and the noise channel.

## Overview

The SN76489 PSG chip produces square-wave tones and noise. Unlike FM voices, which have complex multi-operator synthesis, PSG channels have no built-in amplitude control -- the envelope is the only way to shape how the sound evolves over time. Open the PSG envelope editor from the **Instrument Panel** by selecting an envelope and clicking **Edit**, or double-clicking the entry directly.

## What PSG Sounds Like

PSG channels produce simple buzzy square waves. They work well for percussion imitations (hi-hats, snare-like bursts), fast arpeggios, and sound effects. The envelope controls only volume over time -- pitch is determined entirely by note entry in the tracker grid. Think of the envelope as a volume stamp applied from the moment a note triggers.

## Editing Envelopes

The editor displays the envelope as a vertical bar graph. Each bar represents one step, and the bar height indicates volume level.

- **Volume `0`** is the loudest (tallest bar).
- **Volume `7`** is the quietest (shortest bar).
- Each step plays for one driver frame at the current tempo.

Click on a bar to set its volume, or click and drag across multiple bars to draw a volume curve in one motion. The Y axis maps directly to volume: click near the top for quiet values, near the bottom for loud values. Step indices are labeled in hex below each bar.

Horizontal grid lines mark the eight volume levels (`0`--`7`) with numeric labels on the right edge of the canvas.

## Managing Steps

Use the buttons below the bar graph to add or remove steps:

| Button | Action |
|--------|--------|
| **+Step** | Append a new step at volume `0` (loudest) after the last step |
| **-Step** | Remove the last step from the envelope |

The **Steps** label between the buttons displays the current step count. An envelope with zero steps produces no sound -- the envelope ends immediately and the channel goes silent.

## Name

The **Name** text field at the top of the dialog sets the envelope's display name. This name appears in the **Instrument Panel** list as `XX: Name`, where `XX` is the hex index. Choose a descriptive name like "Short Decay" or "Hi-Hat" so you can identify envelopes at a glance.

## Preview

Click **Preview** to hear the envelope applied to a PSG tone at middle C for approximately 500 ms. Preview stops any active song playback first to ensure the PSG channel is available. After 500 ms the channel is silenced automatically.

If the playback engine is not connected (no audio device available), the preview button does nothing.

## Confirming or Discarding Edits

The editor works on a copy of the original envelope. Click **OK** to apply your changes back to the song, or **Cancel** to discard them. No changes are written until you confirm.

## Tips

- Two-to-four-step envelopes make good percussion. A single loud step followed by one or two quick decay steps creates convincing hi-hat and kick imitations.
- Longer envelopes (eight or more steps) with gradual decay from `0` to `7` work well for sustained melodic tones that fade naturally.
- Setting volume `0` on every step produces a sustained square wave with no envelope shaping -- the note plays at full volume until the next note or rest.
