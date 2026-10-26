"""RCON director for `VIEW=compare`: casts the skill and runs the camera plan.

Realtime mode (default): the camera plan runs on the wall clock. Stepped mode (DIRECTOR_STEP_TICKS set): the server is
frozen (`/tick freeze`) and advanced one tick at a time (`/tick step 1`); after every step the director waits until the
viewer's screen shows the new tick in the stamp (the stamp datapack's boss bar) and then dwells so the client draws it at
least twice. The camera plan is applied by tick (t_ms / 50), right before the step into that tick. Output is then one
settled frame per tick whatever the render rate. Env: DIRECTOR_STEP_TICKS (ticks after the cast), DIRECTOR_STEP_PRE (ticks
before the cast), DIRECTOR_STEP_DISPLAY (X display, e.g. :0), DIRECTOR_STEP_SCREEN (WxH), DIRECTOR_STEP_GUI (gui scale),
DIRECTOR_STEP_DWELL (seconds, default 0.35), DIRECTOR_STEP_POLL_FPS (persistent stamp grabber rate, default 60; 0 = one ffmpeg per poll).

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


class StampWatcher:
    """One long-lived ffmpeg x11grab of the stamp rectangle (rawvideo on a pipe) and a reader thread that keeps the newest
    decoded stamp: waiting for a tick is a condition wait on that thread instead of spawning ffmpeg per poll (a spawn costs
    0.1-0.3 s on the loaded capture host, per tick). `latest` = (stamp or None, monotonic time the frame was read)."""

    def __init__(self, display, screen_w, screen_h, gui, fps):
        import threading
        import numpy as np
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        from stampcodec import decode_stamp, STAMP_HALF_W, STAMP_H
        self._np, self._decode = np, decode_stamp
        self.x0 = max(0, screen_w // 2 - STAMP_HALF_W * gui)
        self.w = min(screen_w - self.x0, 2 * STAMP_HALF_W * gui)
        self.h = STAMP_H * gui
        self.latest = (None, 0.0)
        self.cond = threading.Condition()
        self.dead = False
        self.proc = subprocess.Popen(
            ["ffmpeg", "-nostdin", "-loglevel", "error", "-f", "x11grab", "-framerate", str(fps), "-video_size", f"{self.w}x{self.h}",
             "-i", f"{display}+{self.x0},0", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        size = self.w * self.h * 3
        while True:
            buf = self.proc.stdout.read(size)
            if len(buf) < size:
                break
            v = self._decode(self._np.frombuffer(buf, self._np.uint8).reshape(self.h, self.w, 3))
            with self.cond:
                self.latest = (v, time.monotonic())
                self.cond.notify_all()
        with self.cond:
            self.dead = True
            self.cond.notify_all()

    def wait_for(self, want, timeout):
        """True once a frame shows stamp `want` (checked on frames read from now on or already read)."""
        end = time.monotonic() + timeout
        with self.cond:
            while self.latest[0] != want:
                left = end - time.monotonic()
                if left <= 0 or self.dead:
                    return False
                self.cond.wait(min(left, 0.25))
            return True

    def close(self):
        try:
            self.proc.terminate()
            self.proc.wait(timeout=5)
        except Exception:
            self.proc.kill()


def gametime(sock):
    return int(re.search(r"(\d+)\D*$", run(sock, "time query gametime")).group(1))


def main_stepped(sock, steps, cast):
    ticks, pre = int(os.environ["DIRECTOR_STEP_TICKS"]), int(os.environ.get("DIRECTOR_STEP_PRE", "0"))
    display = os.environ["DIRECTOR_STEP_DISPLAY"]
    sw, sh = (int(v) for v in os.environ["DIRECTOR_STEP_SCREEN"].split("x"))
    gui, dwell = int(os.environ.get("DIRECTOR_STEP_GUI", "1")), float(os.environ.get("DIRECTOR_STEP_DWELL", "0.35"))

    poll_fps = int(os.environ.get("DIRECTOR_STEP_POLL_FPS", "60"))
    watcher = StampWatcher(display, sw, sh, gui, poll_fps) if poll_fps > 0 else None
    stats = {"n": 0, "wait": 0.0, "max": 0.0}

    def shown(g, what):
        # the stamp function runs at the start of a tick, before the level time advances: with game time g the last executed
        # tick (and so the stamp on screen) is g - 1. A frame stamped s shows the world after tick s ran.
        t0 = time.monotonic()
        if watcher is not None:
            ok = watcher.wait_for((g - 1) % 1024, 30)
        else:
            ok = False
            deadline = time.time() + 30
            while time.time() < deadline:
                if stamp_on_screen(display, sw, sh, gui) == (g - 1) % 1024:
                    ok = True
                    break
        if not ok:
            raise RuntimeError("the viewer never showed tick %d (%s; server game time %d)" % (g, what, gametime(sock)))
        waited = time.monotonic() - t0
        stats["n"] += 1
        stats["wait"] += waited
        stats["max"] = max(stats["max"], waited)
        time.sleep(dwell)

    def step(g):
        # no gametime poll: the viewer showing the new tick's stamp proves the server stepped (and the poll costs an RCON round trip)
        run(sock, "tick step 1")
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
        if watcher is not None:
            watcher.close()
        if stats["n"]:
            print("STEP_STATS n=%d mean_wait_ms=%.0f max_wait_ms=%.0f dwell_ms=%.0f" % (
                stats["n"], 1000 * stats["wait"] / stats["n"], 1000 * stats["max"], 1000 * dwell), file=sys.stderr, flush=True)


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
