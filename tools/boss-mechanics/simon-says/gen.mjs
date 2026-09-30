import { loadWorld } from './world.mjs';
const get = loadWorld(['boss_6,5.json', 'boss_6,6.json', 'boss_6,4.json', 'boss_7,5.json']);
const pal = [], idx = new Map(), cells = [];
for (let y = 117; y <= 126; y++) for (let z = 86; z <= 100; z++) for (let x = 106; x <= 112; x++) {
  let s = get(x, y, z);
  if (!s || s === 'minecraft:air' || s === 'minecraft:cave_air') continue;
  if (x === 110 && y >= 120 && y <= 123 && z >= 92 && z <= 95) continue; // the 16 buttons come and go
  s = s.replace(/^minecraft:/, '');
  if (!idx.has(s)) { idx.set(s, pal.length); pal.push(s); }
  cells.push(`${x - 108},${y - 120},${z - 94},${idx.get(s)}`);
}
console.log(pal.length, 'states,', cells.length, 'blocks');
console.log(pal.join('\n'));
import fs from 'fs';
fs.writeFileSync('structure.txt', JSON.stringify({ pal, cells: cells.join(';') }));
