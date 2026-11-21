# Local extraction results

**Use BS-RoFormer SW as the default separator and ADTOF on its drum stem for
real production audio.** On the individual stems, use MuScriptor Medium
for bass, guitar, piano and residual tonal audio. Basic Pitch on the SW
vocal stem led the small singing check. Basic Pitch on all tonal stems is
the faster CPU option. RMVPE provides a monophonic vocal option.

**For the quality configuration**, use the vocal-first cascade when audio
separation is the priority. It added 0.14 dB to the four-stem average in
this test and took about four times as long. For pooled tonal discovery,
MuScriptor Medium · mix led the full MulTTiPop run on onset F1 at
0.421 and 0.152 with offsets.
YourMT3+ on the mix had the best onset-plus-offset F1 (0.158) and led on
synthetic multi-instrument transcription. Use ADTOF on the separated drum
stem for real productions. This config combines independently tested stages.

## Separation

Five 25-second MUSDB18-HQ test excerpts starting at 45 seconds.
Scale-dependent SDR in dB. Each stem column is the median across tracks.
Wall time and peak Torch reserved memory are on the RX 9070 XT.

| Separator | Bass | Drums | Vocals | Other | Mean | Median s / 25 s | Peak GiB |
|---|---:|---:|---:|---:|---:|---:|---:|
| Viperx vocal → SW | 11.68 | 14.01 | 14.02 | 10.39 | 12.52 | 14.89 | 4.29 |
| BS-RoFormer SW | 11.67 | 14.00 | 13.77 | 10.08 | 12.38 | 3.79 | 2.95 |
| HTDemucs-ft | 9.89 | 11.89 | 10.87 | 7.64 | 10.07 | 18.07 | 1.35 |
| SCNet XL | 7.23 | 10.34 | 12.89 | 7.78 | 9.56 | 18.22 | 1.65 |
| Mix copied to each stem | -4.22 | -3.89 | -5.27 | -6.70 | -5.02 | — | 0 |

The vocal-first stage used the viperx 317 checkpoint and sent mix minus
its vocals to SW. The small SDR gain does not establish a corresponding
gain in transcription. SCNet's vocal SDR was higher than HTDemucs-ft's
on this sample, while HTDemucs-ft scored higher overall.

## Tonal transcription

### MulTTiPop test excerpts

Twelve real productions, 256.14 seconds of annotated audio. Each decoder
sees the same excerpt boundaries. In the Basic Pitch stem run, predictions
were routed to their respective bass, guitar, piano and other groups.
The mixed stem route selects candidates by observed per-family results in
this set and should be validated on unseen songs.

| Candidate | Onset F1 | Onset + offset F1 | Instrument F1 | Wall s | Peak GiB |
|---|---:|---:|---:|---:|---:|
| MuScriptor Medium · mix | 0.421 | 0.152 | 0.329 | 155.9 | 0.93 |
| MuScriptor Medium · SW tonal stems | 0.406 | 0.129 | 0.341 | 100.4 | 0.93 |
| Medium on SW bass/guitar/piano + Basic Pitch on SW other | 0.375 | 0.127 | 0.334 | 79.7 | 0.93 |
| YourMT3+ · mix | 0.329 | 0.158 | 0.258 | 345.5 | 9.92 |
| Basic Pitch · SW tonal stems | 0.271 | 0.090 | 0.226 | 29.1 | 0.00 |
| Basic Pitch · mix | 0.209 | 0.072 | — | 4.6 | 0.00 |

| Input and decoder | Bass | Guitar | Piano | Other |
|---|---:|---:|---:|---:|
| YourMT3+ · mix | 0.336 | 0.260 | 0.315 | 0.216 |
| MuScriptor Medium · mix | 0.526 | 0.390 | 0.271 | 0.176 |
| MuScriptor Medium · SW tonal stems | 0.500 | 0.414 | 0.414 | 0.097 |
| Medium on SW bass/guitar/piano + Basic Pitch on SW other | 0.507 | 0.457 | 0.444 | 0.118 |
| Basic Pitch · SW tonal stems | 0.290 | 0.260 | 0.307 | 0.118 |

Residual class control with the same reference "other" notes on each input.

