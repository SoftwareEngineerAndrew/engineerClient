// The numbers for FloorStats.kt (F1-F6; F7's are MapStats'): each floor's big room mixes, the fitted blood and
// fairy distance tables (calib2.mjs, one per main grid size) and the room names it never has.
//   node gen-floors.mjs tables-f1.json tables-f234.json tables-f56.json > FloorStats.kt
import { readFileSync } from 'node:fs';
import { complete, floorList } from './common.mjs';
import { combosFor } from './sampler2.mjs';
const [t1, t234, t56] = process.argv.slice(2).map(f => JSON.parse(readFileSync(f, 'utf8')));
const every = complete(undefined, floorList('1,2,3,4,5,6,7'));
const all = every.filter(L => L.floor[1] !== '7');
const combos = combosFor(all);
const f = (a, d = 3) => a.map(v => +v.toFixed(d) + 'f').join(', ');
const count = fl => all.filter(L => L.floor[1] === fl).length;

// room names a floor never has. A room on one floor is on every floor above it (the pools only grow), so a
// name is missing below the first floor it's seen on where the floors from there up to that one would have had
// it at least 5 times between them, at its share of its pool (F7's MapStats.NAMES). Seen less, it's kept:
// leaving out a name that's there could name the last rooms wrongly, keeping one that isn't only makes them less sure
const kt = readFileSync(new URL('../../src/mappred/MapStats.kt', import.meta.url), 'utf8');
const NAMES = [...kt.matchAll(/RoomName\("([^"]+)", (\d+), (\d+), (\d+)\)/g)].map(m => ({ name: m[1], pool: +m[2], seen: +m[4] }));
const seen = {}, expect = {}; // per floor: times each name was seen, and would have been at its share of its pool
for (const fl of '1234567') {
  seen[fl] = {}; expect[fl] = {};
  for (const L of every) if (L.floor[1] === fl) for (const r of L.rooms) seen[fl][r.name] = (seen[fl][r.name] || 0) + 1;
  for (let pool = 0; pool <= 9; pool++) {
    const names = NAMES.filter(n => n.pool === pool);
    const here = names.reduce((a, n) => a + (seen[fl][n.name] || 0), 0), share = names.reduce((a, n) => a + n.seen, 0);
    for (const n of names) expect[fl][n.name] = here * n.seen / share;
  }
}
const missing = {};
for (const fl of '123456') {
  missing[fl] = [];
  for (const n of NAMES) {
    const first = [...'1234567'].find(g => seen[g][n.name]);
    if (!first || first <= fl) continue;
    let e = 0; for (let g = +fl; g < +first; g++) e += expect[g][n.name];
    if (e >= 5) missing[fl].push(n.name);
  }
}

const combo = key => `arrayOf(\n${[...combos[key]].sort((a, b) => b.w - a.w).map(c => `        intArrayOf(${c.counts.join(', ')}, ${c.w}),`).join('\n')}\n    )`;
const names = fl => `setOf(${missing[fl].map(n => `"${n}"`).join(', ')})`;
console.log(`package com.engineerclient.mappred

/**
 * The numbers [FloorRules] samples F1-F6 with (F7's are [MapStats]), from the complete maps of each floor
 * recorded on ADR (F and M alike; tools/mappred gen-floors.mjs): ${'1234567'.slice(0, 6).split('').map(fl => `F${fl} ${count(fl)}`).join(', ')}.
 */
object FloorStats {
    /** Big room shape counts (1x2, 1x3, 1x4, 2x2, L) and how many maps had them: F1 alone, F2 always one of each. */
    val COMBOS_1 = ${combo('1')}
    val COMBOS_2 = ${combo('2')}

    /** F3 and F4 alike: 5 big rooms, sometimes 6. */
    val COMBOS_3_4 = ${combo('3')}

    /** F5 and F6 alike: 6 big rooms. */
    val COMBOS_5_6 = ${combo('5')}

    /** Weight of the wither path's walk length on F1-F4 (7 or 8 steps); F5-F6 have F7's 9 or 10. */
    val WALK_SHORT = floatArrayOf(${f(t1.WD)})

    /** Weights of blood's and the fairy's distance from the entrance (tiles), fitted per main grid: F1's 4x5. */
    val BLOOD_FROM_ENTRANCE_1 = floatArrayOf(${f(t1.MW)})
    val FAIRY_FROM_ENTRANCE_1 = floatArrayOf(${f(t1.FM)})

    /** F2-F4's 5x5. */
    val BLOOD_FROM_ENTRANCE_2_4 = floatArrayOf(${f(t234.MW)})
    val FAIRY_FROM_ENTRANCE_2_4 = floatArrayOf(${f(t234.FM)})

    /** F5-F6's 5x6. */
    val BLOOD_FROM_ENTRANCE_5_6 = floatArrayOf(${f(t56.MW)})
    val FAIRY_FROM_ENTRANCE_5_6 = floatArrayOf(${f(t56.FM)})

    /**
     * Room names a floor never has. A room on one floor is on every floor above it, so these are the names first
     * seen on a higher floor where the floors up to it would have had them at least 5 times between them.
     */
${'123456'.split('').map(fl => `    val MISSING_${fl} = ${names(fl)}`).join('\n')}
}`);
