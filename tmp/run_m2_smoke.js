// SkyIsland - M2 combat-slice smoke runner.
//
// Usage:
//   node tmp/run_m2_smoke.js <tag> <timeoutSeconds> [jvm args...]
//
// Why a node runner instead of calling java.exe directly:
//   the local bash shim is broken (exit 127 on dirname/cd/head), so cwd cannot be
//   controlled from the shell. spawn({cwd}) sets it deterministically, which matters
//   because the game writes logs/ screenshots/ saves/ relative to the working directory.
//
// Why not reuse run_m1.js: it hardcodes the M1 jar name, and the artifact is now
//   versioned by the pom (0.2.5-M1.5-FRONTEND). Glob instead of hardcoding so a
//   version bump cannot silently turn "smoke run" into "jar not found".

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const PROJ = 'F:/minecraftspace';
const JAVA = 'D:/software/jdk-25/bin/java.exe';

const tag = process.argv[2] || 'smoke';
const timeoutSeconds = Number(process.argv[3] || 90);
const extra = process.argv.slice(4);

// Why an override exists at all:
//   the jar used to be resolved from target/ every time. That is wrong for a
//   *long* run: Maven rewrites target/ in place, and the JVM lazily reads
//   classes straight out of the jar it was launched with. A concurrent `
//   mvn test`/`clean` therefore yanks the class files out from under a running
//   game. This was observed twice: once as a frame-loop stall (20000-frame
//   stage timeout) and once as a 5x slowdown (17s -> 2m37s).
//   Copy the jar somewhere stable, point SKYISLAND_JAR at it, and no build can
//   disturb the run.
const override = process.env.SKYISLAND_JAR;

let JAR;
if (override) {
  if (!fs.existsSync(override)) {
    console.error('[ERROR] SKYISLAND_JAR does not exist: ' + override);
    process.exit(3);
  }
  JAR = override;
} else {
  const jars = fs.readdirSync(path.join(PROJ, 'target'))
      .filter((f) => /^skyisland-.*\.jar$/.test(f) && !f.startsWith('original-'))
      .map((f) => ({ f, mtime: fs.statSync(path.join(PROJ, 'target', f)).mtimeMs }))
      .sort((a, b) => b.mtime - a.mtime);

  if (jars.length === 0) {
    console.error('[ERROR] no skyisland jar in target/ -> run `node tmp/build.js clean package` first');
    process.exit(3);
  }
  JAR = path.join(PROJ, 'target', jars[0].f);
}

const outFile = PROJ + '/tmp/m2_' + tag + '.stdout.txt';
const errFile = PROJ + '/tmp/m2_' + tag + '.stderr.txt';
const metaFile = PROJ + '/tmp/m2_' + tag + '.meta.json';

const args = ['--enable-native-access=ALL-UNNAMED', ...extra, '-jar', JAR];
const t0 = Date.now();

const out = fs.createWriteStream(outFile);
const err = fs.createWriteStream(errFile);

const p = spawn(JAVA, args, {
  cwd: PROJ,
  windowsHide: true,
  env: Object.assign({}, process.env, { JAVA_HOME: 'D:\\software\\jdk-25' }),
});

let killed = false;
const timer = setTimeout(() => {
  killed = true;
  console.log('[WARN] timeout after ' + timeoutSeconds + 's -> destroying process');
  try { p.kill('SIGKILL'); } catch (e) { /* ignore */ }
}, timeoutSeconds * 1000);

p.stdout.pipe(out);
p.stderr.pipe(err);

p.on('exit', (code, signal) => {
  clearTimeout(timer);
  const elapsed = (Date.now() - t0) / 1000;
  out.end(() => {
    err.end(() => {
      fs.writeFileSync(metaFile, JSON.stringify({
        tag, jar: path.basename(JAR), args, exitCode: code, signal: signal,
        killedByTimeout: killed, elapsedSeconds: Number(elapsed.toFixed(2)),
        stdoutFile: outFile, stderrFile: errFile,
        finishedAt: new Date().toISOString(),
      }, null, 2), 'utf8');
      console.log('=== exit=' + code + ' signal=' + signal + ' killed=' + killed
        + ' elapsed=' + elapsed.toFixed(1) + 's ===');
      console.log('stdout -> ' + outFile);
    });
  });
});
