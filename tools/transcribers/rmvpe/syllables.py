"""Syllable boundaries and note segmentation on the RMVPE 10 ms grid."""

from dataclasses import dataclass

import numpy as np
from scipy.ndimage import median_filter
from scipy.signal import find_peaks


@dataclass(frozen=True)
class Settings:
    window_samples: int = 2048
    flux_scale: float = 100
    flux_percentile: float = 93
    onset_spacing_frames: int = 14
    onset_voiced_frames: int = 6
    rms_before_frames: int = 8
    rms_after_frames: int = 5
    rms_rise_db: float = 6
    median_frames: int = 11
    pitch_change_semitones: float = 1
    pitch_change_frames: int = 8
    pitch_reference_update: float = .15
    bridge_frames: int = 6
    min_voiced_frames: int = 10
    min_note_frames: int = 9
    min_pitch: int = 40

    def __post_init__(self):
        if (self.window_samples < 2 or self.window_samples % 2
                or not self.flux_scale > 0 or not 0 <= self.flux_percentile <= 100
                or min(self.onset_spacing_frames, self.onset_voiced_frames,
                       self.rms_before_frames, self.rms_after_frames, self.median_frames,
                       self.pitch_change_frames, self.min_voiced_frames, self.min_note_frames) < 1
                or not self.rms_rise_db >= 0 or not self.pitch_change_semitones > 0
                or not 0 <= self.pitch_reference_update <= 1 or self.bridge_frames < 0
                or not 1 <= self.min_pitch <= 127 or self.median_frames % 2 != 1):
            raise ValueError("Invalid syllable segmentation settings")


def syllable_onsets(samples, rate, voiced, settings):
    """Measure centered Hann-window flux and RMS at source-time pitch frames."""
    if not len(voiced) or not voiced.any():
        return []
    window = settings.window_samples
    padded = np.pad(samples, (window // 2, window // 2))
    frames = np.lib.stride_tricks.sliding_window_view(padded, window)
    centers = np.arange(min(len(voiced), len(samples) * 100 // rate)) * rate // 100
    hann = np.hanning(window)
    flux = np.zeros(len(centers))
    rms = np.zeros(len(centers))
    previous = np.zeros((1, window // 2 + 1))
    for start in range(0, len(centers), 512):
        end = min(start + 512, len(centers))
        batch = frames[centers[start:end]] * hann
        spectra = np.log1p(settings.flux_scale * np.abs(np.fft.rfft(batch, axis=1)))
        flux[start:end] = np.maximum(np.diff(spectra, axis=0, prepend=previous), 0).sum(axis=1)
        rms[start:end] = 20 * np.log10(np.sqrt((batch ** 2).mean(axis=1)) + 1e-9)
        previous = spectra[-1:]
    if not len(flux):
        return []
    flux[0] = 0
    peaks, _ = find_peaks(flux, height=np.percentile(flux, settings.flux_percentile),
                          distance=settings.onset_spacing_frames)
    onsets = [supported_attack(voiced, rms, int(p), settings) for p in peaks]
    return sorted({onset for onset in onsets if onset is not None})


def supported_attack(voiced, rms, onset, settings):
    carried = voiced[max(0, onset - 6):onset].sum() >= 4
    if carried:
        # A flux peak followed by a break in an already voiced vowel is usually its release.
        tail = voiced[onset:onset + 16]
        if len(tail) == 16 and any(not tail[i:i + 8].any() for i in range(9)):
            return None
    after = rms[onset:onset + settings.rms_after_frames].max()
    before = rms[max(0, onset - settings.rms_before_frames):onset].min()
    if after - before < settings.rms_rise_db:
        return None
    lookahead = voiced[onset:onset + settings.onset_voiced_frames]
    if lookahead.sum() >= max(1, settings.onset_voiced_frames - 2):
        return onset
    # The centered analysis window can place the peak before the vowel.
    # Snap such peaks to the nearby sustained voicing start.
    for start in np.flatnonzero(lookahead):
        landing = onset + int(start)
        count = voiced[landing:landing + settings.onset_voiced_frames].sum()
        if count >= max(1, settings.onset_voiced_frames - 1):
            return landing
    return None


def segment(pitches, onsets, settings):
    voiced = pitches > 0
    starts = np.flatnonzero(voiced & ~np.r_[False, voiced[:-1]]) if len(voiced) else []
    boundaries = {"syllable": list(onsets), "voicing": list(map(int, starts)), "pitchChange": []}
    if not voiced.any():
        return [], boundaries

    frames = np.arange(len(pitches))
    smoothed = median_filter(np.interp(frames, frames[voiced], pitches[voiced]), size=settings.median_frames)
    reference, deviation = None, 0
    for frame, pitch in enumerate(smoothed):
        if not voiced[frame]:
            # Reset the pitch reference at each voicing start. Keep pending pitch
            # evidence until the next stable frame, matching the strict segmentation.
            reference = None
        elif reference is None:
            reference = pitch
        elif abs(pitch - reference) > settings.pitch_change_semitones:
            deviation += 1
            if deviation >= settings.pitch_change_frames:
                boundaries["pitchChange"].append(frame - deviation + 1)
                reference, deviation = pitch, 0
        else:
            deviation = 0
            update = settings.pitch_reference_update
            reference = (1 - update) * reference + update * pitch

    cuts = sorted(set().union(*boundaries.values()))
    notes = []
    for start, stop in zip(cuts, cuts[1:] + [len(pitches)]):
        segment_pitches = pitches[start:stop][voiced[start:stop]]
        if len(segment_pitches) < settings.min_voiced_frames:
            continue
        end, gap = start, 0
        for frame in range(start, stop):
            if voiced[frame]:
                end, gap = frame + 1, 0
            else:
                gap += 1
                if gap > settings.bridge_frames:
                    break
        notes.append([start, end, round(float(np.median(segment_pitches)))])
    for note, following in zip(notes, notes[1:]):
        if 0 < following[0] - note[1] <= settings.bridge_frames:
            note[1] = following[0]
    return [[start / 100, end / 100, pitch] for start, end, pitch in notes
            if pitch >= settings.min_pitch and end - start >= settings.min_note_frames], boundaries
