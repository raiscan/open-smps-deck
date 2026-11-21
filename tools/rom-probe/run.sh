#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
if [[ ${1:-} == --help || ${1:-} == -h ]]; then
    exec python3 "$here/run.py" "$@"
fi
source "$here/bizhawk.sh"
configure_bizhawk
umask 077
unset DISPLAY WAYLAND_DISPLAY
exec python3 "$here/run.py" "$@"
