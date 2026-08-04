#!/usr/bin/env python3
"""The realistic version of the abort test.

Instead of just opening and dropping a bare RFCOMM channel, this runs a real
comboctl session against the pump and kills it mid-flight - which is what a
range loss does: the link dies while the application layer session is live, so
the pump never receives CTRL_DISCONNECT.

Then it watches whether the pump re-registers its serial port record.

Usage: applayer_kill_test.py <bdaddr> [rounds]
"""
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__)))
from sdp_probe import probe_spp  # noqa: E402

COMBOCTL = "/storage/projects/ComboCtl"
TOOL = f"{COMBOCTL}/bolusCancelTest/build/install/bolusCancelTest/bin/bolusCancelTest"
STATE = f"{COMBOCTL}/bench-pumps.json"
JAVA_HOME = "/usr/lib/jvm/temurin-17-jdk"
LOG = "/tmp/applayer_kill_session.log"


def probe(addr, tries=3):
    for _ in range(tries):
        try:
            return probe_spp(addr)
        except Exception:
            time.sleep(2)
    return None


def run_round(addr, n):
    print(f"\n=== round {n} ===", flush=True)

    before = probe(addr)
    print(f"before: {before}", flush=True)
    if not before or not before["records"]:
        print("pump is not offering the record to begin with - aborting round", flush=True)
        return None

    # This pump accepts RFCOMM only intermittently, so keep trying to get a
    # session going before concluding anything.
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    live = False
    for attempt in range(1, 9):
        with open(LOG, "w") as log:
            proc = subprocess.Popen(
                [TOOL, "--state", STATE, "poll", "--address", addr.lower(), "--seconds", "120"],
                stdout=log, stderr=subprocess.STDOUT, cwd=COMBOCTL, env=env,
            )
        for _ in range(45):
            time.sleep(2)
            try:
                txt = open(LOG, errors="replace").read()
            except FileNotFoundError:
                continue
            if "deliveryState" in txt or "NOT_DELIVERING" in txt or "DELIVERING" in txt:
                live = True
                break
            if "failed=true" in txt or proc.poll() is not None:
                break
        if live:
            print(f"session came up on attempt {attempt}", flush=True)
            break
        print(f"  attempt {attempt}: session did not come up", flush=True)
        proc.kill()
        subprocess.run(["pgrep", "-x", "java"], capture_output=True, text=True)
        time.sleep(6)

    if not live:
        print("could not get a session going at all:", flush=True)
        print(open(LOG, errors="replace").read()[-400:], flush=True)
        return None

    print("session is live and polling the pump", flush=True)
    time.sleep(3)

    killed = subprocess.run(["pgrep", "-x", "java"], capture_output=True, text=True).stdout.split()
    for pid in killed:
        subprocess.run(["kill", "-9", pid])
    print(f"SIGKILL sent to java {killed} - link dies with the session still open", flush=True)

    stranded_for = None
    for i in range(19):
        r = probe(addr, tries=1)
        state = "unreachable" if r is None else f"records={r['records']}"
        print(f"  +{i * 5}s: {state}", flush=True)
        if r is not None:
            if r["records"] == 0 and stranded_for is None:
                stranded_for = i * 5
            elif r["records"] == 1:
                stranded_for = None
        time.sleep(5)

    if stranded_for is None:
        print("VERDICT: record survived an aborted application layer session", flush=True)
        return True
    print(f"VERDICT: STRANDED (still gone {90 - stranded_for}s after it first vanished)", flush=True)
    return False


def main():
    addr = sys.argv[1]
    rounds = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    results = []
    for n in range(1, rounds + 1):
        results.append(run_round(addr, n))
    print(f"\nresults: {results}", flush=True)
    return 0 if all(r is True for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