| Candidate | Other onset F1 | Other onset + offset F1 |
|---|---:|---:|
| YourMT3+ · mix | 0.216 | 0.106 |
| YourMT3+ · SW other | 0.161 | 0.063 |
| MuScriptor Medium · mix | 0.176 | 0.082 |
| MuScriptor Medium · SW other | 0.097 | 0.046 |

### MuScriptor model sizes

Three matched MulTTiPop excerpts from pop punk, swing pop and rap.
These are a subset of the primary evaluation. Small used FP32 and
Medium/Large used BF16. Large used tie forcing.

| Candidate | Onset F1 | Onset + offset F1 | Instrument F1 | Wall s | Peak GiB |
|---|---:|---:|---:|---:|---:|
| MuScriptor Medium | 0.502 | 0.207 | 0.461 | 72.4 | 0.93 |
| MuScriptor Large | 0.462 | 0.192 | 0.411 | 176.6 | 3.51 |
| MuScriptor Small | 0.457 | 0.146 | 0.418 | 57.3 | 0.94 |
| YourMT3+ | 0.355 | 0.194 | 0.284 | 73.5 | 9.92 |

This is an exploratory checkpoint-size comparison. The song-level
arrangements and the note durations differ enough that a rank on three
clips should not set a general model-size policy.

### Slakh2100-redux test excerpts

Eighteen test tracks, first 30 seconds of each, scored against their
aligned mixture MIDI. This is a synthetic test with a wider gap between
the mix models than the primary production set.

| Candidate | Onset F1 | Onset + offset F1 | Instrument F1 | Wall s | Peak GiB |
|---|---:|---:|---:|---:|---:|
| YourMT3+ · mix | 0.823 | 0.655 | 0.809 | 181.6 | 2.42 |
| Basic Pitch · SW tonal stems | 0.498 | 0.190 | 0.459 | 65.3 | 0.00 |
| Basic Pitch · mix | 0.444 | 0.148 | — | 7.8 | 0.00 |

## Drums

Five GM class groups, 50 ms onset tolerance. The band detector emits only
kick, snare and hi-hat. Model rows use the same time spans as their tonal tests.

### MulTTiPop

| Candidate | Overall | Kick | Snare | Tom | Hi-hat | Cymbal | Wall s | Peak GiB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| ADTOF · SW drums (CPU) | 0.708 | 0.696 | 0.714 | 0.286 | 0.724 | 0.740 | 16.3 | 0.00 |
| ADTOF · SW drums (ROCm) | 0.708 | 0.696 | 0.714 | 0.286 | 0.724 | 0.740 | 30.5 | 0.46 |
| ADTOF · mix | 0.655 | 0.653 | 0.721 | 0.174 | 0.624 | 0.732 | 5.8 | 0.00 |
| MuScriptor Medium · mix | 0.509 | 0.529 | 0.655 | 0.492 | 0.491 | 0.160 | 155.9 | 0.93 |
| Band baseline · SW drums | 0.403 | 0.542 | 0.463 | 0.000 | 0.365 | 0.000 | 5.9 | 0.00 |
| YourMT3+ · mix | 0.308 | 0.628 | 0.405 | 0.030 | 0.182 | 0.074 | 345.5 | 9.92 |
| Band baseline · mix | 0.234 | 0.350 | 0.069 | 0.000 | 0.294 | 0.000 | 2.6 | 0.00 |

### Slakh2100-redux

| Candidate | Overall | Kick | Snare | Tom | Hi-hat | Cymbal | Wall s | Peak GiB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| YourMT3+ · mix | 0.935 | 0.974 | 0.943 | 0.984 | 0.906 | 0.857 | 181.6 | 2.42 |
| ADTOF · SW drums (CPU) | 0.757 | 0.817 | 0.528 | 0.725 | 0.812 | 0.200 | 22.2 | 0.00 |
| ADTOF · SW drums (ROCm) | 0.757 | 0.817 | 0.528 | 0.725 | 0.812 | 0.200 | 33.6 | 0.34 |
| ADTOF · mix | 0.680 | 0.804 | 0.609 | 0.652 | 0.738 | 0.075 | 13.9 | 0.00 |
| Band baseline · SW drums | 0.581 | 0.770 | 0.484 | 0.000 | 0.561 | 0.000 | 9.5 | 0.00 |
| Band baseline · mix | 0.441 | 0.520 | 0.131 | 0.000 | 0.523 | 0.000 | 7.4 | 0.00 |

