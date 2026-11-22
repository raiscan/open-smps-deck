# Headless ROM probe

Boot a Genesis ROM in BizHawk 2.11's GPGX core, script controller inputs, and
check whether a title music patch plays the expected song, keeps the title idle,
and still accepts Start. The probe runs without EmuHawk, a display, or an audio
device.

The probe accepts modified ROMs without a hash gate. It records the SHA-1 and
SHA-256 of the ROM and any supplied reference WAV in `report.json`. Patched
ROMs and captures are for personal use and must never be committed. Keep inputs,
builds, and all run outputs in private directories outside the checkout.

## Setup and build

Use an extracted **BizHawk 2.11 Linux x64** release, Mono with the `mcs` compiler
(`mono-devel`), and Python 3.9 or newer. Set `BIZHAWK_HOME` to the release root
containing `dll/`. The default is
`${XDG_DATA_HOME:-$HOME/.local/share}/BizHawk-2.11-linux-x64`.
NumPy is needed only for WAV analysis. ffmpeg with libx264 is optional for video.

From the repository root, set `build` to your private build directory and run:

```sh
tools/rom-probe/build.sh "$build"
```

## Run a scenario

For example, set `rom` and `reference` to the input file paths, and `out` to a
new or empty output directory. This S3K scenario checks the injected bank after
the intro and watches whether the first title visit stays idle for 60 seconds.
Adjust the frames and identity for your patch and region.

```sh
tools/rom-probe/run.sh --build "$build" --rom "$rom" --profile s3k \
  --out "$out" --frames 5400 --reference "$reference" \
  --title-song-from 300 --bank 0x59 --no-demo
```

To test Start separately, choose a different output directory. Schedule the
press after the title animation accepts input and before the demo timeout.
An early Start may skip the intro while the game stays in title mode.

```sh
tools/rom-probe/run.sh --build "$build" --rom "$rom" --profile s3k \
  --out "$start_out" --frames 850 --press 700:Start --start-game 700 120 \
  --watch pad=68k:0xF604:2
```

- `--frames` counts emulated frames. Frame 0 is the first call to advance the
  core. Each row samples RAM after advancing that frame. Report times use
  GPGX's frame rate and the start timestamp of each sampled frame.
- Repeat `--press FRAME:BUTTON` for a sequence. A press holds the button for
  two frames. Buttons are `Up`, `Down`, `Left`, `Right`, `A`, `B`, `C`, and
  `Start`. Presses can overlap.
- Repeat `--watch NAME=CPU:OFFSET[:SIZE]` for extra RAM columns. Use `68k` or
  `z80` and a RAM-domain offset, such as `0xF600` for CPU address `0xFFF600`.
  Sizes are 1, 2, or 4 bytes, with big-endian reads for 68K and little-endian
  reads for Z80. Addresses and expected values accept decimal or `0x` hex.
- `--reference WAV` records the file's hashes. Use the analysis command below
  to compare it with the captured audio.

### Checks

Checks are opt-in and can be combined. The exit status is 0 if all selected
checks pass (or if no checks were selected), 1 if any selected check fails,
and 2 for invalid input or a capture error.

| Check | Pass condition |
| --- | --- |
| `--title-song-from FRAME` | The selected frame is on title, and every sampled frame from there through the end of that title visit or capture matches all supplied identity criteria. Provide at least one of `--bank BYTE`, `--song BYTE`, or `--pointer TRACK:MIN:MAX`. Pointer ranges are inclusive. The report includes the first matching frame anywhere in that visit and the first failure. A bank or song ID can persist after playback ends. Add a pointer criterion when an active track is required. |
| `--no-demo [SECONDS]` | The first contiguous title visit lasts at least this long with the demo flag clear and no scheduled input. It defaults to 60 seconds. The idle interval ends on departure, any button input, or capture end. |
| `--start-game PRESS_FRAME WINDOW` | A scheduled Start follows a title frame and the first title departure reaches a profile-specific level or menu mode with the demo flag clear, at most `WINDOW` frames after that press. The pre-level high bit is ignored for mode matching. |

All profiles report game mode, demo flag and demo timer. The music fields
come from the disassembly symbols cited in [GameProfile.cs](GameProfile.cs).

| Profile | Music fields |
| --- | --- |
| `s3k` | Z80 `zSongBank` and track pointers. Track names are `fm6_dac`, `fm1` through `fm5`, and `psg1` through `psg3`. |
| `s1` | 68K sound request byte, current voice-table pointer, and track pointers. The driver consumes the request byte, so use `--pointer` for sustained identity checks. |
| `s2` | Z80 `zCurSong`, music bank selector (the driver stores a bank flag), and track pointers. |

S1 and S2 accept track names `dac`, `fm1` through `fm6`, and `psg1` through
`psg3`. Inactive track pointers are logged as 0. These RAM layouts were checked
on stock NTSC World REV01 for S1 and S2 and a locked-on S3K image. A patch
or revision that moves RAM variables can require a profile change.

