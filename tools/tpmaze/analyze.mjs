// Every Teleport Maze seen in extract.mjs's output: its hops (who stood on which pad and where they landed, looking where),
// the final pad (the one into the end, which every landing looks at), the pad pairs, and what all mazes obey; then whether
// any two mazes are the same one.   node analyze.mjs <maze.jsonl> <mazes.json>
import { readFileSync, writeFileSync } from 'node:fs';

// Odin's pad order (TpMazeModel's): seven cells of four, then the end pad (28) and the start pad (29).
export const FRAMES = [[4, 12], [4, 6], [10, 12], [10, 6], [4, 20], [4, 14], [10, 20], [10, 14], [4, 28], [4, 22], [10, 28], [10, 22], [12, 28], [12, 22], [18, 28], [18, 22],
  [20, 28], [20, 22], [26, 28], [26, 22], [26, 20], [26, 14], [20, 20], [20, 14], [26, 12], [26, 6], [20, 12], [20, 6], [15, 14], [15, 12]];
export const LAND = FRAMES.map(([x, z], i) => [x + (i === 28 ? 1 : x % 8 === 4 ? 1 : -1), z + (z % 8 === 6 ? 1 : -1)]);
export const END = 28, START = 29;
export const cell = p => p < 28 ? p >> 2 : p === END ? 7 : 8;
const CELL_X0 = [4, 4, 4, 12, 20, 20, 20], CELL_Z0 = [6, 14, 22, 22, 22, 14, 6];
/** "1NW".."7SE" (cell 1-7, corner by canonical compass: -z north, -x west), "END", "START". */
export const label = p => p === END ? 'END' : p === START ? 'START'
  : `${cell(p) + 1}${FRAMES[p][1] === CELL_Z0[cell(p)] ? 'N' : 'S'}${FRAMES[p][0] === CELL_X0[cell(p)] ? 'W' : 'E'}`;

const landPad = (x, z) => {
  const fx = Math.floor(x), fz = Math.floor(z);
  if (Math.abs(x - fx - 0.5) > 0.03 || Math.abs(z - fz - 0.5) > 0.03) return -1;
  return LAND.findIndex(([a, b]) => a === fx && b === fz);
};
const onLanding = (x, z) => LAND.some(([a, b]) => Math.abs(x - a - 0.5) < 0.03 && Math.abs(z - b - 0.5) < 0.03);

/** The pads a player standing at canonical (x, z) could be on: those of the cell around them, or the start and end pads in the middle. */
function padsAround(x, z) {
  for (let c = 0; c < 7; c++) if (x >= CELL_X0[c] && x < CELL_X0[c] + 7 && z >= CELL_Z0[c] && z < CELL_Z0[c] + 7) return [0, 1, 2, 3].map(i => c * 4 + i);
  return x >= 11 && x < 20 && z >= 5 && z < 22 ? [END, START] : [];
}

/**
 * A player's teleports in the maze. A landing is a sample on a landing cell's middle at slab height, coming from somewhere
 * else. Teammates are drawn eased: a tick or two of samples on the straight line to the landing, after however long they
 * stood still on the frame (a player standing still writes no samples). The jump starts at the sample before those, and the
 * pad stood on is the frame of the cell there nearest it. 'high': on top of that frame, at most 10 ticks before landing;
 * 'mid': within 1.3 of it, as recently; 'low': anything else (a gap in the track, a jump from outside every cell).
 */