## Singing

Four 28-second MIR-ST500 test excerpts. References contain lead vocal notes
only. These clips do not measure harmony vocal transcription.

| Candidate | Vocal onset F1 | Vocal onset + offset F1 | Wall s (CPU) |
|---|---:|---:|---:|
| Basic Pitch · SW vocals | 0.269 | 0.092 | 0.87 |
| RMVPE · SW vocals | 0.173 | 0.092 | 2.49 |
| RMVPE · mix | 0.173 | 0.081 | 3.99 |
| Basic Pitch · mix | 0.128 | 0.038 | 2.36 |

Both trackers benefitted from separation on offset F1. A ROCm RMVPE
run reproduced the CPU metrics and took 10.09 s
at 0.99 GiB, including warm-up.
ROSVOT's GGUF
checkpoint loaded into the staging area, but the tested audio.cpp v0.9.5
portable CLI rejected the `rosvot` family. No ROSVOT score is included.

## Coverage and limitations

- MulTTiPop test segment IDs are ANmpAQBdgyM, ANmpWXyPoyM, ANmpwXpqoyM, AQodXnMWoDl, DpgvEaAdmad, DpgvYzOkmad, DpgvlzBDoad, JNgqLNNJmrz, KexEOYYXm_B, KexEwljwm_B, KyvmredKoOW, ZOxVrYeyxdq.
  The three model-size clips are ANmpWXyPoyM, DpgvlzBDoad and KexEwljwm_B.
  The aligned notes come from existing MIDI arrangements and can omit audible
  parts or differ in articulation. They are an approximation of the recording.
  [MulTTiPop](https://arxiv.org/abs/2607.08756)
- Slakh test IDs are Track01876, Track01886, Track01896, Track01904, Track01917, Track01931, Track01945, Track01955, Track01965, Track01977, Track01989, Track02000, Track02010, Track02023, Track02037, Track02048, Track02061, Track02079. The 18-excerpt score uses `all_src.mid`.
  The full-track controls use the MIDI files from the rendered sources.
  These two references can differ. [Slakh2100-redux](https://zenodo.org/records/4599666)
- MUSDB18-HQ excerpts are from Angels In Amplifiers – I'm Alright,
  BKS – Bulldozer, Girls Under Glass – We Feel Alright, Secretariat – Borderline,
  and The Mountaineering Club – Mallory. [MUSDB18-HQ](https://zenodo.org/records/3338373)
- MIR-ST500 test IDs are 401, 403, 404 and 405. The first requested links for
  402 and 406 did not produce usable audio in this run.
  [MIR-ST500](https://github.com/york135/singing_transcription_ICASSP2021)
- GPU runs used PyTorch 2.13.0 + ROCm 10.1.0. Shared workload and backend warm-up
  increased wall time and Torch reserve in some batches. Timing is descriptive.
  On differently loaded runs, YourMT3+ reserved 9.92 GiB on the production set
  and 2.42 GiB on Slakh. CPU models show zero GPU allocation.
- The detector evaluates MIDI attacks and releases. Pitch bends, expressive
  control, reverb tails and timbre are outside note F1. Raw production and
  synthetic renders measure different failure modes.

## Full-length Slakh controls

These five local tracks use MIDI from the actual rendered sources. SW median
SDR was 13.29 dB bass, 19.94 dB drums and
16.29 dB for all remaining instruments merged.

| Candidate | Onset F1 | Onset + offset F1 | Instrument F1 | Wall s | Peak GiB |
|---|---:|---:|---:|---:|---:|
| YourMT3+ · mix | 0.686 | 0.569 | 0.634 | 230.7 | 3.93 |
| SW + Basic Pitch + ADTOF | 0.523 | 0.255 | 0.432 | 54.1 | 0.00 |

The Suno comparison is a separate controlled experiment. The harness consumes
completed downloads using the same source clock and reports pending tracks.
