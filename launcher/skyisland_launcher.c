/*
 * ===========================================================================
 *  SkyIsland launcher  --  native Windows .exe entry point for the game
 * ===========================================================================
 *
 *  Why this exists
 *  ---------------
 *  The project ships a fat jar plus .bat launchers. A .bat cannot carry an
 *  icon, cannot be pinned to the taskbar properly, and shows a console window
 *  on every double-click. This is a real .exe with the game icon embedded, so
 *  a single double-click entry point can live on the Desktop.
 *
 *  It does exactly what play-m3.bat does, only natively:
 *    1. find the project dir  (SKYISLAND_HOME > the exe's own dir > built-in)
 *    2. find a JDK            (SKYISLAND_JDK > built-in > JAVA_HOME > PATH)
 *    3. require EXACTLY ONE REAL target\skyisland-*.jar. Two *different* jars
 *       means a stale build, and guessing which one to run is how you end up
 *       testing last week's bytecode. Same rule as play-m3.bat, same refusal
 *       to guess. The shade plugin's byte-identical "-shaded.jar" alias and its
 *       "original-*" thin backup are excluded on purpose (see step 3).
 *    4. create the throwaway play save dir
 *    5. run  <java> --enable-native-access=ALL-UNNAMED <extra args>
 *              -Dskyisland.infiniteReserve=true
 *              -Dskyisland.loadout=dev
 *              -Dskyisland.worldName=islands-play
 *              -Dskyisland.settingsFile=<proj>\tmp\islands-play-settings.json
 *              -Dskyisland.saveDir=<proj>\tmp\islands-play-saves
 *              -jar <jar>
 *       (extra args land before the fixed -D switches, exactly as in the .bat,
 *        so the fixed ones always win)
 *
 *  Console behaviour
 *  -----------------
 *  This is a CONSOLE subsystem binary on purpose: that is the only way to keep
 *  stdout redirection (`SkyIsland.exe -version > out.txt`) working, and this
 *  project leans on stdout evidence heavily.
 *    - double-clicked   -> we own the console, so we hide it (clean launch)
 *    - run from a shell -> the console is shared, we leave it alone
 *  Compile with -DSKYISLAND_KEEP_CONSOLE=1 for the variant that never hides
 *  the console (that is the SkyIsland-console.exe build).
 *
 *  Env overrides (all optional; mostly so the failure paths are testable):
 *    SKYISLAND_HOME       project dir
 *    SKYISLAND_JDK        JDK dir
 *    SKYISLAND_NO_DIALOG  =1 -> print errors to stderr instead of showing a
 *                         modal dialog (a modal box would block a test run)
 *
 *  Exit codes: 2 no JDK, 3 not exactly one real jar, 4 cannot prepare dirs,
 *              5 cannot start the JVM, otherwise the game's own exit code.
 *
 *  Build:  node tmp/build_launcher.js   (see that file for why the .exe is
 *          not tracked and therefore must be rebuilt after any source change)
 * ===========================================================================
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>

#define MAXP 4096
#define MAXCMD (MAXP * 4)

static const wchar_t* const kDefaultProjectDir = L"F:\\minecraftspace";
static const wchar_t* const kDefaultJdkDir = L"D:\\software\\jdk-25";

/* ---------------------------------------------------------------------------
 * Play world identity.
 *
 * ★ 2026-10-08: the world name moved again, from "m3-play" to "islands-play".
 *
 * WHY a new name instead of keeping m3-play (and why this matters):
 *   the world *generator* changed. M1–M5 ran TestWorldGenerator (a 64x64 test
 *   platform); 2026-10-08 added IslandWorldGenerator (PRD 4.2: main island
 *   32x32 + four resource islands + starter hut). A save is only "just a set of
 *   block deltas on top of whatever the generator makes" -- so reusing the old
 *   save would apply the old world's dug-out cells and placed blocks on top of
 *   the NEW terrain. The result is not a crash and not a clean start: it is a
 *   world with holes in it that nobody placed and cannot explain.
 *   ⇒ A generator change requires a new save dir, exactly like a
 *     saveVersion bump requires migration. `SaveManager` warns when
 *     generatorId/generatorVersion differ, but "warned and loaded anyway" is
 *     not the outcome we want on a play entry point.
 *
 * The Desktop button is also RENAMED (SkyIsland 启动.exe, no version number in
 * the name). It used to say "M3" while actually loading the M4 island world --
 * a name that lies is worse than no name, because it dates the moment it stops
 * being true. See docs/testing/WORLD_ISLAND_GENERATOR_REPORT.md.
 * ------------------------------------------------------------------------- */
