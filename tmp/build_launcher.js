/*
 * ===========================================================================
 *  Build the native SkyIsland launcher.
 * ===========================================================================
 *
 *  Why this script exists
 *  ----------------------
 *  The launcher used to be compiled by hand. That is exactly the setup that
 *  produced the 2026-10-03 bug: the source moved forward (M3 world name, the
 *  DEV loadout switch, the jar-resolution fix) but the only .exe in the repo
 *  was a frozen 09-23 binary, so the Desktop entry point kept launching the
 *  M2.1 world with M2.1 rules and nobody noticed for two milestones.
 *
 *  `.gitignore` excludes launcher/*.exe on purpose (a ~300 KB binary diff on
 *  every rebuild is not worth it), which means the SOURCE is the tracked
 *  artifact and this script is the only supported way to turn it back into an
 *  .exe. If you change skyisland_launcher.c, run this.
 *
 *  Outputs
 *    launcher/SkyIsland.exe           GUI-ish build: hides its console on a
 *                                     double-click (the Desktop entry point)
 *    launcher/SkyIsland-console.exe   same, but never hides the console
 *                                     (what you want for log capture)
 *
 *  Usage
 *    node tmp/build_launcher.js
 *    node tmp/build_launcher.js --console-only
 *
 *  Toolchain
 *    Expects MinGW-w64 at D:\mingw64 (gcc 16.1.0 + windres). Override with
 *    MINGW_DIR if yours lives elsewhere.
 * ===========================================================================
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const PROJ = 'F:/minecraftspace';
const LAUNCHER = path.join(PROJ, 'launcher');
const BUILD = path.join(LAUNCHER, 'build');
const MINGW = process.env.MINGW_DIR || 'D:\\mingw64';
const INCLUDE = path.join(MINGW, 'x86_64-w64-mingw32', 'include');

const CFLAGS = [
  '-std=c11',
  '-O2',
  '-Wall',
  '-Wextra',
  '-Werror',
  // ★ No -municode: the source has a plain `int main(void)` and only uses the
  //   wide Win32 API (GetCommandLineW etc.), not `wmain`. Passing -municode
  //   makes the CRT demand `wWinMain` and the link fails with an
  //   "undefined reference to wWinMain" that has nothing to do with the source.
  // The launcher links only kernel32/user32-ish bits it gets from windows.h.
  // Static-libgcc + -static keeps the .exe a single file the Desktop can run.
  '-static-libgcc',
  '-static',
];

function run(cmd, args, opts) {
  const r = spawnSync(cmd, args, Object.assign({ stdio: 'inherit' }, opts || {}));
  if (r.error) {
    console.error(`[build_launcher] cannot run ${cmd}: ${r.error.message}`);
    process.exit(1);
  }
  if (r.status !== 0) {
    console.error(`[build_launcher] ${cmd} exited ${r.status}`);
    process.exit(r.status === null ? 1 : r.status);
  }
}

function mustExist(p, what) {
  if (!fs.existsSync(p)) {
    console.error(`[build_launcher] missing ${what}: ${p}`);
    process.exit(1);
  }
}

const consoleOnly = process.argv.includes('--console-only');

mustExist(path.join(MINGW, 'bin', 'gcc.exe'), 'gcc (set MINGW_DIR)');
mustExist(path.join(MINGW, 'bin', 'windres.exe'), 'windres (set MINGW_DIR)');
mustExist(INCLUDE, 'mingw include dir (set MINGW_DIR)');
mustExist(path.join(LAUNCHER, 'skyisland_launcher.c'), 'launcher source');
mustExist(path.join(LAUNCHER, 'skyisland_launcher.rc'), 'launcher .rc');
mustExist(path.join(LAUNCHER, 'skyisland_launcher.manifest'), 'launcher .manifest');
mustExist(path.join(LAUNCHER, 'skyisland.ico'), 'icon');

fs.mkdirSync(BUILD, { recursive: true });

const resObj = path.join(BUILD, 'launcher_res.o');
console.log(`[build_launcher] windres -> ${resObj}`);
run(path.join(MINGW, 'bin', 'windres.exe'), [
  '-I', INCLUDE,
  '-i', path.join(LAUNCHER, 'skyisland_launcher.rc'),
  '-o', resObj,
  // The .rc references "skyisland.ico" by bare name; windres resolves it
  // relative to the include path, which would be the mingw include dir.
  // Point it at launcher/ instead so the checked-in icon is the one embedded.
  '-I', LAUNCHER,
  '-I', INCLUDE,
]);

function build(outName, extraDefines) {
  const out = path.join(LAUNCHER, outName);
  console.log(`[build_launcher] gcc -> ${out}`);
  const r = spawnSync(
    path.join(MINGW, 'bin', 'gcc.exe'),
    CFLAGS.concat([
      ...extraDefines,
      '-I', INCLUDE,
      path.join(LAUNCHER, 'skyisland_launcher.c'),
      resObj,
      '-o', out,
    ]),
    { encoding: 'utf8' }
  );
  if (r.error) {
    console.error(`[build_launcher] cannot run gcc: ${r.error.message}`);
    process.exit(1);
  }
  const noise = `${r.stdout || ''}${r.stderr || ''}`;
  if (noise) {
    process.stdout.write(noise);
  }
  // NB: `.rsrc merge failure: multiple non-default manifests` is a **warning**
  // from mingw's linker, not an error. gcc's spec always links
  // `default-manifest.o`, and skyisland_launcher.rc embeds our own manifest at
  // the same resource id, so the linker complains while still producing a
  // working binary (exit code 0). Verified by running the built exe.
  // Do NOT "fix" it by dropping the manifest from the .rc -- the manifest is
  // what declares asInvoker + dpiAware + longPathAware.
  if (r.status !== 0) {
    console.error(`[build_launcher] gcc exited ${r.status}`);
    process.exit(r.status === null ? 1 : r.status);
  }
  if (!fs.existsSync(out)) {
    console.error(`[build_launcher] gcc reported success but ${out} is missing`);
    process.exit(1);
  }
  const kb = (fs.statSync(out).size / 1024).toFixed(0);
  console.log(`[build_launcher]   ${outName} ${kb} KB`);
}

if (!consoleOnly) {
  build('SkyIsland.exe', []);
}
build('SkyIsland-console.exe', ['-DSKYISLAND_KEEP_CONSOLE=1']);

console.log('[build_launcher] done.');
console.log('[build_launcher] To publish the Desktop entry point, copy SkyIsland.exe');
console.log('[build_launcher] to the Desktop. The .exe is NOT tracked by git;');
console.log('[build_launcher] whoever clones must run this script.');
