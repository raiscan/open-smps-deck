# Reconstruction edit branches

The main journal lives at `WORK/edits.json`. A branch is a version 1 JSON
object at `WORK/edits/branches/NAME.json` with these fields.

| Field | Meaning |
| --- | --- |
| `version` | Integer 1 |
| `name`, `author`, `createdAt` | Branch identifier, human or agent name/model, and UTC timestamp |
| `parent` | `revision` uses the same content fingerprint as a reconstruction note handoff. `editCount` counts the main journal at that revision |
| `groups` | Array of `{id, label, rationale}` objects |
| `edits` | Ordered journal intents with optional `group` and `provenance` |
| `removedEditIds`, `order` | Optional journal removals and explicit ordering for imported histories |

Parent journal snapshots are shared by revision under `WORK/edits/parents`.
An existing ID in a delta replaces the parent entry in place. New IDs append.
A stitch records `provenance: {branch, author, group}` on each incoming
edit. A saved branch is based on main. Stitching into a named destination
updates that branch or creates it when absent.

```sh
opensmpsdeck reconstruct branch create WORK NAME [--author NAME]
opensmpsdeck reconstruct branch list WORK
opensmpsdeck reconstruct branch show WORK NAME
opensmpsdeck reconstruct branch import WORK NAME COPY [--author NAME]
opensmpsdeck reconstruct branch diff WORK NAME [--json]
opensmpsdeck reconstruct branch rebase WORK NAME
opensmpsdeck reconstruct branch merge WORK NAME... --into TARGET [--dry-run]
opensmpsdeck reconstruct branch delete WORK NAME
opensmpsdeck reconstruct run WORK --branch NAME
opensmpsdeck reconstruct assemble WORK --branch NAME
opensmpsdeck reconstruct describe WORK --branch NAME [--json]
opensmpsdeck reconstruct trace WORK --branch NAME [--json]
opensmpsdeck reconstruct render WORK --branch NAME OUT.wav
opensmpsdeck reconstruct edit WORK --branch NAME add JSON
```

The stitch command accepts `--parts 1,3`, `--span START:END` in source
seconds, `--groups g1,g2` and `--edits id1,id2`. Selectors intersect and
retain complete intent operations. Source order determines replay order.
Dry runs return JSON with direct and indirect conflicts. Resolve conflicts
through selection or by revising a branch before committing the stitch.

Builds return an isolated work directory under `WORK/edits/builds`.
Branch inspection and render commands use the same cached build.
Studio can use the public headless `ReconstructionBranches` API and
treat a returned build directory as an ordinary reconstruction work
directory for inspection. The MCP `reconstruct_branch` tool supports
the branch operations using an `action` field.