/* ---------------------------------------------------------------------------
 *  CREATIVE variant (compile with -DSKYISLAND_CREATIVE=1)
 * ---------------------------------------------------------------------------
 * WHY a separate binary rather than an argument:
 *   this launcher already forwards extra args, so `SkyIsland.exe
 *   -Dskyisland.gameMode=creative` DOES reach the JVM. And it does nothing.
 *   PRD_BLOCK_CREATIVE 4.3: the mode is fixed when the save is created. The
 *   survival world in this very file already exists, so the game reads
 *   survival from level.json and (correctly) ignores the switch.
 *   The symptom is a perfectly normal game whose inventory has no 创造 tab,
 *   and the only clue is one log line. That is the worst kind of failure:
 *   it looks like a broken build rather than a locked world.
 *
 *   => the creative entry needs its OWN world + save dir, which is what the
 *      SKYISLAND_CREATIVE branch below supplies.
 *
 * ★ These constants MUST stay in lockstep with play-creative.bat.
 *   Guard: tmp/check_creative_sync.js parses BOTH files and compares them.
 *   They are deliberately #define'd and then assigned once, so that the guard
 *   has exactly one `kWorldName = L"..."` per branch to read. Writing two
 *   `static const wchar_t* const kWorldName` lines under #if/#else would make
 *   the guard's regex match whichever came first -- i.e. it would validate the
 *   survival build while the creative build drifted. */
#ifdef SKYISLAND_CREATIVE
#  define SKY_WORLD L"islands-creative"
#  define SKY_SAVE  L"tmp\\islands-creative-saves"
#  define SKY_SET   L"tmp\\islands-creative-settings.json"
/* No infiniteReserve, no loadout=dev: guns are a SURVIVAL-only grant
 * (PRD 5.6), so asking for the dev loadout here would be asking for a thing
 * creative mode never hands out. A wrong guess in either direction is silent. */
#  define SKY_SWITCHES L"-Dskyisland.gameMode=creative "
#else
#  define SKY_WORLD L"islands-play"
#  define SKY_SAVE  L"tmp\\islands-play-saves"
#  define SKY_SET   L"tmp\\islands-play-settings.json"
/* Play switches, kept in lockstep with play.bat. Both are "fail towards the
 * product default": without them this entry point would silently differ from
 * the .bat one, which is exactly the class of bug this file already had.
 *   infiniteReserve=true -> ReserveMode.PROTOTYPE (v2 19-7 debug wording)
 *   loadout=dev          -> rifles + transition material kit, i.e. the DEV /
 *                           TEST loadout. A typo in the value would fall back to
 *                           SURVIVAL (fewer guns), never to a broken launcher. */
#  define SKY_SWITCHES L"-Dskyisland.infiniteReserve=true -Dskyisland.loadout=dev "
#endif

static const wchar_t* const kWorldName = SKY_WORLD;
static const wchar_t* const kSaveRelDir = SKY_SAVE;
static const wchar_t* const kSettingsRel = SKY_SET;
static const wchar_t* const kExtraSwitches = SKY_SWITCHES;

