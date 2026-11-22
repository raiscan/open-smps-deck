# Playback and export

Press **Play** to listen from the arrangement playhead, or **Space** to toggle
Play/Stop outside text fields. Click the ruler to seek and Shift-drag to loop
a range. **S** and **M** on the lanes control audition solo and mute. Editing
the project stops playback of the previous snapshot.

In a reconstruction, choose **Open as arrangement** to open the final
`arrangement.smps.json` in the timeline, piano roll and command editor. Save
hand edits there. **Apply** in the reconstruction window warns before
rebuilding over those edits and can preserve an edited copy.

Choose **Export…** from either Studio window. Exports use the project snapshot
and the pinned OpenGGF S3K renderer.

| Format | Use |
|---|---|
| WAV | 48 kHz stereo 16-bit PCM for editing or lossless listening |
| MP3 | Stereo listening copy, encoded by ffmpeg with libmp3lame |
| VGM 1.71 | Mega Drive register log for YM2612 and SN76489 VGM players |
| Raw SMPS | Baked S3K track for a compatible S3K driver, with local voices |
| SMPS package | S3K bank, bindings, declared resources and editable source for Studio and engine integration |

WAV and MP3 export need ffmpeg on `PATH`. Export the whole song or specify
a span in project seconds. Fade out over the end of that interval, or
choose **Separate channel stems** for five FM stems, three PSG tones,
PSG noise and DAC. Export the stems as a directory or ZIP. A looping native
bank can be rendered for multiple passes.

Use the SMPS package for a native bank or for any project with declared
DAC samples or envelopes. OpenGGF's S3K driver is the playback target for
the native contents. Engine integration uses the bank and its manifest
bindings. OpenGGF currently has no direct ZIP loader.

See the [Studio guide](studio.md#listen-and-export) and
[CLI and MCP export commands](../../headless/README.md#export-project-output).

## Test in game

Export a patched Sonic 3 & Knuckles ROM or a BPS patch to hear your song on
the title screen. Register your clean ROM in Studio or with the CLI first.
[Test in game setup and options](14-test-in-game.md)
