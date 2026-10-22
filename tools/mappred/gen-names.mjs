// The room names seen on F7/M7 maps, by pool (shape / special type), with their secrets and how often they came up.
import { complete, inc } from './common.mjs';
const Ls = complete();
const POOL = { TwoByOne: 0, ThreeByOne: 1, FourByOne: 2, TwoByTwo: 3, L: 4 };
const poolOf = r => r.type === 'NORMAL' ? (r.shape === 'OneByOne' ? 5 : POOL[r.shape]) : { PUZZLE: 6, TRAP: 7, CHAMPION: 8, RARE: 9, BLOOD: 10, FAIRY: 11, ENTRANCE: 12 }[r.type];
const m = new Map();
for (const L of Ls) for (const r of L.rooms) { const k = r.name; if (!m.has(k)) m.set(k, { name: k, pool: poolOf(r), secrets: r.max, n: 0 }); m.get(k).n++; }
const rows = [...m.values()].sort((a, b) => a.pool - b.pool || b.n - a.n);
console.log(`    // ${Ls.length} complete F7/M7 maps: name, pool, secrets, times seen`);
console.log('    val NAMES = listOf(\n' + rows.map(r => `        RoomName(${JSON.stringify(r.name)}, ${r.pool}, ${r.secrets}, ${r.n}),`).join('\n') + '\n    )');
