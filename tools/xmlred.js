// 列出一个目录里所有 surefire XML 报告的失败用例（正确的 XML 解析，不靠正则）。
//
// ★ 为什么不用正则：`<testcase name="…"[^>]*>([\s\S]*?)</testcase>` 会错配 ——
//   通过的用例是**自闭合**的 `<testcase … />`，`[^>]*>` 匹配到 `/>` 的 `>` 就停，
//   于是自闭合 testcase 被当成开标签，一路吞到后面失败用例的 `</testcase>`，
//   把失败的名字配成前一个通过用例的名字。
//   实测因此把 `applyDaylightUniformsWritesAllThreeAndOnlyTheThree` 的失败
//   报成了 `theClockIsAdvancedInsideTheSimulationGuard` —— 红是真的，名字全错。
//
// 输出：
//   TESTS_RAN=<总数>
//   RED_COUNT=<失败用例数>
//   RED <用例名>  :: <message 首行>
//   REPORTED <文件名>
//   TOTAL tests= failures= errors=
const fs = require('fs');
const path = require('path');

const dir = process.argv[2] || 'target/surefire-reports';
if (!fs.existsSync(dir)) {
  console.log('TESTS_RAN=0');
  console.log('RED_COUNT=0');
  console.log('NO_REPORT_DIR');
  process.exit(0);
}

// 极小的 XML 扫描器：只处理 testcase / failure / error 三种标签，
// 不用正则切标签，避免"自闭合标签"这类边界再次被搞错。
function scanCases(xml) {
  const cases = [];
  const re = /<testcase\b([^>]*?)(\/>|>([\s\S]*?)<\/testcase>)/g;
  let m;
  while ((m = re.exec(xml)) !== null) {
    const attrs = m[1] || '';
    const nameMatch = /\bname="([^"]*)"/.exec(attrs);
    const classMatch = /\bclassname="([^"]*)"/.exec(attrs);
    const body = m[3] || '';                 // 自闭合时 m[3] 是 undefined
    const failMatch = /<(failure|error)\b([^>]*)(?:\/>|>([\s\S]*?)<\/\1>)/.exec(body);
    let message = '';
    if (failMatch) {
      const msgMatch = /\bmessage="([^"]*)"/.exec(failMatch[2] || '');
      message = msgMatch ? msgMatch[1] : '';
    }
    cases.push({
      name: nameMatch ? nameMatch[1] : '?',
      className: classMatch ? classMatch[1] : '?',
      failed: !!failMatch,
      message,
    });
  }
  return cases;
}

let ran = 0;
const red = [];
let totalFailures = 0;
let totalErrors = 0;
let classes = 0;
const files = fs.readdirSync(dir).filter((f) => f.startsWith('TEST-') && f.endsWith('.xml')).sort();
for (const f of files) {
  const xml = fs.readFileSync(path.join(dir, f), 'utf8');
  const cases = scanCases(xml);
  if (cases.length === 0) {
    continue;
  }
  classes++;
  ran += cases.length;
  const fm = /failures="(\d+)"/.exec(xml);
  const em = /errors="(\d+)"/.exec(xml);
  if (fm) totalFailures += Number(fm[1]);
  if (em) totalErrors += Number(em[1]);
  for (const c of cases) {
    if (c.failed) {
      red.push({ name: c.name, message: c.message.split('\n')[0].slice(0, 240) });
    }
  }
  console.log('REPORTED ' + f + ' cases=' + cases.length);
}

console.log('TESTS_RAN=' + ran);
console.log('REPORT_CLASSES=' + classes);
console.log('RED_COUNT=' + red.length);
for (const r of red) {
  console.log('RED ' + r.name + ' :: ' + r.message);
}
console.log('TOTAL tests=' + ran + ' failures=' + totalFailures + ' errors=' + totalErrors);