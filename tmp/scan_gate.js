const fs = require('fs');
function dec(p) { return new TextDecoder('gbk').decode(fs.readFileSync(p)); }

console.log('##### 三个 gate saveDir 的内容（上一轮留下的存档）#####');
for (const d of ['gate-m1-saves', 'gate-ui-saves', 'gate-m2-saves']) {
  const p = 'F:/minecraftspace/tmp/' + d;
  if (!fs.existsSync(p)) { console.log(d + ' -> 不存在'); continue; }
  const walk = (q, pre) => fs.readdirSync(q, { withFileTypes: true }).forEach(e => {
    const full = q + '/' + e.name;
    if (e.isDirectory()) walk(full, pre + e.name + '/');
    else console.log('  ' + d + '/' + pre + e.name + '  ' + fs.statSync(full).size + ' B');
  });
  console.log(d + ' :');
  walk(p, '');
}

console.log('');
console.log('##### 本轮各门禁日志里的"读档/新世界"字样 #####');
for (const t of ['m1', 'ui', 'm2']) {
  const L = dec('F:/minecraftspace/tmp/m2_gate-' + t + '.stdout.txt').split(/\r?\n/);
  console.log('-- ' + t + ' --');
  L.forEach(l => { if (/读档|新世界|已应用存档|存档已存在|未找到/.test(l)) console.log('   ' + l.trim()); });
}
