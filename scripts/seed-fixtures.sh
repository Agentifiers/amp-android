#!/usr/bin/env bash
# Seeds the U08 flow fixtures on one emulator: a solid blue PNG in Pictures (attach-image.ad picks it from the
# Photo Picker) and a 1 MiB text file in Download (files-roundtrip.ad uploads it). Prints the text file's SHA-256,
# which the files-stored app event must report for the uploaded copy.
# Usage: scripts/seed-fixtures.sh <serial>
set -euo pipefail
serial="${1:?usage: $0 <serial>}"
source "$(dirname "$0")/device-guard.sh"
guard_device "$serial" "seed fixtures"

out="$(mktemp -d)"
python3 - "$out" <<'EOF'
import struct, sys, zlib
out = sys.argv[1]
w = h = 256
raw = b''.join(b'\x00' + bytes([0x1e, 0x5b, 0xd8]) * w for _ in range(h))
def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
open(f'{out}/amp-blue.png', 'wb').write(png)
text = bytearray()
i = 1
while True:
    line = f"roundtrip line {i:06d} the quick brown fox jumps over the lazy dog 0123456789\n".encode()
    if len(text) + len(line) > 1048576 - 64:
        break
    text += line
    i += 1
tail = f"roundtrip end after line {i - 1:06d}\n".encode()
text += b'#' * (1048576 - len(text) - len(tail) - 1) + b'\n' + tail
assert len(text) == 1048576
open(f'{out}/roundtrip-1mib.txt', 'wb').write(bytes(text))
EOF

adb -s "$serial" shell mkdir -p /sdcard/Pictures /sdcard/Download
adb -s "$serial" push "$out/amp-blue.png" /sdcard/Pictures/amp-blue.png >/dev/null 2>&1
adb -s "$serial" push "$out/roundtrip-1mib.txt" /sdcard/Download/roundtrip-1mib.txt >/dev/null 2>&1
for f in /sdcard/Pictures/amp-blue.png /sdcard/Download/roundtrip-1mib.txt; do
  adb -s "$serial" shell content call --method scan_file --uri content://media --arg "$f" >/dev/null 2>&1 \
    || adb -s "$serial" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$f" >/dev/null
done
shasum -a 256 "$out/roundtrip-1mib.txt" | cut -d' ' -f1
rm -rf "$out"
