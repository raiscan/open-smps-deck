#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
root=$PWD
jar="$root/studio/target/opensmpsdeck-studio-0.1.0-SNAPSHOT.jar"
classes="$root/studio/target/test-classes"
# The reactor output supplies the public stock resources used in native previews.
headless_resources="$root/headless/target/classes"
if [[ ! -f "$jar" || ! -f "$classes/com/opensmpsdeck/studio/StudioSurfaceCapture.class" ||
      ! -f "$headless_resources/stock/s3k.json" ]]; then
    echo "Build the Studio capture classes first with mvn -B -pl studio -am package." >&2
    exit 1
fi

scratch_root=${STUDIO_CAPTURE_SCRATCH:-"$HOME/scratch/agent-tmp/runtime"}
mkdir -p "$scratch_root"
scratch=$(mktemp -d "$scratch_root/task-XXXXXXXX")
trap 'rm -rf -- "$scratch"' EXIT

for size in 1920x1080 1366x768; do
    width=${size%x*}
    height=${size#*x}
    profile="$scratch/$size"
    mkdir -p "$profile"
    java_args=(
        --enable-native-access=ALL-UNNAMED
        "-Djava.io.tmpdir=$profile" "-Duser.home=$profile" "-Djava.util.prefs.userRoot=$profile"
        "-Dstudio.capture.width=$width" "-Dstudio.capture.height=$height"
        "-Dglass.gtk.uiScale=1" "-Dprism.order=sw"
    )
    display=()
    if command -v xvfb-run >/dev/null; then
        display=(xvfb-run -a -s "-screen 0 ${size}x24")
        java_args+=("-Dstudio.capture.isolatedDisplay=true")
    fi
    XDG_CONFIG_HOME="$profile/config" XDG_DATA_HOME="$profile/data" XDG_CACHE_HOME="$profile/cache" \
        TMPDIR="$profile" "${display[@]}" env HOME="$profile" java "${java_args[@]}" \
        -cp "$classes:$jar:$headless_resources" com.opensmpsdeck.studio.StudioSurfaceCapture "$@"
done
