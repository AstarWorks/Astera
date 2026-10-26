#!/usr/bin/env python3
"""vfx-shot reaper: removes capture clients and `astera-shot-*` stacks whose run is gone.

Ownership is decided by the per-stack lock `<state>/stack.lock` (flock): a live run holds it for its whole life (its
watchdog keeps holding it until the teardown after the run is done), so a stack whose lock can be taken has no live owner.
An unlocked stack is kept only while it is a deliberately kept warm stack (`SHOT_KEEP=1`: `<state>/keep-until` in the
future); a stack of the old script (no `<state>/owner`) is kept while it was active within the TTL. Everything else is
torn down: client process groups (portablemc + its java), Xvfb, the parker, and the compose project (containers,
volumes, network), found by docker labels so no compose file is needed.

  reap.py [--root ROOT] [--skip-state DIR] [--make-room N] [--dry]     # host sweep (vfx-shot start, make vfx-shot-reap)
  reap.py --only-state DIR [--force] [--lock-held] [--wait-lock S]     # one stack (watchdog, parker, make vfx-shot-down)

Never touches anything that is not ours: only processes of this uid that carry a vfx-shot client marker
(VFX_SHOT_STATE / XDG_RUNTIME_DIR under `.clients/`), parker.sh, or the exact Xvfb command line vfx-shot uses, and only
compose projects named `astera-shot-*` (never `astera-dev-*` / `astera-e2e-*`).
"""
import argparse
import calendar
import fcntl
import glob
import json
import os
import re
import signal
import socket
import subprocess
import sys
import time

NOW = time.time()
CLK = os.sysconf("SC_CLK_TCK")
UID = os.getuid()
PROJECT_RE = re.compile(r"^astera-shot-(?!probe-)[A-Za-z0-9_.-]+$")
CLIENT_DIR_RE = re.compile(r"^(.*)/\.clients/([^/]+)/[^/]+/?$")
XVFB_ARGV_RE = re.compile(r"^Xvfb -displayfd 1 -screen 0 \d+x\d+x24 -nolisten tcp$")

ap = argparse.ArgumentParser()
ap.add_argument("--root", help="only stacks / processes of this checkout")
ap.add_argument("--skip-state", default="", help="the calling run's own state dir")
ap.add_argument("--only-state", default="", help="reap just this state dir")
ap.add_argument("--force", action="store_true", help="--only-state: reap even a kept (SHOT_KEEP=1) stack")
ap.add_argument("--lock-held", action="store_true", help="--only-state: the caller already holds its stack lock")
ap.add_argument("--wait-lock", type=float, default=0, help="--only-state: wait this long for a live run to finish")
ap.add_argument("--make-room", type=int, default=0, help="evict idle kept stacks until fewer than N other stacks run")
ap.add_argument("--ttl", type=int, default=int(os.environ.get("SHOT_KEEP_TTL") or os.environ.get("SHOT_TIMEOUT") or 2700))
ap.add_argument("--timeout", type=int, default=int(os.environ.get("SHOT_TIMEOUT") or 2700))
ap.add_argument("--registry", default=os.environ.get("SHOT_REGISTRY", "/tmp/claude-1000/astera-vfx-shot/stacks"))
ap.add_argument("--dry", action="store_true", default=os.environ.get("SHOT_REAP_DRY") == "1")
A = ap.parse_args()


def log(msg):
    print(f"[vfx-shot reap] {'(dry) ' if A.dry else ''}{msg}", file=sys.stderr, flush=True)


def norm(p):
    return os.path.normpath(p) if p else p


def in_scope(path):
    return not A.root or (path or "").startswith(norm(A.root) + "/")


# ------------------------------------------------------------------ processes
def my_ancestors():
    out, p = set(), os.getpid()
    while p > 1:
        out.add(p)
        try:
            p = int(open(f"/proc/{p}/stat").read().rsplit(")", 1)[1].split()[1])
        except (OSError, ValueError, IndexError):
            break
    return out


SKIP_PIDS = my_ancestors()
UPTIME = float(open("/proc/uptime").read().split()[0])


def proc_info(pid):
    try:
        if os.stat(f"/proc/{pid}").st_uid != UID:
            return None
        stat = open(f"/proc/{pid}/stat").read().rsplit(")", 1)[1].split()
        argv = open(f"/proc/{pid}/cmdline", "rb").read().split(b"\0")
        env = open(f"/proc/{pid}/environ", "rb").read().split(b"\0")
    except OSError:
        return None
    argv = [a.decode(errors="replace") for a in argv if a]
    envd = {}
    for e in env:
        k, _, v = e.decode(errors="replace").partition("=")
        envd[k] = v
    try:
        cwd = os.readlink(f"/proc/{pid}/cwd")
    except OSError:
        cwd = ""
    return {
        "pid": pid, "ppid": int(stat[1]), "pgid": int(stat[2]), "state": stat[0],
        "age": UPTIME - int(stat[19]) / CLK, "argv": argv, "env": envd, "cwd": cwd,
    }


