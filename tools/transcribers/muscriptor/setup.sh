#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: setup.sh INSTALL_DIR cpu|rocm [small|medium|large] --accept-license

Requires uv. Set MUSCRIPTOR_PYTHON to choose a Python version (default 3.11).
ROCm requires MUSCRIPTOR_TORCH_INDEX and optionally MUSCRIPTOR_TORCH and
MUSCRIPTOR_TORCHAUDIO package specifications for your GPU and ROCm release.
USAGE
}

if [[ ${1:-} == --help ]]; then usage; exit 0; fi
if [[ $# -lt 2 || $# -gt 4 ]]; then usage >&2; exit 2; fi
install_dir=$1
backend=$2
model=large
if [[ ${3:-} != --accept-license && $# -ge 3 ]]; then model=$3; fi
case "$model" in small|medium|large) ;; *) usage >&2; exit 2 ;; esac
case "$backend" in
  cpu) torch_index=https://download.pytorch.org/whl/cpu ;;
  rocm) torch_index=${MUSCRIPTOR_TORCH_INDEX:?Set MUSCRIPTOR_TORCH_INDEX to your ROCm wheel index} ;;
  *) usage >&2; exit 2 ;;
esac

printf '%s\n' "MuScriptor code is MIT. Model weights are CC BY-NC 4.0 with additional model-card conditions." \
  "Review and accept the terms while signed into https://huggingface.co/MuScriptor/muscriptor-$model" \
  "This downloads gated files using your own Hugging Face login."
case "$#:${3:-}:${4:-}" in
  3:--accept-license:|4:"$model":--accept-license) ;;
  *) printf '%s\n' 'Pass --accept-license after accepting the model-card terms on Hugging Face.' >&2; exit 2 ;;
esac

mkdir -p -- "$install_dir"
install_dir=$(cd -- "$install_dir" && pwd)
venv="$install_dir/venv"
uv venv --allow-existing --python "${MUSCRIPTOR_PYTHON:-3.11}" "$venv"
uv pip install --python "$venv/bin/python" --index "$torch_index" \
  "${MUSCRIPTOR_TORCH:-torch}" "${MUSCRIPTOR_TORCHAUDIO:-torchaudio}"
"$venv/bin/python" - "$backend" > "$install_dir/torch-constraints.txt" <<'PY'
from importlib.metadata import version
import sys
import torch

expected_hip = sys.argv[1] == "rocm"
if bool(torch.version.hip) != expected_hip or (not expected_hip and torch.version.cuda):
    sys.exit("Installed torch does not match the selected backend. Check the wheel index.")
for package in ("torch", "torchaudio"):
    print(f"{package}=={version(package)}")
PY
uv pip install --python "$venv/bin/python" --index "$torch_index" \
  --constraint "$install_dir/torch-constraints.txt" muscriptor==0.4.0
"$venv/bin/python" - "$install_dir" "$model" <<'PY'
from pathlib import Path
import sys

from huggingface_hub import HfApi, get_token, hf_hub_download

root, model = Path(sys.argv[1]), sys.argv[2]
if not get_token():
    sys.exit("Log in with hf auth login (or supply HF_TOKEN) and rerun setup.")
destination = root / "weights" / model
repo = f"MuScriptor/muscriptor-{model}"
# Resolve a single revision for the weights and their matching architecture.
revision = HfApi().model_info(repo, token=True).sha
for filename in ("config.json", "model.safetensors"):
    hf_hub_download(repo, filename, revision=revision, token=True, local_dir=destination)
print(f"MuScriptor {model} ({revision}) installed in {root}")
print(f"Weights: {destination / 'model.safetensors'}")
PY
