# Native asset package, version 1

`compile-package PROJECT.json OUTPUT.smps.zip` and Studio's **Assets → Export
package** write a bounded native track plus its dependencies. The source remains
the shared JSON project; track and envelope bytes are already compiled/native.
OpenGGF does not currently have a loader for this ZIP. This is an explicit
adapter contract for tools and engine integration, not a new driver dialect.

## Archive layout

- `track.bin`: base-zero S3K bank, including its terminal local FM voice table.
- `source.smps.json`: complete canonical source, including unused assets.
- `manifest.json`: version, mode, bank base, resource mapping and hashes.
- `psg/001.bin` …: raw PSG-volume envelope bytes, indices 1–255.
- `mod/001.bin` …: raw modulation envelope bytes, indices 1–127.

Native indices are assigned in declared asset order independently per kind.
Zero means no envelope; native modulation 128 is reserved for custom modulation.
IDs and display names never become archive paths. All declared envelopes are
included even if no current source references them. Local voices are retained in
the bank and described by zero-based index in the manifest.

The manifest contains:

```json
{
  "schemaVersion": 1,
  "mode": "S3K",
  "bankBase": 0,
  "track": {"path": "track.bin", "sha256": "<hex SHA-256>"},
  "source": {"path": "source.smps.json", "sha256": "<hex SHA-256>"},
  "voices": [{"id": "voice-lead", "name": "Lead", "index": 0}],
  "envelopes": [{
    "path": "psg/001.bin", "sha256": "<hex SHA-256>",
    "kind": "PSG_VOLUME", "id": "pluck", "name": "Pluck", "index": 1
  }]
}
```

ZIP entries are UTF-8, stored without compression, and have fixed timestamps.
Entry order is track, source, manifest, then envelope resources in source order.
Identical source produces identical archive bytes. Validation, compilation,
resource resolution and source-size checks precede file creation. Export uses
CREATE_NEW; an existing output or symlink is never overwritten. Failure or
cancellation removes only the incomplete output created by this export.

## Driver integration

An integration reads and verifies the manifest and hashes, loads the native bank
at base0, and builds `Map<Integer, byte[]>` tables using manifest native indices.
For the pinned OpenGGF adapter:

```java
var data = new Sonic3kSmpsData(trackBytes, 0);
data.setPsgEnvelopes(psgPrograms);
data.setModEnvelopes(modulationPrograms);
// Construct the sequencer/session activation only after resources are installed.
```

`S3kRenderer.render(CompiledProject, ...)` already performs resource validation,
copies those maps and installs them before sequencer/snapshot creation. Studio
preview, project WAV export and `render-project` use that path. Byte-only `render`
has no external tables and refuses nonzero unresolved envelope references.

The project compiler emits F5/F4 assignments at v3 source resets; its notes and
commands refer to the corresponding native indices. No resource conversion is
needed. This repository does not yet provide an archive reader or an OpenGGF
in-engine ZIP loader, so existing-song importing is unaffected.

## Current resource boundary

The [project format](../../project-core/README.md) defines the bounded renderable
envelope profile. Every asset, including unused declarations, must validate.
Control-only reset cycles, unsupported envelope commands, unknown kinds, missing
references and reserved modulation indices fail before PCM or output creation.

DAC samples/mappings/rates, banked/shared/global voices, full envelope control
semantics and cross-song dependencies remain tracked in the
[S3K support matrix](s3k-support.md). The archive is dependency-complete for this
slice's supported local voices and envelope profiles, not arbitrary S3K tracks.
