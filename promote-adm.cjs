// node promote-adm.cjs <public tree>: run by promote.sh. This repo builds Engineer Client; the public release
// is ADM (Advanced Dungeons Mod). Renames what players see (mod id and name, panel, chat prefix, commands,
// config and log folders, jar and release names, README); packages stay com.engineerclient. Exits non-zero
// when a rename it relies on matched nothing, or when the old name is still showing somewhere.
const fs = require('fs'), path = require('path');
const root = process.argv[2];
if (!root) { console.error('usage: node promote-adm.cjs <public tree>'); process.exit(2); }

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) { if (e.name !== '.git' && e.name !== 'build' && e.name !== 'libs') walk(p); }
    else if (/\.(kt|java|json|kts|yml|md)$/.test(e.name)) files.push(p);
  }
})(root);

// [pattern, replacement, required]: a required rule must match somewhere in the tree.
const rules = [
  // Mod metadata, jar and releases.
  ['"id": "engineerclient",', '"id": "adm",', true],
  ['"name": "Engineer Client",', '"name": "ADM",', true],
  ['"description": "An Odin addon for Hypixel SkyBlock dungeons:', '"description": "Advanced Dungeons Mod, an Odin addon for Hypixel SkyBlock dungeons:', true],
  // An old Engineer Client jar left in mods is then a duplicate (an error naming both), not a second copy.
  ['  "license": "All-Rights-Reserved",\n', '  "license": "All-Rights-Reserved",\n  "provides": ["engineerclient"],\n', true],
  ['archivesName.set("engineerclient")', 'archivesName.set("adm")', true],
  ['# releases/download/release-<channel>/EngineerClient-<mc>.jar', '# releases/download/release-<channel>/ADM-<mc>.jar', false],
  ['jar="EngineerClient-$MC.jar"', 'jar="ADM-$MC.jar"', true],
  ['**[Download EngineerClient for Minecraft %s](%s)**', '**[Download ADM for Minecraft %s](%s)**', true],
  ['--title "EngineerClient for Minecraft $MC"', '--title "ADM for Minecraft $MC"', true],
  // Panel, prefix, commands, text.
  ['Category.custom("Engineer Client"', 'Category.custom("ADM"', true],
  ['const val PREFIX = "§8[§6EC§8] "', 'const val PREFIX = "§8[§6ADM§8] "', true],
  // /adm opens Odin's menu; /ec stays the command for the rest.
  ['listOf("engineerclient", "EngineerClient", "ec")', 'listOf("ec")', true],
  ['Added by engineerClient.', 'Added by ADM.', true],
  ['desc = "engineerClient\'s solver', 'desc = "ADM\'s solver', false],
  ['"EC Dynamic Term Roles is OFF", "enable it in the Engineer Client panel"', '"ADM Dynamic Term Roles is OFF", "enable it in the ADM panel"', false],
  ['ENGINEER_SPLITS("Engineer Splits")', 'ENGINEER_SPLITS("ADM Splits")', true],
  ['desc = "Odin Splits, or Engineer Splits:', 'desc = "Odin Splits, or ADM Splits:', false],
  ['desc = "Engineer Splits: ', 'desc = "ADM Splits: ', false],
  ['Look: Engineer Splits', 'Look: ADM Splits', false],
  // Settings and folders (ConfigMigration moves an Engineer Client install's over on first start).
  ['private const val NAME = "engineerclient"', 'private const val NAME = "adm"', true],
  ['ModuleConfig("engineerclient.json")', 'ModuleConfig("adm.json")', true],
  ['.resolve("engineerclient.json")', '.resolve("adm.json")', true],
  ['getModContainer("engineerclient")', 'getModContainer("adm")', true],
  ['"engineerclient ${FabricLoader', '"adm ${FabricLoader', false],
  ['resolve("config").resolve("engineerclient")', 'resolve("config").resolve("adm")', true],
  ['resolve("logs").resolve("engineerclient")', 'resolve("logs").resolve("adm")', true],
  ['configDir.resolve("engineerclient")', 'configDir.resolve("adm")', false],
  ['config/engineerclient', 'config/adm', true],
  ['addons/engineerclient.json', 'addons/adm.json', false],
  ['logs/engineerclient', 'logs/adm', false],
  // README.
  ['# EngineerClient\n\nAn [Odin]', '# ADM\n\nAdvanced Dungeons Mod: an [Odin]', false],
  ['https://github.com/undonecoffee/EngineerClient/', 'https://github.com/undonecoffee/adm/', false],
  [/EngineerClient-(26[\d.]*)\.jar/g, 'ADM-$1.jar', false],
  ['released yet; EngineerClient 26.3 is built', 'released yet; ADM 26.3 is built', false],
];

const hits = new Array(rules.length).fill(0);
for (const f of files) {
  let s = fs.readFileSync(f, 'utf8');
  const o = s;
  rules.forEach(([a, b], i) => {
    if (a instanceof RegExp) { const n = (s.match(a) || []).length; if (n) { hits[i] += n; s = s.replace(a, b); } }
    else if (s.includes(a)) { hits[i] += s.split(a).length - 1; s = s.split(a).join(b); }
  });
  if (s !== o) fs.writeFileSync(f, s);
}

let bad = false;
rules.forEach(([a], i) => { if (rules[i][2] && !hits[i]) { console.error('promote-adm: nothing matched ' + JSON.stringify(String(a))); bad = true; } });
// What players would still see of the old name.
const leftovers = [/Category\.custom\("Engineer Client"/, /"name": "Engineer Client"/, /"id": "engineerclient"/, /§6EC§8/, /config\/engineerclient/, /ModuleConfig\("engineerclient/, /Added by engineerClient/, /undonecoffee\/EngineerClient/];
for (const f of files) {
  const s = fs.readFileSync(f, 'utf8');
  for (const re of leftovers) if (re.test(s)) { console.error('promote-adm: ' + path.relative(root, f) + ' still has ' + re); bad = true; }
}
process.exit(bad ? 1 : 0);
