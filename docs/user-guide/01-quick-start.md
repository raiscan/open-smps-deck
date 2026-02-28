# Quick Start

Get OpenSMPS Deck running and make your first sound in under two minutes.

## Prerequisites

- **Java 21** or later (check with `java -version`)
- **Apache Maven** 3.9+ (check with `mvn -version`)

Clone the repository, then build:

```bash
mvn compile
```

Launch the application:

```bash
mvn exec:java -pl app -Dexec.mainClass=com.opensmps.deck.Launcher
```

## What You See

The window opens with an empty song. From top to bottom:

- **Menu bar** and **Transport bar** -- play/stop controls, tempo, SMPS mode selector.
- **Tracker grid** -- 10 columns, one per channel: FM1 through FM5, DAC, PSG1 through PSG3, and Noise. Each column shows note, instrument, and effect sub-columns.
- **Instrument panel** (right side) -- voice bank for FM voices and PSG envelope list.
- **Order list** (bottom) -- pattern arrangement for the full song.

The cursor starts on row 0 of the **FM1** channel. The default octave is 4.

## Make Your First Sound

### 1. Enter a note

Press `Z`. A **C-4** note appears on the first row of FM1. The cursor advances to the next row.

### 2. Play it back

Press `Space`. The built-in default FM voice plays your note. Press `Space` again to stop, or press `Escape`.

### 3. Add more notes

Press `X` for D, `C` for E, `V` for F. You now have four notes in a row. Press `Space` to hear the sequence.

### 4. Change the octave

Press `F5` to switch to octave 5. Press `Z` again -- you get **C-5**, one octave higher. Use `F1` through `F8` to set the octave directly.

### 5. Create an FM voice

In the **Instrument panel** on the right, click **+** under **Voice Bank**. In the dialog that appears, click **Init** to load a default voice, then click **OK**. Now enter a note -- it uses your new voice. Double-click the voice name to open the **FM Voice Editor** and shape the sound.

## Next Steps

- [Tutorial: Your First Song](02-tutorial-first-song.md) -- full walkthrough from empty project to exported SMPS binary.
- [Interface Overview](03-interface-overview.md) -- detailed breakdown of every panel, for users who already know trackers.
