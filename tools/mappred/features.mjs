// Features of each unknown tile of an observation (sim.mjs observe), all from what the map shows.

const DIRS = [[1, 0], [-1, 0], [0, 1], [0, -1]];
export function edgeId(x, z, dx, dz) {
  if (dx === 1) return 'h' + x + ',' + z;
  if (dx === -1) return 'h' + (x - 1) + ',' + z;
  if (dz === 1) return 'v' + x + ',' + z;
  return 'v' + x + ',' + (z - 1);
}

/** The frame that puts the entrance on the top row, left half (6x6). */
export const SYM = [
  (x, z) => [x, z], (x, z) => [5 - z, x], (x, z) => [5 - x, 5 - z], (x, z) => [z, 5 - x],
  (x, z) => [5 - x, z], (x, z) => [z, x], (x, z) => [x, 5 - z], (x, z) => [5 - z, 5 - x],
];
export function frameOf(obs) {
  for (const [, r] of obs.rooms) if (r.type === 'ENTRANCE') {
    const e = r.tiles[0];
    for (const s of SYM) { const [x, z] = s(e.x, e.z); if (z === 0 && x <= 2) return s; }
  }
  return SYM[0];
}

export function analyse(obs) {
  const { W, H, tile, edge } = obs;
  const ek = (x, z, dx, dz) => edge.get(edgeId(x, z, dx, dz));
  const unknown = [];
  for (let z = 0; z < H; z++) for (let x = 0; x < W; x++) if (tile[z][x] < 0) unknown.push([x, z]);
  // components of unknown tiles joined through unknown edges
  const comp = Array.from({ length: H }, () => Array(W).fill(-1));
  const comps = [];
  for (const [sx, sz] of unknown) {
    if (comp[sz][sx] >= 0) continue;
    const c = { id: comps.length, tiles: [], entries: 0, entryKinds: { n: 0, w: 0, b: 0 } };
    const q = [[sx, sz]]; comp[sz][sx] = c.id;
    while (q.length) {
      const [x, z] = q.pop(); c.tiles.push([x, z]);
      for (const [dx, dz] of DIRS) {
        const nx = x + dx, nz = z + dz;
        if (nx < 0 || nz < 0 || nx >= W || nz >= H) continue;
        const k = ek(x, z, dx, dz);
        if (tile[nz][nx] >= 0) { if (k === 'n' || k === 'w' || k === 'b') { c.entries++; c.entryKinds[k]++; } continue; }
        if (comp[nz][nx] < 0) { comp[nz][nx] = c.id; q.push([nx, nz]); }
      }
    }
    comps.push(c);
  }
  // depth from the entry tiles inside each component
  const depth = Array.from({ length: H }, () => Array(W).fill(99));
  const q = [];
  for (const [x, z] of unknown) {
    for (const [dx, dz] of DIRS) {
      const nx = x + dx, nz = z + dz;
      if (nx < 0 || nz < 0 || nx >= W || nz >= H || tile[nz][nx] < 0) continue;
      const k = ek(x, z, dx, dz);
      if (k === 'n' || k === 'w' || k === 'b') { depth[z][x] = 0; q.push([x, z]); break; }
    }
  }
  for (let i = 0; i < q.length; i++) {
    const [x, z] = q[i];
    for (const [dx, dz] of DIRS) {
      const nx = x + dx, nz = z + dz;
      if (nx < 0 || nz < 0 || nx >= W || nz >= H || tile[nz][nx] >= 0) continue;
      if (depth[nz][nx] > depth[z][x] + 1) { depth[nz][nx] = depth[z][x] + 1; q.push([nx, nz]); }
    }
  }
  const found = { P: 0, T: 0, Y: 0, B: 0, F: 0, R: 0 };
  let entrance = null, blood = null, fairy = null;
  for (const [, r] of obs.rooms) {
    if (found[r.cls] !== undefined) found[r.cls]++;
    if (r.type === 'ENTRANCE') entrance = r.tiles[0];
    if (r.type === 'BLOOD') blood = r.tiles[0];
    if (r.type === 'FAIRY') fairy = r.tiles[0];
  }
  // the path's head: the unknown tile behind a wither or blood door
  let head = null, headKind = null;
  for (const [x, z] of unknown) for (const [dx, dz] of DIRS) {
    const nx = x + dx, nz = z + dz;
    if (nx < 0 || nz < 0 || nx >= W || nz >= H || tile[nz][nx] < 0) continue;
    const k = ek(x, z, dx, dz);
    if (k === 'w' || k === 'b') { head = [x, z]; headKind = k; }
  }
  return { unknown, comp, comps, depth, found, entrance, blood, fairy, head, headKind };
}