/* ---------------------------------------------------------------------------
 * Small wide-string helpers.
 *
 * Deliberately not swprintf: mingw-w64 maps it to the old msvcrt one, whose
 * signature has no buffer size, and the "safe" _snwprintf_s is not available
 * there either. Hand-rolled bounded copy/concat is boring and portable.
 * ------------------------------------------------------------------------- */
static void w_set(wchar_t* dst, size_t cap, const wchar_t* src) {
    size_t i = 0;
    if (cap == 0) {
        return;
    }
    while (src[i] != 0 && i + 1 < cap) {
        dst[i] = src[i];
        ++i;
    }
    dst[i] = 0;
}

static void w_add(wchar_t* dst, size_t cap, const wchar_t* src) {
    size_t n = 0;
    size_t i = 0;
    while (n < cap && dst[n] != 0) {
        ++n;
    }
    if (n + 1 >= cap) {
        return;
    }
    while (src[i] != 0 && n + 1 < cap) {
        dst[n] = src[i];
        ++n;
        ++i;
    }
    dst[n] = 0;
}

static void w_join(wchar_t* dst, size_t cap, const wchar_t* dir, const wchar_t* rel) {
    size_t n;
    w_set(dst, cap, dir);
    n = wcslen(dst);
    if (n > 0 && dst[n - 1] != L'\\' && dst[n - 1] != L'/') {
        w_add(dst, cap, L"\\");
    }
    w_add(dst, cap, rel);
}

static void w_uint(unsigned v, wchar_t* out, size_t cap) {
    wchar_t tmp[32];
    int n = 0;
    size_t i = 0;
    if (v == 0) {
        w_set(out, cap, L"0");
        return;
    }
    while (v > 0 && n < 31) {
        tmp[n++] = (wchar_t)(L'0' + (v % 10u));
        v /= 10u;
    }
    while (i < (size_t)n && i + 1 < cap) {
        out[i] = tmp[n - 1 - (int)i];
        ++i;
    }
    out[i] = 0;
}

static BOOL w_is_file(const wchar_t* p) {
    DWORD a = GetFileAttributesW(p);
    return (a != INVALID_FILE_ATTRIBUTES) && ((a & FILE_ATTRIBUTE_DIRECTORY) == 0);
}

static BOOL w_is_dir(const wchar_t* p) {
    DWORD a = GetFileAttributesW(p);
    return (a != INVALID_FILE_ATTRIBUTES) && ((a & FILE_ATTRIBUTE_DIRECTORY) != 0);
}

static BOOL env_get(const wchar_t* name, wchar_t* out, size_t cap) {
    DWORD n = GetEnvironmentVariableW(name, out, (DWORD)cap);
    if (n == 0 || n >= (DWORD)cap) {
        if (cap > 0) {
            out[0] = 0;
        }
        return FALSE;
    }
    return TRUE;
}

static BOOL env_is_one(const wchar_t* name) {
    wchar_t b[16];
    if (!env_get(name, b, 16)) {
        return FALSE;
    }
    return b[0] == L'1';
}

/* ---------------------------------------------------------------------------
 * Console output. Messages are ASCII on purpose (same reason the .bat bodies
 * are ASCII): a Chinese console is GBK, and mixing codepages in a diagnostic
 * is how you get an unreadable error at the exact moment you need it most.
 * Paths still go through the OEM/ANSI codepage so they render correctly.
 * ------------------------------------------------------------------------- */
static void say(FILE* f, const wchar_t* w) {
    char buf[MAXP * 2];
    int n;
    buf[0] = 0;
    n = WideCharToMultiByte(CP_ACP, 0, w, -1, buf, (int)sizeof(buf) - 1, NULL, NULL);
    buf[sizeof(buf) - 1] = 0;
    if (n > 0) {
        fputs("[SkyIsland] ", f);
        fputs(buf, f);
        fputc('\n', f);
    }
    fflush(f);
}

