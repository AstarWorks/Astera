"""RCON director for `VIEW=compare`: casts the skill and runs the camera plan.

Realtime mode (default): the camera plan runs on the wall clock. Stepped mode (DIRECTOR_STEP_TICKS set): the server is
frozen (`/tick freeze`) and advanced one tick at a time (`/tick step 1`); after every step the director waits until the
viewer's screen shows the new tick in the stamp (the stamp datapack's boss bar) and then dwells so the client draws it at
least twice. The camera plan is applied by tick (t_ms / 50), right before the step into that tick. Output is then one
settled frame per tick whatever the render rate. Env: DIRECTOR_STEP_TICKS (ticks after the cast), DIRECTOR_STEP_PRE (ticks
before the cast), DIRECTOR_STEP_DISPLAY (X display, e.g. :0), DIRECTOR_STEP_SCREEN (WxH), DIRECTOR_STEP_GUI (gui scale),
DIRECTOR_STEP_DWELL (seconds, default 0.35).

usage: python3 -I director.py <host> <port> <password> <plan.tsv> <cast-command...>

plan.tsv: `t_ms<TAB>command` lines, t relative to the cast. One RCON connection; `STAMP <epoch>` goes to stderr just
before the cast is sent (same contract as rcon.py --stamp). Each plan command is sent when its time is due (hard
cuts: the camera is teleported once per key).
"""
import os
import re
import socket
import subprocess
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


def stamp_on_screen(display, screen_w, screen_h, gui):
    """Game time mod 1024 currently shown by the viewer's client, or None (one x11grab of the stamp rectangle)."""
    import numpy as np
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from stampcodec import decode_stamp, STAMP_HALF_W, STAMP_H
    x0 = max(0, screen_w // 2 - STAMP_HALF_W * gui)
    w = min(screen_w - x0, 2 * STAMP_HALF_W * gui)
    h = STAMP_H * gui
    out = subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-f", "x11grab", "-video_size", f"{w}x{h}",
                          "-i", f"{display}+{x0},0", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                         capture_output=True, timeout=20).stdout
    if len(out) != w * h * 3:
        return None
    return decode_stamp(np.frombuffer(out, np.uint8).reshape(h, w, 3))


def gametime(sock):
    return int(re.search(r"(\d+)\D*$", run(sock, "time query gametime")).group(1))


def main_stepped(sock, steps, cast):
    ticks, pre = int(os.environ["DIRECTOR_STEP_TICKS"]), int(os.environ.get("DIRECTOR_STEP_PRE", "0"))
    display = os.environ["DIRECTOR_STEP_DISPLAY"]
    sw, sh = (int(v) for v in os.environ["DIRECTOR_STEP_SCREEN"].split("x"))
    gui, dwell = int(os.environ.get("DIRECTOR_STEP_GUI", "1")), float(os.environ.get("DIRECTOR_STEP_DWELL", "0.35"))

    def shown(g, what):
        # the stamp function runs at the start of a tick, before the level time advances: with game time g the last executed
        # tick (and so the stamp on screen) is g - 1. A frame stamped s shows the world after tick s ran.
        deadline = time.time() + 30
        while time.time() < deadline:
            if stamp_on_screen(display, sw, sh, gui) == (g - 1) % 1024:
                time.sleep(dwell)
                return
        raise RuntimeError("the viewer never showed tick %d (%s)" % (g, what))

    def step(g):
        run(sock, "tick step 1")
        deadline = time.time() + 30
        while gametime(sock) < g + 1:
            if time.time() > deadline:
                raise RuntimeError("server did not step to tick %d" % (g + 1))
            time.sleep(0.02)
        shown(g + 1, "step")

    run(sock, "tick freeze")
    try:
        g = gametime(sock)
        shown(g, "freeze")
        for _ in range(pre):
            step(g)
            g += 1
        print("MARK_BEFORE %.3f %d" % (time.time(), g), file=sys.stderr, flush=True)
        print("STAMP %.3f" % time.time(), file=sys.stderr, flush=True)
        print(run(sock, cast), flush=True)
        print("CAST_ACK %.3f" % time.time(), file=sys.stderr, flush=True)
        g_cast = gametime(sock)
        print("MARK_AFTER %.3f %d" % (time.time(), g_cast), file=sys.stderr, flush=True)
        if g_cast != g:
            raise RuntimeError("game time moved while frozen (%d -> %d)" % (g, g_cast))
        pending = sorted((round(t_ms / 50.0), t_ms, cmd) for t_ms, cmd in steps)
        for k in range(ticks + 1):
            # tick g_cast + k executes in this step (frames stamped g_cast + k); a camera cut due at tick k goes out right before it
            while pending and pending[0][0] <= k:
                _d, t_ms, cmd = pending.pop(0)
                print("CUT_MARK %d %.3f %d" % (t_ms, time.time(), g), file=sys.stderr, flush=True)
                run(sock, cmd)
                print("CAMERA %d SENT %.3f ACK %.3f" % (t_ms, time.time(), time.time()), file=sys.stderr, flush=True)
            step(g)
            g += 1
        return 0
    finally:
        run(sock, "tick unfreeze")


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
        # Tick marks: the server game time right before and right after the cast, on this same connection.
        # RCON commands queued back to back normally run in the same server tick, so before == after pins the cast
        # tick exactly; a difference of one tick is recorded as the uncertainty.
        if os.environ.get("DIRECTOR_STEP_TICKS"):
            return main_stepped(sock, steps, cast)
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
