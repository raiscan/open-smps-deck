# Sonic 1 ROM patch engine

`S1RomPatch` consumes the player's clean Sonic 1 World REV01 image (512 KiB,
SHA-1 `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B`) and a resolved S1 portable
bank. It builds a new image and JSON report in memory. `S1RomTarget` plugs into
the verified ROM export contract. Test in game compiles editable arrangements
with the S1 portable compiler and also accepts resolved S1 portable packages.

The engine checks the entire input SHA-1, the expected cartridge instructions,
header fields and table pointers, and the expected decompressed DAC driver
layout. All addresses are fixed for REV01. They were checked against a
byte-identical `sonic.lst` build of the disassembly. All ranges below are
half-open unless specified otherwise.

## 68K placement and checksum

The bank starts at `0x080000`, immediately after the original image. The
engine appends any new envelope table, envelope data, and DAC installation
code after the bank. Unused space through `0x100000` is filled with `FF`.
The 68K addresses the bank directly.

| ROM address | Source | Purpose |
| --- | --- | --- |
| `0x18E` | `sonic.asm:174–180` | Cartridge header checksum |
| `0x1A4` | `sonic.asm:183–184` | Inclusive end address, changed to `0x0FFFFF` |
| `0x31C` | `sonic.asm:380–393` | Boot checksum routine |
| `0x71998`, `0x71A9C` | `s1.sounddriver.asm:31,99–118` | Master music pointer and 19 longword song pointers |
| `0x30DC` | `sonic.asm:2019` | Title music request |
| `0x3216` | `sonic.asm:2159–2163` | Title demo timeout branch |
| `0x318E` | `sonic.asm:2080–2086` | Title scrolling failsafe |

`CheckSumCheck` reads the inclusive end address from the header. It adds each
big-endian 16-bit word from `0x200` through that boundary into a 16-bit sum,
then compares the sum to the header. REV01 declares no cartridge SRAM
(`EnableSRAM = 0`, `sonic.asm:37,187–195`). The header end field has only the
checksum reader in the listing cross-references. The unused `DemoRecorder`
at `_inc/MoveSonicInDemo.asm:12–28` references the original assembly-time
`EndOfRom` as a write destination for a development cartridge. The retail
execution paths use no ROM-end mirror to reach code or data.

The replacement slot is a music ID in `0x81`–`0x93`, default `0x8A` (title).
At the default slot the original title instruction works unchanged. A different
slot with the title redirect option changes only the title request immediate.
The ending requests `0x8B` and the credits request `0x91`
(`sonic.asm:2243,3611,3686`). The level select sound test can request any
slot, including title (`sonic.asm:2210–2228`). Replacing a gameplay or
credits slot affects every request for that ID. The retail speed-shoes
tempo table has entries for `0x81`–`0x88`. Later IDs read past that table
into the music pointers, a retail quirk relevant when replacing one of the
first three pointers (`s1.sounddriver.asm:69–99,795–809`).

With no-demo selected, the engine NOPs the timer-expiry branch and makes the
later scroll-limit branch unconditional. Without the second change the title
eventually returns to the Sega screen. Start and level select keep their
existing branches.

## Bank and PSG

The bank starts with a six-byte header followed by four-byte DAC/FM entries
and six-byte PSG entries. `Sound_PlayBGM` adds the song address to the
big-endian voice and track offsets (`s1.sounddriver.asm:795–839,848–903`).
The pinned `Sonic1SmpsData` decodes the same relative header. Retail
`F6`, `F7`, and `F8` use a signed word displacement based at the second
operand byte (`cfJumpTo` reads two bytes, adds the word, then subtracts one).
The pinned S1 sequencer selects relative pointers as well. Moving a complete
bank preserves these relationships. The accepted compiler profile has finite
streams and emits none of those control-flow commands. The engine caps the
bank at 32,768 bytes and accepts its local 25-byte FM voices in S1 operator
order. The arrangement profile supports DAC, FM1–FM5, and PSG1–PSG3.