static void fail(const wchar_t* msg, int code) {
    say(stderr, msg);
    if (!env_is_one(L"SKYISLAND_NO_DIALOG")) {
        MessageBoxW(NULL, msg, L"SkyIsland launcher", MB_OK | MB_ICONERROR);
    }
    ExitProcess((UINT)code);
}

/* ---------------------------------------------------------------------------
 * Is this file one of the two artefacts the shade plugin leaves behind that are
 * NOT a runnable candidate?
 *
 *   skyisland-<v>-shaded.jar  a BYTE-IDENTICAL alias of the real artifact
 *                             (verified: same sha256, 5794668 B). Counting it
 *                             is what made both this launcher and play-m3.bat
 *                             refuse to start with "Expected exactly 1 jar".
 *   original-skyisland-<v>.jar  the thin pre-shade backup (679100 B, no deps).
 *
 * The suffix test is a FIXED-WIDTH tail compare, not a wildcard, and that is
 * deliberate: the .bat equivalent went through `if ... neq "skyisland-*-shaded"`
 * first, and cmd's string comparison does NOT glob (only `if exist` does), so
 * the alias was counted anyway. Here in C there is no glob trap -- but the
 * lesson is worth keeping in the shape of the code: compare the exact tail.
 * ------------------------------------------------------------------------- */
static BOOL w_is_shade_artefact(const wchar_t* name) {
    static const wchar_t tail[] = L"-shaded.jar";
    const size_t n = wcslen(name);
    const size_t m = (sizeof(tail) / sizeof(tail[0])) - 1; /* 11 */
    if (n < m) {
        return FALSE;
    }
    return _wcsicmp(name + (n - m), tail) == 0;
}

/* Everything after the exe path in the raw command line, preserved verbatim
 * (GetCommandLineW so we do not lose any non-ASCII argument). */
static const wchar_t* extra_args(void) {
    const wchar_t* p = GetCommandLineW();
    if (p == NULL) {
        return L"";
    }
    while (*p == L' ' || *p == L'\t') {
        ++p;
    }
    if (*p == L'"') {
        ++p;
        while (*p != 0 && *p != L'"') {
            ++p;
        }
        if (*p == L'"') {
            ++p;
        }
    } else {
        while (*p != 0 && *p != L' ' && *p != L'\t') {
            ++p;
        }
    }
    while (*p == L' ' || *p == L'\t') {
        ++p;
    }
    return p;
}

