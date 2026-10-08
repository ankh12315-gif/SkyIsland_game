/*
 * ===========================================================================
 *  ImeBridge -- stop the IME from stealing keys inside the game window
 * ===========================================================================
 *
 *  THE PROBLEM
 *  -----------
 *  Chinese Windows ships an IME (Microsoft Pinyin) whose "use SHIFT to switch
 *  Chinese/English" option is on by default. While the game window has focus,
 *  pressing SHIFT flips the IME instead of reaching the game -- so flight
 *  descend (SHIFT while flying) silently does nothing.
 *
 *  WHY THE FIRST ATTEMPT DID NOT WORK (kept, because the lesson is the point)
 *  -------------------------------------------------------------------------
 *  Attempt 1 called `ImmAssociateContextEx(hwnd, NULL, IACE_DEFAULT)` right
 *  after window creation. It "worked" by its own measurement --
 *  ImmGetContext() went from non-NULL to NULL -- and Shift still toggled the
 *  IME. Two reasons, both worth keeping in the file header:
 *
 *    1. FOCUS RE-ACTIVATES THE IME. Window#claimForeground() calls
 *       glfwShowWindow + glfwFocusWindow AGAIN, long after the detach, and
 *       the IME comes back with the focus. Detaching once at creation is a
 *       snapshot, not a rule.
 *
 *    2. MORE IMPORTANT: IMM32's default-context mechanism is the *legacy* input
 *       path. Since Windows 8 the active path is TSF (Text Services
 *       Framework, msctf.dll). TSF's SHIFT-toggle is driven by the window
 *       accepting IME context changes via WM_IME_SETCONTEXT, and detaching an
 *       IMM context does not opt the window out of that.
 *
 *  THE FIX ACTUALLY USED
 *  ---------------------
 *  Subclass the window procedure and:
 *
 *    - WM_IME_SETCONTEXT -> detach the IMM context and return 0.
 *      Returning 0 is the documented "this window does not want IME context
 *      changes", and it is the message TSF actually honours. Because it runs
 *      on *every* activation, it also covers reason 1 automatically.
 *
 *    - WM_ACTIVATE -> detach again, defensively.
 *
 *    - everything else -> CallWindowProcW on the previous proc, untouched.
 *
 *  This is the standard Win32 "games disable IME in their window" technique.
 *
 *  ★ WHY IT STILL EXPOSES QUERIES AND COUNTERS
 *    "We subclassed it" is not evidence that it works. These expose:
 *      - ImmGetContext() before/after,  (is the IMM context really gone)
 *      - a counter of messages our proc actually saw (did we even get called)
 *      - a counter of WM_IME_SETCONTEXT specifically (is this the right lever)
 *    A subclass that is never invoked looks exactly like one that works, from
 *    inside the process. The game logs all of it so the claim is checkable
 *    instead of "trust me, press SHIFT".
 *
 *  RUNTIME DEGRADATION
 *  -------------------
 *  Loading this DLL is OPTIONAL. If it is missing, `ImeBridge.isAvailable()`
 *  returns false, the game logs ONE warning, and everything else keeps
 *  working. A missing optional native helper must never stop the game.
 *
 *  Build:  node tmp/build_launcher.js   (also builds this DLL)
 * ===========================================================================
 */

#include <jni.h>
#include <windows.h>
#include <imm.h>

/*
 * Window procedure type + the previous proc.
 *
 * ★ `CALLBACK` must NOT appear on a *function pointer type* here: on x64
 *   MinGW, `CALLBACK` expands to `__stdcall`, which x64 does not have, and
 *   gcc rejects it with
 *       "'stdcall' attribute only applies to function types"
 *   while `-Werror` turns it into a build failure. `WINAPI` is the portable
 *   spelling (empty on x64, correct on x86). Using the same typedef for the
 *   declaration and the pointer also removes the int-conversion error that
 *   comes from casting a raw integer without the right target type.
 *
 * Only one game window exists per process, so the previous proc is a plain
 * static. It is a static rather than a per-window map on purpose: a map would
 * need allocation and cleanup rules for a window that is created once and
 * destroyed at exit, and getting that wrong is a use-after-free.
 */
typedef LRESULT (WINAPI *skyi_wndproc_t)(HWND, UINT, WPARAM, LPARAM);

static LRESULT WINAPI skyisland_ime_wndproc(HWND hwnd, UINT msg,
                                            WPARAM wparam, LPARAM lparam);

/* NULL = not installed yet. */
static skyi_wndproc_t prev_proc = NULL;

/* Diagnostics -- see the header on why these exist. */
static volatile LONG proc_calls = 0;
static volatile LONG ime_context_msgs = 0;
static volatile LONG inputlang_msgs = 0;

static void detach_imm(HWND hwnd)
{
    ImmAssociateContextEx(hwnd, NULL, IACE_DEFAULT | IACE_IGNORENOCONTEXT);
}

