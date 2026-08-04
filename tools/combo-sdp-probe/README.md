# Combo SDP probe

Asks a Combo pump directly whether it currently offers its Serial Port service, by speaking SDP over
L2CAP PSM 1 by hand. Nothing caches the answer, which is what makes it useful: Android's own service
discovery gives the same result but buries it in HCI logs that are off by default, and
`BluetoothDevice.getUuids()` returns a cached list that this query does not refresh.

Written to diagnose the failure mode described under "Combo stops offering its serial port service"
in `KNOWN-ISSUES.md`. Runs on Linux with plain CPython — no PyBluez, no root.

## Usage

```
python3 sdp_probe.py spp    [addr]     # the one question that matters
python3 sdp_probe.py browse [addr]     # every service record the pump offers
python3 sdp_probe.py watch  [addr] [n] # repeat the spp probe n times
```

A healthy pump answers:

```
{'records': 1, 'handles': [65536], 'channel': 1, 'name': 'SerialLink', 'seconds': 0.041}
```

`records: 0` means the pump is reachable but offers no serial port — either because a session is
open right now, or because it stranded one and needs a button press.

Note that the Combo does not list itself in the public browse group, so `browse` returns nothing.
Only the targeted query for UUID `0x1101` finds the record.

## Reproducing the failure

`stress.py` provokes the fault and checks after each round whether the record survived.

```
python3 stress.py rfcomm 40   # open the data channel and drop it; one round is enough
python3 stress.py abort  60   # abort SDP mid-transaction; no effect, kept as a control
python3 stress.py churn  60   # connect/disconnect L2CAP without SDP
```

**This leaves the pump unusable until someone presses one of its buttons.** Only run it against a
bench pump that is not connected to anyone.
