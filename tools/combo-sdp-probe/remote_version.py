#!/usr/bin/env python3
"""Ask a connected device for its LMP version and manufacturer.

BlueZ caches this from the first connection and never re-reads it, so it does
not show up in a btmon capture of a later connect. Here we send the HCI command
ourselves over a raw HCI socket, which needs CAP_NET_RAW.

Usage: remote_version.py <bdaddr>
"""
import socket
import struct
import sys
import threading
import time

HCI_CHANNEL_RAW = 0
HCI_FILTER = 2
OGF_LINK_CTL = 0x01
OCF_READ_REMOTE_VERSION = 0x001D
EVT_CMD_STATUS = 0x0F
EVT_READ_REMOTE_VERSION_COMPLETE = 0x0C

# Bluetooth SIG company identifiers seen on Roche pumps and common BT silicon.
COMPANIES = {
    0x000A: "Cambridge Silicon Radio (CSR) / Qualcomm",
    0x000F: "Broadcom",
    0x0002: "Intel",
    0x001D: "Qualcomm",
    0x005D: "Realtek",
    0x0499: "Ruuvi",
}

LMP_VERSIONS = {
    0x00: "Bluetooth 1.0b", 0x01: "Bluetooth 1.1", 0x02: "Bluetooth 1.2",
    0x03: "Bluetooth 2.0 + EDR", 0x04: "Bluetooth 2.1 + EDR",
    0x05: "Bluetooth 3.0 + HS", 0x06: "Bluetooth 4.0", 0x07: "Bluetooth 4.1",
    0x08: "Bluetooth 4.2", 0x09: "Bluetooth 5.0", 0x0A: "Bluetooth 5.1",
    0x0B: "Bluetooth 5.2", 0x0C: "Bluetooth 5.3", 0x0D: "Bluetooth 5.4",
}


def keep_link_up(addr, stop):
    """Hold an L2CAP connection so the ACL link exists while we query."""
    while not stop.is_set():
        try:
            s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
            s.settimeout(15)
            s.connect((addr, 1))          # SDP PSM, no authentication needed
            while not stop.is_set():
                time.sleep(0.2)
            s.close()
            return
        except Exception:
            time.sleep(0.5)


def main():
    addr = sys.argv[1] if len(sys.argv) > 1 else "00:0E:2F:80:9E:4B"

    stop = threading.Event()
    t = threading.Thread(target=keep_link_up, args=(addr, stop), daemon=True)
    t.start()
    time.sleep(4)                          # let the ACL link come up

    sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_RAW, socket.BTPROTO_HCI)
    sock.bind((0,))
    # struct hci_filter is {u32 type_mask; u32 event_mask[2]; u16 opcode;} and the
    # kernel expects its padded size of 16 bytes, not the packed 14.
    sock.setsockopt(socket.SOL_HCI, HCI_FILTER,
                    struct.pack("<IIIH2x", 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0))
    sock.settimeout(10)

    handle = 0x0100                        # first BR/EDR connection on this adapter
    opcode = (OGF_LINK_CTL << 10) | OCF_READ_REMOTE_VERSION
    pkt = struct.pack("<BHBH", 0x01, opcode, 2, handle)
    sock.send(pkt)
    print(f"sent Read Remote Version Information for handle {handle:#06x}")

    deadline = time.time() + 10
    while time.time() < deadline:
        try:
            data = sock.recv(260)
        except socket.timeout:
            break
        if len(data) < 3 or data[0] != 0x04:        # HCI event packet
            continue
        evt, plen = data[1], data[2]
        body = data[3:3 + plen]
        if evt == EVT_READ_REMOTE_VERSION_COMPLETE and len(body) >= 8:
            status, h, ver, mfr, sub = struct.unpack("<BHBHH", body[:8])
            print(f"\nstatus      : {status:#04x}")
            print(f"handle      : {h:#06x}")
            print(f"LMP version : {ver:#04x}  {LMP_VERSIONS.get(ver, 'unknown')}")
            print(f"manufacturer: {mfr:#06x} ({mfr})  {COMPANIES.get(mfr, 'see Bluetooth SIG company id list')}")
            print(f"subversion  : {sub:#06x} ({sub})")
            stop.set()
            return 0
        if evt == EVT_CMD_STATUS and len(body) >= 4 and body[0] != 0x00:
            print(f"command status error {body[0]:#04x}")

    print("no Read Remote Version Information Complete received")
    stop.set()
    return 1


if __name__ == "__main__":
    sys.exit(main())