int main(void) {
    wchar_t exePath[MAXP];
    wchar_t exeDir[MAXP];
    wchar_t probe[MAXP];
    wchar_t proj[MAXP];
    wchar_t targetDir[MAXP];
    wchar_t jarPath[MAXP];
    wchar_t javaPath[MAXP];
    wchar_t tmpDir[MAXP];
    wchar_t saveDir[MAXP];
    wchar_t settingsPath[MAXP];
    wchar_t buf[MAXP];
    wchar_t msg[MAXP * 2];
    wchar_t info[MAXP * 2];
    wchar_t* cmd;
    const wchar_t* extra;
    int jarCount = 0;
    size_t i;
    DWORD exitCode = 0;
    STARTUPINFOW si;
    PROCESS_INFORMATION pi;

    /* ---- console policy: hide it only when we own it (a double-click) ---- */
#ifndef SKYISLAND_KEEP_CONSOLE
    {
        DWORD pids[8];
        DWORD nproc = GetConsoleProcessList(pids, 8);
        if (nproc <= 1) {
            HWND hcon = GetConsoleWindow();
            if (hcon != NULL) {
                ShowWindow(hcon, SW_HIDE);
            }
        }
    }
#endif

    /* ---- 1. project dir ------------------------------------------------- */
    if (env_get(L"SKYISLAND_HOME", buf, MAXP) && buf[0] != 0) {
        w_set(proj, MAXP, buf);
    } else {
        exePath[0] = 0;
        GetModuleFileNameW(NULL, exePath, MAXP);
        exePath[MAXP - 1] = 0;
        w_set(exeDir, MAXP, exePath);
        i = wcslen(exeDir);
        while (i > 0 && exeDir[i - 1] != L'\\' && exeDir[i - 1] != L'/') {
            --i;
        }
        if (i > 0) {
            exeDir[i - 1] = 0;
        } else {
            w_set(exeDir, MAXP, L".");
        }
        w_join(probe, MAXP, exeDir, L"target");
        if (w_is_dir(probe)) {
            w_set(proj, MAXP, exeDir);
        } else {
            w_set(proj, MAXP, kDefaultProjectDir);
        }
    }

    /* ---- 2. java --------------------------------------------------------
     * Order: SKYISLAND_JDK > the built-in jdk-25 > JAVA_HOME > PATH.
     *
     * WHY the built-in comes before JAVA_HOME: this project builds with JDK 25
     * and that is what the .bat launchers use. JAVA_HOME on this machine points
     * at jdk-23, and the old order silently ran the game on 23 while every log
     * and gate said 25 -- "works fine either way" until it does not, at which
     * point nothing in the evidence points at the real cause. */
    javaPath[0] = 0;
    if (env_get(L"SKYISLAND_JDK", buf, MAXP) && buf[0] != 0) {
        w_join(javaPath, MAXP, buf, L"bin\\java.exe");
    }
    if (javaPath[0] == 0 || !w_is_file(javaPath)) {
        w_join(javaPath, MAXP, kDefaultJdkDir, L"bin\\java.exe");
    }
    if (javaPath[0] == 0 || !w_is_file(javaPath)) {
        if (env_get(L"JAVA_HOME", buf, MAXP) && buf[0] != 0) {
            w_join(javaPath, MAXP, buf, L"bin\\java.exe");
        }
    }
    if (!w_is_file(javaPath)) {
        w_set(javaPath, MAXP, L"java");
    }
    if (wcscmp(javaPath, L"java") == 0) {
        w_set(msg, MAXP * 2, L"No JDK found.\n\n");
        w_add(msg, MAXP * 2, L"Tried, in order:\n");
        w_add(msg, MAXP * 2, L"    $SKYISLAND_JDK\\bin\\java.exe\n");
        w_add(msg, MAXP * 2, L"    ");
        w_add(msg, MAXP * 2, kDefaultJdkDir);
        w_add(msg, MAXP * 2, L"\\bin\\java.exe\n");
        w_add(msg, MAXP * 2, L"    $JAVA_HOME\\bin\\java.exe\n\n");
        w_add(msg, MAXP * 2, L"Install a JDK 21+ (this project builds on 25).");
        fail(msg, 2);
    }

    /* ---- 3. the jar ----------------------------------------------------- */
    w_join(targetDir, MAXP, proj, L"target");
    jarPath[0] = 0;
    {
        wchar_t pat[MAXP];
        WIN32_FIND_DATAW fd;
        HANDLE h;
        w_join(pat, MAXP, targetDir, L"skyisland-*.jar");
        h = FindFirstFileW(pat, &fd);
        if (h != INVALID_HANDLE_VALUE) {
            do {
                if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
                    continue;
                }
                /* never the shade plugin's leftover artefacts: the byte-identical
                 * "-shaded.jar" alias (it matches this very glob) and the
                 * "original-*" thin backup. Neither is a second version. */
                if (w_is_shade_artefact(fd.cFileName)) {
                    continue;
                }
                if (_wcsnicmp(fd.cFileName, L"skyisland-", 10) != 0) {
                    continue;
                }
                ++jarCount;
                if (jarCount == 1) {
                    w_join(jarPath, MAXP, targetDir, fd.cFileName);
                }
            } while (FindNextFileW(h, &fd));
            FindClose(h);
        }
    }
    if (jarCount != 1) {
        wchar_t num[32];
        w_set(msg, MAXP * 2, L"Expected exactly 1 runnable jar in\n");
        w_add(msg, MAXP * 2, targetDir);
        w_add(msg, MAXP * 2, L"\n\nfound ");
        w_uint((unsigned)jarCount, num, 32);
        w_add(msg, MAXP * 2, num);
        w_add(msg, MAXP * 2, L".\n\nIgnored on purpose: original-*.jar (thin pre-shade backup) "
                              L"and *-shaded.jar (byte-identical alias of the real artifact).\n\n"
                              L"Two DIFFERENT skyisland-*.jar files means a stale build. "
                              L"Which is actually there:\n");
        {
            /* Same reasoning as play-m3.bat's `dir /b` in the failure branch:
             * a guard that only says "found 2" leaves the person stuck at
             * "what do I even have". Listing the directory is 3 seconds of work. */
            wchar_t listPat[MAXP];
            WIN32_FIND_DATAW lfd;
            HANDLE lh;
            w_join(listPat, MAXP, targetDir, L"*.jar");
            lh = FindFirstFileW(listPat, &lfd);
            if (lh != INVALID_HANDLE_VALUE) {
                do {
                    if (lfd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
                        continue;
                    }
                    w_add(msg, MAXP * 2, L"    ");
                    w_add(msg, MAXP * 2, lfd.cFileName);
                    w_add(msg, MAXP * 2, L"\n");
                } while (FindNextFileW(lh, &lfd));
                FindClose(lh);
            }
        }
        w_add(msg, MAXP * 2,
          L"\nThis is expected right after a world-generator change: the play save\n"
          L"dir was renamed with it (the old one holds block deltas that no longer\n"
          L"match the terrain). If you see this on a normal launch, rebuild with:\n"
          L"    node tmp/build.js clean package");
        fail(msg, 3);
    }

    /* ---- 4. throwaway play dirs ---------------------------------------- */
    w_join(tmpDir, MAXP, proj, L"tmp");
    w_join(saveDir, MAXP, proj, kSaveRelDir);
    w_join(settingsPath, MAXP, proj, kSettingsRel);
    CreateDirectoryW(tmpDir, NULL);
    CreateDirectoryW(saveDir, NULL);
    if (!w_is_dir(saveDir)) {
        w_set(msg, MAXP * 2, L"Cannot create the play save dir:\n");
        w_add(msg, MAXP * 2, saveDir);
        fail(msg, 4);
    }

    /* ---- 4b. warn when a creative world already exists -------------------
     * The one failure mode of this entry point that is completely silent.
     * PRD_BLOCK_CREATIVE 4.3 fixes the mode when the save is created, so if
     * this world already exists the -Dskyisland.gameMode=creative switch is
     * ignored -- and the game looks entirely normal except the inventory has
     * no 创造 tab. Say it here, where there is still a console.
     *
     * Deliberately a warning on stdout, not `fail()`: an existing save is a
     * perfectly legitimate thing to re-open, so refusing to launch would be
     * wrong. It only means "you are not getting creative this time". */
