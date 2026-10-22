// Every secret spawn site found in the recordings (items, bats, wither essence / redstone key skulls), clustered in each room
// design's own frame, with its spawn radius: Hypixel spawns it at the first 5-tick check with a player inside the room
// within R blocks (3D, feet) of it. R is fitted per site from first sights: R >= nearest in-room player at first sight,
// R < nearest in-room player UB ticks before.
//   node sites.mjs <pack> <ex dir> <out.json> [limit]
import { writeFileSync } from 'node:fs';
import { loadPack, recordings } from './common.mjs';
import { events } from './events.mjs';
import { fit } from './fit.mjs';

const [packFile, dir, outFile, lim = 'Infinity'] = process.argv.slice(2);
const pack = loadPack(packFile);
const TRACK = { item: 28, bat: 40 };
const NO_BATS = new Set(['Entrance', 'Blood', 'Fairy']); // door bats at the room's edge
const d3 = (p, s) => Math.hypot(p[0] - s[0], p[1] - s[1], p[2] - s[2]);
const UBS = [12, 20];

// 1. every fresh first sight, with its bounds
const evs = [];
let recs = 0;
const floors = {};
for (const rec of recordings(dir, +lim)) {
  recs++; floors[rec.floor] = (floors[rec.floor] || 0) + 1;
  for (const e of events(pack, rec, { all: true })) {
    if (!e.fresh) continue;
    if (e.kind === 'bat' && NO_BATS.has(pack.byId.get(e.design)?.name)) continue;
    if (e.kind !== 'skull') {
      let ok = true;
      for (let k = 1; k <= 10 && ok; k++) { const me = e.pos(k).find(([n]) => n === e.self); if (!me || Math.max(Math.abs(me[1][0] - e.x), Math.abs(me[1][2] - e.z)) > TRACK[e.kind]) ok = false; }
      if (!ok) continue;
    }
    const ref = e.kind === 'item' ? [Math.floor(e.x) + 0.5, Math.floor(e.y), Math.floor(e.z) + 0.5] : e.kind === 'bat' ? [e.x, e.y, e.z] : [e.x, e.y - 0.5, e.z];
    const inRoom = q => e.pl.owns(Math.floor(q[0]), Math.floor(q[2]));
    const me0 = e.pos(0).find(([n]) => n === e.self)?.[1];
    const recBox = me0 ? Math.max(Math.abs(me0[0] - ref[0]), Math.abs(me0[2] - ref[2])) : Infinity;
    let lb = Infinity; for (const [, q] of e.pos(0)) if (inRoom(q)) lb = Math.min(lb, d3(q, ref));
    const ub = {};
    for (const k of UBS) { let u = Infinity; for (const [, q] of e.pos(k)) if (inRoom(q)) u = Math.min(u, d3(q, ref)); ub[k] = u; }
    const lbOk = Number.isFinite(lb) && recBox + lb <= 44;
    evs.push({ kind: e.kind, design: e.design, sid: e.sid, cell: e.cell, item: e.item, tex: e.tex, floor: rec.floor, lb: lbOk ? lb : null, ub });
  }
}

// 2. the wither essence and redstone key skins: the commonest skins on the pack's essence / key sites
const texCount = {};
for (const e of evs) if (e.kind === 'skull' && e.sid && e.tex) texCount[e.tex] = (texCount[e.tex] || 0) + 1;
const SKULL_TEX = new Set(Object.entries(texCount).filter(([, n]) => n >= 20).map(([t]) => t));
const texType = {};
for (const e of evs) if (e.kind === 'skull' && e.sid && e.tex) { const t = pack.byId.get(e.design)?.secrets?.find(x => x.id === e.sid)?.type; if (t) (texType[e.tex] ||= {})[t] = ((texType[e.tex] ||= {})[t] || 0) + 1; }
const TEX_ROLE = Object.fromEntries(Object.entries(texType).map(([t, c]) => [t, Object.entries(c).sort((a, b) => b[1] - a[1])[0][0]]));

// 3. clusters per design and kind: cells within 2 (items land anywhere in their cell, bats hang in theirs)
const clusters = [];
const groups = new Map();
for (const e of evs) {
  if (e.kind === 'skull' && !e.sid && !SKULL_TEX.has(e.tex)) continue; // puzzle and decoration heads
  const k = e.design + '|' + e.kind; if (!groups.has(k)) groups.set(k, []); groups.get(k).push(e);
}
for (const [k, list] of groups) {
  const [design, kind] = k.split('|');
  const cells = new Map();
  for (const e of list) { const c = e.cell.join(','); if (!cells.has(c)) cells.set(c, []); cells.get(c).push(e); }
  const order = [...cells].sort((a, b) => b[1].length - a[1].length);
  const taken = new Set();
  for (const [c, l] of order) {
    if (taken.has(c)) continue;
    const [x, y, z] = c.split(',').map(Number);
    const mine = [];
    for (const [c2, l2] of order) {
      if (taken.has(c2)) continue;
      const [x2, y2, z2] = c2.split(',').map(Number);
      const near = kind === 'skull' ? x2 === x && y2 === y && z2 === z : kind === 'bat' ? Math.max(Math.abs(x2 - x), Math.abs(z2 - z)) <= 2 && Math.abs(y2 - y) <= 6 : Math.max(Math.abs(x2 - x), Math.abs(y2 - y), Math.abs(z2 - z)) <= 2;
      if (near) { taken.add(c2); mine.push(...l2); }
    }
    clusters.push({ design, kind, cell: [x, y, z], evs: mine });
  }
}

