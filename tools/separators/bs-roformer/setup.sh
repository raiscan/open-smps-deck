#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'USAGE'
Usage: setup.sh INSTALL_DIR cpu|rocm|cuda [--download-weights]

Requires uv, Git and FFmpeg. BS_ROFORMER_PYTHON defaults to 3.11.
For ROCm or CUDA set BS_ROFORMER_TORCH_INDEX to a compatible wheel index.
BS_ROFORMER_TORCH can select a specific torch package version.
Pass --download-weights to explicitly fetch the published model assets.
USAGE
}

if [[ ${1:-} == --help ]]; then usage; exit 0; fi
if [[ $# -lt 2 || $# -gt 3 || ( $# -eq 3 && ${3:-} != --download-weights ) ]]; then
    usage >&2
    exit 2
fi
backend=$2
case "$backend" in
    cpu) torch_index=https://download.pytorch.org/whl/cpu ;;
    rocm|cuda) torch_index=${BS_ROFORMER_TORCH_INDEX:?Set BS_ROFORMER_TORCH_INDEX for your accelerator} ;;
    *) usage >&2; exit 2 ;;
esac

command -v ffmpeg >/dev/null || { echo "Install FFmpeg before running setup." >&2; exit 1; }
mkdir -p -- "$1"
install_dir=$(cd -- "$1" && pwd)
venv="$install_dir/venv"
uv venv --allow-existing --python "${BS_ROFORMER_PYTHON:-3.11}" "$venv"
uv pip install --python "$venv/bin/python" --index "$torch_index" "${BS_ROFORMER_TORCH:-torch>=2.6}"
"$venv/bin/python" - "$backend" > "$install_dir/torch-constraints.txt" <<'PY'
import sys
import torch

backend = "rocm" if torch.version.hip else "cuda" if torch.version.cuda else "cpu"
if sys.argv[1] != backend:
    sys.exit(f"Expected a {sys.argv[1]} PyTorch build, got {backend}. Check your wheel index.")
print(f"torch=={torch.__version__}")
PY
# Keep the installed Torch build, and use PyPI for the adapter's other dependencies.
uv pip install --python "$venv/bin/python" \
    --constraint "$install_dir/torch-constraints.txt" \
    "bs-roformer-infer @ git+https://github.com/openmirlab/bs-roformer-infer.git@e3ad851fb24333ae36ae4bfd5ec327dbbf764bea"

if [[ ${3:-} == --download-weights ]]; then
    printf '%s\n' 'The weight licence is unknown. Check the terms for your intended use.'
    destination="$install_dir/weights"
    mkdir -p -- "$destination"
    scratch=$(mktemp -d "$destination/.download-XXXXXXXX")
    trap 'rm -rf -- "$scratch"' EXIT
    mirror=https://huggingface.co/koidn/BS-RoFormer-SW-6stem/resolve/833a625f574bc9db538447bcc3a43c0d2f28bd70
    for name in BS-Rofo-SW-Fixed.ckpt BS-Rofo-SW-Fixed.yaml; do
        url="$mirror/$name"
        printf 'Fetching %s\n' "$url"
        curl --fail --location --silent --show-error --retry 2 "$url" -o "$scratch/$name"
    done
    (
        cd "$scratch"
        sha256sum BS-Rofo-SW-Fixed.ckpt BS-Rofo-SW-Fixed.yaml | tee SHA256SUMS
        sha256sum --check <<'SUMS'
24e7d35ee9c64415673d3fd33e06a67cac2c103c5df6267ba1576459c775916e  BS-Rofo-SW-Fixed.ckpt
f9fada9f94e5ba2d2e4600196299459294bc5f532b314c209cc156ac63e4329b  BS-Rofo-SW-Fixed.yaml
SUMS
    )
    mv -- "$scratch/"{BS-Rofo-SW-Fixed.ckpt,BS-Rofo-SW-Fixed.yaml,SHA256SUMS} "$destination/"
fi

printf 'Adapter environment installed in %s\n' "$venv"
