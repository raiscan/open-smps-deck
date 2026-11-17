# DAC samples and triggers

Add sample authoring to the existing Studio, shared project, headless renderer
and native package. Use the pinned OpenGGF DAC stream with its original timing.

- Project core adds strict v4 with inline canonical base64 unsigned PCM, a native
  rate byte, and stable sample IDs. DAC sources hold one-shot trigger events,
  one per tick, on the single DAC lane. Existing v1–v3 bytes remain compatible.
- Compile triggers into the first positional header slot. Native waits use
  explicit rests. A new hit replaces the active sample. Bound the stop time
  from the last surviving trigger and the sample's native rate. Test exact
  stream bytes, repeats, trimming, and input bounds.
- Render with explicit DAC maps before session activation, validate resolved
  triggers before output, and package PCM and mappings with the baked track.
  Test PCM and sample transitions through the pinned driver.
- Add a sample library with WAV and unsigned PCM input, native rate and waveform
  controls, and a source editor with sample rows. Use the existing undo, save
  and external refresh flow and exercise it on isolated displays.
- Document the supported PCM, stream, and package contracts and remaining FM6
  ownership and native DAC command work. Verify and update the open reboot PR.