const oneHot = (n, i) => { const a = new Array(n).fill(0); if (i >= 0 && i < n) a[i] = 1; return a; };
const bucket = (v, cuts) => { let i = 0; while (i < cuts.length && v > cuts[i]) i++; return i; };

export const FEATURE_NAMES = [];
export function features(obs, A, frame) {
  const { W, H, tile, edge } = obs;
  const ek = (x, z, dx, dz) => edge.get(edgeId(x, z, dx, dz));
  const remP = Math.max(0, obs.puzzles - A.found.P);
  const remK = remP + (A.found.T ? 0 : 1) + (A.found.Y ? 0 : 1);
  const nUnknown = A.unknown.length;
  const out = [];
  for (const [x, z] of A.unknown) {
    let front = 0, doorsIn = 0, walls = 0, knownNoDoor = 0, unkSides = 0;
    for (const [dx, dz] of DIRS) {
      const nx = x + dx, nz = z + dz;
      if (nx < 0 || nz < 0 || nx >= W || nz >= H) { walls++; continue; }
      if (tile[nz][nx] < 0) { unkSides++; continue; }
      const k = ek(x, z, dx, dz);
      if (k === 'n' || k === 'w' || k === 'b') { doorsIn++; front = Math.max(front, k === 'b' ? 3 : k === 'w' ? 2 : 1); }
      else knownNoDoor++;
    }
    const c = A.comps[A.comp[z][x]];
    const [px, pz] = frame(x, z);
    const dist = p => p ? Math.abs(p[0] - x) + Math.abs(p[1] - z) : 0;
    const f = [
      ...oneHot(36, pz * 6 + px),
      ...oneHot(4, front),
      ...oneHot(4, Math.min(doorsIn, 3)),
      ...oneHot(3, walls),
      ...oneHot(5, knownNoDoor),
      ...oneHot(5, unkSides),
      ...oneHot(8, bucket(c.tiles.length, [1, 2, 3, 4, 6, 9, 14])),
      ...oneHot(5, Math.min(c.entries, 4)),
      ...(c.tiles.length === 1 ? oneHot(3, Math.min(c.entries, 3) - 1) : [0, 0, 0]),
      ...oneHot(6, Math.min(remP, 5)),
      A.found.T ? 1 : 0, A.found.Y ? 1 : 0, A.found.B ? 1 : 0, A.found.F ? 1 : 0,
      nUnknown / 36, remK / 8, remK / Math.max(1, nUnknown),
      A.head ? 1 : 0, A.head ? dist(A.head) / 10 : 0, A.head && A.head[0] === x && A.head[1] === z ? 1 : 0,
      A.headKind === 'b' ? 1 : 0,
      dist([A.entrance.x, A.entrance.z]) / 10,
      Math.min(A.depth[z][x], 10) / 10,
      A.blood ? dist([A.blood.x, A.blood.z]) / 10 : 0,
      A.fairy ? dist([A.fairy.x, A.fairy.z]) / 10 : 0,
      (36 - nUnknown) / 36,
      c.tiles.length / 36, c.entries / 6,
      c.entryKinds.w ? 1 : 0,
      1,
    ];
    out.push(f);
  }
  return out;
}
