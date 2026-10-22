// sites.json (sites.mjs) -> the repo's secret-spawns.json: one line per secret site, what ADS reads and what people look up.
//   node export.mjs <sites.json> <out.json> <ex dir>
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
const [inFile, outFile, exDir] = process.argv.slice(2);
const j = JSON.parse(readFileSync(inFile, 'utf8'));
// the recordings' dates, from their ids (yyyymmdd-...)
const days = readdirSync(exDir).filter(f => f.endsWith('.json.gz')).map(f => `${f.slice(0, 4)}-${f.slice(4, 6)}-${f.slice(6, 8)}`).sort();

const keep = j.sites.filter(s => s.secret || (s.kind === 'bat' ? s.n >= 25 && !s.event : true));
const med = a => { const s = [...a].sort((x, y) => x - y); return s.length ? +s[Math.floor((s.length - 1) / 2)].toFixed(1) : null; };
const defaults = {};
for (const t of ['item', 'bat', 'essence']) defaults[t] = med(keep.filter(s => s.type === t && s.secret && !s.event).map(s => s.r));

const sites = keep.map(s => {
  const o = { room: s.room, design: s.design, type: s.type, secret: s.secret, pos: s.pos };
  if (s.packPos && s.packPos.some((v, i) => v !== s.pos[i])) o.packPos = s.packPos;
  if (s.event) { o.r = null; o.spawns = 'on an event, not on approach'; }
  else { o.r = s.r; o.err = +Math.max(0.5, Math.abs(s.r20 - s.r)).toFixed(1); }
  o.n = s.n;
  o.agree = +(1 - s.bad / s.n).toFixed(2);
  if (s.byFloor.F7 != null && !s.event) o.f7 = s.byFloor.F7;
  if (s.byFloor.M7 != null && !s.event) o.m7 = s.byFloor.M7;
  if (!s.secret) o.note = s.kind === 'bat' ? 'not on the rooms pack list; may be a second spot of the room\'s bat' : 'not on the rooms pack list';
  return o;
});
const out = {
  format: 'ads-secret-spawns', version: 1,
  built: new Date().toISOString().slice(0, 10),
  from: `${j.recordings} ADR recordings (${Object.entries(j.floors).filter(([, n]) => n >= 50).map(([f, n]) => `${f} ${n}`).join(', ')}), uploaded ${days[0]} to ${days[days.length - 1]}; ${j.spawns} first sights`,
  rule: 'Hypixel checks every 5 ticks; a secret spawns at the first check that finds a party member inside its room within r blocks of pos (3D, from the player\'s feet to the item\'s cell bottom centre, the bat, or the skull block\'s centre).',
  frame: 'pos and packPos are in the room design\'s canonical frame (the rooms pack\'s, FORMAT-rooms 4); secret is the rooms pack secret id (null: a site the pack does not have).',
  fields: 'r: the radius in blocks, err: how far it moved with the timing allowance (12 vs 20 ticks), n: spawns seen, agree: the share of them r explains, f7/m7: r from that floor alone. packPos: where the pack has it, when that differs (bats: the pack has where they were killed).',
  defaults,
  sites,
};
writeFileSync(outFile, JSON.stringify(out, null, 0).replace(/\{"room"/g, '\n{"room"').replace(/\]\}$/, '\n]}') + '\n');
const c = {}; for (const s of sites) c[s.type] = (c[s.type] || 0) + 1;
console.log(sites.length, 'sites', c, 'defaults', defaults, 'event', sites.filter(s => s.r == null).length, 'new', sites.filter(s => !s.secret).length);