#ifdef SKYISLAND_CREATIVE
    {
        wchar_t levelPath[MAXP];
        w_join(levelPath, MAXP, saveDir, kWorldName);
        w_add(levelPath, MAXP, L"\\level.json");
        if (w_is_file(levelPath)) {
            wchar_t warn[MAXP * 2];
            w_set(warn, MAXP * 2, L"[WARN] This creative world ALREADY EXISTS.\n"
                                    L"[WARN] The game mode is fixed when a save is created\n"
                                    L"[WARN] (PRD 4.3), so this run keeps whatever mode it\n"
                                    L"[WARN] was created with. If the CREATIVE tab is missing,\n"
                                    L"[WARN] that is why -- it is not a broken build.\n"
                                    L"[WARN] For a fresh creative world, delete or rename:\n"
                                    L"[WARN]   ");
            w_add(warn, MAXP * 2, levelPath);
            say(stdout, warn);
        }
    }
#endif
    if (!SetCurrentDirectoryW(proj)) {
        w_set(msg, MAXP * 2, L"Cannot switch to the project dir:\n");
        w_add(msg, MAXP * 2, proj);
        fail(msg, 4);
    }

    /* ---- 5. launch ----------------------------------------------------- */
    cmd = (wchar_t*)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, MAXCMD * sizeof(wchar_t));
    if (cmd == NULL) {
        fail(L"Out of memory.", 5);
    }
    w_add(cmd, MAXCMD, L"\"");
    w_add(cmd, MAXCMD, javaPath);
    w_add(cmd, MAXCMD, L"\" --enable-native-access=ALL-UNNAMED ");
    extra = extra_args();
    if (extra[0] != 0) {
        w_add(cmd, MAXCMD, extra);
        w_add(cmd, MAXCMD, L" ");
    }
    w_add(cmd, MAXCMD, kExtraSwitches);
    w_add(cmd, MAXCMD, L"-Dskyisland.worldName=");
    w_add(cmd, MAXCMD, kWorldName);
    w_add(cmd, MAXCMD, L" -Dskyisland.settingsFile=\"");
    w_add(cmd, MAXCMD, settingsPath);
    w_add(cmd, MAXCMD, L"\" -Dskyisland.saveDir=\"");
    w_add(cmd, MAXCMD, saveDir);
    w_add(cmd, MAXCMD, L"\" -jar \"");
    w_add(cmd, MAXCMD, jarPath);
    w_add(cmd, MAXCMD, L"\"");

    w_set(info, MAXP * 2, L"SkyIsland launcher\n  jar      : ");
    w_add(info, MAXP * 2, jarPath);
    w_add(info, MAXP * 2, L"\n  world    : ");
    w_add(info, MAXP * 2, kWorldName);
    w_add(info, MAXP * 2, L"\n  save dir : ");
    w_add(info, MAXP * 2, saveDir);
    w_add(info, MAXP * 2, L"\n  switches : ");
    w_add(info, MAXP * 2, kExtraSwitches);
    w_add(info, MAXP * 2, L"\n  java     : ");
    w_add(info, MAXP * 2, javaPath);
    say(stdout, info);
    say(stdout, L"launching (this window hides itself on a double-click; use SkyIsland-console.exe to keep it)");

    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    si.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    si.hStdError = GetStdHandle(STD_ERROR_HANDLE);

    if (!CreateProcessW(NULL, cmd, NULL, NULL, TRUE, 0, NULL, NULL, &si, &pi)) {
        wchar_t num[32];
        w_set(msg, MAXP * 2, L"Could not start the JVM (CreateProcess failed, error ");
        w_uint((unsigned)GetLastError(), num, 32);
        w_add(msg, MAXP * 2, num);
        w_add(msg, MAXP * 2, L").\n\nCommand was:\n");
        w_add(msg, MAXP * 2, cmd);
        HeapFree(GetProcessHeap(), 0, cmd);
        fail(msg, 5);
    }
    HeapFree(GetProcessHeap(), 0, cmd);

    CloseHandle(pi.hThread);
    WaitForSingleObject(pi.hProcess, INFINITE);
    GetExitCodeProcess(pi.hProcess, &exitCode);
    CloseHandle(pi.hProcess);

    if (exitCode != 0) {
        wchar_t num[32];
        w_set(msg, MAXP * 2, L"SkyIsland exited with code ");
        w_uint((unsigned)exitCode, num, 32);
        w_add(msg, MAXP * 2, num);
        w_add(msg, MAXP * 2, L".\n\nThe game also writes its own log; see the newest file in:\n");
        w_add(msg, MAXP * 2, proj);
        w_add(msg, MAXP * 2, L"\\logs");
        fail(msg, (int)exitCode);
    }

    say(stdout, L"SkyIsland exited cleanly (code 0).");
    return 0;
}
