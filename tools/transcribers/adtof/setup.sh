#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 1 ]]; then echo "Usage: setup.sh INSTALL_DIR" >&2; exit 2; fi
venv="$1/venv"
uv venv --allow-existing --python 3.11 "$venv"
uv pip install --python "$venv/bin/python" --index https://download.pytorch.org/whl/cpu torch
uv pip install --python "$venv/bin/python" \
  'adtof-pytorch @ git+https://github.com/xavriley/ADTOF-pytorch@85c192e78f716ea0b111cc8a5ee4a8f6a3a4f8a9'
