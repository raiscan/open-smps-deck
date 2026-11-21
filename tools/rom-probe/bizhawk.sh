# Shared by the build and run entry points.
configure_bizhawk() {
    local default_home="${XDG_DATA_HOME:-$HOME/.local/share}/BizHawk-2.11-linux-x64"
    if ! command -v mono >/dev/null; then
        echo "Mono is required (install mono-devel)." >&2
        return 1
    fi
    local home="${BIZHAWK_HOME:-$default_home}"
    local file
    for file in BizHawk.Common.dll BizHawk.Emulation.Common.dll BizHawk.Emulation.Cores.dll \
        gpgx.wbx.zst libwaterboxhost.so; do
        if [[ ! -f "$home/dll/$file" ]]; then
            echo "Missing $file. Set BIZHAWK_HOME to an extracted BizHawk 2.11 Linux x64 release." >&2
            return 1
        fi
    done
    BIZHAWK_DLL=$(cd "$home/dll" && pwd -P)
    export MONO_PATH="$BIZHAWK_DLL" LD_LIBRARY_PATH="$BIZHAWK_DLL${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
}
