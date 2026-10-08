/*
 * ===========================================================================
 *  ImeBridge -- remove the IME context from the game window (Windows only)
 * ===========================================================================
 *
 *  WHAT PROBLEM THIS SOLVES
 * ------------------------
 *  Mainland China Windows ships an IME (Microsoft Pinyin) whose "use SHIFT to
 *  switch Chinese/English" option is ON by default. While the game window has
 *  focus and an IME context, pressing SHIFT flips the IME state instead of
 *  reaching the game as a plain modifier -- so flight descend (SHIFT while
 *  flying) silently does nothing, and the player has no way out except
 *  changing an OS setting. Big commercial games avoid this by detaching the
 *  IME from their window; when the window loses focus the IME comes back,
 *  which is exactly the behaviour asked for.
 *
 *  WHY THIS IS A SEPARATE .dll
 * ---------------------------
 *  The correct API is `ImmAssociateContextEx(hwnd, NULL, IACE_DEFAULT)`, which
 *  lives in imm32.dll. Checked before writing this file:
 *
 *    - LWJGL 3.4.3 does NOT bind imm32 (javap on org.lwjgl.system.windows
 *      shows no Imm* functions; only `WM_IME_*` message constants).
 *    - GLFW's own native library does NOT import imm32.dll at all
 *      (`objdump -p glfw.dll` lists only KERNEL32/USER32/GDI32/SHELL32).
 *      => the `GLFW_IME` window hint is NOT a way out on Windows.
 *
 *  So the call has to come from C. This project already builds native code
 *  with mingw-w64 (the launcher exe), so the toolchain is here; the extra
 *  cost is one small file and one more build step.
 *
 *  ★ WHY IT EXPOSES A QUERY TOO (`nIsEnabledForWindow`)
 *    Because "we call the API" is not evidence that it worked. `ImmGetContext`
 *    lets the game log the before/after state and a test assert it, so this
 *    fix is verifiable headlessly instead of "trust me, press SHIFT and see".
 *
 *  RUNTIME DEGRADATION (important)
 *  ------------------------------
 *  Loading this DLL is OPTIONAL. If it is missing, `ImeBridge.isAvailable()`
 *  returns false, the game logs ONE warning explaining what still works and
 *  what does not, and everything else keeps working. A missing optional native
 *  helper must never be able to stop the game from starting.
 *
 *  Build:  node tmp/build_launcher.js   (also builds this DLL)
 * ===========================================================================
 */

#include <jni.h>
#include <windows.h>
#include <imm.h>

/*
 * Detach the IME from `hwnd`.
 *
 * Returns JNI_TRUE when the window has no IME context afterwards.
 *
 * `ImmAssociateContextEx` returning NULL is ambiguous: it means either
 * "there was no context to begin with" (already fine) or "it failed"
 * (typically ERROR_INVALID_PARAMETER on a window owned by another thread).
 * Hence the explicit `ImmGetContext` re-check rather than trusting the return
 * value -- a shim that reports success while the IME is still attached would
 * make the game's own log lie, which is the exact class of problem this
 * project keeps having to catch.
 */
JNIEXPORT jboolean JNICALL
Java_com_skyisland_platform_ImeBridge_nDisableForWindow(JNIEnv *env, jclass cls, jlong hwnd)
{
    HWND h = (HWND)(intptr_t)hwnd;
    if (h == NULL) {
        return JNI_FALSE;
    }
    (void)env;
    (void)cls;

    SetLastError(0);
    /* IACE_IGNORENOCONTEXT: succeed quietly if this window never had one.
     * NOTE the spelling -- Win32 spells it IACE_IGNORENOCONTEXT (no underscore
     * before NOCONTEXT). Writing IACE_IGNORE_NOCONTEXT compiles fine until the
     * header is reached and then dies with a "did you mean" that looks like a
     * typo in *our* name rather than in ours -> ours. */
    ImmAssociateContextEx(h, NULL, IACE_DEFAULT | IACE_IGNORENOCONTEXT);

    /* Authoritative check, not the return value. */
    return ImmGetContext(h) == NULL ? JNI_TRUE : JNI_FALSE;
}

/*
 * Is an IME context currently attached to `hwnd`?
 *
 * This is the evidence hook: the game logs it before and after the detach, and
 * ResourceCoreRegen-style wiring tests can assert the transition happened.
 */
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