function hopsOf(track) {
  const out = [];
  for (let k = 1; k < track.length; k++) {
    const e = track[k], prev = track[k - 1];
    if (e[0] === -1 || prev[0] === -1) continue;
    const [t, x, y, z, yaw] = e;
    if (Math.abs(y - 69.5) > 0.03) continue;
    const land = landPad(x, z);
    if (land < 0 || (Math.abs(prev[1] - x) < 0.03 && Math.abs(prev[3] - z) < 0.03)) continue;
    // in flight: within 3 ticks of landing, part of the way along the line from the sample before it to the landing
    const inFlight = q => {
      const a = track[q - 1], b = track[q];
      if (!a || a[0] === -1 || t - b[0] > 3) return false;
      const vx = x - a[1], vz = z - a[3], len2 = vx * vx + vz * vz;
      if (len2 < 4) return false;
      const u = ((b[1] - a[1]) * vx + (b[3] - a[3]) * vz) / len2;
      return u > 0.05 && u < 0.97 && Math.abs((b[1] - a[1]) * vz - (b[3] - a[3]) * vx) / Math.sqrt(len2) < 0.4;
    };
    let i = k - 1;
    while (i >= 1 && inFlight(i)) i--;
    const o = track[i];
    if (Math.hypot(o[1] - x, o[3] - z) <= 1.5) continue;
    let frm = -1, dist = Infinity;
    const near = padsAround(o[1], o[3]);
    for (const p of near.length ? near : FRAMES.keys()) { const d = Math.hypot(o[1] - FRAMES[p][0] - 0.5, o[3] - FRAMES[p][1] - 0.5); if (d < dist) { dist = d; frm = p; } }
    const fresh = t - o[0] <= 10 && near.length > 0;
    const conf = fresh && o[2] >= 69.7 && dist <= 1.0 ? 'high' : fresh && dist <= 1.3 && !onLanding(o[1], o[3]) ? 'mid' : 'low';
    out.push({ t, land, yaw, frm, frmDist: +dist.toFixed(2), conf });
  }
  return out;
}

/** The pads whose landing cells lie on a landing's line of sight (within [tol] degrees), and how far off the closest is. */
export function aim(land, yaw, tol = 0.35) {
  const lx = LAND[land][0] + 0.5, lz = LAND[land][1] + 0.5, on = [];
  let best = 999;
  LAND.forEach(([a, b], i) => {
    if (i === land || i >= 28) return;
    const y = Math.atan2(-(a + 0.5 - lx), b + 0.5 - lz) * 180 / Math.PI;
    const off = Math.abs(((y - yaw + 540) % 360) - 180);
    best = Math.min(best, off);
    if (off <= tol) on.push(i);
  });
  return { on, off: +best.toFixed(2) };
}

function maze(o) {
  const hops = [];
  for (const [who, track] of Object.entries(o.players)) for (const h of hopsOf(track)) hops.push({ who, ...h });
  // the recorder's own teleport packets carry the exact yaw
  for (const st of o.selfTps || []) for (const h of hops) if (h.who === o.self && Math.abs(h.t - st[0]) <= 1) h.yaw = st[4];
  if (!hops.length) return null;
  hops.sort((a, b) => a.t - b.t || (a.who < b.who ? -1 : 1));
  const votes = new Map();
  let aimed = 0;
  for (const h of hops) {
    if (h.land === END) continue;
    const a = aim(h.land, h.yaw);
    h.aim = a.on; h.aimOff = a.off;
    if (a.on.length) { aimed++; for (const p of a.on) votes.set(p, (votes.get(p) || 0) + 1); }
  }
  const top = Math.max(0, ...votes.values());
  const tops = [...votes].filter(([, n]) => n === top).map(([p]) => p);
  const final = tops.length === 1 ? tops[0] : null;
  // A pad's teleport turns you to look at the final pad; a setback (Hypixel putting you back where you were) leaves your own
  // yaw. Landings that look at nothing, or not at the final pad, are not a pad's doing.
  for (const h of hops) if (h.land !== END && !(final === null ? h.aim.length : h.aim.includes(final))) h.conf = 'setback';
  const pairs = {}, conflicts = [];
  for (const h of hops) {
    if (h.conf === 'low' || h.conf === 'setback' || h.frm === h.land) continue;
    for (const [a, b] of [[h.frm, h.land], [h.land, h.frm]]) {
      if (a in pairs && pairs[a] !== b) conflicts.push([a, pairs[a], b]);
      else pairs[a] = b;
    }
  }
  return {
    id: o.id, group: o.group, floor: o.floor, startMs: o.startMs, rot: o.maze.rot, final, finalVotes: aimed, finalAgree: top,
    intoEnd: hops.filter(h => h.land === END && (h.conf === 'high' || h.conf === 'mid')).map(h => h.frm), pairs, conflicts, hops,
  };
}