def scan_procs():
    """Every vfx-shot process of this uid -> (pid info, state dir or None, kind)."""
    found = []
    for d in os.listdir("/proc"):
        if not d.isdigit() or int(d) in SKIP_PIDS:
            continue
        p = proc_info(int(d))
        if not p or not p["argv"]:
            continue
        argv, env = p["argv"], p["env"]
        state, kind = None, None
        if env.get("VFX_SHOT_STATE"):
            state, kind = env["VFX_SHOT_STATE"], "client"
        elif CLIENT_DIR_RE.match(env.get("XDG_RUNTIME_DIR", "")):
            m = CLIENT_DIR_RE.match(env["XDG_RUNTIME_DIR"])
            state, kind = f"{m.group(1)}/.state/{m.group(2)}", "client"
        elif len(argv) >= 3 and os.path.basename(argv[0]) == "bash" and argv[1].endswith("/tools/vfx-shot/parker.sh"):
            state, kind = argv[2], "parker"
        elif os.path.basename(argv[0]) == "Xvfb" and XVFB_ARGV_RE.match(" ".join(["Xvfb", *argv[1:]])):
            kind = "xvfb"   # old launches: no marker, attributed through the XPID of a state's clients/*.env
        if kind:
            found.append((p, norm(state) if state else None, kind))
    return found


def pid_alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def kill_procs(infos, why):
    """TERM the process group of every session leader (setsid launches: portablemc + java, Xvfb) or the pid, then KILL."""
    targets = []
    for p in infos:
        if p["pid"] in SKIP_PIDS:
            continue
        grp = p["pgid"] == p["pid"] and p["pgid"] not in SKIP_PIDS and p["pgid"] != os.getpgid(0)
        targets.append((p["pid"], grp))
    if not targets:
        return
    log(f"killing {len(targets)} process(es) ({why}): " + " ".join(f"{'-' if g else ''}{pid}" for pid, g in targets))
    if A.dry:
        return
    for sig in (signal.SIGCONT, signal.SIGTERM):   # a parked client may be SIGSTOPped: thaw so it can die cleanly
        for pid, grp in targets:
            try:
                os.killpg(pid, sig) if grp else os.kill(pid, sig)
            except OSError:
                pass
    deadline = time.time() + 5
    while time.time() < deadline and any(pid_alive(pid) for pid, _ in targets):
        time.sleep(0.2)
    for pid, grp in targets:
        try:
            os.killpg(pid, signal.SIGKILL) if grp else os.kill(pid, signal.SIGKILL)
        except OSError:
            pass


# ------------------------------------------------------------------ docker
def docker(*args, check=False):
    r = subprocess.run(["docker", *args], capture_output=True, text=True)
    if check and r.returncode:
        raise RuntimeError(r.stderr.strip())
    return r.stdout


def docker_projects():
    """project -> {root, created (epoch), running}. Only astera-shot-*."""
    out = {}
    ids = docker("ps", "-aq", "--filter", "label=com.docker.compose.project").split()
    if not ids:
        return out
    try:
        info = json.loads(docker("inspect", *ids) or "[]")
    except json.JSONDecodeError:
        return out
    for c in info:
        lab = c.get("Config", {}).get("Labels") or {}
        proj = lab.get("com.docker.compose.project", "")
        if not PROJECT_RE.match(proj):
            continue
        files = lab.get("com.docker.compose.project.config_files", "").split(",")
        root = ""
        for f in files:
            if f.endswith("/tools/vfx-shot/compose.override.yml"):
                root = norm(f[: -len("/tools/vfx-shot/compose.override.yml")])
        created = c.get("Created", "")
        try:
            ts = calendar.timegm(time.strptime(created[:19], "%Y-%m-%dT%H:%M:%S"))
        except ValueError:
            ts = NOW
        e = out.setdefault(proj, {"root": root, "created": ts, "running": False})
        e["created"] = min(e["created"], ts)
        e["running"] |= bool(c.get("State", {}).get("Running"))
        e["root"] = e["root"] or root
    return out


