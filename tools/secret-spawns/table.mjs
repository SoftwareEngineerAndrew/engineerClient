// secret-spawns.json -> the per-room table in docs/ads/secret-spawns.md (between the TABLE markers).
//   node table.mjs <secret-spawns.json> <secret-spawns.md>
import { readFileSync, writeFileSync } from 'node:fs';
const [jsonFile, mdFile] = process.argv.slice(2);
const j = JSON.parse(readFileSync(jsonFile, 'utf8'));
const TYPE = { item: 'item', bat: 'bat', essence: 'essence skull', redstoneKey: 'key skull' };
const rows = ['| Room | Secret | Type | Spawn radius | Spawns at (room frame) | Seen |', '|---|---|---|---|---|---|'];
for (const s of j.sites) {
  const r = s.r == null ? 'on an event' : `${s.r} ± ${s.err}`;
  const at = s.pos.join(', ') + (s.packPos ? ` (pack: ${s.packPos.join(', ')})` : '');
  rows.push(`| ${s.room} | ${s.secret ?? '-'} | ${TYPE[s.type] ?? s.type}${s.secret ? '' : ' *'} | ${r} | ${at} | ${s.n} |`);
}
const md = readFileSync(mdFile, 'utf8');
const [head, rest] = md.split('<!-- TABLE -->');
const tail = rest.split('<!-- /TABLE -->')[1];
writeFileSync(mdFile, `${head}<!-- TABLE -->\n${rows.join('\n')}\n<!-- /TABLE -->${tail}`);
console.log(rows.length - 2, 'rows');
