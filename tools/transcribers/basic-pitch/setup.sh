#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 1 ]]; then echo "Usage: setup.sh INSTALL_DIR" >&2; exit 2; fi
venv="$1/venv"
uv venv --allow-existing --python 3.11 "$venv"
uv pip install --python "$venv/bin/python" --no-deps basic-pitch==0.4.0
uv pip install --python "$venv/bin/python" onnxruntime==1.23.2 librosa resampy pretty-midi mir-eval
