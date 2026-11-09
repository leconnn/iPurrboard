"""Flatten one keyboard's keyplanes into key rows with geometry.

usage: kbflat.py <dat> <layout-name> [keyplane-substring]
Geometry values print as number+unit, unit bits: 1 = 'pt', 2 = '%', 3 = '?'.
"""
import sys

sys.path.insert(0, '.')
from kbtree import Tree  # noqa: E402

UNIT = {1: '', 2: '%', 3: '?'}


def fmt_geom(g):
    if not isinstance(g, dict) or 'geom' not in g:
        return str(g)
    parts = []
    for grp in g['geom']:
        parts.append(','.join('-' if v is None else f'{v[0]}{UNIT[v[1]]}' for v in grp))
    return ' | '.join(parts)


def main():
    t = Tree(sys.argv[1])
    sys.stdout.reconfigure(encoding='utf-8')
    root, _ = t.obj(t.k.B + t.k.index[sys.argv[2]], 12)
    want = sys.argv[3] if len(sys.argv) > 3 else ''
    print('keyboard', root['name'], 'shape', fmt_geom(root['props'].get('KBshape')))
    for plane in root['subtrees']:
        if want not in plane['name']:
            continue
        print('\n== keyplane', plane['name'])
        for k, v in (plane.get('props') or {}).items():
            if not isinstance(v, list):
                print('   prop', k, '=', v)
        for layout in plane['subtrees']:
            if not isinstance(layout, dict):
                continue
            print(' -- layout', layout.get('name'), 'shape', fmt_geom((layout.get('props') or {}).get('KBshape')))
            parts = {n.get('type'): n for n in layout.get('subtrees', []) if isinstance(n, dict)}
            rows = parts.get('keyset', {}).get('subtrees', [])
            geoms = parts.get('geometrySet', {}).get('subtrees', [])
            attrs = parts.get('attributeSet', {}).get('subtrees', [])
            for ri, row in enumerate(rows):
                if not isinstance(row, dict):
                    print('   row?', row)
                    continue
                glist = geoms[ri] if ri < len(geoms) and isinstance(geoms[ri], dict) else {}
                gs = glist.get('subtrees', [])
                print(f"   row {row.get('name')}  geomlist={glist.get('name')} props={glist.get('props')}")
                for ki, key in enumerate(row.get('subtrees', [])):
                    p = key.get('props', {}) if isinstance(key, dict) else {}
                    g = gs[ki] if ki < len(gs) else None
                    print(f"     {key.get('name', key) if isinstance(key, dict) else key!s:40} "
                          f"disp={p.get('KBdisplayString')!r} out={p.get('KBrepresentedString')!r} "
                          f"dt={p.get('KBdisplayType')} it={p.get('KBinteractionType')}  geom {fmt_geom(g)}")
            for a in attrs:
                if isinstance(a, dict):
                    print('   attrs', a.get('name'), a.get('props'))


if __name__ == '__main__':
    main()
