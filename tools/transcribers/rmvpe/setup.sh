#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 1 ]]; then echo "Usage: setup.sh INSTALL_DIR" >&2; exit 2; fi
install_dir="$1"
revision=81eed5e8f68b6bed1789f682fe78cdd324495afc
mkdir -p "$install_dir"
git init "$install_dir/rvc"
git -C "$install_dir/rvc" fetch --depth=1 https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI.git "$revision"
git -C "$install_dir/rvc" checkout --detach FETCH_HEAD
uv venv --allow-existing --python 3.11 "$install_dir/venv"
uv pip install --python "$install_dir/venv/bin/python" --index-url https://download.pytorch.org/whl/cpu torch
uv pip install --python "$install_dir/venv/bin/python" numpy scipy soundfile librosa==0.11.0
