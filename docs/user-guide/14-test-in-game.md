# 14. Test in game

Hear your song through the retail Sonic 2 or Sonic 3 & Knuckles sound driver. Test in
game replaces the title-screen music in a copy of your clean cartridge image.
Start the patched image in a Mega Drive / Genesis emulator or on a flash cart
and wait for the title screen. With Studio's default settings, the title plays
your song and stays on screen instead of starting the idle demo. Start still
enters the game.

## Select a clean ROM

Use a raw, deinterleaved dump from your own cartridge. The ROM library checks
the full image against these revisions.

| Game | Retail revision | Size (bytes) | CRC32 | Test in game |
| --- | --- | ---: | --- | --- |
| `s1` | Sonic 1 World REV01 | 524,288 | `AFE05EEE` | Coming soon. [Cartridge engine](../architecture/s1-rom-patch.md) awaits project compiler integration. |
| `s2` | Sonic 2 World REV01 | 1,048,576 | `7B905383` | Available |
| `s3k` | Sonic 3 & Knuckles (locked-on) | 4,194,304 | `63522553` | Available |

In Studio, open **ROM settings…** and choose **Browse…** next to the game.
**OK** means the dump verified. **Changed on disk** means the saved path is
unreadable or no longer matches the expected revision. Browse again to
select a clean file. **Clear** forgets the path and leaves the file on disk.

With the headless JAR, set `deck_jar` to its path and run:

```sh
java -jar "$deck_jar" rom set s3k "/path/to/locked-on.gen"
java -jar "$deck_jar" rom list
```

Use `rom clear s3k` to remove the setting. `rom-patch --rom PATH` overrides
the saved path for one export.

## Export a ROM, a BPS patch, or both

1. In Studio, choose **Export…**, select the game next to **Test in game…**,
   and click the button once the ROM verifies. Sonic 2 takes an editable
   arrangement. Native S3K projects target S3K.
2. Choose a destination for **Patched ROM**, **BPS patch**, or both.
3. Leave **Skip title demo** on for uninterrupted title-screen listening.
   **Loop song** is off by default. It requires every channel to reach a
   finite stop on the same tick and rejects unsupported loop or tempo control.
   Sonic 2 leaves unused, immediately stopped channels out of the loop.
4. Review the confirmation and export. The summary shows the bank, music slot,
   compiled size, output paths, and any drum or envelope approximations.

A patched `.gen` image contains copyrighted cartridge data. **Personal use
only. Do not redistribute.** A `.bps` patch is the shareable form. Material
derived from stock assets remains subject to the original rights. The recipient
uses **Apply Patch**, selects the BPS and the same clean ROM revision, and saves
to a new `.gen` file. [Floating IPS](https://github.com/Alcaro/Flips)

From the CLI, use an editable project, a legacy S3K package, or an S2 portable package.

```sh
java -jar "$deck_jar" rom-patch song.smps.json --out song.s3k.gen --bps song.s3k.bps --no-demo
java -jar "$deck_jar" rom-patch song.smps.json --driver s2 --out song.s2.gen --no-demo
java -jar "$deck_jar" rom-patch song.s2.smps.zip --bps song.s2.bps --no-demo
```

The CLI skips the demo only when you pass `--no-demo`. The default title slot
is `0x25` for S3K and `0x99` for S2. To test another music slot from the title screen, pass
`--slot ID --title-music`. Existing output files require `--force`.
[CLI and MCP options](../../headless/README.md#test-an-arrangement-in-sonic-2-or-sonic-3--knuckles)

## Fit the cartridge

- The compiled song has a 32 KB (32,768 byte) limit. S3K uses about 26 KB
  of free fragments in its largest bank. S2 expands the ROM to 2 MiB and
  uses a full dedicated music bank. Shorten the song, trim layering, or
  reuse repeated bytecode if it exceeds the available space.
- S3K encodes custom drums as 4-bit DPCM at their original rates and reports
  signal-to-noise ratios when available. S2 maps drums to the closest retail
  sample and rate, which can sound different from the pinned-driver preview.
- Both engines can approximate PSG volume envelopes with stock contours when
  their space fills. S2 can fit a single new envelope of up to 40 bytes, including
  its hold terminator, or several shorter ones. S2 supports only native
  four-argument modulation commands. S3K custom modulation envelopes require
  an exact stock match.

## Boot the result

- In an emulator, open the patched `.gen` as a regular Mega Drive / Genesis
  game and start from a cold boot. A save state can bypass the startup code
  that installs your song. [Genesis Plus GX documentation](https://docs.libretro.com/library/genesis_plus_gx/)
- On hardware, copy the patched `.gen` to the flash cart's SD card, then
  select it from the cart menu and boot. Follow your cart's instructions
  for compatible SD cards and file systems. [Mega EverDrive Core manual](https://krikzz.com/pub/support/mega-everdrive/core-series/mega-ed-core-manual.pdf)

The project verifies playback in emulators only. Users perform real-hardware
testing on their own equipment.
