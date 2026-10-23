// What every floor's complete maps do, floor by floor (F and M apart): the rules.mjs checks, and the numbers
// that differ between floors (common.mjs FLOORS).   node floors.mjs [floors, default 1,2,3,4,5,6,7]
import { complete, floorList, inc } from './common.mjs';
import { walkFrom } from './walk.mjs';
import { SHAPES } from './sampler2.mjs';
const fmt = o => Object.entries(o).sort((a, b) => (isNaN(a[0]) || isNaN(b[0]) ? b[1] - a[1] : a[0] - b[0])).map(([k, v]) => `${k}:${v}`).join(' ');
const all = complete(undefined, floorList(process.argv[2] || '1,2,3,4,5,6,7'));
for (const f of [...new Set(all.map(L => L.floor))].sort((a, b) => a[1] - b[1] || (a < b ? -1 : 1))) {
  const maps = all.filter(L => L.floor === f), F = maps[0].rules, W = F.w, H = F.h, n = maps.length;
  const s = { rooms: {}, big: {}, bigTiles: {}, maxPerShape: {}, P: {}, T: {}, Y: {}, F: {}, R: {}, col: {}, colTypes: {}, colHosts: {}, topUp: {},
    specialsDead: 0, normalNotDead: 0, deadEq: 0, witherOnPath: 0, fairyOnPath: 0, fairyGap: {}, walk: {}, parity: 0, ent: {}, entEdge: 0, entCorner: 0 };
  for (const L of maps) {
    const deg = r => L.g.get(r.i).length;
    const big = L.rooms.filter(r => r.shape !== 'OneByOne');
    inc(s.rooms, L.rooms.length); inc(s.big, big.length); inc(s.bigTiles, big.reduce((a, r) => a + r.tiles.length, 0));
    inc(s.maxPerShape, Math.max(0, ...SHAPES.map(sh => big.filter(r => r.shape === sh).length)));
    const cnt = t => L.rooms.filter(r => r.type === t).length;
    const P = cnt('PUZZLE'), T = cnt('TRAP'), Y = cnt('CHAMPION'), R = cnt('RARE');
    inc(s.P, P); inc(s.T, T); inc(s.Y, Y); inc(s.F, cnt('FAIRY')); inc(s.R, R);
    if (L.rooms.filter(r => !['NORMAL', 'FAIRY'].includes(r.type)).every(r => r.shape === 'OneByOne' && deg(r) === 1)) s.specialsDead++;
    if (L.rooms.filter(r => r.type === 'NORMAL' && r.shape === 'OneByOne').every(r => deg(r) >= 2)) s.normalNotDead++;
    const dead = L.rooms.filter(r => r.shape === 'OneByOne' && deg(r) === 1 && !['ENTRANCE', 'BLOOD'].includes(r.type));
    if (dead.length === P + T + Y + R) s.deadEq++;
    // the puzzle column: what's in it, what it hangs off, and how many the main grid's dead ends leave for it
    const col = L.rooms.filter(r => r.tiles[0].x >= W);
    if (F.column) {
      inc(s.col, col.length);
      for (const r of col) { inc(s.colTypes, r.type); const h = L.rooms[L.g.get(r.i)[0].to]; inc(s.colHosts, h.type + (h.shape === 'OneByOne' ? ' 1x1' : ' big')); }
      inc(s.topUp, `${dead.length - col.length}->${col.length}`);
    }
    // the wither path: entrance to blood, every inner door a wither door, the fairy on it
    const E = L.rooms.find(r => r.type === 'ENTRANCE'), B = L.rooms.find(r => r.type === 'BLOOD'), Fy = L.rooms.find(r => r.type === 'FAIRY');
    const prev = new Map([[E.i, -1]]); const q = [E.i];
    for (let i = 0; i < q.length; i++) for (const e of L.g.get(q[i])) if (!prev.has(e.to)) { prev.set(e.to, q[i]); q.push(e.to); }
    const path = []; for (let c = B.i; c !== -1; c = prev.get(c)) path.unshift(c);
    let wOk = true;
    for (const d of L.doors) {
      if (d.what !== 'w' || d.a < 0 || d.b < 0) continue;
      const pa = path.indexOf(d.a), pb = path.indexOf(d.b), lo = Math.min(pa, pb);
      if (pa < 0 || pb < 0 || Math.abs(pa - pb) !== 1 || lo === 0 || lo === path.length - 2) wOk = false;
    }
    if (wOk) s.witherOnPath++;
    if (Fy && path.includes(Fy.i)) s.fairyOnPath++;
    const w = walkFrom(L, E.i), D = w.get(B.i).dist;
    inc(s.walk, D); if (Fy) inc(s.fairyGap, D - w.get(Fy.i).dist);
    const e = E.tiles[0], b = B.tiles[0];
    if ((D - Math.abs(e.x - b.x) - Math.abs(e.z - b.z)) % 2 === 0) s.parity++;
    const onX = e.x === 0 || e.x === W - 1, onZ = e.z === 0 || e.z === H - 1;
    if (onX || onZ) s.entEdge++; if (onX && onZ) s.entCorner++;
  }
  const pc = v => `${v}/${n} (${(100 * v / n).toFixed(1)}%)`;
  console.log(`\n=== ${f}: ${n} complete maps, main grid ${W}x${H}${F.column ? ` + puzzle column (target ${F.target})` : ''}`);
  console.log('rooms', fmt(s.rooms), '| big rooms', fmt(s.big), '| their tiles', fmt(s.bigTiles), '| most of one shape', fmt(s.maxPerShape));
  console.log('puzzles', fmt(s.P), '| trap', fmt(s.T), '| yellow', fmt(s.Y), '| fairy', fmt(s.F), '| rare', fmt(s.R));
  if (F.column) console.log('column rooms', fmt(s.col), '| types', fmt(s.colTypes), '| behind', fmt(s.colHosts), '\n  main grid dead ends -> column rooms', fmt(s.topUp));
  console.log('specials 1x1 dead ends', pc(s.specialsDead), '| normal 1x1 never a dead end', pc(s.normalNotDead), '| dead ends = P+T+Y+R', pc(s.deadEq));
  console.log('wither doors only inside the path', pc(s.witherOnPath), '| fairy on it', pc(s.fairyOnPath), '| walk', fmt(s.walk), '| fairy before blood', fmt(s.fairyGap), '| walk parity', pc(s.parity));
  console.log('entrance on the edge', pc(s.entEdge), '| in a corner', pc(s.entCorner));
}
