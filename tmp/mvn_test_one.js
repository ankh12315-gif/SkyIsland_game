// 只跑一个测试类，并把 surefire 的失败断言名打到 stdout。
//
// 为什么需要它：反向验证脚本要反复"注入断线 → 跑一条测试 → 看哪条红了"。
// 直接调 mvn 的话每次都要起一个 JVM（约 3 秒）而且输出要自己从
// surefire 报告里挖；这个包装把「跑哪个类」与「怎么读失败」两件事
// 固化下来，反向验证脚本就不用重复实现一遍。
//
// 用法：node tmp/mvn_test_one.js ResourceCoreRegenTest
'use strict';

const path = require('path');
const { spawnSync } = require('child_process');

const PROJ = process.env.SKYISLAND_PROJ || 'F:/minecraftspace';
const MVN = process.env.MVN || 'D:\\software\\maven\\apache-maven-3.9.9\\bin\\mvn.cmd';
const JDK = process.env.JDK_HOME || 'D:\\software\\jdk-25';

const cls = process.argv[2];
if (!cls) {
  console.error('[mvn_test_one] usage: node tmp/mvn_test_one.js <TestClass>');
  process.exit(2);
}

const r = spawnSync(MVN, [
  '-s', path.join(PROJ, 'toolchain', 'settings.xml'),
  '-B', 'test', `-Dtest=${cls}`,
], {
  cwd: PROJ,
  shell: true,
  windowsHide: true,
  encoding: 'utf8',
  env: Object.assign({}, process.env, {
    JAVA_HOME: JDK,
    MAVEN_OPTS: '-Dfile.encoding=UTF-8',
  }),
  timeout: 600000,
});

const out = (r.stdout || '') + (r.stderr || '');
// 只保留有用行：失败摘要 + 断言消息 + 编译错误。
// 全量输出有几百行噪声，而反向验证只关心"哪条红了、为什么"。
const keep = out.split(/\r?\n/).filter(l =>
  /Tests run:.*Skipped|ERROR\]   |AssertionFailedError|expected:|BUILD (SUCCESS|FAILURE)|COMPILATION|\.java:\[\d+,\d+\]/.test(l));
console.log(keep.join('\n'));

process.exit(r.status === null ? 1 : r.status);