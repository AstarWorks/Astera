#!/usr/bin/env python3
"""Wait until an X display stops changing: screen_quiet.py DISPLAY VIDEO_SIZE [MAX_SECONDS] [THRESHOLD].

vfx-shot's warm-up cast leaves client-side particles / displays alive for seconds (a sleet's snow, an arc's sparks). They are
the "noise" framestamp.py measures before the cast and multiplies by 3 to decide whether the real cast changed the picture:
a shot filmed while the warm-up fx still drift can hide its own burst under that threshold ("no fx was ever drawn"). The
server cannot clear client particles, so the only honest signal is the picture itself: sample the display until two
consecutive 0.5 s samples differ by less than THRESHOLD (mean abs grey level of a 160 x 90 copy; a still scene is < 0.03).

Prints "quiet <diff> after <s>s" and exits 0, or "busy <diff> after <s>s" and exits 1 when MAX_SECONDS passed first.
"""
import subprocess
import sys
import time

import numpy as np

display, size = sys.argv[1], sys.argv[2]
limit = float(sys.argv[3]) if len(sys.argv) > 3 else 20.0
thr = float(sys.argv[4]) if len(sys.argv) > 4 else 0.04
GW, GH = 160, 90


def sample():
    r = subprocess.run(
        ["ffmpeg", "-nostdin", "-loglevel", "error", "-f", "x11grab", "-framerate", "4", "-video_size", size, "-i", display,
         "-vf", f"scale={GW}:{GH},format=gray", "-frames:v", "2", "-f", "rawvideo", "-"],
        capture_output=True, timeout=20)
    raw = r.stdout
    if len(raw) < 2 * GW * GH:
        return None
    a = np.frombuffer(raw[:GW * GH], np.uint8).astype(float)
    b = np.frombuffer(raw[GW * GH:2 * GW * GH], np.uint8).astype(float)
    return float(np.abs(a - b).mean())


t0 = time.time()
last, calm = None, 0
while True:
    d = sample()
    last = d if d is not None else last
    calm = calm + 1 if d is not None and d < thr else 0
    if calm >= 2:
        print(f"quiet {last:.3f} after {time.time() - t0:.1f}s")
        sys.exit(0)
    if time.time() - t0 > limit:
        print(f"busy {last if last is None else round(last, 3)} after {time.time() - t0:.1f}s")
        sys.exit(1)
