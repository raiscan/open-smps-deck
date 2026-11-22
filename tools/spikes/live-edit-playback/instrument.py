"""Copy and time selected calls without changing production classes in the build."""

from pathlib import Path
import sys
root = Path('headless/src/main/java/com/opensmpsdeck/headless')
dest = Path(sys.argv[1])
hooks = {
    'ReconstructionStages': [
        ('var continuity = ReconstructionLeadContinuity.analyze(score, structure.notes());','continuity'),
        ('var roles = boundEdits.assignRoles(score, structure, rate, plan, continuity, scoreEdits, outcomes);','roleAssignment'),
        ('var cymbalLevels = ReconstructionCymbals.measure(score);','cymbalMeasure'),
        ('var salience = chordReduction ? ReconstructionStemParts.salience(score, roles) : Map.<String, Double>of();','salience'),
        ('var holds = allocationHolds(score, roles);','allocationHolds'),
    ],
    'ReconstructionCli': [
        ('var fitted = matchQuietly(() -> ReconstructionVoiceStage.fit(score, catalogue, sourcePlacement, previous, targets, selected));','patchFit'),
        ('var assembly = prepareAssembly(work, score, structure, allocation, voicing, catalogue, intents, outcomes, policyDecisions, null);','prepareAssembly'),
        ('var sustain = matchQuietly(() -> ReconstructionSustain.inspect(score, catalogue, leadNotes, leadVoice));','sustainInspect'),
        ('result = arrangement.arrange(echoes, !echoBankLimit, !bendSizeLimit, -1, bankPass);','arrange'),
        ('playback = TargetPlayback.prepare(result.project());','compile'),
        ('if (timeline.warped()) ReferenceStretch.write(reference, aligned, frames, timeline.sourceDuration(), timeline::source);','referenceStretch'),
        ('playback.writeWave(audio, frames);','render'),
        ('var heldNotes = matchQuietly(() -> ReconstructionHeldNotes.inspect(score, allocation, rendered));','heldNoteReport'),
    ],
}
for cls, entries in hooks.items():
    src = (root / (cls + '.java')).read_text()
    for statement, label in entries:
        assert statement in src
        src = src.replace(statement, f'long stamp_{label} = System.nanoTime();\n{statement}\nStageTiming.sub("{label}", stamp_{label});',1)
    (dest / (cls + '.java')).write_text(src)
