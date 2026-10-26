// GameStats.kt, the numbers the Kotlin game-process sampler (src/mappred/GameSampler.kt, sampler3.mjs's steps)
// draws an F7 map's big rooms with: every big-room mix of the complete F7/M7 maps (counts in SHAPES order and
// how many maps had it; the 6-room ones too, 1% of maps) and its one-try success rate (placelik.mjs MIXRATE,
// 'big/try1|<counts>'). A mix is drawn by count / rate: the game makes the whole map again (a new mix too) when a
// try fails, so the maps show each mix as often as it is drawn times how often it gets through.
//     LAYOUTS=layouts.jsonl node <tools>/gen-game.mjs zmix.json > <repo>/src/mappred/GameStats.kt
// Only these totals go in the repo, never the recorded maps.
// It counts every map in LAYOUTS: to score the predictor on held-out maps, make the table from the training maps only
// (a LAYOUTS file of them), or the score is a little optimistic (about 0.003 per tile; README, "In the mod").
import { readFileSync } from 'node:fs';
import { complete } from './common.mjs';
import { SHAPES } from './sampler2.mjs';

const zmix = JSON.parse(readFileSync(process.argv[2] || 'zmix.json', 'utf8'));
const maps = complete();
const count = new Map();
for (const L of maps) {
  const k = SHAPES.map(s => L.rooms.filter(r => r.shape === s).length).join('');
  count.set(k, (count.get(k) || 0) + 1);
}
const rows = [...count].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1));
const missing = rows.filter(([k]) => !(zmix['big/try1|' + k] > 0)).map(([k]) => k);
if (missing.length) { console.error(`no success rate for ${missing.join(', ')}`); process.exit(1); }
const rooms = k => [...k].reduce((a, b) => a + +b, 0);
const six = rows.filter(([k]) => rooms(k) === 6).reduce((a, [, w]) => a + w, 0);
const rate = k => zmix['big/try1|' + k].toPrecision(6).replace(/e/, 'E');

console.log(`package com.engineerclient.mappred

/**
 * What [GameSampler] draws an F7 map's big rooms with (tools/mappred gen-game.mjs writes this file; change that,
 * not this). [MIXES]: each big-room mix of the ${maps.length} complete F7/M7 maps (${six} of them with 6 big rooms),
 * as counts of 1x2, 1x3, 1x4, 2x2 and L (the [Shape] order), then how many maps had it. [MIX_RATE]: the chance one
 * try with that mix gets through (one random spot per big room with none clashing, the door tree and its dead
 * ends), per try (tools/mappred placelik.mjs MIXRATE, big/try1). The game makes the map again, mix and all, when a
 * try fails, so a mix is drawn by maps / rate.
 */
object GameStats {
    val MIXES = arrayOf(
${rows.map(([k, w]) => `        intArrayOf(${[...k].join(', ')}, ${w}),`).join('\n')}
    )

    val MIX_RATE = doubleArrayOf(
${rows.map(([k]) => `        ${rate(k)},`).join('\n')}
    )
}`);
