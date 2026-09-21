// SkyIsland - Maven build runner (mvn clean package).
// Usage: node build.js [goal...]     default goal: clean package

const { spawn } = require('child_process');
const fs = require('fs');

const PROJ = 'F:/minecraftspace';
const MVN = 'D:/software/maven/apache-maven-3.9.9/bin/mvn.cmd';
const goals = process.argv.slice(2);
if (goals.length === 0) { goals.push('clean', 'package'); }

const logFile = PROJ + '/tmp/build.log';
const out = fs.createWriteStream(logFile);

const p = spawn(MVN, ['-s', 'toolchain/settings.xml', '-B', ...goals], {
  cwd: PROJ,
  shell: true,
  windowsHide: true,
  env: Object.assign({}, process.env, {
    JAVA_HOME: 'D:\\software\\jdk-25',
    MAVEN_OPTS: '-Dfile.encoding=UTF-8',
  }),
});

p.stdout.pipe(out);
p.stderr.pipe(out);

const t0 = Date.now();
p.on('exit', (code) => {
  out.end();
  const txt = fs.readFileSync(logFile, 'utf8');
  const lines = txt.split(/\r?\n/).filter((l) => l.trim().length > 0);
  console.log('=== build exit code = ' + code + '  (' + ((Date.now() - t0) / 1000).toFixed(1) + 's) ===');
  console.log(lines.slice(-60).join('\n'));
  process.exit(code === 0 ? 0 : 1);
});
