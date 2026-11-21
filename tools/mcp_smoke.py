#!/usr/bin/env python3
"""Exercise the packaged stdio server with an original MIDI phrase and demo audio."""
import argparse
import base64
import hashlib
from array import array
import math
import json
import os
import shutil
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import wave


class Client:
    def __init__(self, jar, modern=False, env=None):
        self.jar = jar
        self.modern = modern
        self.env = env
        self.next_id = 0

    def __enter__(self):
        self.process = subprocess.Popen(
            ["java", "-jar", str(self.jar), "mcp"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8", env=self.env,
        )
        try:
            if self.modern:
                result = self.request("server/discover")
                assert "2026-07-28" in result["supportedVersions"], result
            else:
                result = self.request("initialize", protocolVersion="2025-11-25",
                                      capabilities={}, clientInfo={"name": "smoke", "version": "1"})
                assert result["protocolVersion"] == "2025-11-25", result
                self.send({"method": "notifications/initialized"})
                assert self.request("ping") == {}
            assert "tools" in result["capabilities"]
            return self
        except BaseException:
            self.__exit__()
            raise

    def __exit__(self, *_):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
            raise
        finally:
            self.process.stdout.close()

    def send(self, message):
        self.process.stdin.write(json.dumps({"jsonrpc": "2.0", **message}) + "\n")
        self.process.stdin.flush()

    def request(self, method, **params):
        if self.modern:
            params.setdefault("_meta", {}).update({
                "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                "io.modelcontextprotocol/clientCapabilities": {},
                "io.modelcontextprotocol/clientInfo": {"name": "smoke", "version": "1"},
            })
        self.next_id += 1
        self.send({"id": self.next_id, "method": method, "params": params})
        self.progress = []
        while True:
            line = self.process.stdout.readline()
            assert line, f"Server stopped while handling {method}"
            message = json.loads(line)
            assert message["jsonrpc"] == "2.0", message
            if message.get("method") == "notifications/progress":
                self.progress.append(message["params"])
                continue
            assert message["id"] == self.next_id and "error" not in message, message
            return message["result"]

    def call(self, tool, *, progress=False, error=None, **arguments):
        args = {name: str(value) if isinstance(value, Path) else value
                for name, value in arguments.items()}
        meta = {"progressToken": "smoke-progress"} if progress else {}
        result = self.request("tools/call", name=tool, arguments=args, _meta=meta)
        value = result["structuredContent"]
        if error is not None:
            assert result["isError"] and error in value["error"], result
        else:
            assert not result.get("isError"), result
        if progress:
            points = [update["progress"] for update in self.progress]
            assert points == sorted(set(points)) and points, self.progress
            assert all(p["progressToken"] == meta["progressToken"] for p in self.progress)
        else:
            assert not self.progress
        return value


def fixtures(jar, root):
    def cli(*args):
        subprocess.run(["java", "-jar", str(jar), *map(str, args)], check=True,
                       stdout=subprocess.DEVNULL)

    rips = root / "rips"
    rips.mkdir()
    demo = rips / "demo.smps.json"
    mix = root / "mix.wav"
    catalogue = root / "catalogue.json"
    cli("demo-project", demo)
    cli("render-project", demo, mix, "--frames", 120)
    cli("catalog", "scan", catalogue, "--rips", rips)
    midi = root / "fixture.mid"
    # Format 0, 96 PPQ, 120 BPM default, four distinct notes.
    track = b"".join(bytes([0, 0x90, pitch, 96, 96, 0x80, pitch, 0])
                     for pitch in (67, 69, 71, 74)) + b"\x00\xff\x2f\x00"
    midi.write_bytes(b"MThd" + struct.pack(">IHHH", 6, 0, 1, 96)
                     + b"MTrk" + struct.pack(">I", len(track)) + track)
    return {"midi": str(midi), "wav": str(mix), "catalogue": str(catalogue)}


def smoke(jar, root):
    inputs = fixtures(jar, root)
    work = root / "song"
    project = work / "arrangement.smps.json"
    card = root / "scorecard.json"
    group = {"mean": {"noteF1": 1}, "measured": {"noteF1": 1}, "cases": 1}
    card.write_text(json.dumps(dict(version=1, manifestSha256="fixture", catalogueSha256="fixture",
                                    holdout=False, audioRefine=False, goldInputs={}, cases=[],
                                    overall=group, gold=group, profiles={}, variants={})))
    regressed = root / "regressed.json"
    changed = json.loads(card.read_text())
    changed["overall"]["mean"]["noteF1"] = 0.9
    regressed.write_text(json.dumps(changed))
    adapter = root / "mock-separator.py"
    adapter.write_text("""
import json
from pathlib import Path
import shutil
import sys
import wave

source, output, device = sys.argv[1:]
assert device == "cpu"
folder = Path(output)
(folder / "stems").mkdir()
shutil.copyfile(source, folder / "mix.wav")
stems = ["bass", "drums", "other", "vocals", "guitar", "piano"]
for stem in stems:
    path = folder / "stems" / f"{stem}.wav"
    if stem == "drums":
        with wave.open(source, "rb") as mix, wave.open(str(path), "wb") as drums:
            drums.setparams(mix.getparams())
            drums.writeframes(bytes(mix.getnframes() * mix.getnchannels() * mix.getsampwidth()))
    else:
        shutil.copyfile(source, path)
(folder / "separation.json").write_text(json.dumps({
    "tool": "fixture", "model": "fixture", "version": "1",
    "checkpointSha256": "0" * 64, "stemOrder": stems, "settings": {},
}))
""")
    config = root / "separators.json"
    config.write_text(json.dumps({"fixture": [sys.executable, str(adapter), "{input}", "{outputDir}", "{device}"]}))
    transcribers = root / "transcribers.json"
    transcribers.write_text("{}")
    env = {**os.environ, "OPENSMPSDECK_SEPARATORS": str(config),
           "OPENSMPSDECK_TRANSCRIBERS": str(transcribers)}
    with Client(jar, env=env) as rpc:
        tools = rpc.request("tools/list")["tools"]
        expected = set("""
            transcribers_list separators_list separate reconstruct_import reconstruct_run reconstruct_stage reconstruct_describe reconstruct_plan_template
            reconstruct_audition reconstruct_trace reconstruct_trace_diff reconstruct_view reconstruct_edit reconstruct_sections reconstruct_voices
            artifact_read artifact_write project_validate project_export reconstruct_rerun
            render_clip render_compare notes_list notes_add notes_resolve reference_add
            catalog_search catalog_audition bench_compare
        """.split())
        assert {tool["name"] for tool in tools} == expected
        for tool in tools:
            schema = tool["inputSchema"]
            assert schema["type"] == "object" and set(schema["required"]) <= schema["properties"].keys()
        assert rpc.call("separators_list") == {"separators": ["fixture"]}
        separated = root / "separated"
        result = rpc.call("separate", input=inputs["wav"], outputDir=separated,
                          separator="fixture", device="cpu", progress=True)
        assert Path(result["provenance"]).is_file()
        checked = rpc.call("reconstruct_import", folder=separated, workDir=root / "audio-import")
        assert checked["status"] == "warning" and "runRequest" not in checked, checked
        assert any(issue["code"] == "needs_audio_only_transcription" for issue in checked["issues"]), checked
        assert rpc.call("bench_compare", old=card, new=card, tolerance=0)["passed"]
        comparison = rpc.call("bench_compare", old=card, new=regressed, tolerance=0)
        assert not comparison["passed"]
        delta, = comparison["deltas"]
        assert delta["result"] == "regression" and math.isclose(delta["delta"], -0.1), comparison
        results = rpc.call("catalog_search", catalogue=inputs["catalogue"], kind="fm-voice")
        variant = results["items"][0]["variantId"]
        rpc.call("catalog_audition", catalogue=inputs["catalogue"], variant=variant,
                 workDir=root, output=root / "audition.wav")
        download = root / "download"
        download.mkdir()
        shutil.copyfile(inputs["midi"], download / "suno.mid")
        shutil.copyfile(inputs["wav"], download / "mix.wav")
        imported = root / "imported"
        report = rpc.call("reconstruct_import", folder=download, workDir=imported,
                          catalogue=inputs["catalogue"], progress=True)
        assert report == json.loads((imported / "import.json").read_text())
        assert report["status"] == "warning", report
        assert {entry["role"] for entry in report["files"]} == {"midi", "mix"}
        assert report["runArguments"][:2] == ["reconstruct", "run"]
        checked = rpc.call("artifact_read", workDir=imported, artifact="import")["value"]
        assert checked == report
        recipe = report["runRequest"]
        assert recipe["inputs"]["catalogue"] == inputs["catalogue"]
        import_run = rpc.call("reconstruct_run", **recipe, progress=True)
        assert Path(import_run["files"]["arrangement.smps.json"]).is_file()
        voice_work = recipe["workDir"]
        voice_list = rpc.call("reconstruct_voices", workDir=voice_work)
        original_voice = next(voice for voice in voice_list["voices"] if voice["kind"] == "tonal")
        voice_id = original_voice["id"]
        note_id = original_voice["noteIds"][0]
        form = rpc.call("reconstruct_voices", workDir=voice_work, action="split", entries=[{
            "id": "split-voice", "author": "human", "voiceId": voice_id, "targetId": "answer",
            "name": "Answer", "noteIds": [note_id],
        }])
        assert next(voice for voice in form["voices"] if voice["id"] == "answer")["noteIds"] == [note_id]
        rpc.call("reconstruct_voices", workDir=voice_work, action="merge", entries=[{
            "id": "merge-voice", "author": "human", "voiceId": "answer", "targetId": voice_id,
        }])
        rpc.call("reconstruct_voices", workDir=voice_work, action="rename", entries=[{
            "id": "rename-voice", "author": "human", "voiceId": voice_id, "name": "Lead instrument 1",
        }])
        rpc.call("reconstruct_voices", workDir=voice_work, action="assign", entries=[{
            "id": "assign-voice", "author": "agent", "voiceId": voice_id,
            "assignment": {"laneType": "FM", "instrument": variant},
        }])
        rpc.call("reconstruct_run", workDir=voice_work, **{"from": "transcribe"})
        voices = rpc.call("artifact_read", workDir=voice_work, artifact="voices")["value"]
        assert not voices["issues"]
        identified = next(voice for voice in voices["voices"] if voice["id"] == voice_id)
        assert identified["name"] == "Lead instrument 1" and identified["assignment"]["instrument"] == variant
        assignments = rpc.call("artifact_read", workDir=voice_work, artifact="voicing")["value"]["voiceAssignments"]
        assert assignments and all(value["instrument"] == variant for value in assignments.values())
        traced = rpc.call("reconstruct_trace", workDir=voice_work, lane="FM1")["notes"]
        assert traced and traced[0]["voice"]["name"] == "Lead instrument 1"
        files = rpc.call("reconstruct_run", workDir=work, inputs=inputs, options={"seconds": 2}, progress=True)["files"]
        assert Path(files["manifest.json"]) == work / "manifest.json"
        manifest = rpc.call("artifact_read", workDir=work, artifact="manifest")["value"]
        exported = rpc.call("project_export", workDir=work, output="output.wav", format="wav", progress=True)
        assert Path(exported["output"]).read_bytes() == Path(files["render.wav"]).read_bytes()
        arrangement = Path(files["arrangement.smps.json"])
        attached = arrangement.read_bytes()
        detached = json.loads(attached)
        detached["detachedFromReconstruction"] = True
        arrangement.write_text(json.dumps(detached))
        rpc.call("reconstruct_run", workDir=work, **{"from": "assemble"}, error="hand edits")
        rpc.call("reconstruct_rerun", workDir=work, error="hand edits")
        assert json.loads(arrangement.read_bytes()) == detached
        rpc.call("reconstruct_run", workDir=work, **{"from": "assemble"}, options={"overwriteDetached": True})
        assert arrangement.read_bytes() == attached
        before_layers = Path(files["render.wav"]).read_bytes()
        rpc.call("reconstruct_stage", workDir=work, stage="assemble", options={"lanePolicyPreset": "s3k-layering"})
        layered = rpc.call("artifact_read", workDir=work, artifact="report")["value"]["lanePolicies"]
        assert [rule["policy"] for rule in layered] == ["psgDouble", "fmDouble"]
        assert rpc.call("artifact_read", workDir=work, artifact="voicing")["value"]["lanePolicies"] == {
            "preset": "s3k-layering"
        }
        rpc.call("reconstruct_stage", workDir=work, stage="assemble", options={"lanePolicyPreset": "off"})
        assert arrangement.read_bytes() == attached and Path(files["render.wav"]).read_bytes() == before_layers
        assert manifest["inputs"]["midi"]["sha256"] == hashlib.sha256(Path(inputs["midi"]).read_bytes()).hexdigest()
        trace = rpc.call("reconstruct_trace", workDir=work, start=0, end=2, lane="FM1")["notes"]
        assert trace and all(row["voiced"][0]["evidence"] == "assembled" for row in trace)
        clusters = rpc.call("reconstruct_trace", workDir=work, start=0, end=2, part=0, byOnset=True)["clusters"]
        assert clusters and all("sourceStart" in cluster and "projectStart" in cluster for cluster in clusters)
        target = trace[0]["id"]
        preview = rpc.call("reconstruct_edit", workDir=work, action="add", dryRun=True,
                           edit={"id": "preview", "reason": "Confirm current role", "op": "setRole",
                                 "noteIds": [target], "fromRole": "counter", "role": "melody"})
        assert preview["notes"][0]["noteId"] == target and preview["notes"][0]["satisfied"]
        assert preview["outcomes"][0]["status"] == "satisfied" and not (work / "edits.json").exists()
        echo = {"id": "echo-preview", "reason": "Echo one attack", "op": "layer",
                "mode": "echo", "laneType": "FM", "delayFrames": 10, "levelDb": -9, "noteIds": [target]}
        preview = rpc.call("reconstruct_edit", workDir=work, action="add", dryRun=True, edit=echo)
        layer = preview["outcomes"][0]["layer"]
        assert preview["from"] == "assemble" and layer["noteId"] == target
        assert layer["status"] == "applied" and layer["attenuation"] == 12 and layer["delayFrames"] == 10
        assert not (work / "edits.json").exists() and Path(files["render.wav"]).read_bytes() == before_layers
        scored = trace[0]["score"]
        source = scored["note"]
        cut_at = (source["start"] + source["end"]) / 2
        split = {"id": "split-preview", "reason": "Synthetic cut", "op": "splitNote", "noteId": target, "at": cut_at}
        preview = rpc.call("reconstruct_edit", workDir=work, action="add", edit=split, dryRun=True)
        assert [n["id"] for n in preview["outcomes"][0]["resultNotes"]] == [
            "edit-split-preview-left", "edit-split-preview-right"]
        merge = {"id": "merge-preview", "reason": "Synthetic join", "op": "mergeNotes",
                 "noteIds": ["edit-split-preview-left", "edit-split-preview-right"]}
        preview = rpc.call("reconstruct_edit", workDir=work, action="add", edits=[split, merge], dryRun=True)
        assert preview["outcomes"][1]["resultNotes"][0]["id"] == "edit-split-preview-left"
        assert preview["outcomes"][1]["removedNotes"][0]["id"] == "edit-split-preview-right"
        pin = {"id": "type-preview", "reason": "Keep existing FM type", "op": "pinLane",
               "noteIds": [target], "laneType": "FM"}
        preview = rpc.call("reconstruct_edit", workDir=work, action="add", edit=pin, dryRun=True)
        assert preview["outcomes"][-1]["laneType"] == "FM" and preview["outcomes"][-1]["lane"] == "FM1"
        assert not (work / "edits.json").exists()
        artifact_hashes = {name: hashlib.sha256(Path(path).read_bytes()).digest()
                           for name, path in files.items()}
        view = rpc.request("tools/call", name="reconstruct_view", arguments={
            "workDir": str(work), "start": 0.25, "end": 0.75, "render": True,
            "output": "evidence.png", "width": 800, "lanes": ["FM1"], "parts": [0], "colorBy": "voice",
        })
        assert not view["isError"], view
        legend = view["structuredContent"]
        assert legend["plot"]["renderTop"] is not None and legend["notes"], legend
        assert voice_id in legend["voiceColors"] and legend["noteVoices"]
        assert all(note["lane"] == "FM1" and note["status"] and note["pitchName"]
                   for note in legend["notes"])
        png, body = view["content"]
        assert png["type"] == "image" and png["mimeType"] == "image/png"
        assert base64.b64decode(png["data"]).startswith(b"\x89PNG\r\n\x1a\n")
        assert base64.b64decode(png["data"]) == Path(legend["path"]).read_bytes()
        assert json.loads(body["text"]) == legend
        notes = root / "evidence-notes.json"
        notes.write_text("[[0.3, 0.5, 69]]\n")
        expanded = rpc.request("tools/call", name="reconstruct_view", arguments={
            "workDir": str(work), "start": 0.25, "end": 0.75, "render": True, "output": "expanded.png",
            "width": 800, "notes": str(notes), "harmonicsAt": ["A4@0.4", "72"],
            "ridges": True, "sharedDb": True, "pitchPreset": "drums",
        })
        assert not expanded["isError"], expanded
        details = expanded["structuredContent"]
        assert any(note["evidence"] == notes.name and note["pitch"] == 69 for note in details["notes"])
        assert [target["pitch"] for target in details["harmonicsAt"]] == [69, 72]
        assert details["pitchMin"] == 35 and details["pitchMax"] == 81
        assert details["levels"]["shared"] and details["levels"]["renderPeakDbfs"] is not None
        ridges = json.loads(Path(details["ridgesPath"]).read_text())["ridges"]
        assert ridges and all(0.25 < ridge["time"] < 0.75 and math.isfinite(ridge["strength"]) for ridge in ridges)
        assert all(hashlib.sha256(Path(files[name]).read_bytes()).digest() == digest
                   for name, digest in artifact_hashes.items())
        assert not rpc.call("reconstruct_trace_diff", oldWorkDir=work, newWorkDir=work, span="0,2")["changes"]
        rpc.call("reconstruct_stage", workDir=work, stage="voice")
        rpc.call("reference_add", project=project, wav=inputs["wav"], role="mix")
        note = rpc.call("notes_add", project=project, at=0.25, end=0.75,
                        lane="FM1", text="Try a clearer lead", author="Reviewer")["id"]
        read = rpc.call("artifact_read", workDir=work, artifact="voicing")
        rpc.call("artifact_write", workDir=work, artifact="voicing", value={"version": 1}, error="lanes")
        assert rpc.call("artifact_read", workDir=work, artifact="voicing") == read
        gain = ["lanes", "FM1", "gain"]
        rpc.call("artifact_write", workDir=work, artifact="voicing", selection=gain, value=9, error="FM1.gain")
        rpc.call("artifact_write", workDir=work, artifact="voicing", selection=gain, value=1.25)
        assert rpc.call("artifact_read", workDir=work, artifact="voicing", selection=gain)["value"] == 1.25
        release = ["lanes", "FM1", "articulation", "minimumReleaseRate"]
        rpc.call("artifact_write", workDir=work, artifact="voicing", selection=release, value=0)
        rpc.call("reconstruct_run", workDir=work, **{"from": "voice"})
        assert rpc.call("project_validate", project=project)["valid"]
        full = work / "render.wav"
        before_warning = full.read_bytes()
        voicing = rpc.call("artifact_read", workDir=work, artifact="voicing")["value"]
        voicing["sustainWarning"] = "Synthetic FM1 sustain warning."
        rpc.call("artifact_write", workDir=work, artifact="voicing", value=voicing)
        rpc.call("reconstruct_stage", workDir=work, stage="assemble")
        report = rpc.call("artifact_read", workDir=work, artifact="report")["value"]
        probe = report["sustainProbe"]
        assert probe["totalNotes"] > 0 and 0 <= probe["notesProbed"] <= 3
        assert report["voicingWarnings"] == ([probe["warning"]] if "warning" in probe else [])
        assert voicing["sustainWarning"] not in report["voicingWarnings"]
        assert full.read_bytes() == before_warning
        plain = work / "unmasked.wav"
        rpc.call("render_clip", project=project, output=plain, start=0.25, end=0.75)
        with wave.open(str(full), "rb") as reference, wave.open(str(plain), "rb") as wav:
            reference.setpos(12000)
            assert wav.readframes(24000) == reference.readframes(24000)
        current = read["value"]["sourceInstruments"]["part-0"]["fm"]["variantId"]
        audition = rpc.call("reconstruct_audition", workDir=work, lane="FM1", start=0.25, end=0.75,
                            variants=[current], output=work / "phrase.wav", rmsJson="phrase.rms.json")
        assert audition["startTick"] == 15 and audition["endTick"] == 45
        assert len(audition["candidates"][0]["rms"]) == 7
        assert audition["candidates"][0]["variantId"] == current
        assert json.loads(Path(audition.pop("rmsJson")).read_text()) == audition
        assert (work / "phrase.wav").read_bytes() == plain.read_bytes()
        assert audition["sourceStart"] == audition["projectStart"] == 0.25
        gate = trace[0]["voiced"][0]["gate"]
        by_note = rpc.call("reconstruct_audition", workDir=work, note=target, variants=[current],
                           output=work / "note.wav")
        assert by_note["noteId"] == target and by_note["startTick"] == gate["startTick"]
        assert by_note["endTick"] == gate["endTick"]
        clip = work / "clip.wav"
        rpc.call("render_clip", project=project, output=clip, start=0.25, end=0.75, solo=["FM1"])
        with wave.open(str(clip), "rb") as wav:
            assert (wav.getframerate(), wav.getnchannels(), wav.getnframes()) == (48000, 2, 24000)
            assert any(wav.readframes(24000))
        silent = work / "silent.wav"
        rpc.call("render_clip", project=project, output=silent, start=0.25, end=0.75, solo=["FM1"], mute=["FM1"])
        with wave.open(str(silent), "rb") as wav:
            # Fully muted chip output can have a fixed DC bias.
            pcm = wav.readframes(24000)
            assert len(pcm) == 24000 * 4 and pcm == pcm[:4] * 24000
        rpc.call("render_clip", project=project, output=clip, start=0, end=1, error="already exists")
        rpc.call("render_clip", project=project, output=root / "escape.wav", start=0, end=1,
                 error="inside")
        assert not (root / "escape.wav").exists()
        try:
            (work / "escape").symlink_to(root, target_is_directory=True)
        except OSError:
            if sys.platform != "win32":
                raise
        else:
            rpc.call("render_clip", project=project, output=work / "escape" / "escape.wav",
                     start=0, end=1, error="inside")
            assert not (root / "escape.wav").exists()
        ab = rpc.call("render_compare", render=full, reference=inputs["wav"],
                      workDir=work, output=work / "ab.wav", start=0.25, end=0.75)
        assert ab["levelMatched"] and ab["splitSeconds"] == 0.5
        with wave.open(str(work / "ab.wav"), "rb") as wav:
            assert wav.getnframes() == 48000
            rms = []
            for _ in range(2):
                samples = array("h", wav.readframes(24000))
                if sys.byteorder != "little":
                    samples.byteswap()
                rms.append(math.sqrt(sum(sample * sample for sample in samples) / len(samples)))
            assert math.isclose(*rms, abs_tol=1), rms
        proposed = rpc.call("reconstruct_sections", workDir=work)
        assert proposed["sections"] == proposed["automatic"] and proposed["sections"]
        span = {"id": "chorus-1", "name": "Chorus", "level": "section", "author": "human",
                "reason": "Synthetic chorus", "sourceStart": 0.25, "sourceEnd": 0.75}
        form = rpc.call("reconstruct_sections", workDir=work, action="add", entries=[span])
        authored, = [row for row in form["sections"] if row["id"] == span["id"]]
        assert authored["author"] == "human" and authored["sourceStart"] == 0.25
        assert authored["startBar"] > 1 and form["automatic"] == proposed["automatic"]
        assert rpc.call("artifact_read", workDir=work, artifact="sections")["value"]["entries"][0]["reason"] == span["reason"]
        by_section = rpc.request("tools/call", name="reconstruct_view", arguments={
            "workDir": str(work), "section": "chorus-1", "output": "section.png", "width": 640,
        })
        assert not by_section["isError"], by_section
        assert by_section["structuredContent"]["sourceStart"] == 0.25
        pair = rpc.call("render_compare", render=full, reference=inputs["wav"], workDir=work,
                        output=work / "section.wav", section="chorus-1")
        assert pair["splitSeconds"] == 0.5
        scoped = {"id": "scoped", "reason": "Synthetic passage", "op": "setRole", "part": 0,
                  "span": {"section": "chorus-1"}, "role": "melody"}
        preview = rpc.call("reconstruct_edit", workDir=work, action="add", dryRun=True, edit=scoped)
        assert preview["notes"] and all(0.25 <= note["sourceStart"] < 0.75 for note in preview["notes"])
        period = dict(span, id="answer-period", name="Answer", level="period")
        rpc.call("reconstruct_sections", workDir=work, action="add", entries=[period])
        rpc.call("artifact_write", workDir=work, artifact="roleplan",
                 value={"version": 1, "rules": [{"section": "answer-period", "part": 0, "role": "melody"}]})
        report = rpc.call("reconstruct_stage", workDir=work, stage="roles")
        assert report["rolePlanTimes"][0]["sourceEnd"] == 0.75
        (work / "roleplan.json").unlink()
        changed = {"id": "chorus-1", "author": "agent", "reason": "Correct label", "name": "Refrain"}
        form = rpc.call("reconstruct_sections", workDir=work, action="rename", entries=[changed])
        assert any(row["label"] == "Refrain" and row["author"] == "agent" for row in form["sections"])
        del changed["name"]
        form = rpc.call("reconstruct_sections", workDir=work, action="remove", entries=[changed])
        assert all(row["id"] != "chorus-1" for row in form["sections"])
        preview = rpc.call("reconstruct_edit", workDir=work, action="add", dryRun=True, edit=scoped)
        assert preview["outcomes"][0]["status"] == "invalid"
        assert [n["id"] for n in rpc.call("notes_list", project=project, openOnly=True)["notes"]] == [note]
        rpc.call("notes_resolve", project=project, id=note, reply="Raised the melody gain.", author="Agent")
        assert rpc.call("notes_list", project=project, openOnly=True)["notes"] == []
        notes = rpc.call("notes_list", project=project)
        assert notes["notes"][0]["reply"]["text"] == "Raised the melody gain."
        assert len(json.loads(project.read_text())["references"]) == 1
        edit = {"id": "answer", "reason": "synthetic missing attack", "op": "insertNote", "part": 0,
                "start": 1.7, "end": 1.9, "pitch": 67, "role": "melody", "lane": "FM1"}
        added = rpc.call("reconstruct_edit", workDir=work, action="add", edit=edit, rerun=True)
        assert added["from"] == "transcribe" and added["traceDiff"]["span"] == [1.7, 1.9]
        assert any(change["id"] == "edit-answer" for change in added["traceDiff"]["changes"])
        assert rpc.call("reconstruct_edit", workDir=work, action="list")["edits"] == [edit]
        rpc.call("reconstruct_edit", workDir=work, action="add", edit=edit, error="duplicate edit ID")
        removed = rpc.call("reconstruct_edit", workDir=work, action="remove", id="answer", rerun=True)
        assert not removed["edits"]
        assert any(change["id"] == "edit-answer" for change in removed["traceDiff"]["changes"])
        batch = [{"id": f"level-{n}", "reason": "synthetic batch", "op": "setVelocity",
                  "part": 0, "span": [0, 2], "velocity": 90 - n} for n in range(20)]
        added = rpc.call("reconstruct_edit", workDir=work, action="add", edits=batch, rerun=True)
        assert added["from"] == "transcribe" and added["ids"] == [edit["id"] for edit in batch]
        assert all(result["status"] == "applied" for result in added["lastRun"])
        rpc.call("reconstruct_edit", workDir=work, action="add", edit=edit, edits=batch, error="Choose edit or edits")
        assert rpc.call("notes_list", project=project) == notes
        built_wave = Path(files["render.wav"]).read_bytes()
        proposed = rpc.call("reconstruct_edit", workDir=work, propose=True, edits=[
            {**edit, "group": "phrase"},
            {**edit, "id": "alternate", "pitch": 66, "group": "phrase"},
        ])
        assert all(row["edit"]["status"] == "proposed" for row in proposed["proposals"])
        listed = rpc.call("reconstruct_edit", workDir=work, action="proposals", group="phrase")
        assert len(listed["proposals"]) == 2
        preview = rpc.call("reconstruct_run", workDir=work, withProposals=True, proposalId="answer", progress=True)
        assert preview["ids"] == ["answer"] and Path(preview["workDir"]).parent == work.parent
        pair = preview["comparisons"][0]
        assert pair["order"] == ["built", "with-proposals"] and Path(pair["output"]).is_file()
        assert rpc.call("reconstruct_edit", workDir=work, action="accept", id="answer")["from"] == "transcribe"
        assert rpc.call("reconstruct_edit", workDir=work, action="reject", group="phrase")["ids"] == ["alternate"]
        assert Path(files["render.wav"]).read_bytes() == built_wave
        steady = root / "steady"
        files = rpc.call("reconstruct_run", workDir=steady, inputs=inputs,
                         options={"seconds": 2, "tempo": 120, "steadyTempo": "80"})["files"]
        steady_wav = steady / "reference-steady.wav"
        assert Path(files["reference-steady.wav"]) == steady_wav
        assert json.loads(Path(files["arrangement.smps.json"]).read_text())["references"][0]["path"] == steady_wav.name
        for reference, name in [(inputs["wav"], "auto-ab.wav"), (steady_wav, "explicit-ab.wav")]:
            rpc.call("render_compare", render=steady / "render.wav", reference=reference, workDir=steady,
                     output=steady / name, start=2.1, end=2.5, time="project")
        source_ab = rpc.call("render_compare", render=steady / "render.wav", reference=inputs["wav"], workDir=steady,
                             output=steady / "source-ab.wav", start=1.4, end=2.5 * 2 / 3)
        assert source_ab["startTick"] == 126 and source_ab["endTick"] == 150
        assert (steady / "auto-ab.wav").read_bytes() == (steady / "explicit-ab.wav").read_bytes()
        assert (steady / "auto-ab.wav").read_bytes() == (steady / "source-ab.wav").read_bytes()

    with Client(jar, modern=True) as rpc:
        assert rpc.request("tools/list")["tools"] == tools
        assert rpc.call("notes_list", project=project) == notes
        assert rpc.call("catalog_search", catalogue=inputs["catalogue"], kind="fm-voice") == results

    transcriber = root / "mock-transcriber.py"
    transcriber.write_text("""
import json
from pathlib import Path
import sys

source, output, role = sys.argv[1:]
pitches = {"vocals": 67, "bass": 43, "guitar": 55, "piano": 60, "other": 64}
assert Path(source).is_file() and role == Path(source).stem
pitch = pitches[role]
Path(output).write_text(json.dumps([[0.25, 0.50, pitch], [0.75, 1.0, pitch + 2]]))
""")
    transcribers.write_text(json.dumps({
        "fixture": [sys.executable, str(transcriber), "{input}", "{output}", "{role}"],
    }))
    with Client(jar, env=env) as rpc:
        checked = rpc.call("reconstruct_import", folder=separated, workDir=root / "mcp-audio-run",
                           catalogue=inputs["catalogue"])
        request = checked["runRequest"]
        assert request["inputs"]["audioOnly"] and "midi" not in request["inputs"], request
        assert request["options"]["transcriber"] == "fixture", request
        assert set(request["inputs"]["stems"]) == {"bass", "drums", "guitar", "keyboard", "other", "vocals"}, request
        assert request["options"]["stemRoles"] == {"keyboard": "piano"}, request
        roles = {entry["family"]: entry["evidence"]["audioOnlyRole"]
                 for entry in checked["files"] if entry["role"] == "stem"}
        assert roles == {"bass": "bass", "drums": "drums", "guitar": "guitar",
                         "keyboard": "piano", "other": "other", "vocals": "lead"}, roles
        rpc.call("reconstruct_run", **request)
        arrangement = Path(request["workDir"]) / "arrangement.smps.json"
        assert rpc.call("project_validate", project=arrangement)["valid"]

    cli_work = root / "cli-audio-run"
    subprocess.run(["java", "-jar", str(jar), "reconstruct", "import", str(separated), str(cli_work),
                    "--catalogue", inputs["catalogue"], "--run"], env=env, check=True,
                   stdout=subprocess.DEVNULL)
    cli_reconstruction = cli_work / "reconstruction"
    assert (cli_reconstruction / "render.wav").is_file()
    subprocess.run(["java", "-jar", str(jar), "validate-project",
                    str(cli_reconstruction / "arrangement.smps.json")], check=True,
                   stdout=subprocess.DEVNULL)

    print(f"MCP smoke passed ({len(tools)} tools, including separated audio-only run via CLI and MCP)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--scratch", type=Path, help="Parent directory for disposable smoke files")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="opensmps-mcp-", dir=args.scratch) as scratch:
        smoke(args.jar.resolve(), Path(scratch).resolve())


if __name__ == "__main__":
    main()
