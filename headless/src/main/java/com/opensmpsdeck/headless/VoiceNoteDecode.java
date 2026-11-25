package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.project.TargetProfile;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scores every note of a part against stock FM patches on its whole shape up
 * to key-off, then decodes one patch per note with a cost for each change.
 * The catalogue is a closed set and the chip core renders it exactly, so this
 * is a lookup. It reads the part's own stem and never edits a patch.
 */
final class VoiceNoteDecode {
    static final int RATE = VoiceSpectra.RATE;
    private static final int FFT = 512, HOP = 100, BANDS = 48;
    private static final double MAX_SECONDS = .5;
    private static final int MAX_FRAMES = ((int) (MAX_SECONDS * RATE) - FFT) / HOP + 1;
    private static final int TEMPLATE_TICKS = (FFT + (MAX_FRAMES - 1) * HOP) / VoiceSpectra.SAMPLES_PER_TICK + 3;
    /** Frames that cover the first 85 ms, the span the attack shortlist reads. */
    static final int ATTACK_FRAMES = 6;
    /** A note needs this many frames before its later part counts as sustain. */
    static final int SUSTAIN_FRAMES = 12;
    private static final int SURVEY_NOTES = 32, SURVEY_PITCHES = 10, CANDIDATES = 24;
    // A change must be worth an eighth of a perfect note match.
    private static final double SCALE = 8, SWITCH_COST = 1;
    // A recurring figure lends half its own evidence, so local evidence can overrule it.
    private static final double FIGURE_WEIGHT = .5;
    private static final int CACHE_LIMIT = 60_000;
    private static final int LEVEL_VOLUME = 127 - 24;
    private static final double[] HANN = new double[FFT];
    private static final float[][] MEL = mel();
    private static final Map<Key, float[][]> TEMPLATES = new ConcurrentHashMap<>();

    static {
        for (int i = 0; i < FFT; i++) HANN[i] = .5 - .5 * Math.cos(Math.TAU * i / FFT);
    }

    private record Key(String asset, int pitch, TargetGame target) {}

    /** A note in source seconds with its stable score ID. */
    record Note(String id, double start, double end, int pitch) {}

    /**
     * Per-note evidence. {@code scores}, {@code attack} and {@code sustain} hold one
     * cosine per candidate. Sustain is NaN on notes too short to have one.
     */
    record Scored(Note note, int frames, double weight, float[] scores, float[] attack, float[] sustain) {}

    record Result(List<String> candidates, List<Scored> notes, int[] labels) {
        int switches() {
            int count = 0;
            for (int i = 1; i < labels.length; i++) if (labels[i] != labels[i - 1]) count++;
            return count;
        }
    }

    private final InstrumentCatalogue catalogue;
    private final TargetGame target;
    private final List<InstrumentCatalogue.Variant> voices;
    private final LeadEffectMatcher.Spectrum fft = new LeadEffectMatcher.Spectrum(FFT);

    VoiceNoteDecode(InstrumentCatalogue catalogue, TargetGame target) {
        this.catalogue = catalogue;
        this.target = target;
        // Without a catalogue it only reads audio.
        voices = catalogue == null ? List.of() : VoicePatchShortlist.stockVoices(catalogue, target);
    }

    /**
     * @param notes the part's FM notes in time order
     * @param always variants that must be candidates, such as the part's current patch
     * @param figures whether recurring figures vote
     */
    Result decode(List<Note> notes, Path stem, List<String> always, boolean figures) throws IOException {
        var mono = read(stem);
        var range = TargetProfile.of(target).fm();
        var measured = new ArrayList<Note>();
        var features = new ArrayList<float[][]>();
        var weights = new ArrayList<Double>();
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            int start = (int) Math.round(note.start() * RATE);
            int length = (int) Math.round(Math.min(note.end() - note.start(), MAX_SECONDS) * RATE);
            if (length <= 0 || start < 0 || start + FFT > mono.length) continue;
            int frames = Math.min(Math.max(1, (length - FFT) / HOP + 1), (mono.length - start - FFT) / HOP + 1);
            var frame = features(mono, start, Math.min(frames, MAX_FRAMES));
            if (silent(frame)) continue;
            // A note shorter than one frame shares it with whatever follows. Another
            // note of the part sounding over the attack blurs the comparison too.
            double weight = Math.min(1, length / (double) FFT);
            if (i > 0 && notes.get(i - 1).end() > note.start() + .005) weight *= .5;
            measured.add(note);
            features.add(frame);
            weights.add(weight);
        }
        if (measured.isEmpty()) return new Result(List.of(), List.of(), new int[0]);