def remove_project(proj, why):
    if not PROJECT_RE.match(proj):
        return
    log(f"removing compose project {proj} ({why})")
    if A.dry:
        return
    lab = f"label=com.docker.compose.project={proj}"
    me = socket.gethostname()
    for net in docker("network", "ls", "-q", "--filter", lab).split():
        docker("network", "disconnect", "-f", net, me)
    cts = docker("ps", "-aq", "--filter", lab).split()
    if cts:
        docker("rm", "-f", "-v", *cts)
    vols = docker("volume", "ls", "-q", "--filter", lab).split()
    if vols:
        docker("volume", "rm", "-f", *vols)
    for net in docker("network", "ls", "-q", "--filter", lab).split():
        docker("network", "rm", net)


# ------------------------------------------------------------------ states
def read_kv(path):
    d = {}
    try:
        for line in open(path):
            k, _, v = line.rstrip("\n").partition("=")
            d[k] = v
    except OSError:
        pass
    return d


def try_lock(state, wait=0.0):
    """-> open file holding the stack lock, or None when a live run holds it. A missing state dir counts as unlocked."""
    path = os.path.join(state, "stack.lock")
    if not os.path.isdir(state):
        return open(os.devnull, "a")
    f = open(path, "a")
    deadline = time.time() + wait
    while True:
        try:
            fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return f
        except OSError:
            if time.time() >= deadline:
                f.close()
                return None
            time.sleep(1)


def last_activity(state, clients_dir):
    newest = 0.0
    for pat in (f"{state}/*", f"{state}/clients/*", f"{clients_dir}/*/client.log"):
        for p in glob.glob(pat):
            if os.path.basename(p) == "stack.lock":
                continue
            try:
                newest = max(newest, os.stat(p).st_mtime)
            except OSError:
                pass
    return newest


def clients_dir_of(state):
    m = re.match(r"^(.*)/\.state/([^/]+)$", state)
    return f"{m.group(1)}/.clients/{m.group(2)}" if m else ""


class Stack:
    def __init__(self, state):
        self.state = state
        self.clients = clients_dir_of(state)
        self.owner = read_kv(os.path.join(state, "owner"))
        self.project = ""
        try:
            self.project = open(os.path.join(state, "project")).read().strip()
        except OSError:
            pass
        if not self.project:
            self.project = "astera-shot-" + os.path.basename(state)
        self.procs = []

    def keep_until(self):
        try:
            return float(open(os.path.join(self.state, "keep-until")).read().strip() or 0)
        except (OSError, ValueError):
            return 0.0

    def pinned(self):
        """The capture farm's stack (SHOT_PIN=1): a kept stack that other checkouts' runs never evict to make room (its TTL still applies)."""
        return os.path.exists(os.path.join(self.state, "pinned"))

    def recorded_pids(self):
        """XPID / CPID of clients/*.env, parker.pid."""
        pids = set()
        for f in glob.glob(f"{self.state}/clients/*.env"):
            kv = read_kv(f)
            for k in ("CPID", "XPID"):
                if kv.get(k, "").isdigit():
                    pids.add(int(kv[k]))
        try:
            pids.add(int(open(f"{self.state}/parker.pid").read().strip()))
        except (OSError, ValueError):
            pass
        return pids

    def teardown(self, why, project_known=True):
        kill_procs([p for p, _ in self.procs], f"{self.state}: {why}")
        if project_known:
            remove_project(self.project, why)
        if A.dry:
            return
        for pat in ("clients/*.env",):
            for f in glob.glob(f"{self.state}/{pat}"):
                os.unlink(f)
        for n in ("parker.on", "parker.pid", "capture.active", "keep-until", "pinned", "subnet", "deployed.json", "owner"):
            try:
                os.unlink(os.path.join(self.state, n))
            except OSError:
                pass
        try:
            os.unlink(os.path.join(A.registry, self.project))
        except OSError:
            pass


def collect():
    stacks = {}

    def get(state):
        state = norm(state)
        if state not in stacks:
            stacks[state] = Stack(state)
        return stacks[state]

    procs = scan_procs()
    xvfbs = []
    for p, state, kind in procs:
        if state:
            get(state).procs.append((p, kind))
        else:
            xvfbs.append(p)
    for f in glob.glob(f"{A.registry}/*"):
        try:
            get(open(f).read().strip())
        except OSError:
            pass
    projects = docker_projects()
    by_project = {}
    for proj, info in projects.items():
        st = None
        for s in stacks.values():
            if s.project == proj:
                st = s
        if not st and info["root"]:
            sid = proj[len("astera-shot-"):]
            cands = sorted(glob.glob(f"{info['root']}/build/*/.state/{sid}"))
            if cands:
                st = get(cands[0])
                st.project = proj
        by_project[proj] = st
    # old-style Xvfb: belongs to the state whose clients/*.env records it, else an orphan
    orphans = []
    for p in xvfbs:
        owner = next((s for s in stacks.values() if p["pid"] in s.recorded_pids()), None)
        if owner:
            owner.procs.append((p, "xvfb"))
        else:
            orphans.append(p)
    return stacks, projects, by_project, orphans


