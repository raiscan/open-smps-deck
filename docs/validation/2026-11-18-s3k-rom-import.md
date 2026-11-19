# Locked-on S3&K import verification

The packaged importer read the local unheadered 4 MiB locked-on cartridge
with SHA-1 `cfbf98c36c776677290a872547ac47c53d2761d6`. It used OpenGGF at
`f3175b2225ad3b15be40d9075a7bb6a4dbeae4ca` for song offsets and resources.
The cartridge checksum matched before and after the run.

| Check | Result |
| --- | --- |
| Packaged CLI catalogue | Listed all 51 IDs |
| Import, save and reopen | 51 projects opened as v5 with all 1,671,168 bank bytes matching their cartridge locations |
| Existing output | Repeat import refused the destination and preserved its exact bytes |
| Bounded playback | All 51 rendered 360 presentations each. IDs `18` and `2E` shared a header and matched in PCM |
| Extra Life | ID `2A` rendered with ID `05` as prior music and 120 presentations of warm-up. WAV length was 288,000 stereo frames at 48 kHz |
| Packaged Studio | Opened an imported Launch Base Act 1 project at 200% scale |

The aggregate PCM SHA-256 across the 51 imports, in ascending ID order, was
`05142468a31c07e7e2ba4683641aa9300c187c2534f0e67e8aee2f254759cca3`.
The Extra Life WAV PCM SHA-256 was
`71cae4d2545a91e63fc4c845155b2cba929be7ed5a956c031cc8a3dbfcc196e4`.
Both match the pre-import probe that assembled projects directly from the
loader. Whole-song console PCM and register parity remain unverified.
[Re-pin reference](2026-11-17-s3k-repin.md)

The synthetic regression tests cover a call into a pre-header phrase,
local voice-table extent and overlap, sparse DAC aliases with distinct
rates, original and resolved modulation bytes, and rejection of an
unrecognized cartridge. Studio tests cover opening a new watched document
while keeping unsaved edits and drafts in the originating window.

The 108-source extraction passed provenance and independent upstream and
vendored PCM/chip-write checks. The eight extraction-tool tests and all
six packaged Studio DPI cases at 100–200% passed.
The isolated-display project-core, headless and Studio run passed all
182 tests. The full reactor reproduced the nine known legacy app failures
and two skips. [Legacy baseline](2026-11-17-s3k-foundation.md)
