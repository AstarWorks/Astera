"""poll.py <subnet-file> <out> <seconds>: poll display count over one RCON connection."""
import sys, time, socket, re, os, struct
sf, out, secs = sys.argv[1], sys.argv[2], float(sys.argv[3])
dbg = open(out + '.dbg', 'w')


def send(s, rid, kind, body):
    data = struct.pack('<ii', rid, kind) + body.encode() + b'\x00\x00'
    s.sendall(struct.pack('<i', len(data)) + data)


def rx(s, n):
    b = b''
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c:
            raise ConnectionError('closed')
        b += c
    return b


def recv(s):
    (n,) = struct.unpack('<i', rx(s, 4)); p = rx(s, n)
    rid, kind = struct.unpack('<ii', p[:8]); return rid, p[8:-2].decode('utf-8', 'replace')


deadline = time.time() + 2400
s = None
while time.time() < deadline:
    if os.path.exists(sf):
        host = open(sf).read().strip() + '.2'
        try:
            s = socket.create_connection((host, 25575), timeout=5)
            send(s, 1, 3, 'astera-shot'); rid, _ = recv(s)
            if rid != -1:
                break
            s = None
        except Exception as e:
            dbg.write(f'{time.time():.1f} {host} {e!r}\n'); dbg.flush()
            s = None
    time.sleep(2)
f = open(out, 'w'); i = 10; end = None
while s is not None:
    try:
        tot = 0
        for t in ('item', 'block', 'text'):
            i += 1; send(s, i, 2, f'execute if entity @e[type=minecraft:{t}_display]')
            _, b = recv(s)
            m = re.search(r'count: *(\d+)', b, re.I); tot += int(m.group(1)) if m else 0
    except Exception as e:
        f.write(f'# end {e!r}\n'); break
    f.write(f'{time.time():.3f} {tot}\n'); f.flush()
    if tot > 30 and end is None:
        end = time.time() + secs
    if end and time.time() > end:
        break
    time.sleep(0.05)
