# S3K playback foundation: verification

Verified locally on 2026-11-17 with OpenJDK 25.0.5 and Maven 3.10.0, in the
isolated `codex/reimagined` worktree. Upstream pin:
`a1c4aac7ae2708f5c487e8000da80502e854abe2`.

## Passing checks

- `python3 tools/openggf_audio.py check --source /path/to/OpenGGF`: all 87 Java
  sources and retained licenses/notices match committed upstream objects and
  the reviewed one-import patch. The upstream checkout's dirty files are ignored.
- `python3 -m unittest discover -s tools/tests -v`: 8 tests passed.
- `mvn -B -pl headless -am verify`: build/package passed; 5 Java playback/CLI
  tests passed, no failures or skips.
- `python3 tools/verify_openggf_audio_parity.py --source /path/to/OpenGGF
  --scratch /path/to/disk/scratch`: independent original-source/vendored builds
  produced identical PCM and chip writes for the three raw fixtures below.
  The production demo adapter also produced matching PCM against each build.
- Executed the workflow's packaged-CLI smoke step locally: runnable JAR produced
  a 2-second, 48 kHz, 16-bit stereo WAV (96,000 stereo frames). Its PCM matched
  the production golden; all embedded sources, licenses/notices and the lock
  matched their recorded hashes. Reviewed YAML syntax and local documentation
  link targets.

PCM SHA-256 measurements:

| Fixture | SHA-256 |
| --- | --- |
| Native asymmetric FM voice | `c7047ee90745914b8e9eaa665a7e6bd89c5ac3e0fbf58c2ccff038953fb5da51` |
| FM CALL/RETURN/LOOP and S3K E5 operands | `c8cc7da6cc76e845c114f00974658e6113337fe96549e044d8058097289091db` |
| PSG | `b0d1c7c08431c033bcdab8194db0ff1d6f8c45882c48264f07e474e49a2db3a0` |
| Production-activation FM/PSG demo | `5e455223632e6ef88262ac953f08f9eab181d34f5e59076d60355f80f7219c73` |

These are extraction and bounded playback checks, not arbitrary ROM import,
gameplay integration, Suno reconstruction or a claim of newly verified console
accuracy. The new CLI intentionally rejects control flow and external assets;
the raw parity fixtures exercise capabilities in the full extracted core.

## Existing suite baseline and comparison

Pre-change base `fa2997712a34c6b89fc1e74950c0b22893eb4db3`:
`mvn -B test` ran 699 tests, with 9 failures, 0 errors and 2 skips.
After the change, `mvn -B --fail-at-end test` ran 704 tests, with the **same 9
failures, 0 errors and 2 skips**. Compared test identity, exception type and
assertion message, not only totals. There are no new or worsened failures.

Eight failures require an absent, ignored local rip,
`docs/SMPS-rips/Sonic The Hedgehog 2/2-1C Drowning.sm2`:

- `TestDrowningPlaybackDiagnostic.diagnoseCompiledBinaryIntegrity`
- `TestDrowningPlaybackDiagnostic.diagnoseImportFileVsImportData`
- `TestDrowningPlaybackDiagnostic.diagnosePlaybackEnginePath`
- `TestDrowningTempoImport.diagnosePipelineTempoPreservation`
- `TestDrowningTempoImport.importedPhrasesContainSetTempoBytes`
- `TestDrowningTempoImport.playbackActuallyChangesTempoForDrowning`
- `TestDrowningTempoImport.rawFileContainsSetTempoBytes`
- `TestDrowningTempoImport.recompiledBinaryContainsSetTempoBytes`

The ninth, `TestSmpsImporter.testDacIniWithSubdirectory`, expects `DAC_81` but
gets `DAC\DAC_81` on this Linux host. These legacy issues were not changed.

The two existing skips are
`TestTimelineBuilderImport.emeraldHillZoneBuildsValidTimeline` and
`TestTimelineBuilderImport.zoomLevelsAreValid`.

## Review and remaining scope

Independent review found a packaging attribution issue. It was corrected by
retaining upstream NOTICE and embedding the exact extracted Java sources along
with license texts, so source-comment notices survive executable distribution.
The packaged bytes were checked after the correction. Shade reports the expected
overlapping `META-INF/MANIFEST.MF`; the executable entry point is explicitly set
and was exercised.

The legacy app still uses its old `synth-core`. New project authoring, resource
packages, richer command validation, timeline/piano-roll UI, live refresh and
external-agent skill/MCP are subsequent milestones. CI is configured for the
new foundation, including fetching only the locked upstream revision for parity;
local verification does not imply a hosted CI run has completed.
