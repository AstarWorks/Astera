#!/usr/bin/env python3
"""Drives reap.py's host sweep with a stubbed docker project list (no docker, nothing removed) and prints the decisions.

  python3 -I tools/vfx-shot/reap-sweep-check.py        # exit 1 when a decision differs from the expected one
Cases: a live foreign stack (root invisible), a live foreign stack older than the grace, an exited foreign stack older than the
grace, an exited young foreign stack, a live project without a root label, a registered neighbour state dir that is invisible
and running, and the caller's own stack.
"""
import importlib.util
import os
import sys

here = os.path.dirname(os.path.abspath(__file__))
sys.argv = ["reap.py", "--dry"]
spec = importlib.util.spec_from_file_location("reap", os.path.join(here, "reap.py"))
reap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reap)

now, removed = reap.NOW, []
own_root = here.rsplit("/tools/", 1)[0]
projects = {
    "astera-shot-live1": {"root": "/nonexistent/w/aaa", "created": now - 600, "running": True},
    "astera-shot-liveold": {"root": "/nonexistent/w/bbb", "created": now - 5 * 3600, "running": True},
    "astera-shot-deadold": {"root": "/nonexistent/w/ccc", "created": now - 5 * 3600, "running": False},
    "astera-shot-deadnew": {"root": "/nonexistent/w/ddd", "created": now - 600, "running": False},
    "astera-shot-noroot": {"root": "", "created": now - 600, "running": True},
    "astera-shot-own": {"root": own_root, "created": now - 600, "running": True},
}
expect = {"astera-shot-deadold"}   # the only one the sweep may remove

st_reg = reap.Stack("/nonexistent/w/bbb/build/x/.state/liveold")   # registered neighbour state, dir invisible
st_reg.project = "astera-shot-liveold"
own = reap.Stack(os.path.join(own_root, "build/none/.state/own"))
own.project = "astera-shot-own"
stacks = {st_reg.state: st_reg}
by_project = {p: None for p in projects}
by_project["astera-shot-liveold"] = st_reg
by_project["astera-shot-own"] = own


def fake_remove(proj, why):
    removed.append(proj)
    print(f"DECISION remove {proj}: {why}")


reap.collect = lambda: (stacks, projects, by_project, [])
reap.remove_project = fake_remove
reap.A.skip_state = own.state
reap.main()
print("removed:", sorted(removed))
sys.exit(0 if set(removed) == expect else 1)
