# Importing

Import existing SMPS music, voice banks, and audio samples from files or ROM rips.

## Overview

OpenSMPS Deck provides several import paths for bringing external data into your project. You can import complete SMPS binary files as new songs, load FM voice banks and individual patches from `.ovm` and `.rym2612` files, scan directories of SMPS files to cherry-pick specific voices, and add DAC samples from WAV or raw PCM files. These tools make it straightforward to study existing Sonic music, remix classic tracks, and build a personal voice library from ROM rips.

## Importing SMPS Binary Files

Choose **File > Import SMPS** to open a raw SMPS binary as a new song. The file chooser accepts four extensions:

- `.bin` -- standard SMPSPlay binary rip
- `.s3k` -- Sonic 3 & Knuckles SMPS format
- `.sm2` -- Sonic 2 SMPS format
- `.smp` -- generic SMPS binary

The importer reads the six-byte SMPS header to extract the voice table pointer, FM and PSG channel counts, dividing timing, and tempo. It then parses each FM channel entry (four bytes: data pointer, key offset, volume offset) and each PSG channel entry (six bytes: data pointer, key offset, volume offset, modulation envelope, instrument index).

For each channel, the importer follows the data pointer and scans forward through the bytecode, then passes the track data through the `HierarchyDecompiler`. The decompiler runs a three-pass analysis: first it identifies subroutine boundaries (CALL targets through RETURN), then it performs a linear scan of the main stream to split data into phrases at structural boundaries (subroutine calls, loops, key displacement changes), and finally it resolves any JUMP target to a chain entry index for the loop point. Header state such as key displacement and volume offsets are prepended as coordination flag commands so the imported tracks play back correctly.

The voice table region is located using the header pointer and bounded by the nearest track data pointer. Each 25-byte block in that region is extracted as an FM voice and added to the song's voice bank. Voice count is capped at 64.

The result is a new Song with a full hierarchical arrangement -- each channel gets a chain of phrase references decompiled from the original SMPS track structure, and all shared subroutines become shared phrases in the Phrase Library. Voices and track data are reconstructed from the binary. This is useful for studying how existing Sonic music is structured, analyzing voice patches, or remixing classic tracks.

## Importing Voice Banks

There are two voice bank file formats you can import, each handled through the same UI flow.

### From .ovm Files

An `.ovm` file is a JSON voice bank containing multiple FM voices and optionally PSG envelopes. To import one:

1. Choose **File > Import Voice Bank**, or click **Import Bank...** in the **Voice Bank** section of the instrument panel.
2. Browse to the `.ovm` file and open it.
3. If the bank contains multiple voices, a selection dialog appears (see [Voice Selection Dialog](#voice-selection-dialog) below). Select the voices you want and click **OK**.
4. The selected voices are appended to the current song's voice bank. Any PSG envelopes in the bank are also imported and appended to the PSG envelope list.

The `.ovm` format stores each voice as a name and 25 bytes of hex-encoded SMPS voice data. PSG envelopes are stored the same way -- a name and hex-encoded volume step data terminated by `0x80`.

### From .rym2612 Files

A `.rym2612` file is an XML patch file from the RYM2612 VSTi FM synthesizer. The importer reads the XML, converts the floating-point parameter values to SMPS-compatible register data, and produces a single FM voice.

The conversion maps RYM2612 parameters to YM2612 registers:

- **Algorithm** and **Feedback** map directly (both 0--7).
- For each operator: **MUL**, **DT**, **TL**, **RS**, **AR**, **AM**, **D1R**, **D2R**, **D1L** (labeled D2L in RYM2612), and **RR** are clamped to their respective register ranges.
- Detune values are converted from signed (-3 to +3) to the YM2612 register format (0--7).

The imported voice is named using the `patchName` attribute from the XML file. It is added directly to the voice bank without a selection dialog since `.rym2612` files contain a single patch.

## Importing Voices from SMPS Files

Choose **File > Import Voices** to scan a directory of SMPS binary files and extract all FM voices for selective import. This is the most powerful way to build a voice library from ROM rips.

1. A dialog opens with a **Browse...** button. Click it and select a directory containing SMPS files (`.bin`, `.s3k`, `.sm2`, `.smp`).
2. The importer scans every matching file in the directory. For each file, it parses the SMPS header, locates the voice table, and extracts all FM voices. Duplicate voices (identified by identical 25-byte voice data) are automatically filtered out.
3. The discovered voices appear in a filterable table. Each row shows the voice's source song name, its original index within that song, and its FM algorithm number (0--7).
4. Use the filter field and multi-select to pick the voices you want, then click **OK**.
5. The selected voices are added to the current song's voice bank.

Files that fail to parse (corrupted data, incomplete headers, non-SMPS content) are silently skipped. A warning is logged but does not interrupt the scan.

## Voice Selection Dialog

The voice selection dialog appears when importing from `.ovm` banks with multiple voices or when scanning a directory of SMPS files.

### Filter Field

At the top of the dialog is a text field with the placeholder "Filter by song name or algorithm...". Type to filter the table in real time:

- Enter part of a song name to show only voices from matching files.
- Enter an algorithm number (`0` through `7`) to show only voices using that algorithm.

The filter matches against the source song name (case-insensitive) and the algorithm number as a string. Clear the field to show all voices again.

### Table Columns

The table displays four columns:

| Column | Description |
|--------|-------------|
| **Name** | Composite label showing the source song name and original voice index (e.g., "GreenHill #2"). |
| **Source Song** | The filename (minus extension) of the SMPS file the voice was extracted from. |
| **Index** | The voice's position in the original song's voice bank (zero-based). |
| **Algo** | The FM algorithm number (0--7), extracted from bit 0--2 of the first voice data byte. |

### Selecting and Importing

Hold `Ctrl` and click to select multiple individual voices, or hold `Shift` and click to select a range. Click **OK** to import all selected voices into the current song's voice bank. They are appended in the order they appear in the table. Click **Cancel** to discard the selection and import nothing.

## Importing DAC Samples

Click the **+** button in the **DAC Samples** section of the instrument panel. A file chooser opens with the following supported formats:

- **WAV files** (`.wav`) -- automatically converted to unsigned 8-bit mono PCM. Handles 8-bit and 16-bit sources, signed and unsigned encodings, and stereo-to-mono downmix.
- **Raw PCM files** (`.pcm`, `.bin`) -- read verbatim as unsigned 8-bit PCM data with no header parsing.

Imported samples are assigned a default playback rate of `0x0C`. The sample name is derived from the filename minus its extension.

For full details on editing, duplicating, deleting, and entering DAC notes, see [DAC Samples](06-dac-samples.md).

## Tips

- Use **File > Import Voices** to grab specific patches from Sonic ROM rips. Point the directory browser at a folder of extracted SMPS binaries and scan them all at once. This is the fastest way to collect a library of authentic Genesis voices for your own compositions.

- The filter field in the voice selection dialog accepts algorithm numbers -- type `7` to find all additive synthesis patches, or `4` to find two-carrier voices suited for organs and layered tones.
