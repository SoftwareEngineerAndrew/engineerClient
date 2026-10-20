import { readFileSync } from 'node:fs';
import { parse, graph } from './layout.mjs';
/** Each timeline as its layout plus, per room index, the tick it showed on the map (null = never), relative to Mort. */
export function loadTimelines(file = 'timelines.jsonl') {
  const out = [];
  for (const l of readFileSync(file, 'utf8').split('\n')) {
    if (!l) continue;
    const rec = JSON.parse(l);
    const L = parse({ ...rec, lines: rec.lines.filter(x => /^\{"k":"(meta|roomsAll|dslots|floor)"/.test(x)) });
    if (!L) continue;
    L.g = graph(L);
    let start = null; const seen = new Map(); const enters = [];
    const key = tiles => tiles.map(([x, z]) => x + ',' + z).sort().join(' ');
    // room index by any tile
    const byTile = (x, z) => L.grid[z]?.[x];
    for (const line of rec.lines) {
      const o = JSON.parse(line);
      if (o.k === 'chat' && start === null && /Mort: Here, I found this map/.test(o.m)) start = o.t;
      if (o.k === 'rooms') for (const [name, type, check, tiles] of o.r) {
        if (check === 'UNDISCOVERED' || !tiles.length) continue;
        const i = byTile(tiles[0][0], tiles[0][1]);
        if (i === undefined || i < 0 || seen.has(i)) continue;
        seen.set(i, o.t);
      }
      if (o.k === 'room') enters.push([o.t, o.name]);
    }
    L.start = start; L.seen = seen; L.enters = enters;
    out.push(L);
  }
  return out;
}
