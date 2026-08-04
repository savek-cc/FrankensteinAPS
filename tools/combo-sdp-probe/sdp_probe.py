#!/usr/bin/env python3
"""Direct SDP probe for the Combo pump, bypassing every cache.

Speaks SDP over L2CAP PSM 1 by hand, so the answer reflects what the pump says
right now - which is exactly the thing that could not be checked on Android.

  browse   enumerate every service record the pump offers
  spp      ask only for Serial Port (UUID 0x1101); this is the failure condition
  watch N  run the spp probe N times and report how the answer changes
"""
import socket
import struct
import sys
import time

PSM_SDP = 1
PDU_SERVICE_SEARCH_REQ = 0x02
PDU_SERVICE_SEARCH_RSP = 0x03
PDU_SERVICE_ATTR_REQ = 0x04
PDU_SERVICE_ATTR_RSP = 0x05
PDU_ERROR_RSP = 0x01

UUID_SPP = 0x1101
UUID_PUBLIC_BROWSE_GROUP = 0x1002

ATTR_SERVICE_RECORD_HANDLE = 0x0000
ATTR_SERVICE_CLASS_ID_LIST = 0x0001
ATTR_PROTOCOL_DESCRIPTOR_LIST = 0x0004
ATTR_SERVICE_NAME = 0x0100


class SdpError(Exception):
    pass


def connect(addr, timeout=20.0):
    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
    s.settimeout(timeout)
    s.connect((addr, PSM_SDP))
    return s


def _txn(sock, pdu_id, params, tid):
    sock.send(struct.pack(">BHH", pdu_id, tid, len(params)) + params)
    data = sock.recv(4096)
    if len(data) < 5:
        raise SdpError(f"short response ({len(data)} bytes)")
    rsp_id, rsp_tid, plen = struct.unpack(">BHH", data[:5])
    if rsp_id == PDU_ERROR_RSP:
        raise SdpError(f"SDP error response, code {struct.unpack('>H', data[5:7])[0]:#06x}")
    return rsp_id, data[5:5 + plen]


def service_search(sock, uuid16, tid=1, max_records=32):
    """Returns the list of service record handles matching the UUID."""
    pattern = bytes([0x35, 0x03, 0x19]) + struct.pack(">H", uuid16)
    handles, cont = [], b"\x00"
    while True:
        _, params = _txn(sock, PDU_SERVICE_SEARCH_REQ,
                         pattern + struct.pack(">H", max_records) + cont, tid)
        total, current = struct.unpack(">HH", params[:4])
        body = params[4:4 + current * 4]
        handles += [struct.unpack(">I", body[i:i + 4])[0] for i in range(0, len(body), 4)]
        cont_len = params[4 + current * 4]
        if cont_len == 0:
            return total, handles
        cont = params[4 + current * 4:5 + current * 4 + cont_len]
        tid += 1


def service_attributes(sock, handle, tid=100):
    """Returns the raw attribute list blob for one service record."""
    attr_range = bytes([0x35, 0x05, 0x0A, 0x00, 0x00, 0xFF, 0xFF])
    blob, cont = b"", b"\x00"
    while True:
        req = struct.pack(">I", handle) + struct.pack(">H", 0x0400) + attr_range + cont
        _, params = _txn(sock, PDU_SERVICE_ATTR_REQ, req, tid)
        count = struct.unpack(">H", params[:2])[0]
        blob += params[2:2 + count]
        cont_len = params[2 + count]
        if cont_len == 0:
            return blob
        cont = params[2 + count:3 + count + cont_len]
        tid += 1


def parse_de(buf, off=0):
    """Minimal SDP data element parser -> (value, next_offset)."""
    hdr = buf[off]
    typ, size_idx = hdr >> 3, hdr & 0x07
    off += 1
    if size_idx < 5:
        # Size index 0..4 means 1/2/4/8/16 bytes. The nil type is the sole exception:
        # it carries no data at all.
        if typ == 0:
            return None, off
        size = (1, 2, 4, 8, 16)[size_idx]
    elif size_idx == 5:
        size = buf[off]; off += 1
    elif size_idx == 6:
        size = struct.unpack(">H", buf[off:off + 2])[0]; off += 2
    else:
        size = struct.unpack(">I", buf[off:off + 4])[0]; off += 4

    raw = buf[off:off + size]
    end = off + size

    if typ == 1:                                   # unsigned int
        return int.from_bytes(raw, "big"), end
    if typ == 2:                                   # signed int
        return int.from_bytes(raw, "big", signed=True), end
    if typ == 3:                                   # UUID
        return int.from_bytes(raw, "big") if size <= 4 else raw.hex(), end
    if typ == 4:                                   # text
        return raw.decode("utf-8", "replace"), end
    if typ == 5:                                   # boolean
        return bool(raw[0]), end
    if typ in (6, 7):                              # sequence / alternative
        items, o = [], off
        while o < end:
            v, o = parse_de(buf, o)
            items.append(v)
        return items, end
    return raw.hex(), end


def attr_map(blob):
    """The attribute list is a DES of alternating attribute-id / value pairs."""
    lists, _ = parse_de(blob, 0)
    if lists and isinstance(lists[0], list):
        lists = lists[0]
    return {lists[i]: lists[i + 1] for i in range(0, len(lists) - 1, 2)}


def rfcomm_channel(attrs):
    proto = attrs.get(ATTR_PROTOCOL_DESCRIPTOR_LIST)
    if not isinstance(proto, list):
        return None
    for layer in proto:
        if isinstance(layer, list) and layer and layer[0] == 0x0003 and len(layer) > 1:
            return layer[1]
    return None


def probe_spp(addr):
    """The single question that matters: does the pump offer Serial Port right now?"""
    t0 = time.time()
    sock = connect(addr)
    try:
        total, handles = service_search(sock, UUID_SPP)
        result = {"records": total, "handles": handles, "channel": None, "name": None}
        if handles:
            attrs = attr_map(service_attributes(sock, handles[0]))
            result["channel"] = rfcomm_channel(attrs)
            result["name"] = attrs.get(ATTR_SERVICE_NAME)
        result["seconds"] = round(time.time() - t0, 3)
        return result
    finally:
        sock.close()


def main():
    addr = sys.argv[2] if len(sys.argv) > 2 else "00:0E:2F:80:9E:4B"
    mode = sys.argv[1] if len(sys.argv) > 1 else "spp"

    if mode == "browse":
        sock = connect(addr)
        try:
            total, handles = service_search(sock, UUID_PUBLIC_BROWSE_GROUP)
            print(f"public browse group: {total} record(s)")
            for h in handles:
                attrs = attr_map(service_attributes(sock, h))
                print(f"\n  handle {h:#010x}")
                for aid in sorted(attrs):
                    print(f"    attr {aid:#06x} = {attrs[aid]!r}")
        finally:
            sock.close()

    elif mode == "spp":
        print(probe_spp(addr))

    elif mode == "watch":
        n = int(sys.argv[3]) if len(sys.argv) > 3 else 10
        for i in range(n):
            try:
                r = probe_spp(addr)
                print(f"{time.strftime('%H:%M:%S')}  #{i + 1:<3} records={r['records']} "
                      f"channel={r['channel']} name={r['name']!r} in {r['seconds']}s")
            except Exception as e:
                print(f"{time.strftime('%H:%M:%S')}  #{i + 1:<3} FAILED: {type(e).__name__}: {e}")
            time.sleep(2)


if __name__ == "__main__":
    main()
