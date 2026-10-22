"""Read-only candidates: parallel, same-facing faces with projected area overlap.
Visibility, transparency and intentional seams need visual review; never auto-delete.
"""
import numpy as np
from raster import faces, element_points


def surface_pairs(model, max_gap=0.021):
    rows = []
    for i, element in enumerate(model['elements']):
        for face, (points, _) in faces(element).items():
            if face not in element['faces']:
                continue
            p = element_points(element, points)
            u, v = p[1]-p[0], p[3]-p[0]
            normal = np.cross(u, v)
            normal /= np.linalg.norm(normal)
            rows.append((f'e{i+1:04d}', face, p, normal, u/np.linalg.norm(u), v/np.linalg.norm(v)))
    normals = np.array([r[3] for r in rows])
    origins = np.array([r[2][0] for r in rows])
    pairs = []
    # ponytail: quadratic face scan is bounded by current ~200-cuboid assets.
    # Vector prefilter avoids costly polygon checks for unrelated planes.
    for i, a in enumerate(rows):
        gaps = np.abs((origins-a[2][0])@a[3])
        candidates = np.where((normals@a[3] > 1-1e-8) & (gaps <= max_gap+1e-8))[0]
        for j in candidates:
            b = rows[j]
            if j <= i or a[0] == b[0]:
                continue
            if not all(min((a[2]@axis).max(), (b[2]@axis).max()) -
                       max((a[2]@axis).min(), (b[2]@axis).min()) > 1e-6
                       for axis in (a[4], a[5], b[4], b[5])):
                continue
            pairs.append({'a': [a[0], a[1]], 'b': [b[0], b[1]],
                          'gap': round(float(gaps[j]), 6),
                          'kind': 'coplanar' if gaps[j] <= 1e-6 else 'near'})
    return pairs
