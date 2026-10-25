"""RCON director for `VIEW=compare`: casts the skill and runs the camera plan on a wall clock.

usage: python3 -I director.py <host> <port> <password> <plan.tsv> <cast-command...>

plan.tsv: `t_ms<TAB>command` lines, t relative to the cast. One RCON connection; `STAMP <epoch>` goes to stderr just
before the cast is sent (same contract as rcon.py --stamp). Each plan command is sent when its time is due (hard
cuts: the camera is teleported once per key).
"""
import os
import socket
import struct
import sys
import time


def send(sock, req_id, kind, body):
    data = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(data)) + data)


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("rcon closed")
        buf += chunk
    return buf


def recv(sock):
    (length,) = struct.unpack("<i", recv_exact(sock, 4))
    payload = recv_exact(sock, length)
    req_id, kind = struct.unpack("<ii", payload[:8])
    return req_id, kind, payload[8:-2].decode("utf-8", "replace")


def run(sock, command):
    send(sock, 2, 2, command)
    # Cast and teleport replies fit in one packet. The one-shot client's empty
    # probe is unsuitable here: this connection must survive subsequent cuts.
    while True:
        rid, _k, body = recv(sock)
        if rid == 2:
            return body


def main():
    host, port, password, plan = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
    cast = " ".join(sys.argv[5:])
    steps = []
    for line in open(plan):
        if line.strip():
            t, cmd = line.rstrip("\n").split("\t", 1)
            steps.append((int(t), cmd))
    steps.sort(key=lambda s: s[0])
    with socket.create_connection((host, port), timeout=10) as sock:
        send(sock, 1, 3, password)
        if recv(sock)[0] == -1:
            print("rcon: bad password", file=sys.stderr)
            return 2
        # Clapperboard marks: the server game time right before and right after the cast, on this same connection.
        # RCON commands queued back to back normally run in the same server tick, so before == after pins the cast
        # tick exactly; a difference of one tick is recorded as the uncertainty.
        mark = os.environ.get("DIRECTOR_MARK_CMD", "")
        if mark:
            print("MARK_BEFORE %.3f %s" % (time.time(), run(sock, mark).strip()), file=sys.stderr, flush=True)
        t0 = time.time()
        print("STAMP %.3f" % t0, file=sys.stderr, flush=True)
        print(run(sock, cast), flush=True)
        print("CAST_ACK %.3f" % time.time(), file=sys.stderr, flush=True)
        if mark:
            print("MARK_AFTER %.3f %s" % (time.time(), run(sock, mark).strip()), file=sys.stderr, flush=True)
        for t_ms, cmd in steps:
            wait = t0 + t_ms / 1000.0 - time.time()
            if wait > 0:
                time.sleep(wait)
            if mark:
                print("CUT_MARK %d %.3f %s" % (t_ms, time.time(), run(sock, mark).strip()), file=sys.stderr, flush=True)
            sent = time.time()
            run(sock, cmd)
            print("CAMERA %d SENT %.3f ACK %.3f" % (t_ms, sent, time.time()), file=sys.stderr, flush=True)
    return 0


sys.exit(main())