def decide(st, evict=False, force=False):
    """-> (reap?, reason)."""
    if force:
        return True, "forced"
    ku = st.keep_until()
    if st.pinned() and evict:
        return False, "pinned capture-farm stack"
    if ku > NOW and not evict:
        return False, f"kept (SHOT_KEEP=1) for another {int(ku - NOW)}s"
    if ku > NOW:
        return True, "kept stack evicted to make room"
    if st.owner:
        return True, "run is gone" if not ku else "kept stack past its TTL"
    idle = NOW - last_activity(st.state, st.clients)
    if idle < A.ttl and not evict:
        return False, f"old-style stack idle {int(idle)}s < TTL {A.ttl}s"
    return True, f"old-style stack idle {int(idle)}s" + (" (evicted to make room)" if evict else "")


def hung_owner(st):
    """A locked stack whose run is far past SHOT_TIMEOUT (its watchdog failed): kill the run so the lock frees."""
    o = st.owner
    try:
        start, pid = float(o.get("START", "")), int(o.get("PID", ""))
    except ValueError:
        return False
    limit = int(o.get("TIMEOUT") or A.timeout) + 600
    if NOW - start < limit or not pid_alive(pid):
        return False
    log(f"{st.state}: run {pid} has been running {int(NOW - start)}s (> SHOT_TIMEOUT + 600 s): killing it")
    if not A.dry:
        for k in ("WATCHDOG", "PID"):
            if o.get(k, "").isdigit():
                try:
                    os.kill(int(o[k]), signal.SIGKILL)
                except OSError:
                    pass
        time.sleep(2)
    return True


def main():
    stacks, projects, by_project, orphans = collect()
    reaped = set()

    def handle(st, evict=False, force=False, lock_held=False, wait=0.0):
        if not lock_held:
            lk = try_lock(st.state, wait)
            if lk is None and hung_owner(st):
                lk = try_lock(st.state, 5)
            if lk is None:
                log(f"{st.state}: live run holds the stack lock, skipped")
                return False
        ok, why = decide(st, evict, force)
        if not ok:
            if st.procs or st.project in projects:
                log(f"{st.state}: {why}, left alone")
            return False
        st.teardown(why, project_known=st.project in projects)
        reaped.add(st.project)
        return True

    if A.only_state:
        st = stacks.get(norm(A.only_state)) or Stack(norm(A.only_state))
        sys.exit(0 if handle(st, force=A.force, lock_held=A.lock_held, wait=A.wait_lock) else 1)

    skip = norm(A.skip_state)
    for state, st in sorted(stacks.items()):
        if state == skip or not in_scope(state):
            continue
        if not st.procs and st.project not in projects and not os.path.exists(os.path.join(state, "keep-until")):
            continue   # nothing running for this state
        handle(st)
    for proj, info in projects.items():
        if proj in reaped or (by_project.get(proj) and by_project[proj].state == skip):
            continue
        if A.root and info["root"] != norm(A.root):
            continue
        st = by_project.get(proj)
        if st is None:
            if not info["root"] or not os.path.isdir(info["root"]):
                remove_project(proj, "its checkout no longer exists")
                reaped.add(proj)
            elif NOW - info["created"] > A.timeout:
                remove_project(proj, f"no state dir found and older than SHOT_TIMEOUT ({A.timeout}s)")
                reaped.add(proj)
    for p in orphans:
        if A.root and p["cwd"] != norm(A.root):
            continue
        if p["ppid"] == 1 and p["age"] > A.timeout:
            kill_procs([p], f"orphan Xvfb, {int(p['age'])}s old")

    if A.make_room > 0:
        live = [pr for pr, info in projects.items() if info["running"] and pr not in reaped
                and not (by_project.get(pr) and by_project[pr].state == skip)
                and not (by_project.get(pr) and by_project[pr].pinned())]
        if len(live) >= A.make_room:
            cands = [by_project[pr] for pr in live if by_project.get(pr) and in_scope(by_project[pr].state)]
            cands.sort(key=lambda s: last_activity(s.state, s.clients))
            for st in cands:
                if len(live) < A.make_room:
                    break
                if handle(st, evict=True):
                    live.remove(st.project)
            if len(live) >= A.make_room:
                log(f"WARN: {len(live)} other shot stack(s) still running (live runs, or outside SHOT_REAP_SCOPE): {' '.join(live)}")


if __name__ == "__main__":
    main()
