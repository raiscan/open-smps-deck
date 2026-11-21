#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
source "$here/bizhawk.sh"
if [[ $# != 1 ]]; then
    echo "Usage: $0 BUILD_DIR" >&2
    exit 2
fi
configure_bizhawk
if ! command -v mcs >/dev/null; then
    echo "The Mono C# compiler mcs is required (install mono-devel)." >&2
    exit 1
fi
mkdir -p -- "$1"
build=$(cd -- "$1" && pwd -P)
if ! mcs -sdk:4.7.2 -langversion:7.2 -optimize+ -r:Facades/netstandard.dll -out:"$build/RomProbe.exe" \
    -r:"$BIZHAWK_DLL/BizHawk.Common.dll" \
    -r:"$BIZHAWK_DLL/BizHawk.Emulation.Common.dll" \
    -r:"$BIZHAWK_DLL/BizHawk.Emulation.Cores.dll" \
    "$here/RomProbe.cs" "$here/GameProfile.cs"; then
    echo "Could not compile against BizHawk 2.11. Check BIZHAWK_HOME and the Mono compiler." >&2
    exit 1
fi
mono "$build/RomProbe.exe" --check-version
printf 'Built %s\n' "$build/RomProbe.exe"