// 4. fit each, match it to a pack secret, keep the real ones
const PACK_KIND = { item: ['item'], bat: ['bat'], skull: ['essence', 'redstoneKey'] };
const sites = [];
for (const c of clusters) {
  const n = c.evs.length;
  if (n < 8) continue;
  const fits = UBS.map(k => fit(c.evs.map(e => ({ lb: { m: e.lb }, ub: { m: e.ub[k] } })), 'm'));
  const f = fits[0];
  if (f.bad / n > 0.3) continue; // not a secret that spawns on approach (or two things mixed)
  const d = pack.byId.get(c.design);
  // the pack's secret of that kind nearest the cluster (a bat's pack spot may be where it was killed: up to 8 away)
  let best = null, bd = Infinity;
  for (const s of d?.secrets || []) {
    if (!PACK_KIND[c.kind].includes(s.type)) continue;
    const dd = Math.max(Math.abs(s.x - c.cell[0]), Math.abs(s.y - c.cell[1]), Math.abs(s.z - c.cell[2]));
    if (dd < bd) { bd = dd; best = s; }
  }
  const tol = c.kind === 'bat' ? 16 : c.kind === 'item' ? 3 : 1;
  const items = {}; for (const e of c.evs) if (e.item) items[e.item] = (items[e.item] || 0) + 1;
  const byFloor = {};
  for (const fl of ['F7', 'M7']) { const sub = c.evs.filter(e => e.floor === fl); if (sub.length >= 15) byFloor[fl] = +fit(sub.map(e => ({ lb: { m: e.lb }, ub: { m: e.ub[12] } })), 'm').r.toFixed(1); }
  const texes = {}; for (const e of c.evs) if (e.tex) texes[e.tex] = (texes[e.tex] || 0) + 1;
  const tex = Object.entries(texes).sort((a, b) => b[1] - a[1])[0]?.[0];
  const event = f.nlb < 5 || f.lo < 0.5;
  if (c.kind === 'bat' && n < 25 && !(best && bd <= 8)) continue;
  sites.push({
    type: c.kind === 'skull' ? (best && bd <= tol ? best.type : TEX_ROLE[tex] ?? 'skull') : c.kind, event,
    room: d?.name ?? c.design, design: c.design, shape: d?.shape, kind: c.kind,
    secret: best && bd <= tol ? best.id : null, packType: best && bd <= tol ? best.type : null,
    pos: c.cell, packPos: best && bd <= tol ? [best.x, best.y, best.z] : null,
    r: +f.r.toFixed(1), r20: +fits[1].r.toFixed(1), lo: +f.lo.toFixed(2), hi: Number.isFinite(f.hi) ? +f.hi.toFixed(2) : null,
    n, bad: f.bad, byFloor, items: c.kind === 'item' ? items : undefined,
  });
}
// a pack secret claimed by two clusters keeps the bigger one (bats: the nearest unclaimed); the other is its own site
const claimed = new Map();
for (const s of sites.sort((a, b) => b.n - a.n)) {
  if (!s.secret) continue;
  const k = s.design + ' ' + s.secret;
  if (claimed.has(k)) { s.secret = null; s.packType = null; s.packPos = null; } else claimed.set(k, s);
}
sites.sort((a, b) => a.room.localeCompare(b.room) || a.kind.localeCompare(b.kind) || (a.secret ?? 'z').localeCompare(b.secret ?? 'z', undefined, { numeric: true }) || b.n - a.n);
writeFileSync(outFile, JSON.stringify({ recordings: recs, floors, spawns: evs.length, skullTextures: [...SKULL_TEX], sites }, null, 1));
const tot = sites.reduce((s, x) => s + x.n, 0), bad = sites.reduce((s, x) => s + x.bad, 0);
const by = {}; for (const s of sites) { const k = s.kind + (s.secret ? ' (pack)' : ' (new)'); by[k] = (by[k] || 0) + 1; }
console.log(`${recs} recordings, ${evs.length} spawns, ${sites.length} sites`, by, `${(bad / tot * 100).toFixed(1)}% of their spawns disagree`);
