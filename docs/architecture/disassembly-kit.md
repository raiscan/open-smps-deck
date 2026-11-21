# SMPS2ASM disassembly kits

`export INPUT OUTPUT --format disasm` accepts an arrangement, a
reconstruction work directory, or an embedded-bank schema-4 `.smps.zip`. Use a new
output directory or a `.zip`, which wraps the same files in a kit directory.
Project and work-directory inputs target skdisasm. Portable packages target
s1disasm, s2disasm or skdisasm according to their driver. For S1 and S2, see
[Export a classic kit](#export-a-classic-kit).

## skdisasm files and rights

- `Sound/Music/OSD_<id>.asm` contains the SMPS2ASM header, tracks and local
  FM voices. Its relocations use labels. The kit preserves the entire bank,
  including any bytes before the header. `INCOMPLETE_RELOCATIONS` means
  unreached or unclassified bytes remain literal. Check them for pointers
  and DAC operands before moving the bank or reusing any additional songs
  stored there.
- `Sound/DAC/<NN>.wav` contains unsigned 8-bit mono PCM for each new sample.
  A sample used at several rates has one WAV. `dac-setup.asm` supplies the
  selector rows, pointer-table extension and setup records.
- `global-voices.asm` and `envelope-setup.asm` appear when the package needs
  driver-resident assets or binding edits. Their slot numbers match the
  package. `manifest.json` is the original portable manifest. The relative
  entries it describes belong to the portable package.
- Recognised stock DAC assets with unchanged playback rate and no transforms
  use retail note constants. Default kits leave stock voice includes and
  transformed stock payloads to local materialisation. Re-export with
  `--rom PATH` to check the asset hashes and write those files for personal
  use. A ROM-materialised kit must stay private. Retail song data still
  exports with `RETAIL_SONG_DATA`. Check the README and manifest for rights
  and other provenance warnings.

## Install in skdisasm

1. Work in a copy of your checkout. Copy the kit's `Sound/` tree into it.
   Include the music source inside a music `startBank` / `finishBank`
   pair. In the Z80 driver source, select a music ID and edit the matching
   entry of `z80_MusicPointers` to point to the kit's exported **song
   label**. Edit `z80_MusicBanks` for the same ID to select the bank. The
   header may start partway into the included bytes. Sonic 3's
   `bankswitchToMusicS3` fixes the upper bank bits. Place Sonic 3 music at
   file offsets `$80000`–`$FFFFF` unless you change that bank-switch scheme.
   Keep each complete music bank within `$8000` bytes.
2. For new samples, apply section 1 of `dac-setup.asm` to `DAC_Banks` in
   `Sound/Z80 Sound Driver.asm`. Preserve its idle row and all existing
   playable rows through `$C4`. Append the new `zmake68kBanks` rows in
   note order ahead of the next routine. For a replacement, edit its existing
   row in place. A note `n` indexes this table at `n & $7F`. At the maximum
   `$DF` the table occupies 96 bytes including the idle row.
3. Apply section 2 inside `startDACBank` in `sonic3k.macros.asm`, after
   the `$C4` pointer and before the first setup record. These placeholder
   entries extend each retail pointer table to the same last note. Apply
   section 3 in the 68000 source after the retail DAC banks. Each added bank
   defines its own pointer table in note order (no idle entry), setup records,
   and generated sample includes. The playback loop indexes that table at
   `(n & $7F) - 1`. Keep each setup record and its sample in the same
   bank, especially on Sonic 3 where `DAC_Setup` emits placeholder metadata
   for cross-bank samples. Each new bank uses two bytes per note slot and five
   bytes per active setup, plus the packed DPCM bytes. The exporter checks the
   `$8000` fit for known samples and `finishBank` checks the assembled size.
   Recheck the retail banks too. An extension adds two pointer bytes per new
   note to each retail bank. For custom notes the kit declares its own
   prefixed constants in the music source.
4. Apply envelope pointer edits to `z80_VolEnvPointers` or
   `z80_ModEnvPointers`, inserting new pointers inside the table and
   emitting any payload separately in the Z80 data block. Check other songs
   that use overwritten slots. For custom modulation with `$82` or `$84`
   operands, the stock driver reads the operand from its code region. Apply
   the existing skdisasm `fix_sndbugs` operand-address change in
   `zDoModEnvelope_cont`. After adding the cursor to `HL`, copy the
   resulting address to `BC` before reading the command and restoring
   `HL`. The operand handlers then read the following envelope byte.
   This also alters stock modulation that relies on the original bug. If
   preserving it matters, keep the bug for those slots or supply their
   resolved playback operands. Apply any global voice edits at their
   indicated slots in `z80_UniVoiceBank`. A package's abstract global-table
   pointer becomes that Z80 address. If you change the size of an earlier
   driver table, update any literal universal-voice pointers used by your
   other music or SFX to point to the moved label.
5. Confirm the RAM boundaries in the assembler listing. In the stock
   Sonic 3 build, the executable block ends at `$114B` with 437 bytes
   free before the table block at `$1300`. Appending all 27 new rows
   alone consumes 27 of those bytes. The table block ends at `$1B43`,
   189 bytes below `zDataStart=$1C00`. With `fix_sndbugs`,
   `zDataStart=$1BF0`, so read the actual build listing. The save tracks
   end at `$1FA0`, immediately before the reserved `$60`-byte CPU stack.
   Retain the `$1300` executable boundary and the RAM/stack limits. The
   DAC change needs no new variables. In the Sonic 3 source, if additional
   envelopes or global voices grow the table block, adjust
   `Size_of_Snd_driver2_guess` and make the `SndDrvInit` copy cover the
   actual code and tables without crossing `zDataStart`. Remove or reduce
   its stock extra `$BC` copy padding accordingly. S&K decompresses the
   blocks separately. Recheck the compressed ROM reservations and the
   uncompressed Z80 addresses in that build. Follow the assembler's
   `$1300` and `zDataStart` assertions if other driver changes reduce
   available space.
6. Run the normal Lua build, for example `lua buildS3.lua`. It converts
   WAVs into `Sound/DAC/generated/*.inc` and DPCM payloads before
   assembly. If the bank additions exceed the final ROM padding address,
   move that boundary and check compatibility with your target ROM layout.
   For a 2 MiB S&K cartridge that must retain physical lock-on support,
   reclaim space within the original region instead of placing new data
   in the lock-on address window.
   For `$B2` and `$B3` stock references from locked-on S3K, use the
   Sonic & Knuckles sample variant even when building Sonic 3.

The driver treats `$E0` and above as coordination flags, so the new
note range ends at `$DF`. No track-range comparison, playback flag,
or DAC index change is needed through that limit. To reuse an unused retail
slot, supply `--replace-dac NN` in hexadecimal. Repeat for several slots.
The listed slots receive custom bindings first. Any remaining bindings
start at `$C5`. Audit your other music and SFX before choosing replacements.
A single sample that exceeds a DAC bank even on its own needs reslicing.

## Rate and DPCM

The WAV sample rate is chosen inside the truncation interval of
`dpcmLoopCounter(int(sample_rate))`. All 256 rate bytes are reachable.
For zero, use the interval where the integer division in the macro
produces −1, before it adds one. Byte zero is emitted literally and the Z80's `DJNZ` executes 256 delay
iterations. For secondary bindings, `DAC_Setup ptr, scale` uses a
12-decimal scale padded inside the target integer's rounding interval.
Stock DAC note reuse compares the binding's rate byte with the retail
playback rate in `stock/s3k-dac-rates.txt`. Each row gives the note byte
and rate byte in hexadecimal from the pinned locked-on loader's DAC mapping.

DPCM cannot reproduce arbitrary 8-bit PCM. The kit reports
`DPCM_INEXACT` per sample when encoding and decoding changes any byte or
adds an end sample. Odd-length PCM is padded by one sample because the
build converter drops an incomplete nibble pair. `--dpcm-closed` writes
the decoded quantised result as the WAV. The skdisasm build then decodes
back to the exported WAV, matching the package's PCM exactly when the
original sample is DPCM-representable. For inexact samples the flag changes
the PCM and OpenGGF still plays the original package bytes.

## Local acceptance

Use your own ROM and an arrangement with FM, PSG and DAC. Export the kit
with `--rom` and `--dpcm-closed`, apply the steps above in a scratch
checkout, and run the Lua build there. Use listing symbols to extract the
inserted bank. Compare it to the package's assembled bank after applying
its listed address relocations and the README's DAC note mapping. Check
each custom setup rate and decode its DPCM payload back to the WAV.
The Linux `buildS3.lua` and `buildSK.lua` builds were checked this way on a
private reconstruction with expanded ROM layouts. In both builds, the
inserted 12,927-byte bank matched after relocation and note remapping.
All four custom DAC setup rates and decoded waveforms matched. These
checks inspected the assembled binaries.

## Export a classic kit

Compile the arrangement to the desired portable driver before exporting
the kit. For example:

```sh
java -jar "$deck_jar" compile-package song.smps.json song.smps.zip --portable --driver s2 --speed-up-tempo 64
java -jar "$deck_jar" export song.smps.zip song-kit --format disasm --dpcm-closed
```

Classic kits use lowercase `sound/music` and the matching checkout's
`sound/_smps2asm_inc.asm`. The assembly preserves each bank's native FM
operator storage order, including all voice bits. An identity PSG binding
uses the retail table entry. For other PSG bindings, apply the generated
`envelope-setup.asm` rows and append its data outside the pointer table.
Audit other music and SFX before changing a retail slot. S2 indexes the
PSG pointer table with an eight-bit doubled index, so slot 128 is the
highest it can address. Use the matching game ROM with `--rom PATH` to
materialise references, including S2 PSG data from the shared ROM extractor.
Keep the materialised kit private. `RETAIL_SONG_DATA` also applies to
classic packages with retail provenance.

The README gives the native speed-shoes byte from
`driverOptions.speedUpTempo`. When omitted, install the normal header
tempo byte in the shoes table for that ID. See the
[tempo conversion](driver-support.md#tempo-conversion) for the source clock.

### Install in s1disasm

1. Copy the kit's `sound/` tree into a scratch checkout. In
   `s1.sounddriver.asm`, include the song after the SMPS2ASM macros.
   For example, replace the `Music81` include and change `ptr_mus81`
   in `MusicIndex` to `dc.l OSD_<id>`. Keep the alignment before the next
   song. S1 reads the song from 68000 ROM and uses big-endian offsets
   relative to its header. There is no music bank table.
2. Write the speed-shoes byte to `SpeedUpIndex[music ID − $81]`. In the
   stock checkout, only the first eight entries exist. For later IDs,
   fill earlier missing entries through the chosen slot before
   `MusicIndex`. Use those songs' intended native tempos. The stock
   driver otherwise reads the adjacent pointer bytes for those IDs.
   Apply PSG edits to `PSG_Index` using 68000 longwords.
3. The stock Z80 DAC driver has three sample records. The 68000 driver
   treats notes `$88–$8B` as pitch variants of timpani, writing the pitch
   into the `$83` record. When a song uses direct `dTimpani` after any pitch
   variant, the direct note inherits that pitch. To give `dTimpani` its
   original pitch on each trigger, add a check for `d0.b == $83` in the
   direct path of `DACUpdateTrack`, immediately before the write to
   `zDAC_Sample`. On equality, write
   `#dpcmLoopCounter(zDAC_Timpani.sample_rate)` as a byte to
   `(z80_ram+zTimpani_Pitch).l`, then continue to the normal sample write.
4. To use a new sample, explicitly pass `--replace-dac 81` or
   `--replace-dac 82` for each custom binding in order. The kit replaces
   the corresponding kick or snare include in `sound/z80.asm` and leaves
   its metadata macro to derive the pitch from the WAV. Audit all other
   users of those notes. The kit rejects additional custom bindings.
   Independent new sample slots require changes to the 68000 note
   dispatch and the Z80 sample selector, tables and available RAM.
5. Run `lua build.lua`. Check the listing's uncompressed driver size
   against `z80_stack=$1FFC`, including all three packed samples. Check
   the compressed driver reservation (`Size_of_DAC_driver_guess`) and
   validate the resulting ROM layout. The kit requires no extra 68000
   track RAM.

### Install in s2disasm

1. Copy the kit's `sound/` tree into a scratch checkout. In `s2.asm`
   select a music ID and replace that song's generated include with
   `include "sound/music/generated/OSD_<id>.inc"`. Keep the original
   `Mus_*` label, its `music_ptr` entry inside `MusicPoint1` or
   `MusicPoint2`, and the matching `music_metadata` entry in
   `zMasterPlaylist`. For example, ID `$82` uses `Mus_EHZ` and
   `zMusIDPtr_EHZ`. The wrapper reports the compression flag to the
   metadata macro, which also supplies the bank select bit. Keep
   each bank within `$8000` bytes and retain the include's alignment.
2. To build a Saxman song, replace the old filename stem with
   `OSD_<id>` in `sound/music/list of compressed songs.txt`. The build
   assembles at the Z80 buffer address `$1380`. Use compressed placement
   only up to `$7C0` uncompressed bytes. For an uncompressed song, leave
   the new stem out of that list. The SMPS2ASM macros emit little-endian
   pointers into the active Z80 ROM window at `$8000–$FFFF`.
3. Write the speed-shoes byte to `zSpedUpTempoTable[music ID − $81]` in
   `s2.sounddriver.asm`. Apply PSG edits to `zPSG_EnvTbl` using Z80 words.
4. Apply the sections of `dac-setup.asm`. Add the data before `SndDAC_End`
   in `s2.asm`, append sample pointers to `zDACPtrTbl`, and edit or append
   the marked note rows in `zDACMasterPlaylist`. Extensions start at
   `$92` and end at `$DF`. Repeat `--replace-dac NN` to use audited
   unused rows in `$81–$91` first. A replacement edits a playlist row
   in place. Custom sample records follow the seven retail records. An
   untransformed stock sample at a different rate uses a custom note row
   pointing to the existing sample. For example, S2 REV01's pinned
   portable loader reports a different rate for `$91` than s2disasm
   assembles. The kit retains the package rate with its own row.
   The two low-byte table lookups require the generated
   `ensure1byteoffset` sizes. Each table must fit entirely within one
   256-byte page. The selector can address up to 64 sample records in
   total. ROM and Z80 RAM limits can reduce those counts.
5. Run `lua build.lua`. Use the assembler listing to check that Z80 code
   and tables end by `zMusicData=$1380`, including page padding.
   If they run out of space, reduce the added data or reclaim driver space.
   Check that the compressed Z80 driver fits its reservation
   (`Size_of_Snd_driver_guess`). All packed samples must fit within
   the same bank selected by `SndDAC_Start`. Increase
   `Size_of_DAC_samples` as needed up to `$8000`, and recheck the
   resulting driver, DAC and music ROM layout.

### Classic rate and DPCM

Stock DAC note reuse compares the binding's playback byte with
`stock/s1-dac-rates.txt` or `stock/s2-dac-rates.txt`. Each row gives the
hexadecimal note and rate bytes from the retail setup or playlist.

The S1 and S2 WAV rate formulas use
`1 + (Z80_Clock / hz − (baseCycles / 2) + (13 / 2)) / 13`
with truncating integer division. The bases are 301 and 295 cycles per
two samples, respectively. Every rate byte has a WAV sample rate in
its truncation interval. S2 writes the bytes literally in the playlist,
so several rates can share one sample. Both drivers use the S3K delta
table. The same `DPCM_INEXACT` reporting, odd-length padding, and
`--dpcm-closed` behaviour apply.

### Classic local acceptance

The Linux `lua build.lua` scripts built an original FM/PSG/DAC arrangement
in both scratch checkouts. The S1 kick/snare replacement and S2 DAC extension matched all 487 package
bank bytes after the applicable pointer relocation and DAC note remapping.
The S2 build was verified with both Saxman and uncompressed ROM placement. Each finished ROM's two DAC
rate bytes and decoded samples matched the kit. S2 needed a `$3900` DAC
reservation for this fixture.
