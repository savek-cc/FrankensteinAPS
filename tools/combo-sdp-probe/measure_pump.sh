#!/usr/bin/env bash
# One command per pump: wait until it answers, read its LMP version, then find out
# whether an aborted RFCOMM session strands its serial port record.
#
# Usage: measure_pump.sh <bdaddr>
set -u
cd "$(dirname "$0")/.."
ADDR="$1"

echo "=== $ADDR ==="

# The Combo blocks Bluetooth while its display is lit, so wait it out first.
python3 - "$ADDR" <<'PY'
import sys, time
sys.path.insert(0, 'bench')
from sdp_probe import probe_spp
addr = sys.argv[1]
for i in range(40):
    try:
        r = probe_spp(addr)
        print(f"reachable after {i+1} attempt(s): records={r['records']} channel={r['channel']} name={r['name']!r}")
        sys.exit(0)
    except Exception:
        time.sleep(8)
print("never became reachable")
sys.exit(1)
PY
[ $? -ne 0 ] && exit 1

echo
echo "--- LMP version ---"
for a in 00:0E:2F:80:9E:4B 00:0E:2F:78:5D:75 00:0E:2F:EA:13:5D 00:0E:2F:9E:E0:B4 "$ADDR"; do
    bluetoothctl disconnect "$a" > /dev/null 2>&1
done
sleep 4
timeout 150 python3 bench/remote_version.py "$ADDR"

echo
echo "--- aborted session: does the record survive? ---"
timeout 400 python3 bench/stress.py rfcomm 12 "$ADDR"
