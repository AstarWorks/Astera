"""Minimal RCON client (Source RCON protocol), one connection per invocation.

usage: python3 -I rcon.py <host> <port> <password> [--stamp] <command...>

Prints the command's reply (ANSI colour stripped) on stdout. With --stamp, prints
`STAMP <epoch-seconds>` on stderr immediately before the command is sent -- the capture
script uses it to align video frames with the server tick the command lands on.
"""
import re
import socket
import struct
import sys
import time

ANSI = re.compile(r"\x1b\[[0-9;]*m|\u00a7.")


def send(sock: socket.socket, req_id: int, kind: int, body: str) -> None:
    data = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(data)) + data)


def recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("rcon closed")
        buf += chunk
    return buf


def recv(sock: socket.socket) -> tuple[int, int, str]:
    (length,) = struct.unpack("<i", recv_exact(sock, 4))
    payload = recv_exact(sock, length)
    req_id, kind = struct.unpack("<ii", payload[:8])
    return req_id, kind, payload[8:-2].decode("utf-8", "replace")


def main() -> int:
    host, port, password = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    args = sys.argv[4:]
    stamp = False
    if args and args[0] == "--stamp":
        stamp = True
        args = args[1:]
    command = " ".join(args)
    with socket.create_connection((host, port), timeout=10) as sock:
        send(sock, 1, 3, password)
        req_id, _kind, _ = recv(sock)
        if req_id == -1:
            print("rcon: bad password", file=sys.stderr)
            return 2
        if stamp:
            print(f"STAMP {time.time():.3f}", file=sys.stderr, flush=True)
        send(sock, 2, 2, command)
        send(sock, 3, 0, "")  # empty probe: its reply marks the end of a (possibly multi-packet) response
        parts = []
        while True:
            rid, _kind, body = recv(sock)
            if rid == 3:
                break
            parts.append(body)
        print(ANSI.sub("", "".join(parts)))
    return 0


sys.exit(main())