/** Teleports in each player's unbroken walk from the start pad to the end (every hop leaves the cell the last one landed in). */
function walks(m) {
  const out = [];
  for (const who of new Set(m.hops.map(h => h.who))) {
    const hs = m.hops.filter(h => h.who === who && h.conf !== 'setback');
    if (!hs.length || hs[0].frm !== START || hs.at(-1).land !== END) continue;
    if (hs.every((h, i) => (h.conf === 'high' || h.conf === 'mid') && (i === 0 || cell(h.frm) === cell(hs[i - 1].land)))) out.push(hs.length);
  }
  return out;
}

/**
 * Steps (teleports) from cell [from] to cell [to], each leaving a cell by one of its pads. By known pairs only, it is at least
 * the real distance; [optimistic] also lets a pad not seen lead to any cell with a pad not seen (pairs only join unknown pads
 * to unknown pads), which is at most it. Equal: settled.
 */
function dist(pairs, from, to, optimistic) {
  const open = c => [0, 1, 2, 3].some(i => !((c * 4 + i) in pairs));
  const seen = new Map([[from, 0]]), q = [from];
  while (q.length) {
    const c = q.shift(), d = seen.get(c);
    if (c === to) return d;
    const next = new Set();
    for (let i = 0; i < 4; i++) { const p = c * 4 + i; if (p in pairs && pairs[p] < 28) next.add(cell(pairs[p])); }
    if (optimistic && open(c)) for (let k = 0; k < 7; k++) if (k !== c && open(k)) next.add(k);
    for (const n of next) if (!seen.has(n)) { seen.set(n, d + 1); q.push(n); }
  }
  return Infinity;
}

