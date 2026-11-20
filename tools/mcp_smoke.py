#!/usr/bin/env python3
"""Exercise the packaged stdio server with an original MIDI phrase and demo audio."""
import argparse
from array import array
import math
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import wave


class Client:
    def __init__(self, jar, modern=False):
        self.jar = jar
        self.modern = modern
        self.next_id = 0

    def __enter__(self):
        self.process = subprocess.Popen(
            ["java", "-jar", str(self.jar), "mcp"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8",
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
    with Client(jar) as rpc:
        tools = rpc.request("tools/list")["tools"]
        expected = set("""
            transcribers_list reconstruct_run reconstruct_stage reconstruct_describe reconstruct_plan_template
            artifact_read artifact_write project_validate
            render_clip render_compare notes_list notes_add notes_resolve reference_add
            catalog_search catalog_audition bench_compare
        """.split())
        assert {tool["name"] for tool in tools} == expected
        for tool in tools:
            schema = tool["inputSchema"]
            assert schema["type"] == "object" and set(schema["required"]) <= schema["properties"].keys()
        assert rpc.call("bench_compare", old=card, new=card, tolerance=0)["passed"]
        comparison = rpc.call("bench_compare", old=card, new=regressed, tolerance=0)
        assert not comparison["passed"]
        delta, = comparison["deltas"]
        assert delta["result"] == "regression" and math.isclose(delta["delta"], -0.1), comparison
        results = rpc.call("catalog_search", catalogue=inputs["catalogue"], kind="fm-voice")
        variant = results["items"][0]["variantId"]
        rpc.call("catalog_audition", catalogue=inputs["catalogue"], variant=variant,
                 workDir=root, output=root / "audition.wav")
        rpc.call("reconstruct_run", workDir=work, inputs=inputs, options={"seconds": 2}, progress=True)
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
        rpc.call("reconstruct_run", workDir=work, **{"from": "voice"})
        assert rpc.call("project_validate", project=project)["valid"]
        full = work / "render.wav"
        plain = work / "unmasked.wav"
        rpc.call("render_clip", project=project, output=plain, start=0.25, end=0.75)
        with wave.open(str(full), "rb") as reference, wave.open(str(plain), "rb") as wav:
            reference.setpos(12000)
            assert wav.readframes(24000) == reference.readframes(24000)
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
        assert [n["id"] for n in rpc.call("notes_list", project=project, openOnly=True)["notes"]] == [note]
        rpc.call("notes_resolve", project=project, id=note, reply="Raised the melody gain.", author="Agent")
        assert rpc.call("notes_list", project=project, openOnly=True)["notes"] == []
        notes = rpc.call("notes_list", project=project)
        assert notes["notes"][0]["reply"]["text"] == "Raised the melody gain."
        assert len(json.loads(project.read_text())["references"]) == 1
        steady = root / "steady"
        files = rpc.call("reconstruct_run", workDir=steady, inputs=inputs,
                         options={"seconds": 2, "tempo": 120, "steadyTempo": "80"})["files"]
        steady_wav = steady / "reference-steady.wav"
        assert Path(files["reference-steady.wav"]) == steady_wav
        assert json.loads(Path(files["arrangement.smps.json"]).read_text())["references"][0]["path"] == steady_wav.name
        for reference, name in [(inputs["wav"], "auto-ab.wav"), (steady_wav, "explicit-ab.wav")]:
            rpc.call("render_compare", render=steady / "render.wav", reference=reference, workDir=steady,
                     output=steady / name, start=2.1, end=2.5)
        assert (steady / "auto-ab.wav").read_bytes() == (steady / "explicit-ab.wav").read_bytes()

    with Client(jar, modern=True) as rpc:
        assert rpc.request("tools/list")["tools"] == tools
        assert rpc.call("notes_list", project=project) == notes
        assert rpc.call("catalog_search", catalogue=inputs["catalogue"], kind="fm-voice") == results
    print(f"MCP smoke passed ({len(tools)} tools, initialize and discovery, independent processes)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--scratch", type=Path, help="Parent directory for disposable smoke files")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="opensmps-mcp-", dir=args.scratch) as scratch:
        smoke(args.jar.resolve(), Path(scratch).resolve())


if __name__ == "__main__":
    main()
