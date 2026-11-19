# DAC samples and one-shot triggers

The DAC source uses the first positional S3K slot and the OpenGGF driver pinned at
`a1c4aac7ae2708f5c487e8000da80502e854abe2`. The native note map points to unsigned
mono PCM and the exact rate byte. Explicit rest units allow the sample to ring.
The compiler extends the final stop to account for the last sample and chip bus
timing. The arrangement clip keeps its authored length.

- The final isolated-display Studio reactor passed 153 tests, including 70
  project-core, 45 headless, and 38 Studio tests. They cover caps and old formats,
  repeated and trimmed DAC hits, compact rests, timing at rate zero, sample
  replacement, missing mappings, package bytes and hashes, WAV/PCM conversion,
  live-refresh drafts, undo, and saving.
- Six packaged native DPI scenarios passed at 100–200%, including physical
  sample-grid hit entry and save after an external file refresh.
- The original pinned-source and vendored builds matched DAC PCM and chip writes
  for 90 streamed bytes. The existing FM, PSG, call/loop, and production demo
  probes also matched. All 8 Python extraction tests passed.
- The packaged example validates to 611 native bytes and renders 1,604
  presentations by default. Its ZIP contains the track, source, manifest, two
  envelopes and two sample byte arrays. All declared hashes were verified.
- The full reactor ran 851 tests before the last focused review fixes, with the
  same nine legacy failure identities and assertion messages and the same two
  skips as the baseline. The final focused reactor covers all code changed
  after that run. [Legacy baseline](2026-11-17-s3k-foundation.md)

The authoring profile uses fixed stereo DAC, one rate per sample, and one hit
per DAC tick. It includes uncompressed 8-/16-bit mono/stereo WAV conversion and
exact unsigned 8-bit raw PCM import. FM6 switching and DAC coordination flags
remain separate driver work. [Current support](../architecture/s3k-support.md)