/** Prints what every maze obeys and returns the same numbers. */
function report(mazes, groups) {
  const st = { groups: groups.n, mazeGroups: groups.maze, mazesSeen: mazes.length, from: Math.min(...mazes.map(m => m.startMs)), to: Math.max(...mazes.map(m => m.startMs)) };
  const all = mazes.flatMap(m => m.hops), pct = (a, b) => `${a} of ${b} (${(100 * a / Math.max(b, 1)).toFixed(1)}%)`;
  const count = xs => { const c = {}; for (const x of xs) c[x] = (c[x] || 0) + 1; return c; };
  const good = h => h.conf === 'high' || h.conf === 'mid';
  console.log(`groups ${groups.n}, with a Teleport Maze ${groups.maze}, with a teleport seen in it ${mazes.length}`);
  st.hops = all.length; st.origin = count(all.map(h => h.conf));
  console.log('hops', st.hops, 'origin', st.origin);
  const aimedHops = all.filter(h => h.aim);
  st.onSight = [aimedHops.filter(h => h.aim.length).length, aimedHops.length];
  console.log('landing yaw on a landing cell (0.35 deg):', pct(...st.onSight));
  const fm = mazes.filter(m => m.finalVotes);
  st.sameAim = [fm.filter(m => m.finalAgree === m.finalVotes).length, fm.length];
  st.sameAimLandings = [fm.reduce((s, m) => s + m.finalAgree, 0), fm.reduce((s, m) => s + m.finalVotes, 0)];
  console.log('mazes where every landing looks at the same pad:', pct(...st.sameAim), '; landings:', pct(...st.sameAimLandings));
  const ends = mazes.filter(m => m.final !== null && m.intoEnd.length);
  st.intoEnd = [ends.filter(m => m.intoEnd.every(p => p === m.final)).length, ends.length];
  console.log('walks into the end from the pad the landings look at:', pct(...st.intoEnd));
  let both = 0, agree = 0;
  for (const m of mazes) {
    const seen = new Map();
    for (const h of m.hops) if (good(h) && h.frm !== h.land) (seen.get(h.frm) || seen.set(h.frm, new Set()).get(h.frm)).add(h.land);
    for (const [a, bs] of seen) for (const b of bs) if (seen.has(b)) { both++; agree += seen.get(b).size === 1 && seen.get(b).has(a); }
  }
  st.bothWays = [agree, both]; st.contradictions = mazes.filter(m => m.conflicts.length).length;
  console.log('pads seen both ways (A to B and B to A):', pct(agree, both), 'agree; mazes with a contradiction:', st.contradictions);
  const pairsOf = m => Object.entries(m.pairs).map(([a, b]) => [+a, b]);
  st.pairs = mazes.reduce((s, m) => s + pairsOf(m).filter(([a, b]) => a < b).length, 0);
  st.sameCell = mazes.reduce((s, m) => s + pairsOf(m).filter(([a, b]) => a < 28 && b < 28 && cell(a) === cell(b)).length, 0) / 2;
  console.log('pairs', st.pairs, 'of them inside one cell:', st.sameCell);
  let cells2 = 0, dup = 0;
  for (const m of mazes) for (let c = 0; c < 7; c++) {
    const dest = [0, 1, 2, 3].map(i => m.pairs[c * 4 + i]).filter(v => v !== undefined).map(cell);
    if (dest.length > 1) { cells2++; dup += new Set(dest).size !== dest.length; }
  }
  st.sharedDest = [dup, cells2];
  console.log('cells with 2+ known pads where two lead to the same cell:', pct(dup, cells2));
  // how far the final pad's cell is from the start's
  const d = { exact: [], atLeast: [] };
  for (const m of mazes) {
    if (m.final === null || !(START in m.pairs)) continue;
    const pairs = { ...m.pairs, [m.final]: END, [END]: m.final };
    const s = cell(m.pairs[START]), f = cell(m.final);
    const lo = dist(pairs, s, f, true), hi = dist(pairs, s, f, false);
    (lo === hi ? d.exact : d.atLeast).push(lo);
  }
  st.steps = { settled: count(d.exact), atLeast: count(d.atLeast) };
  console.log('steps from the start\'s cell to the final pad\'s cell: settled', st.steps.settled, 'at least', st.steps.atLeast);
  // every teleport seen leaving the cell the start pad leads into: does any land in the final pad's cell?
  let fromStart = 0, intoFinal = 0;
  for (const m of mazes) {
    if (m.final === null || !(START in m.pairs)) continue;
    for (const h of m.hops) if (good(h) && h.frm < 28 && cell(h.frm) === cell(m.pairs[START]) && h.frm !== m.pairs[START]) {
      fromStart++; intoFinal += h.land < 28 && cell(h.land) === cell(m.final);
    }
  }
  const chance = nullStartSteps();
  st.startCell = { out: fromStart, intoFinal, chance: chance.oneStep, chanceSteps: chance.d };
  console.log(`teleports out of the start's cell: ${fromStart}, into the final pad's cell: ${intoFinal} (pairs drawn at random: ${(100 * chance.oneStep).toFixed(1)}% would)`);
  console.log(`pairs drawn at random: the final pad's cell 0 / 1 / 2 / 3+ steps from the start's: ${chance.d.map(x => (100 * x).toFixed(1) + '%').join(' / ')}`);
  const ws = mazes.flatMap(walks);
  st.walks = count(ws);
  console.log('teleports per unbroken walk, start pad to end:', st.walks, 'mean', (ws.reduce((a, b) => a + b, 0) / Math.max(ws.length, 1)).toFixed(2));
  st.sight = count(all.filter(h => h.aim?.length).map(h => Math.min(h.aim.length, 5)));
  console.log('pads on one landing\'s line of sight:', st.sight);
  const firstTwo = mazes.map(m => m.hops.filter(h => h.aim?.length).map(h => h.aim)).filter(a => a.length >= 2);
  st.firstTwo = [firstTwo.filter(([a, b]) => a.filter(p => b.includes(p)).length === 1).length, firstTwo.length];
  console.log('first two landings leave one pad:', pct(...st.firstTwo));
  st.finalByPad = count(mazes.filter(m => m.final !== null).map(m => m.final));
  st.startByPad = count(mazes.filter(m => START in m.pairs).map(m => m.pairs[START]));
  console.log('final pad by cell:', count(mazes.filter(m => m.final !== null).map(m => cell(m.final) + 1)));
  console.log('start pad leads to, by cell:', count(mazes.filter(m => START in m.pairs).map(m => cell(m.pairs[START]) + 1)));
  return st;
}

