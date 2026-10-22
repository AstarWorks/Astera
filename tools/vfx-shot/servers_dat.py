"""Write a Minecraft servers.dat (uncompressed NBT) with one entry that pre-accepts server resource packs.

Quick Play (portablemc --join-server) looks the address up in servers.dat; an entry with
acceptTextures=1b makes the client download + apply the server's pack without showing the prompt.

usage: python3 -I servers_dat.py <out-file> <host:port>
"""
import struct
import sys


def tag_string(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def named(tag_id: int, name: str, payload: bytes) -> bytes:
    return bytes([tag_id]) + tag_string(name) + payload


def main() -> None:
    out, address = sys.argv[1], sys.argv[2]
    entry = (
        named(8, "ip", tag_string(address))
        + named(8, "name", tag_string("astera-shot"))
        + named(1, "acceptTextures", b"\x01")
        + b"\x00"
    )
    servers = named(9, "servers", bytes([10]) + struct.pack(">i", 1) + entry)
    root = named(10, "", servers + b"\x00")
    with open(out, "wb") as f:
        f.write(root)


main()
