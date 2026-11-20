# Pinned OpenGGF audio

This module contains a reviewed dependency closure from OpenGGF, preserving its
Java packages. It is the project's sole playback foundation. It also includes the cartridge reader and Sonic 1, Sonic 2, and S3K loaders
used for local music import. Build the new modules with **JDK 25**.

The authoritative pin, source SHA-256 values, licenses and exact local patches
are in [the lock](../upstream/openggf-audio.lock.json). The patches remove an
unused `AudioManager` import, two non-audio S1 profile overloads, and the
S2 envelope fallback and unused S1/S2 priority tables. The benchmark reads
S2 envelope data from the local ROM.
Do not hand-edit generated Java. Project-specific orchestration belongs outside
this module.

Verify the committed snapshot offline:

```sh
python3 tools/openggf_audio.py check
python3 -m unittest discover -s tools/tests -v
mvn -B -pl headless -am test
```

Check provenance or reproduce the snapshot from a local OpenGGF Git clone:

```sh
python3 tools/openggf_audio.py check --source /path/to/OpenGGF
python3 tools/openggf_audio.py sync --source /path/to/OpenGGF
```

These commands read the **locked Git objects**, including licenses, rather than
the clone's current branch or dirty working files. `sync` requires the reviewed
lock hashes to agree before writing. To re-pin, review the new source closure,
update revision, paths and hashes in the lock, then sync, test and run parity.
It intentionally does not automatically discover and bless a new dependency set.

## Independent source-extraction parity

```sh
python3 tools/verify_openggf_audio_parity.py \
  --source /path/to/OpenGGF --scratch /path/to/disposable/disk/scratch
```

The probe compiles the locked **original** source objects independently from
the vendored files and compares PCM and logical chip-write hashes for finite FM,
PSG and S3K CALL/RETURN/LOOP fixtures. The original source retains its dependencies. The probe adds compile-only
stubs for the non-audio types and reads the upstream S2 envelope fallback
from the pinned Git source during the independent build. No reference source is patched.

Both runs use the accurate FM core, the S3K physical policy, NTSC hardware-frame
settings, 48 kHz WAV-equivalent output and 120 presentations at 60 Hz. The probe
uses upstream's direct-read adapter with explicit frame service; the CLI tests
separately exercise production music activation. A fourth probe also compares
the authored production demo adapter against both independent cores. This is **extraction parity**,
not proof of gameplay integration, arbitrary SMPS import, native-console audio
accuracy or Suno reconstruction fidelity.

## Licenses

Upstream's GPL-3.0 text is retained as `LICENSE.upstream`; its bundled mod
exception and third-party LGPL-2.1 and MAME/BSD notices are in `LICENSES/`.
Original source and package provenance notices remain intact. The application
repository's own license remains at the repository root.

`NOTICE.upstream.md` preserves the upstream distribution notice (including
descriptions of components outside this extraction). The built audio and
headless JARs carry the lock, licenses, notices and complete extracted source
under `META-INF/openggf-audio/`, retaining copyright/permission and modification
notices that would otherwise be lost when Java comments are compiled away.
