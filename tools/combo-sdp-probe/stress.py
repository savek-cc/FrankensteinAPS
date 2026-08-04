#!/usr/bin/env python3
"""Try to make the pump drop its SPP record.

Runs a chosen kind of abuse against the pump and checks after every round
whether it still answers with a Serial Port record. Stops as soon as the
record disappears - that is the failure mode we are hunting.

  abort N     connect, start an SDP request, tear the link down mid-transaction
  churn N     connect and disconnect as fast as possible, no SDP at all
  rfcomm N    open the RFCOMM data channel and drop it without talking
"""
import socket
import struct
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from sdp_probe import PSM_SDP, UUID_SPP, probe_spp  # noqa: E402

ADDR = "00:0E:2F:80:9E:4B"


def l2cap(addr, psm, timeout=10.0):
    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
    s.settimeout(timeout)
    s.connect((addr, psm))
    return s


def abort_mid_sdp(addr):
    """Send a service search request, then close without reading the answer."""
    s = l2cap(addr, PSM_SDP)
    pattern = bytes([0x35, 0x03, 0x19]) + struct.pack(">H", UUID_SPP)
    params = pattern + struct.pack(">H", 32) + b"\x00"
    s.send(struct.pack(">BHH", 0x02, 1, len(params)) + params)
    s.close()                                   # no recv on purpose


def churn(addr):
    """Establish and drop the L2CAP link without sending anything."""
    l2cap(addr, PSM_SDP).close()


def rfcomm_drop(addr):
    """Open the actual data channel and drop it without any protocol exchange."""
    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    s.settimeout(15.0)
    try:
        s.connect((addr, 1))
    finally:
        s.close()


MODES = {"abort": abort_mid_sdp, "churn": churn, "rfcomm": rfcomm_drop}


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "abort"
    rounds = int(sys.argv[2]) if len(sys.argv) > 2 else 50
    addr = sys.argv[3] if len(sys.argv) > 3 else ADDR
    action = MODES[mode]

    print(f"mode={mode} rounds={rounds} addr={addr}")
    before = probe_spp(addr)
    print(f"before: {before}")

    errors = 0
    for i in range(1, rounds + 1):
        try:
            action(addr)
        except Exception as e:
            errors += 1
            if errors <= 5:
                print(f"  round {i}: {type(e).__name__}: {e}")

        if i % 10 == 0:
            try:
                r = probe_spp(addr)
                status = "OK " if r["records"] else "*** NO SPP RECORD ***"
                print(f"{time.strftime('%H:%M:%S')}  after {i:>4} rounds "
                      f"({errors} errors): {status} records={r['records']} in {r['seconds']}s")
                if not r["records"]:
                    print("REPRODUCED - pump no longer offers Serial Port")
                    return 2
            except Exception as e:
                print(f"{time.strftime('%H:%M:%S')}  after {i:>4} rounds: "
                      f"probe FAILED: {type(e).__name__}: {e}")

    print(f"done; {errors} errors in {rounds} rounds; record still present")
    return 0


if __name__ == "__main__":
    sys.exit(main())
