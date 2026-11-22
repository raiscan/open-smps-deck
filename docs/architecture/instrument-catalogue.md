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

## First pass in use

Studio opens the index from **Voices & assets → Catalogue**. Choose **Open
catalogue…**, filter by kind or search the names and source songs, then inspect
a variant. **Audition** uses the pinned chips and replaces the current
transport playback. **Add to project** is undoable. It copies exact voices and
envelopes, or a reusable DAC sample at the chosen rate. A PSG noise recipe
brings its referenced envelopes and appends a PSG3 clip after the existing
arrangement. It plays at the target project's tempo. Native bank documents
can audition the catalogue and use an editable copy for deployment.

The CLI writes versioned JSON to standard output and accepts explicit local
paths. Scan rips, repeat `--rom` for several games, or combine both input
types in a single index.

```sh
deck_jar=headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar
java -jar "$deck_jar" catalog scan instruments.json --rom "$s3k_rom"
java -jar "$deck_jar" catalog scan rip-instruments.json --rips "$rip_directory"
java -jar "$deck_jar" catalog search instruments.json --kind dac-sample --limit 10
java -jar "$deck_jar" catalog show instruments.json "$variant_id"
java -jar "$deck_jar" catalog audition instruments.json "$variant_id" audition.wav
java -jar "$deck_jar" catalog apply instruments.json "$variant_id" song.smps.json song-with-asset.smps.json
```

Search returns summaries with asset IDs, variant IDs and driver IDs. It
accepts `--target s1|s2|s3k`, `--query`, `--role`, `--source`,
`--variant`, and `--offset`. The legacy `--driver` text filter still works.
Show returns exact Base64 payloads and occurrence records. Choose a variant
ID when an asset has multiple variants for the requested driver.
Output files use new names. Applying an identical asset reuses the
project's existing entry.

The rip reader accepts self-contained `.s3k` banks, Studio projects and
project packages, and self-contained S1/S2 portable `.smps.zip` banks.
Classic portable packages are scanned from their bank and asset bindings
even when they lack editable source. Loose rips with
missing dependencies and unsupported dialects are listed in scan diagnostics.
A standalone bank must carry its local voices. Use a bound Studio source for
external assets. Broad SMPSPlay companion parsing remains a separate adapter.

The S3&K scan distinguishes declared and played entries and records the
original voice table or slot. Playback tracing covers the bounded preview
and records the first heard occurrence per song, lane, slot, and variant.
It retains observable pitch and envelope settings in short noise recipes.
Envelope bytes that need unresolved driver operands or unsupported controls
stay inspectable by asset ID. The scan diagnostic gives the ID. That data has
no audition variant. Native DAC pan is available in audition. Editable DAC deployment
uses the sample bytes and rate. Initial role filters describe measurable
properties such as FM algorithm, one-shot playback, and noise clock.

The locked-on scan produced 426 playable variants, including 74 DAC variants
across rate and pan and 64 PSG noise recipes. Two scans produced the same
index bytes. Every variant was rendered through the pinned chips for up to
24 presentations. The scan retained six unresolved-envelope diagnostics
and marked the Extra Life cue's request to restore previous music.

Sonic 1 and Sonic 2 scans use verified REV01 images and the pinned loaders.
They index each song's local FM table plus the shared PSG and DAC tables.
PSG envelopes retain their native `80` hold terminator. DAC variants preserve
the loader's decoded PCM and note-specific rate byte, including Sonic 1's
timpani aliases and Sonic 2's 17 note mappings. Auditions use the native
rate base of 301 cycles for S1 and 295 for S2. Stock references use the
original table coordinates and payload hashes.

The v1 index can share exact assets across drivers to avoid duplicating
payloads. Variants carry a driver ID, as do their occurrences. Older variants
without a driver field remain S3K and retain their IDs. FM assets use editable
operator order (4/3/2/1). S2 bank bytes are reordered at ingestion and
returned to S2 order at export. Reconstruction and reference matching use
only variants for their target. An identical FM patch in two games is
available through both games' own variants. This keeps the target's native
FM carrier rules and avoids cross-game envelope and DAC playback assumptions.

## DAC acoustic classes

`catalog dac-classes INDEX.json` derives a versioned profile for each DAC asset
and its recorded native rates. The fitter computes the same features for its
retuned rate probes. The profile measures the first 500 ms at most, pooling
active 32 ms frames on a 10 ms hop. It reports normalized autocorrelation
strength, the fraction of periodic frames, median fundamental frequency and
its spread, movement across broad spectral bands, upper-band spectral
flatness, low and upper energy shares, and the time envelope. The classes are
speech-like, pitched/tonal, noise-like, and membrane/body. PCM with too little
signal to measure is unclassified. These rules estimate suitability for
drum fitting and can group synthetic effects with speech-like sounds.
Played, recognized S3K slots provide separate evidence of drum usage.