The stock table at `0x719A8` contains nine longword pointers. With custom
envelopes, the engine writes a new table with all nine stock pointers in
order, then pointers to the new envelopes. `Go_PSGIndex` at `0x719A4` points
to that table. The engine reuses byte-identical stock or custom entries and
remaps the PSG header envelope byte and `F5` operands. Slot zero still means
no envelope. The driver uses a one-based byte slot and an eight-bit envelope
position. It recognizes `80` as *hold*: it steps back to the same position
and leaves the current chip attenuation in effect. The pinned S1 renderer
uses the same hold mode. Envelopes may contain up to 255 attenuation bytes
followed by `80`. The combined table holds at most 255 entries
(`s1.sounddriver.asm:35–64,1925–1955,1991–1993,2582–2584`).

## DAC driver

`DACDriverLoad` at `0x134A` decompresses the Kosinski block at
`0x72E7C` to Z80 RAM. Both startup and the post-Sega title path call it
(`sonic.asm:416,1225–1240,1903`). Stock kick, snare and timpani end at
Z80 `0x1BC6`, leaving 1,078 bytes to `0x1FFC`. The driver defines its
stack at `0x1FFC` and status/sample mailboxes at `0x1FFD`/`0x1FFF`.
The Sega PCM chant streams from its existing cartridge bank. Its Z80
dispatch is separate from the drums (`sound/z80.asm:13–22,79–113,187–229`).

Each drum entry has a little-endian Z80 pointer and encoded byte length,
then a pitch byte and three padding bytes. The Z80 plays high nibble
first, adds a signed delta modulo 256 to an accumulator starting at
`0x80`, and writes each sample to the YM2612 DAC. The nominal NTSC rate
is `7,159,090 / (301 + 26 × (loops − 1))` Hz, where pitch byte zero
means 256 loops. This is the same base cycle count and accumulator model
used by the pinned S1 loader and DAC playback.

The retail driver indexes only `0x81`–`0x86` as DPCM. The existing
three-entry table starts at `0xD6`. Its apparent `0x84`–`0x86`
entries overlap kick data. `0x87` goes to the Sega PCM routine. To install
custom drums, the engine relocates the table to `0x1BC6`, with six entries
and 1,030 bytes for encoded payloads, and patches the immediate of
`ld iy,zPCM_Table` at Z80 `0x44`. The first three entries are copies of
the originals. The 68K timpani-rate write at ROM `0x71CB4` targets the
copied timpani pitch byte. The original table and sample bytes stay intact.

The hook at ROM `0x136C` replaces the first reset write after decompression
with a call. The original routine has already requested the Z80 bus. The
stub waits for bus grant at `$A11100`, uses byte writes to install the
table, operand and encoded payloads, performs the displaced reset write
and returns to the retail reset delay, deassertion and bus release
(`sonic.asm:1227–1239`, `s1.sounddriver.asm:323–344`).

The engine first tries exact PCM and pitch matches to the stock options.
It trellis-encodes the remaining distinct PCM and pitch pairs with the
revision's delta table when they fit the three custom IDs and aggregate
byte budget. An odd PCM length occupies a full final encoded byte.
If the new set exceeds either limit, the engine maps the remaining drums
to stock kick, snare or a timpani pitch alias. It compares windowed
spectral power, normalized RMS envelope, duration and sample rate.
The aliases set their own pitch, while note `0x83` inherits the last
timpani rate. The two slow aliases `0x8C` and `0x8D` use the same rate,
so only the first is needed as a candidate.

## Report and verification

The JSON report records input and output hashes, music and title IDs, written
ranges, the ROM size, inclusive end address and checksum. It includes the PSG
mapping and relocated table range, the DAC RAM budget, requested and installed
DAC rates and per-note mapping details. Custom drums report RMS error, peak
error and finite SNR in decoded PCM units. Stock approximations report the
chosen sound and distance score. The patched ROM contains copyrighted data
and is for personal use only.

The synthetic tests cover the expansion, checksum, hook writes, both
envelope operand forms, custom and fallback DAC, and BPS round-trip. Set
`OPENSMPSDECK_S1_ROM` to exercise the supported retail revision. The pinned
renderer can play the resolved package as a reference, where its DAC bindings
represent PCM directly. The patched cartridge uses DPCM or substitutes stock
samples and follows retail speed-shoes behavior. S3K modulation envelopes,
FM volume envelopes, SSG-EG and S3K-specific PSG envelope commands require
different authoring for S1.