        var candidates = candidates(measured, features, always, range);
        var scored = new ArrayList<Scored>();
        for (int i = 0; i < measured.size(); i++) {
            var note = measured.get(i);
            var frame = features.get(i);
            int pitch = range.clamp(note.pitch());
            var scores = new float[candidates.size()];
            var attack = new float[candidates.size()];
            var sustain = new float[candidates.size()];
            for (int k = 0; k < candidates.size(); k++) {
                var template = template(candidates.get(k), pitch);
                scores[k] = cosine(frame, template, 0, frame.length);
                attack[k] = cosine(frame, template, 0, Math.min(ATTACK_FRAMES, frame.length));
                sustain[k] = frame.length >= SUSTAIN_FRAMES ? cosine(frame, template, ATTACK_FRAMES, frame.length) : Float.NaN;
            }
            scored.add(new Scored(note, frame.length, weights.get(i), scores, attack, sustain));
        }
        var ids = candidates.stream().map(InstrumentCatalogue.Variant::id).toList();
        return new Result(ids, List.copyOf(scored), viterbi(scored, ids.size(), figures));
    }

    /** The whole stem at the template rate, resampled the way the templates are. */
    static float[] read(Path stem) throws IOException {
        double seconds = ReferenceAudio.durationMicros(stem) / 1e6;
        var mono = new float[(int) (seconds * RATE)];
        for (int at = 0; at < mono.length; at += 2 * RATE) {
            int length = Math.min(2 * RATE, mono.length - at);
            var chunk = VoiceSpectra.resample(ReferenceAudio.read(stem, at / (double) RATE, length / (double) RATE), length);
            System.arraycopy(chunk, 0, mono, at, length);
        }
        return mono;
    }

    /** Surveys every stock patch on notes spread over the part and keeps the patches that win there. */
    private List<InstrumentCatalogue.Variant> candidates(List<Note> notes, List<float[][]> features, List<String> always,
                                                        TargetProfile.PitchRange range) throws IOException {
        var survey = new ArrayList<Integer>();
        var pitches = new LinkedHashSet<Integer>();
        // Prefer notes with a few frames of their own, then fill from the rest.
        for (int minimum : new int[]{3, 1}) {
            var eligible = new ArrayList<Integer>();
            for (int i = 0; i < notes.size(); i++)
                if (features.get(i).length >= minimum && !survey.contains(i)) eligible.add(i);
            int stride = Math.max(1, eligible.size() / SURVEY_NOTES);
            for (int at = 0; at < eligible.size() && survey.size() < SURVEY_NOTES; at += stride) {
                int pitch = range.clamp(notes.get(eligible.get(at)).pitch());
                if (!pitches.contains(pitch) && pitches.size() == SURVEY_PITCHES) continue;
                pitches.add(pitch);
                survey.add(eligible.get(at));
            }
        }
        var points = new double[voices.size()];
        var totals = new double[voices.size()];
        for (int index : survey) {
            int pitch = range.clamp(notes.get(index).pitch());
            var frame = features.get(index);
            var scores = new double[voices.size()];
            for (int v = 0; v < voices.size(); v++) {
                scores[v] = cosine(frame, template(voices.get(v), pitch), 0, frame.length);
                totals[v] += scores[v];
            }
            var order = new ArrayList<Integer>();
            for (int v = 0; v < voices.size(); v++) order.add(v);
            order.sort(Comparator.comparingDouble((Integer v) -> -scores[v]).thenComparing(v -> voices.get(v).id()));
            for (int place = 0; place < Math.min(5, order.size()); place++) points[order.get(place)] += 5 - place;
        }
        var ranked = new ArrayList<Integer>();
        for (int v = 0; v < voices.size(); v++) ranked.add(v);
        ranked.sort(Comparator.comparingDouble((Integer v) -> -points[v]).thenComparingDouble(v -> -totals[v])
                .thenComparing(v -> voices.get(v).id()));
        var chosen = new ArrayList<InstrumentCatalogue.Variant>();
        for (String id : always) {
            var variant = catalogue.match(id, target).variant();
            if (chosen.stream().noneMatch(kept -> kept.assetId().equals(variant.assetId()))) chosen.add(variant);
        }
        for (int v : ranked) {
            if (chosen.size() >= CANDIDATES + always.size()) break;
            var variant = voices.get(v);
            if (chosen.stream().noneMatch(kept -> kept.assetId().equals(variant.assetId()))) chosen.add(variant);
        }
        return chosen;
    }

    /** The best label sequence under a fixed cost for each change of patch. */
    static int[] viterbi(List<Scored> notes, int candidates, boolean figures) {
        int count = notes.size();
        var evidence = new double[count][candidates];
        for (int i = 0; i < count; i++)
            for (int k = 0; k < candidates; k++) evidence[i][k] = notes.get(i).scores()[k];
        if (figures) {
            // Notes that repeat a figure (register, length and the step into them) share evidence.
            var groups = new HashMap<List<Integer>, List<Integer>>();
            for (int i = 0; i < count; i++) {
                var note = notes.get(i).note();
                int step = i == 0 ? 99 : Math.clamp(note.pitch() - notes.get(i - 1).note().pitch(), -12, 12);
                groups.computeIfAbsent(List.of(note.pitch() / 6, (int) Math.round((note.end() - note.start()) * 12), step),
                        ignored -> new ArrayList<>()).add(i);
            }
            for (var members : groups.values()) {
                if (members.size() < 2) continue;
                var sum = new double[candidates];
                for (int i : members) for (int k = 0; k < candidates; k++) sum[k] += notes.get(i).scores()[k];
                for (int i : members)
                    for (int k = 0; k < candidates; k++)
                        evidence[i][k] += FIGURE_WEIGHT * (sum[k] - notes.get(i).scores()[k]) / (members.size() - 1);
            }
        }
        var best = new double[candidates];
        var back = new int[count][candidates];
        for (int i = 0; i < count; i++) {
            int leader = 0;
            for (int k = 1; k < candidates; k++) if (best[k] > best[leader]) leader = k;
            double change = best[leader] - SWITCH_COST;
            var next = new double[candidates];
            for (int k = 0; k < candidates; k++) {
                boolean stay = i == 0 || best[k] >= change;
                back[i][k] = stay ? k : leader;
                next[k] = (i == 0 ? 0 : stay ? best[k] : change) + SCALE * notes.get(i).weight() * evidence[i][k];
            }
            best = next;
        }
        var labels = new int[count];
        int at = 0;
        for (int k = 1; k < candidates; k++) if (best[k] > best[at]) at = k;
        for (int i = count - 1; i >= 0; i--) {
            labels[i] = at;
            at = back[i][at];
        }
        return labels;
    }

    /**
     * A patch's overall level at a pitch, 18 dB below full track volume. Several
     * carriers at full volume clip the channel, so levels are compared lower down.
     */
    double loudness(String variant, int pitch) throws IOException {
        var rendered = VoiceWindows.renderMono(CataloguePlayback.leveledProbe(catalogue, variant,
                TargetProfile.of(target).fm().clamp(pitch), TEMPLATE_TICKS, target, LEVEL_VOLUME), TEMPLATE_TICKS);
        double total = 0;
        for (float sample : rendered) total += sample * (double) sample;
        return Math.sqrt(total / rendered.length);
    }

    private float[][] template(InstrumentCatalogue.Variant variant, int pitch) throws IOException {
        var key = new Key(variant.assetId(), pitch, target);
        var cached = TEMPLATES.get(key);
        if (cached != null) return cached;
        var rendered = rendered(variant, pitch);
        int onset = onset(rendered);
        var result = features(rendered, onset, Math.min(MAX_FRAMES, (rendered.length - onset - FFT) / HOP + 1));
        if (TEMPLATES.size() < CACHE_LIMIT) TEMPLATES.putIfAbsent(key, result);
        return result;
    }

    private float[] rendered(InstrumentCatalogue.Variant variant, int pitch) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("voice decode cancelled");
        return VoiceWindows.renderMono(
                CataloguePlayback.matchProbe(catalogue, variant.id(), pitch, TEMPLATE_TICKS, true, target), TEMPLATE_TICKS);
    }

    /** The driver keys the note on a little after the first sample. */
    private static int onset(float[] rendered) {
        int onset = 0;
        while (onset < 2 * VoiceSpectra.SAMPLES_PER_TICK && Math.abs(rendered[onset]) < 1e-3) onset++;
        return onset == 2 * VoiceSpectra.SAMPLES_PER_TICK ? 0 : onset;
    }

    /** How many 8 ms frames of a note the decode reads, or 0 when it falls outside the audio. */
    static int frames(Note note, int samples) {
        int start = (int) Math.round(note.start() * RATE);
        int length = (int) Math.round(Math.min(note.end() - note.start(), MAX_SECONDS) * RATE);
        if (length <= 0 || start < 0 || start + FFT > samples) return 0;
        return Math.min(Math.min(Math.max(1, (length - FFT) / HOP + 1), (samples - start - FFT) / HOP + 1), MAX_FRAMES);
    }

    /** Linear magnitude spectra, one row per 8 ms hop. The decode itself reads mel bands. */
    float[][] magnitudes(float[] mono, int start, int frames) {
        var result = new float[frames][FFT / 2 + 1];
        for (int frame = 0; frame < frames; frame++) {
            fft.transform(mono, start + frame * HOP, HANN);
            for (int bin = 0; bin <= FFT / 2; bin++) result[frame][bin] = (float) Math.sqrt(fft.power(bin));
        }
        return result;
    }

    /** Linear magnitudes of a stock patch at a pitch and full track volume. Not cached. */
    float[][] magnitudeTemplate(InstrumentCatalogue.Variant variant, int pitch) throws IOException {
        var rendered = rendered(variant, pitch);
        int onset = onset(rendered);
        return magnitudes(rendered, onset, Math.min(MAX_FRAMES, (rendered.length - onset - FFT) / HOP + 1));
    }

    /** The decode's mel bands from linear magnitudes. */
    static float[][] mel(float[][] magnitudes) {
        var result = new float[magnitudes.length][BANDS];
        for (int frame = 0; frame < magnitudes.length; frame++)
            for (int band = 0; band < BANDS; band++) {
                double sum = 0;
                for (int bin = 0; bin <= FFT / 2; bin++) sum += MEL[band][bin] * magnitudes[frame][bin];
                result[frame][band] = (float) Math.sqrt(sum);
            }
        return result;
    }

    /** Spectrum bins within {@code width} bins of a harmonic of the pitch. {@code division} 2 also takes the half harmonics. */
    static float[] comb(int pitch, int division, double width) {
        double fundamental = 440 * Math.pow(2, (pitch - 69) / 12.0) / division, binHz = RATE / (double) FFT;
        var mask = new float[FFT / 2 + 1];
        for (int bin = 0; bin < mask.length; bin++) {
            double hz = bin * binHz, ratio = hz / fundamental;
            if (hz >= fundamental - width * binHz && Math.abs(ratio - Math.round(ratio)) * fundamental / binHz <= width)
                mask[bin] = 1;
        }
        return mask;
    }

    /** Square-root mel magnitudes, 48 bands from 40 Hz to Nyquist, one row per 8 ms hop. */
    private float[][] features(float[] mono, int start, int frames) {
        var result = new float[frames][BANDS];
        var magnitude = new double[FFT / 2 + 1];
        for (int frame = 0; frame < frames; frame++) {
            fft.transform(mono, start + frame * HOP, HANN);
            for (int bin = 0; bin <= FFT / 2; bin++) magnitude[bin] = Math.sqrt(fft.power(bin));
            for (int band = 0; band < BANDS; band++) {
                double sum = 0;
                for (int bin = 0; bin <= FFT / 2; bin++) sum += MEL[band][bin] * magnitude[bin];
                result[frame][band] = (float) Math.sqrt(sum);
            }
        }
        return result;
    }

    private static boolean silent(float[][] frames) {
        double peak = 0;
        for (var frame : frames) for (float value : frame) peak = Math.max(peak, value);
        return peak < 1e-3;
    }

    /** Cosine of the two matrices over a frame range, with one gain for the whole range. */
    static float cosine(float[][] a, float[][] b, int from, int to) {
        double cross = 0, x = 0, y = 0;
        for (int frame = from; frame < Math.min(to, Math.min(a.length, b.length)); frame++) {
            for (int band = 0; band < BANDS; band++) {
                cross += a[frame][band] * b[frame][band];
                x += a[frame][band] * a[frame][band];
                y += b[frame][band] * b[frame][band];
            }
        }
        return x > 0 && y > 0 ? (float) (cross / Math.sqrt(x * y)) : 0;
    }

    private static float[][] mel() {
        double low = hzToMel(40), high = hzToMel(RATE / 2.0);
        var centres = new double[BANDS + 2];
        for (int i = 0; i < centres.length; i++) centres[i] = melToHz(low + (high - low) * i / (BANDS + 1));
        var filters = new float[BANDS][FFT / 2 + 1];
        for (int band = 0; band < BANDS; band++) {
            double left = centres[band], centre = centres[band + 1], right = centres[band + 2];
            for (int bin = 0; bin <= FFT / 2; bin++) {
                double hz = bin * (double) RATE / FFT;
                double weight = Math.max(0, Math.min((hz - left) / (centre - left), (right - hz) / (right - centre)));
                // Equal-area filters, so wide upper bands do not outweigh the fundamental.
                filters[band][bin] = (float) (2 * weight / (right - left));
            }
        }
        return filters;
    }

    private static double hzToMel(double hz) { return 2595 * Math.log10(1 + hz / 700); }
    private static double melToHz(double mel) { return 700 * (Math.pow(10, mel / 2595) - 1); }
}
