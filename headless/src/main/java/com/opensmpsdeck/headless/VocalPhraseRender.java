package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.opensmpsdeck.project.ProjectFiles;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;

/** Render the built lead alone and compare its audibility and timing in source time. */
public final class VocalPhraseRender {
    private static final double ONSET_TOLERANCE = .05;
    private static final double AUDIBLE_DB = -52;

    private VocalPhraseRender() {}

    record Metrics(int syllables, int matchedRenderOnsets, Double meanOnsetErrorMs,
                   double stemVoicedSeconds, double voicedGapSeconds,
                   double pitchComparedSeconds, Double pitchAgreement) {}

    static void cli(String[] args, PrintStream out) throws IOException {
        require(args.length == 4 && args[1].equals("--phrase"), "vocal-render",
                "expected WORK_DIR --phrase ID OUT.wav");
        out.println(render(Path.of(args[0]), args[2], Path.of(args[3])));
    }

    public static JsonObject render(Path work, String phraseId, Path output) throws IOException {
        var data = ReconstructionVocalReview.load(work);
        require(data.audio() != null, "vocal-render", "the selected vocal stem is unavailable");
        var phrase = data.spans().stream().filter(span -> span.id().equals(phraseId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown phrase ID: " + phraseId));
        var range = WaveFile.Range.seconds(data.time().project(phrase.start()), data.time().project(phrase.end()));
        var project = ProjectFiles.load(work.resolve("arrangement.smps.json")).project();
        WaveFile.renderRange(TargetPlayback.prepare(project), output, range, ReconstructionView.soloMask(List.of("FM1")));
        var audio = new VocalStemEvidence(ReconstructionLeadAudio.readMono(output, range.count() / 60.0));
        double projectOffset = range.start() / 60.0;
        var attacks = new ArrayList<Double>();
        var syllables = new ArrayList<Double>();
        double previous = -120;
        int voiced = 0, missed = 0, compared = 0, agreed = 0;
        for (int frame : data.boundaries().syllable()) {
            double seconds = frame / 100.0;
            if (seconds >= phrase.start() && seconds < phrase.end() && data.audio().active(seconds + .03))
                syllables.add(seconds);
        }
        var gates = data.kept().stream().filter(note ->
                note.sourceStart() < phrase.end() && note.sourceEnd() > phrase.start()).toList();
        for (int frame = (int) Math.ceil(phrase.start() * 100); frame / 100.0 < phrase.end(); frame++) {
            double seconds = frame / 100.0;
            double local = data.time().project(seconds) - projectOffset;
            double db = audio.rms(local, data.time().project(seconds + .01) - projectOffset);
            if (db > AUDIBLE_DB && db - previous >= 9
                    && (attacks.isEmpty() || seconds - attacks.getLast() >= .05)) attacks.add(seconds);
            previous = db;
            if (frame >= data.pitches().length || data.pitches()[frame] <= 0 || !data.audio().active(seconds)) continue;
            voiced++;
            if (db < AUDIBLE_DB) {
                missed++;
                continue;
            }
            var gate = gates.stream().filter(note -> note.sourceStart() <= seconds && note.sourceEnd() > seconds)
                    .findFirst().orElse(null);
            if (gate != null) {
                compared++;
                if (Math.abs(data.pitches()[frame] - gate.pitch()) <= .7) agreed++;
            }
        }
        var metrics = metrics(syllables, attacks, voiced, missed, compared, agreed);
        return new Gson().toJsonTree(Map.of("phrase", phraseId, "output", output.toString(),
                "stem", data.stem(), "sourceStart", phrase.start(), "sourceEnd", phrase.end(),
                "projectStart", projectOffset, "projectEnd", range.end() / 60.0, "metrics", metrics)).getAsJsonObject();
    }

    static Metrics metrics(List<Double> syllables, List<Double> attacks, int voiced, int missed,
                           int compared, int agreed) {
        int next = 0, matched = 0;
        double error = 0;
        for (double onset : syllables) {
            while (next < attacks.size() && attacks.get(next) < onset - ONSET_TOLERANCE) next++;
            if (next < attacks.size() && attacks.get(next) <= onset + ONSET_TOLERANCE) {
                error += Math.abs(onset - attacks.get(next++));
                matched++;
            }
        }
        return new Metrics(syllables.size(), matched, matched == 0 ? null : 1000 * error / matched,
                voiced / 100.0, missed / 100.0, compared / 100.0, compared == 0 ? null : agreed / (double) compared);
    }
}