/**
 * Mazes drawn with no rule but "no pad pairs inside its own cell, the start never with the end": how far the final pad's cell
 * is from the start's (in cells), and how often a pad of the start's cell (other than the one you land beside) leads into it.
 */
function nullStartSteps(n = 20000) {
  let seed = 11;
  const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
  const d = [0, 0, 0, 0];
  let tries = 0, hits = 0;
  for (let k = 0; k < n; k++) {
    let pairs;
    do {
      pairs = {};
      const free = [...Array(30).keys()];
      for (let i = free.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [free[i], free[j]] = [free[j], free[i]]; }
      let ok = true;
      while (free.length && ok) {
        const a = free.pop(), opts = free.filter(b => cell(b) !== cell(a) && !(a >= 28 && b >= 28));
        if (!opts.length) { ok = false; break; }
        const b = opts[Math.floor(rnd() * opts.length)];
        free.splice(free.indexOf(b), 1); pairs[a] = b; pairs[b] = a;
      }
      if (!ok) pairs = null;
    } while (!pairs);
    const s = cell(pairs[START]), f = cell(pairs[END]);
    d[Math.min(dist(pairs, s, f, false), 3)]++;
    if (s !== f) for (let i = 0; i < 4; i++) { const p = s * 4 + i; if (p !== pairs[START]) { tries++; hits += pairs[p] < 28 && cell(pairs[p]) === f; } }
  }
  return { d: d.map(x => x / n), oneStep: hits / tries };
}

/** Whether mazes repeat: how often two mazes give a pad the same partner, against relabelled mazes (cells and pads shuffled). */
function repeats(mazes) {
  const known = mazes.map(m => new Map(Object.entries(m.pairs).map(([a, b]) => [+a, b])));
  const fin = mazes.map(m => m.final);
  const compare = kn => {
    let shared = 0, same = 0, three = 0, threeSame = 0, fBoth = 0, fSame = 0;
    for (let i = 0; i < kn.length; i++) for (let j = i + 1; j < kn.length; j++) {
      let ov = 0, ag = 0;
      for (const [p, q] of kn[i]) if (kn[j].has(p)) { ov++; ag += kn[j].get(p) === q; }
      shared += ov; same += ag;
      if (ov >= 3) { three++; threeSame += ag === ov; }
      if (fin[i] !== null && fin[j] !== null) { fBoth++; fSame += fin[i] === fin[j]; }
    }
    return { shared, same, three, threeSame, fBoth, fSame };
  };
  let seed = 7;
  const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
  const shuffle = a => { for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };
  const relabelled = known.map(k => {
    const cells = shuffle([0, 1, 2, 3, 4, 5, 6]), perm = [];
    for (let c = 0; c < 7; c++) for (const i of shuffle([0, 1, 2, 3])) perm.push(cells[c] * 4 + i);
    perm.push(END, START);
    return new Map([...k].map(([a, b]) => [perm[a], perm[b]]));
  });
  const real = compare(known), nul = compare(relabelled), r = x => `${x.same} of ${x.shared} (${(100 * x.same / Math.max(x.shared, 1)).toFixed(1)}%)`;
  console.log(`\nsame partner for a pad two mazes both saw: ${r(real)}; relabelled: ${r(nul)}`);
  console.log(`maze pairs sharing 3+ pads: ${real.three}, all the same: ${real.threeSame}; relabelled ${nul.three}, ${nul.threeSame}`);
  console.log(`maze pairs with the same final pad: ${real.fSame} of ${real.fBoth} (${(100 * real.fSame / Math.max(real.fBoth, 1)).toFixed(2)}%; one in 28 is 3.57%)`);
  return { real, relabelled: nul };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [src, dst] = process.argv.slice(2);
  const mazes = [], groups = { n: 0, maze: 0 };
  for (const line of readFileSync(src, 'utf8').split('\n')) {
    if (!line) continue;
    const o = JSON.parse(line);
    groups.n++;
    if (typeof o.maze !== 'object' || !o.maze) continue;
    groups.maze++;
    const m = maze(o);
    if (m) mazes.push(m);
  }
  const stats = report(mazes, groups);
  stats.repeats = repeats(mazes);
  writeFileSync(dst, JSON.stringify({ stats, mazes }));
}
