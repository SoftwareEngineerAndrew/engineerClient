// Tile-walk distances through the door tree: entering a room at one tile, leaving by a door tile,
// the fewest steps inside the room, plus one step through each door.
const DIRS = [[1, 0], [-1, 0], [0, 1], [0, -1]];
export function inRoom(tiles, s, t) {
  if (s[0] === t[0] && s[1] === t[1]) return 0;
  const set = new Set(tiles.map(q => q.x + ',' + q.z)); const d = new Map([[s.join(','), 0]]); const q = [s];
  for (let i = 0; i < q.length; i++) { const [x, z] = q[i]; for (const [dx, dz] of DIRS) { const n = [x + dx, z + dz], k = n.join(','); if (set.has(k) && !d.has(k)) { d.set(k, d.get(x + ',' + z) + 1); if (n[0] === t[0] && n[1] === t[1]) return d.get(k); q.push(n); } } }
  return 99;
}
/** walk distance from the entrance tile to every room's door tile it's entered by (and that tile). */
export function walkFrom(L, startRoom) {
  const doorOf = new Map(); // "a,b" -> [tile in a, tile in b]
  for (const d of L.doors) { if (d.what === '-' || d.a === d.b || d.a < 0 || d.b < 0) continue; doorOf.set(d.a + ',' + d.b, [[d.ax, d.az], [d.bx, d.bz]]); doorOf.set(d.b + ',' + d.a, [[d.bx, d.bz], [d.ax, d.az]]); }
  const st = L.rooms[startRoom].tiles[0];
  const res = new Map([[startRoom, { dist: 0, at: [st.x, st.z] }]]); const q = [startRoom];
  for (let i = 0; i < q.length; i++) {
    const u = q[i]; const { dist, at } = res.get(u);
    for (const e of L.g.get(u)) {
      if (res.has(e.to)) continue;
      const [a, b] = doorOf.get(u + ',' + e.to);
      res.set(e.to, { dist: dist + inRoom(L.rooms[u].tiles, at, a) + 1, at: b }); q.push(e.to);
    }
  }
  return res;
}
