#!/usr/bin/env bash
# Creates the TriFold-sized AVD the flows run on: 2160x1584 at 320 dpi (1080x792dp), landscape, Android 16.
# The emulator reports no folding features for this AVD; flows use the geometry-override app event for creases.
# Usage: scripts/create-trifold-avd.sh [avd-name]
set -euo pipefail
name="${1:-TriFold_Inner_API36}"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
image='system-images;android-36;google_apis;arm64-v8a'
export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"

"$sdk/cmdline-tools/latest/bin/sdkmanager" --install "$image" >/dev/null < <(yes)
echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -n "$name" -k "$image" -d medium_tablet --force >/dev/null

config="$HOME/.android/avd/$name.avd/config.ini"
python3 - "$config" <<'EOF'
import sys
path = sys.argv[1]
values = {
    "hw.lcd.width": "2160", "hw.lcd.height": "1584", "hw.lcd.density": "320",
    "hw.initialOrientation": "landscape", "hw.keyboard": "yes", "hw.ramSize": "4096",
    "disk.dataPartition.size": "6G", "skin.name": "2160x1584", "skin.path": "_no_skin",
    "showDeviceFrame": "no",
}
drop = ("hw.sensor.hinge", "hw.sensor.posture")
lines = [l for l in open(path).read().splitlines()
         if l.split("=")[0].strip() not in values and not l.startswith(drop)]
lines += [f"{k}={v}" for k, v in values.items()]
open(path, "w").write("\n".join(lines) + "\n")
EOF
echo "created $name; start with: \$ANDROID_HOME/emulator/emulator -avd $name -port 5584 -no-snapshot -no-audio"
