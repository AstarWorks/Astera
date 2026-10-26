"""Minimal, safe legacy (pre-1.13) Anvil reader. Parses data only; never executes anything."""
import struct, zlib, os, glob


def _read_tag(buf, pos, tid):
    if tid == 1: return buf[pos], pos + 1
    if tid == 2: return struct.unpack_from('>h', buf, pos)[0], pos + 2
    if tid == 3: return struct.unpack_from('>i', buf, pos)[0], pos + 4
    if tid == 4: return struct.unpack_from('>q', buf, pos)[0], pos + 8
    if tid == 5: return struct.unpack_from('>f', buf, pos)[0], pos + 4
    if tid == 6: return struct.unpack_from('>d', buf, pos)[0], pos + 8
    if tid == 7:
        n = struct.unpack_from('>i', buf, pos)[0]; pos += 4
        return bytes(buf[pos:pos + n]), pos + n
    if tid == 8:
        n = struct.unpack_from('>H', buf, pos)[0]; pos += 2
        return bytes(buf[pos:pos + n]).decode('utf-8', 'replace'), pos + n
    if tid == 9:
        et = buf[pos]; n = struct.unpack_from('>i', buf, pos + 1)[0]; pos += 5
        out = []
        for _ in range(n):
            v, pos = _read_tag(buf, pos, et); out.append(v)
        return out, pos
    if tid == 10:
        out = {}
        while True:
            t = buf[pos]; pos += 1
            if t == 0: return out, pos
            n = struct.unpack_from('>H', buf, pos)[0]; pos += 2
            name = bytes(buf[pos:pos + n]).decode('utf-8', 'replace'); pos += n
            out[name], pos = _read_tag(buf, pos, t)
    if tid == 11:
        n = struct.unpack_from('>i', buf, pos)[0]; pos += 4
        return list(struct.unpack_from('>%di' % n, buf, pos)), pos + 4 * n
    if tid == 12:
        n = struct.unpack_from('>i', buf, pos)[0]; pos += 4
        return list(struct.unpack_from('>%dq' % n, buf, pos)), pos + 8 * n
    raise ValueError('bad tag %d' % tid)


def parse_nbt(buf):
    assert buf[0] == 10
    n = struct.unpack_from('>H', buf, 1)[0]
    v, _ = _read_tag(buf, 3 + n, 10)
    return v


def chunks(mca_path):
    """Yield chunk NBT dicts of one region file."""
    data = open(mca_path, 'rb').read()
    for i in range(1024):
        off = struct.unpack_from('>I', data, i * 4)[0]
        if off == 0: continue
        sector, count = off >> 8, off & 0xff
        start = sector * 4096
        length, ctype = struct.unpack_from('>IB', data, start)
        raw = data[start + 5:start + 4 + length]
        raw = zlib.decompress(raw) if ctype == 2 else (__import__('gzip').decompress(raw) if ctype == 1 else None)
        if raw is None: continue
        yield parse_nbt(raw)


def legacy_blocks(world_dir):
    """Return dict (x,y,z) -> (id, data) for every non-air block of a legacy world, plus tile entities."""
    out = {}
    tiles = []
    for mca in sorted(glob.glob(os.path.join(world_dir, 'region', '*.mca'))):
        for ch in chunks(mca):
            lvl = ch['Level']
            cx, cz = lvl['xPos'], lvl['zPos']
            for sec in lvl.get('Sections', []):
                y0 = sec['Y'] * 16
                blocks = sec['Blocks']; dat = sec['Data']
                add = sec.get('Add')
                for i in range(4096):
                    b = blocks[i]
                    if add is not None:
                        b |= ((add[i >> 1] >> (4 * (i & 1))) & 15) << 8
                    if b == 0: continue
                    d = (dat[i >> 1] >> (4 * (i & 1))) & 15
                    y = y0 + (i >> 8); z = (i >> 4) & 15; x = i & 15
                    out[(cx * 16 + x, y, cz * 16 + z)] = (b, d)
            for te in lvl.get('TileEntities', []):
                tiles.append(te)
    return out, tiles