### Output

The output directory receives `capture.wav` (44.1 kHz stereo PCM), `probe.log`
(one CSV row per frame, with RAM values in hexadecimal), and `report.json`
(input hashes, timings, check results, and notes). Add `--video` for a silent
`capture.mp4` using the core's frame rate. If ffmpeg is absent, audio and checks
still run and the report records why video was skipped.

## Compare captured and reference WAVs

Install NumPy in your Python environment (`python3 -m pip install numpy`).
The analysis reads PCM WAVs and can compare different sample rates and channel
counts.

```sh
python3 tools/rom-probe/analyze.py "$out/capture.wav" "$reference" \
  --offset-hint 4.4 --out "$out/analysis.json"
```

The script cross-correlates 10 ms RMS bins, tracks local offsets in 10-second
windows, and fits a linear drift to the window offsets. The optional hint
limits the initial match to within three seconds of the given offset. Without
a hint, the script searches the capture for the first reference window.
Positive offsets mean the reference starts later in the capture timeline.
Positive drift means the capture takes longer to cover the same musical
passage. The JSON includes both WAVs' SHA-1 and SHA-256, local and drift-aligned
window correlations, and the overall Pearson correlation across overlapping
audio after alignment. Drift is `null` when fewer than two usable windows
provide a fit. Audio alignment is a diagnostic and has no pass threshold.

Run the synthetic WAV tests locally with NumPy installed. They require neither
a ROM nor BizHawk. The existing Java playback workflow has no NumPy dependency.

```sh
python3 -m unittest discover -s tools/rom-probe -v
```

## Check a second emulator with libretro

The headless libretro frontend runs with stock Python 3.9+ under `python3 -I`.
Use BlastEm for an independent accuracy check, or supply a software Mega Drive
core such as Genesis Plus GX. The core runs as native code in the probe process,
so use a trusted build for your OS and architecture.

On Arch or CachyOS, fetch the package into a private scratch directory without
installing it. Set `scratch_root` to a writable directory outside the checkout.

```sh
scratch=$(mktemp -d "$scratch_root/rom-probe.XXXXXXXX")
url=$(pacman -Spdd libretro-blastem)
curl --fail --location "$url" --output "$scratch/blastem.pkg.tar.zst"
bsdtar -xf "$scratch/blastem.pkg.tar.zst" -C "$scratch" usr/lib/libretro/blastem_libretro.so
export LIBRETRO_CORE="$scratch/usr/lib/libretro/blastem_libretro.so"
```

The tested CachyOS package version was `20261010.190722.gab743653ed11`
(BlastEm reports `1.0.1-pre`). On other distributions, extract the corresponding
package (for example with `dpkg-deb -x` for a Debian package that ships the
core), use a compatible standalone core from the Libretro buildbot, or
[build the libretro core from source](https://github.com/libretro/blastem)
with `make -f Makefile.libretro` from the libretro branch. Pass the extracted
or built shared library with `--core PATH` or `LIBRETRO_CORE`.

```sh
python3 -I tools/rom-probe/libretro_probe.py --rom "$rom" --profile s3k \
  --out "$out" --frames 11400 --no-demo 180 --reference "$reference"
python3 -I tools/rom-probe/libretro_probe.py --rom "$rom" --profile s3k \
  --out "$start_out" --frames 850 --press 700:Start --start-game 700 120
```

Use the same scenario flags, check semantics, and new-or-empty output directory
rule as the BizHawk runner. This frontend accepts modified ROMs for personal
use. Keep patched ROMs, core binaries and captures outside the checkout and
never commit them. The probe reads the ROM from its existing path and sets the
core's system and save directories to the output directory.

The WAV is stereo 16-bit PCM at the core's reported rate rounded to the nearest
integer Hz. `--video` adds a silent MP4 from software video frames using ffmpeg
and libx264. `report.json` records both ROM and core hashes, plus the hashes of
any supplied reference WAV. The `emulator` object records the library name,
version, and SHA-256 of the library file as `path_sha256`. `core.log` records
libretro messages and log format strings from the core.

BlastEm exposes 64 KiB of 68K work RAM and cartridge save RAM where present.
This build exposes no Z80 RAM. The probe uses a flat Z80 RAM memory-map
descriptor if the core supplies one. Otherwise the Z80 fields in `probe.log`
are blank and a requested check that depends on one of those fields is marked
`unavailable` (exit 1). For S2 or S3K on BlastEm, compare the captured WAV with
the pinned render and the BizHawk capture using `analyze.py`. S1 track pointers
are in 68K RAM and can be checked directly. BlastEm and Genesis Plus GX use
host-native 16-bit word storage for 68K RAM. For other cores, the default is
big-endian bytes. Use `--ram-order` to override either choice.

Run the host's synthetic tests with the standard library alone. Keep ROM and
emulator validation local.

```sh
python3 -m unittest discover -s tools/rom-probe -p test_libretro_probe.py -v
```
