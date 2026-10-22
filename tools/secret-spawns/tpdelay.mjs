// Fresh item spawns right after the recorder teleported close: ticks from the teleport to first sight.
import { loadPack, placedRooms, roomAt, recordings, tracks, at } from './common.mjs';
const pack = loadPack(process.argv[2]);
const delays = {}; let n = 0;
for (const rec of recordings(process.argv[3], +process.argv[4] || Infinity)) {
  const placed = placedRooms(pack, rec); const seen = new Set(); const tr = tracks(rec); const self = tr.get(rec.self);
  if (!self) continue;
  for (const [t, id, bat, x, y, z] of rec.sp) {
    if (bat || seen.has(id)) continue; seen.add(id);
    if (y - Math.floor(y) === 0) continue;
    const hit = roomAt(placed, x, z); if (!hit?.r.design) continue;
    if (!(hit.r.design.secrets || []).some(s => s.type === 'item' && Math.max(Math.abs(hit.c[0] - s.x), Math.abs(Math.floor(y) - s.y), Math.abs(hit.c[1] - s.z)) <= 2)) continue;
    // last recorder teleport before t (within 30 ticks)
    const tp = rec.tp.filter(p => p[0] <= t && p[0] >= t - 30).pop(); if (!tp) continue;
    const dAfter = Math.hypot(tp[1] - x, tp[2] - y, tp[3] - z);
    const before = at(self, tp[0] - 1); if (!before) continue;
    const dBefore = Math.hypot(before[0] - x, before[1] - y, before[2] - z);
    if (dBefore - dAfter < 6) continue; // a teleport that brought the recorder much closer
    // nobody else got close in between
    let other = false;
    for (const [name, p] of tr) { if (name === rec.self) continue; for (let k = tp[0] - 40; k <= t; k += 2) { const q = at(p, k); if (q && Math.hypot(q[0] - x, q[1] - y, q[2] - z) < dAfter + 4) { other = true; break; } } if (other) break; }
    if (other) continue;
    const dl = t - tp[0]; delays[dl] = (delays[dl] || 0) + 1; n++;
  }
}
console.log(n, 'spawns after a teleport in; delay ticks:', Object.entries(delays).sort((a, b) => a[0] - b[0]).map(([k, v]) => k + ':' + v).join(' '));