Speech-like profiles require at least 100 ms of active audio, fundamental
frequency variation, and movement in the voiced spectral envelope. Short,
bright transients can qualify as noise-like even when they contain a ringing
mode. The derived index applies listening curation to DAC slots A5, A9, AA,
and BE from the verified locked-on S3K revision. It reports their effective
class as `SPEECH_LIKE`, sets `excludeFromDrumFit`, and records
`curationSource` as `user listening 2026-11-22`. Per-rate profiles retain the
measured acoustic classifications.

To exclude a sound from automatic reconstruction, set the optional
`excludeFromDrumFit` boolean to `true` on its DAC asset in the catalogue's
`assets` array. The asset ID, native recipes, and manual selection remain
available. Older catalogues can be used as inputs to the derived index.

## Matching a reference sound

An agent can rank catalogue variants against a short WAV excerpt from a stem.
Use one note or one drum hit with its tail. For a tone, supply the MIDI note.
The matcher renders each eligible tone through the selected native driver
at that note. Use `--target` for S1 or S2 matching.
DAC and PSG noise candidates keep their recorded rate or noise recipe.

```sh
java -jar "$deck_jar" catalog match instruments.json keys.wav --kind fm-voice \
  --midi 64 --start 12.4 --duration 0.6 --gate-ticks 24 --limit 8
java -jar "$deck_jar" catalog match instruments.json drums.wav --kind dac-sample \
  --start 3.2 --duration 0.25
java -jar "$deck_jar" catalog audition instruments.json "$variant_id" candidate.wav \
  --midi 64 --gate-ticks 24
```

The matcher reads at most three seconds directly from the WAV. It averages
stereo and accepts mono or stereo 16-, 24-, and 32-bit PCM or 32-bit float
at 8–192 kHz. Choose **duration** for the note or hit and its audible release.
For tone matching, **gate-ticks** is the held duration at 60 ticks per second.
It defaults to the excerpt duration. The default MIDI note is 60. DAC and
PSG noise auditions keep their existing recipes regardless of those options.

The JSON response includes the profile version, IDs for the shortlist,
and separate spectral and envelope distances. Lower means closer in this
profile. The spectral score compares the balance of frequency bands and how
it changes at the attack. The envelope score compares the shape over time
after aligning the onset and normalizing gain. Brightness, noisiness, and
attack and decay times help agents interpret the result. Results include
the number of candidates compared and reasons for any skipped variants.

For a Suno reconstruction, use the MIDI for note timing and pitch, and the
WAV stems for timbre. Repeat the search on representative notes and drum
hits, then audition the finalists in the actual arrangement. At this stage
the matcher works on one isolated excerpt at a time. Source separation,
polyphonic matching, and ranking the full rendered arrangement are
separate steps.

A controlled S3&K retrieval check used four reference sounds per kind from
the 251 FM, 74 DAC, and 64 PSG noise variants. The references were resampled
to 44.1 kHz and given lower gain and leading silence with a continuous DC
baseline. The source ranked first for all eight FM and DAC references and
three PSG references. The fourth PSG source ranked second. A discontinuity
at the padding boundary had distorted the short noise results in the initial
check. This measures retrieval of known sounds in the catalogue. Matching
Suno stems requires separate validation.

## Matching lead effects

Use an editable S3&K arrangement with a lead and a matching FM double, each
already on its own hardware lane. Supply the two sequence IDs and a time-aligned
stereo WAV.

```sh
java -jar "$deck_jar" catalog match-effects cover.smps.json reference.wav \
  matched.smps.json --lead lead --double lead-double
```

The command compares native renders of the current arrangement, stereo pan,
fixed tuning offsets, and pitch movement. It measures stereo energy and pitch
around paired lead notes. It fits on alternating four-second blocks and checks
the result on the other blocks within the first minute. A supported profile
is applied to the supplied sequences. The JSON response contains the selected
profile, fit and check distances, and supporting note counts. Mono references
and sparse paired notes produce a status and an unchanged arrangement.

The current effect fit accepts tempo-zero editable projects. Each supplied
sequence must have one untrimmed clip, both clips must start together, and
neither sequence may already contain pan, detune, transpose, or relative or
envelope volume commands. Existing note timing and the other tracks are
preserved.

With `reconstruct --audio-refine`, the reconstruction report also includes
`vibratoEstimates` and `reverbEstimates`. Vibrato requires repeated periodic
pitch motion on isolated held notes, and reports rate in Hz and peak depth in
cents. The reverb analysis looks for repeatable post-note decay and marks
ambiguous tails as unresolved. A supported decay describes the tail and
cannot identify the original room or processor. These two analyses produce
diagnostics. The existing echo path can still place supported repeats on
available FM channels.

## Delivery sequence

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
