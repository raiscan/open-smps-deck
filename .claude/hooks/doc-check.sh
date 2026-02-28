#!/bin/bash
# Doc validation hook: checks if modified source files have corresponding
# user guide chapters that may need updating.
#
# Runs on UserPromptSubmit. Compares git diff of source files against
# doc files to detect potential staleness.

set -euo pipefail

cd "$CLAUDE_PROJECT_DIR" 2>/dev/null || exit 0

# Get all modified files (staged + unstaged) relative to HEAD
CHANGED_FILES=$(git diff --name-only HEAD 2>/dev/null || true)
if [ -z "$CHANGED_FILES" ]; then
    # Also check staged files if HEAD diff is empty
    CHANGED_FILES=$(git diff --name-only --cached 2>/dev/null || true)
fi

if [ -z "$CHANGED_FILES" ]; then
    echo '{}'
    exit 0
fi

DOC_DIR="docs/user-guide"

# Map source files to doc chapters
# Format: "source_suffix|doc_chapter1,doc_chapter2"
MAPPINGS=(
    "ui/TrackerGrid.java|07-tracker-grid.md,11-keyboard-reference.md"
    "ui/FmVoiceEditor.java|04-fm-voice-editor.md"
    "ui/PsgEnvelopeEditor.java|05-psg-envelopes.md"
    "ui/DacSampleEditor.java|06-dac-samples.md"
    "ui/OrderListPanel.java|08-patterns-and-orders.md"
    "ui/MainWindow.java|03-interface-overview.md,11-keyboard-reference.md"
    "ui/MainWindowFileActions.java|03-interface-overview.md,10-importing.md"
    "ui/TransportBar.java|03-interface-overview.md,12-smps-modes.md"
    "ui/InstrumentPanel.java|04-fm-voice-editor.md,05-psg-envelopes.md,06-dac-samples.md"
    "ui/VoiceImportDialog.java|10-importing.md"
    "ui/InstrumentResolveDialog.java|07-tracker-grid.md"
    "audio/PlaybackEngine.java|09-playback-and-export.md"
    "io/WavExporter.java|09-playback-and-export.md"
    "io/SmpsExporter.java|09-playback-and-export.md"
    "io/SmpsImporter.java|10-importing.md"
    "io/VoiceBankFile.java|09-playback-and-export.md,10-importing.md"
    "io/Rym2612Importer.java|10-importing.md"
    "io/DacSampleImporter.java|06-dac-samples.md,10-importing.md"
    "codec/SmpsEncoder.java|07-tracker-grid.md,11-keyboard-reference.md"
    "model/SmpsMode.java|12-smps-modes.md"
)

# Collect stale doc chapters (source changed, doc not changed)
declare -A STALE_DOCS
for mapping in "${MAPPINGS[@]}"; do
    src_suffix="${mapping%%|*}"
    doc_list="${mapping##*|}"

    # Check if any changed file matches this source suffix
    matched_src=""
    while IFS= read -r file; do
        if [[ "$file" == *"$src_suffix" ]]; then
            matched_src="$file"
            break
        fi
    done <<< "$CHANGED_FILES"

    if [ -z "$matched_src" ]; then
        continue
    fi

    # Check each mapped doc chapter
    IFS=',' read -ra docs <<< "$doc_list"
    for doc in "${docs[@]}"; do
        # Check if this doc is also in the changed files
        doc_path="$DOC_DIR/$doc"
        doc_changed=false
        while IFS= read -r file; do
            if [[ "$file" == "$doc_path" ]]; then
                doc_changed=true
                break
            fi
        done <<< "$CHANGED_FILES"

        if [ "$doc_changed" = false ]; then
            src_basename=$(basename "$matched_src")
            if [ -z "${STALE_DOCS[$doc]+x}" ]; then
                STALE_DOCS[$doc]="$src_basename"
            else
                # Avoid duplicate source names
                if [[ "${STALE_DOCS[$doc]}" != *"$src_basename"* ]]; then
                    STALE_DOCS[$doc]="${STALE_DOCS[$doc]}, $src_basename"
                fi
            fi
        fi
    done
done

if [ ${#STALE_DOCS[@]} -eq 0 ]; then
    echo '{}'
    exit 0
fi

# Build the reminder message
MSG="Documentation may need updating:\n"
for doc in $(echo "${!STALE_DOCS[@]}" | tr ' ' '\n' | sort); do
    MSG+="- $doc (${STALE_DOCS[$doc]} was modified)\n"
done
MSG+="Review these chapters if the code changes affect user-facing behavior."

# Escape for JSON
JSON_MSG=$(printf '%s' "$MSG" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\n/\\n/g')

cat <<ENDJSON
{"additionalContext": "$JSON_MSG"}
ENDJSON

exit 0