static LRESULT WINAPI skyisland_ime_wndproc(HWND hwnd, UINT msg,
                                            WPARAM wparam, LPARAM lparam)
{
    InterlockedIncrement(&proc_calls);

    switch (msg) {
    case WM_IME_SETCONTEXT:
        /*
         * ★ MEASURED: on this machine this message arrives **zero** times
         *   (see the `ime =` line in the measurement summary). It is kept
         *   because it is the documented lever on older systems and costs
         *   nothing, but it must NOT be assumed to be the mechanism here.
         *   Counting it is the whole point: without the counter this would
         *   have looked like a working fix forever.
         */
        InterlockedIncrement(&ime_context_msgs);
        detach_imm(hwnd);
        return 0;

    case WM_INPUTLANGCHANGEREQUEST:
        /*
         * Second lever, also counted. The IME sends this when it wants the
         * *app* to switch input language. Refusing it costs nothing when the
         * IME never sends it, and blocks the switch when it does.
         */
        InterlockedIncrement(&inputlang_msgs);
        return 0;

    case WM_ACTIVATE:
        /*
         * Focus brings the IME back (see header reason 1). Detaching here as
         * well means we do not depend on guessing every activation path.
         */
        detach_imm(hwnd);
        break;

    default:
        break;
    }

    if (prev_proc == NULL) {
        return DefWindowProcW(hwnd, msg, wparam, lparam);
    }
    return CallWindowProcW(prev_proc, hwnd, msg, wparam, lparam);
}

JNIEXPORT jboolean JNICALL
Java_com_skyisland_platform_ImeBridge_nInstallForWindow(JNIEnv *env, jclass cls, jlong hwnd)
{
    HWND h = (HWND)(intptr_t)hwnd;
    if (h == NULL) {
        return JNI_FALSE;
    }
    (void)env;
    (void)cls;

    InterlockedExchange(&proc_calls, 0);
    InterlockedExchange(&ime_context_msgs, 0);
    InterlockedExchange(&inputlang_msgs, 0);

    prev_proc = (skyi_wndproc_t)(intptr_t)GetWindowLongPtrW(h, GWLP_WNDPROC);
    if (prev_proc == NULL) {
        return JNI_FALSE;
    }

    if (SetWindowLongPtrW(h, GWLP_WNDPROC,
                          (LONG_PTR)(intptr_t)skyisland_ime_wndproc) == 0) {
        /* Keep the previous proc so a later call can retry cleanly. */
        return JNI_FALSE;
    }

    /*
     * Verify by reading back rather than trusting SetWindowLongPtr's return:
     * on failure it returns 0, and a legitimate wndproc address is never 0,
     * but reading back also proves the value is OUR proc and not a stale
     * cache of what we asked for.
     */
    skyi_wndproc_t now = (skyi_wndproc_t)(intptr_t)GetWindowLongPtrW(h, GWLP_WNDPROC);
    if (now != skyisland_ime_wndproc) {
        return JNI_FALSE;
    }

    detach_imm(h);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_skyisland_platform_ImeBridge_nIsInstalledForWindow(JNIEnv *env, jclass cls, jlong hwnd)
{
    HWND h = (HWND)(intptr_t)hwnd;
    if (h == NULL) {
        return JNI_FALSE;
    }
    (void)env;
    (void)cls;

    skyi_wndproc_t now = (skyi_wndproc_t)(intptr_t)GetWindowLongPtrW(h, GWLP_WNDPROC);
    return now == skyisland_ime_wndproc ? JNI_TRUE : JNI_FALSE;
}

/* How many messages our proc actually saw. 0 = we were never called. */
JNIEXPORT jint JNICALL
Java_com_skyisland_platform_ImeBridge_nProcCallCount(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)InterlockedCompareExchange(&proc_calls, 0, 0);
}

/* How many WM_IME_SETCONTEXT we intercepted. 0 = wrong lever. */
JNIEXPORT jint JNICALL
Java_com_skyisland_platform_ImeBridge_nImeContextMsgCount(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)InterlockedCompareExchange(&ime_context_msgs, 0, 0);
}

/* How many WM_INPUTLANGCHANGEREQUEST we refused. */
JNIEXPORT jint JNICALL
Java_com_skyisland_platform_ImeBridge_nInputLangMsgCount(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)InterlockedCompareExchange(&inputlang_msgs, 0, 0);
}

JNIEXPORT jboolean JNICALL
Java_com_skyisland_platform_ImeBridge_nDisableForWindow(JNIEnv *env, jclass cls, jlong hwnd)
{
    HWND h = (HWND)(intptr_t)hwnd;
    if (h == NULL) {
        return JNI_FALSE;
    }
    (void)env;
    (void)cls;

    detach_imm(h);
    /* Authoritative check, not SetLastError: a shim that reports success
     * while the IME is still attached would make the game's log lie. */
    return ImmGetContext(h) == NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_skyisland_platform_ImeBridge_nIsEnabledForWindow(JNIEnv *env, jclass cls, jlong hwnd)
{
    HWND h = (HWND)(intptr_t)hwnd;
    if (h == NULL) {
        return JNI_FALSE;
    }
    (void)env;
    (void)cls;

    return ImmGetContext(h) != NULL ? JNI_TRUE : JNI_FALSE;
}