# Instrument catalogue for Studio and external agents

The catalogue should let a musician audition a sound and let an agent select it
while recreating an arrangement from stems, MIDI, or audio. Both need the same
chip-rendered audition and the same exact asset bytes. Studio projects should
copy the chosen asset into the project so a shared song can still play without
access to the catalogue.

## Sources we have

The locked-on S3&K importer already extracts global and local FM voices, PSG and
modulation envelopes, and rate-specific DAC bindings. The native arrangement
recorder follows executed commands and notes. Inventory and playback tracing
answer different questions. One records what a bank declares. The other records
which sound a song used, where, and with which settings.

The legacy instrument scanner already understands rip folder discovery, driver
definitions, configured companion files, and several DAC encodings. Reuse its
discovery and parsing where the bytes can be verified against the driver.
The new catalogue should consume those readers through an adapter that produces
Studio-compatible records. Legacy FM voice structures must be converted to the
verified native register order before they are compared or deployed. Keep the
source layout and original bytes with the occurrence record.

The local locked-on S3&K image was measured across 51 music IDs and 50 distinct
bank/header pairs. The larger `smps-rips-master` archive was absent from the
local paths checked. These counts establish a starting corpus and are scoped to
one supported release.

| Item | Observed |
| --- | ---: |
| Distinct FM payloads across global and local voice tables | 251 |
| Distinct decoded DAC PCM blobs | 47 |
| Distinct DAC PCM and rate combinations | 67 |
| PCM blobs that have more than one rate | 10 |
| Distinct typed PSG and modulation envelope payloads | 43 |
| Music IDs with a PSG noise event in the editable preview interval | 26 |

All 26 of those music IDs used the `E7` latch in the preview sweep. Its
white-noise clock follows the third PSG tone channel, so a latch-only index
would merge different pitches, envelopes, and rhythms.

## Asset, playback variant, and source occurrence

| Record | Fields and boundaries |
| --- | --- |
| Asset | Kind, verified native format, content digest, and exact payload or a content-addressed PCM blob |
| Playback variant | Asset reference plus the settings needed to reproduce a sound, including chip/driver profile and the audition recipe |
| Source occurrence | Rip-relative location, driver and revision, song or companion file, original slot, raw bytes or source digest, and track and tick when observed during playback |
| Annotation | Searchable role or character label, its source, and optional confidence. Human corrections take precedence over generated suggestions |

- **FM voices** carry a verified native patch and operator ordering. Record
  bank and slot aliases as occurrences. An audition can include modulation,
  pan, or an envelope as a separate playback variant.
- **DAC samples** store decoded unsigned PCM once, with variants for effective
  rate, timing profile, and pan. Retain original note slot, raw rate byte,
  and source encoding as provenance.
- **PSG noise** is a playable recipe. Record the raw latch and effective clock
  source, fixed divider or tone-linked pitch, volume envelope, attenuation,
  and duration. For tone-linked noise, retain the relevant pitch movement as
  part of the recipe.
- **PSG and modulation envelopes** keep exact validated bytes and their kind.
  Tone instruments can link an envelope and other settings into an audition
  recipe. Native slot indices stay in occurrence records.

Use exact equality for deduplication and deterministic identifiers. Acoustic
similarity orders search results. It never redirects an existing asset reference.
For initial classifications, use auditable properties such as FM algorithm,
sample duration, pitch range, envelope shape, and spectral character measured
from fixed chip-rendered probes. Store the version of the audition and feature
profiles. Add human or agent annotations such as bass, snare, or texture
as contextual suggestions. A voice may play more than one role.

## First delivery

1. Build a user-local, repeatable index from the verified locked-on S3&K image.
   Record reachable song occurrences alongside declared assets. Start the
   rip-directory adapter with dialects whose pointer, voice-order, and companion
   mappings can be checked. The scan summary should show unsupported inputs
   and distinguish unreadable data from missing companions.
2. Expose a shared headless catalogue service with versioned JSON for `catalog
   scan`, `catalog search`, and `catalog show`. Search should filter by kind,
   compatible driver, role, source, and playback variant, with stable IDs and
   pagination. Use rip-relative names in the results intended for agents.
3. Add `catalog audition` through the pinned chips and `catalog apply` into a
   project. Studio can use the same search and audition and deploy a choice
   through its normal undoable project edits. Prove that two DAC rates on one
   blob, an FM patch from two slots, and tone-linked noise keep their settings
   through deployment, save, reopen, and render.

The broader classification pass can rank candidates against reference audio.
A Suno reconstruction agent can search for a sound, audition the top matches,
and test them in context through the existing compile and render loop. It can
do all of that through the headless interface while Studio follows project
file edits.

[Legacy local library design](../superpowers/specs/2026-06-13-user-local-instrument-library-design.md) ·
[Current S3&K support](s3k-support.md)
