package com.skyisland.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code settings.json} 的读写与三档容错（M1.5 规格第 6 条）。
 *
 * <p><b>这个类要回答的问题是"配置文件坏掉时会怎样"。</b>配置文件的特殊之处在于：
 * 它是<u>人手可以改</u>的，而且它的损坏发生在游戏<u>启动之前</u> ——
 * 若处理不当，表现是"游戏打不开"，而玩家完全不会想到是自己三天前改过的那一行。
 * 因此三档容错必须逐档断言，而不是"看起来有 try/catch"：
 * <ol>
 *   <li>文件不存在 → 用默认值 + <b>立刻写出</b>（否则下次启动还是没文件）；</li>
 *   <li>能解析但个别字段坏 → 逐字段回退，<b>其余字段照常生效</b>；</li>
 *   <li>完全无法解析 → <b>改名留证</b>（不是删除，也不是每启动一次就报一次错）+ 回默认。</li>
 * </ol>
 *
 * <p>另外两条不那么显眼、但同样会被玩家感知的契约：
 * <ul>
 *   <li><b>设置必须与世界存档分离。</b>删掉一个世界不该把鼠标手感也删掉；
 *       换个世界玩也不该重设一遍。</li>
 *   <li><b>自动化运行必须能改写到临时文件</b>（{@code -Dskyisland.settingsFile}），
 *       否则每跑一次测试就会覆盖玩家真实的设置。</li>
 * </ul>
 */
class SettingsStoreTest {

    @TempDir
    Path dir;

    @AfterEach
    void clearOverride() {
        System.clearProperty("skyisland.settingsFile");
    }

    private Path settingsFile() {
        return dir.resolve(SettingsStore.FILE_NAME);
    }

    // ============================================================ 路径解析

    @Test
    void systemPropertyOverrideWins() {
        Path custom = dir.resolve("custom-name.json");
        System.setProperty("skyisland.settingsFile", custom.toString());

        assertEquals(custom.toAbsolutePath(), SettingsStore.resolvePath(),
                "自动化运行必须能把设置写到别处，否则测试会覆盖玩家的真实手感");
    }

    @Test
    void blankOverrideFallsBackToTheConfigRoot() {
        System.setProperty("skyisland.settingsFile", "   ");

        assertEquals(SettingsStore.configRoot().resolve(SettingsStore.FILE_NAME),
                SettingsStore.resolvePath());
        assertTrue(SettingsStore.resolvePath().getFileName().toString().endsWith("settings.json"));
    }

    @Test
    void configRootIsNotTheSaveDirectory() {
        Path root = SettingsStore.configRoot();

        assertFalse(root.endsWith("saves"),
                "设置文件必须落在 saves/ 之外：删掉世界不该顺手删掉手感设置");
    }

    // ============================================================ 第一档：文件不存在

    @Test
    void missingFileYieldsDefaultsAndAsksForWriteBack() {
        SettingsStore.LoadResult r = SettingsStore.load(settingsFile());

        assertEquals(SettingsStore.Status.DEFAULTS_CREATED, r.status());
        assertTrue(r.settings().isAllDefaults());
        assertTrue(r.needsWriteBack(), "首次运行必须把默认设置写出去，否则下次启动又走这一支");
        assertFalse(r.notes().isEmpty(), "必须留下'为什么用了默认值'的记录");
        assertEquals(settingsFile(), r.path());
        assertFalse(Files.exists(settingsFile()), "load() 只报告，不写盘 —— 写盘是调用方的决定");
    }

    // ============================================================ 往返

    @Test
    void saveThenLoadReproducesEveryField() throws IOException {
        GameSettings s = new GameSettings();
        s.setMouseSensitivity(1.35);
        s.setInvertMouseY(true);
        s.setFovDeg(85);
        s.setVsync(true);
        s.setShowFps(true);
        s.setMasterVolume(35);
        s.setSfxVolume(60);
        s.keyBindings().set(Action.MOVE_FORWARD, InputBinding.key(GLFW.GLFW_KEY_I));
        s.keyBindings().set(Action.SECONDARY_ACTION, InputBinding.mouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
        s.keyBindings().set(Action.RELOAD, InputBinding.UNBOUND);

        assertTrue(SettingsStore.save(settingsFile(), s));
        SettingsStore.LoadResult r = SettingsStore.load(settingsFile());

        assertEquals(SettingsStore.Status.LOADED, r.status());
        assertTrue(r.notes().isEmpty(), "完全合法的文件不该产生任何备注，实际=" + r.notes());
        assertEquals(s, r.settings(), "写出去的设置必须能一字不差地读回来");
        assertFalse(r.needsWriteBack());
    }

    @Test
    void defaultSettingsRoundTripCleanlyToo() {
        assertTrue(SettingsStore.save(settingsFile(), new GameSettings()));

        SettingsStore.LoadResult r = SettingsStore.load(settingsFile());

        assertEquals(SettingsStore.Status.LOADED, r.status());
        assertTrue(r.settings().isAllDefaults());
        assertTrue(r.notes().isEmpty());
    }

    @Test
    void writtenFileIsHumanReadableAndMentionsEverySetting() throws IOException {
        SettingsStore.save(settingsFile(), new GameSettings());
        String text = Files.readString(settingsFile(), StandardCharsets.UTF_8);

        for (String field : new String[]{"mouseSensitivity", "invertMouseY", "fovDeg", "vsync",
                "showFps", "masterVolume", "sfxVolume", "keyBindings"}) {
            assertTrue(text.contains("\"" + field + "\""),
                    "配置文件是给人看和手改的，字段名必须出现：" + field);
        }
        for (Action a : Action.values()) {
            assertTrue(text.contains("\"" + a.id() + "\""),
                    "每个动作都要落盘（包括用户可能未绑定的），否则'未绑定'会与'没这项'混同：" + a.id());
        }
    }

    @Test
    void repeatedSavesLeaveExactlyOneBackupWindow() throws IOException {
        GameSettings s = new GameSettings();
        s.setFovDeg(80);
        SettingsStore.save(settingsFile(), s);
        s.setFovDeg(90);
        SettingsStore.save(settingsFile(), s);
        s.setFovDeg(60);
        SettingsStore.save(settingsFile(), s);

        assertTrue(SettingsStore.bakExists(settingsFile()));
        assertEquals(90.0, SettingsStore.load(dir.resolve("settings.json.bak")).settings().fovDeg(),
                1e-9, "备份必须是上一版 —— 设置写坏时它是唯一的退路");
        assertEquals(60.0, SettingsStore.load(settingsFile()).settings().fovDeg(), 1e-9);
    }

    // ============================================================ 第二档：个别字段坏

    @Test
    void brokenFieldFallsBackButGoodFieldsStillApply() throws IOException {
        Files.writeString(settingsFile(), """
                {
                  "mouseSensitivity": 1.50,
                  "fovDeg": 1000,
                  "masterVolume": 40
                }
                """, StandardCharsets.UTF_8);

        SettingsStore.LoadResult r = SettingsStore.load(settingsFile());

        assertEquals(SettingsStore.Status.LOADED, r.status(),
                "能解析就不该走'损坏备份'那条路 —— 否则用户每次手改都会留下垃圾文件");
        assertEquals(1.50, r.settings().mouseSensitivity(), 1e-9, "合法字段必须生效");
        assertEquals(40, r.settings().masterVolume());
        assertEquals(GameSettings.MAX_FOV, r.settings().fovDeg(), 1e-9, "越界值应被夹取到上限");
        assertTrue(r.needsWriteBack(), "发生过夹取/缺失时要写回，把文件修成规范形态");

        String joined = String.join("\n", r.notes());
        assertTrue(joined.contains("fovDeg"), "夹取必须留下具体字段名，否则无法定位");
        assertTrue(joined.contains("字段缺失"), "缺失字段要逐条列出，而不是安静地用默认值");
    }

    @Test
    void unknownFieldsAreIgnoredWithoutBackup() throws IOException {
        Files.writeString(settingsFile(), """
                {
                  "mouseSensitivity": 0.80,
                  "thisFieldIsFromTheFuture": {"nested": true},
                  "anotherUnknown": [1, 2, 3]
                }
                """, StandardCharsets.UTF_8);

        SettingsStore.LoadResult r = SettingsStore.load(settingsFile());

        assertEquals(SettingsStore.Status.LOADED, r.status(),
                "未知字段是前向兼容的正常情况，不该被当成损坏");
        assertEquals(0.80, r.settings().mouseSensitivity(), 1e-9);
        assertFalse(SettingsStore.backupExists(settingsFile()));
    }

    @Test
    void nullValuedFieldCountsAsMissing() throws IOException {
        Files.writeString(settingsFile(), """
                {
                  "mouseSensitivity": null,
                  "fovDeg": 75
                }
                """, StandardCharsets.UTF_8);

        SettingsStore.LoadResult r = SettingsStore.load(settingsFile());

        assertEquals(GameSettings.DEFAULT_SENSITIVITY, r.settings().mouseSensitivity(), 1e-9,
                "JSON null 不是 0.0 —— 把它当数值读会得到'灵敏度 0'，视角直接失灵");
        assertEquals(75.0, r.settings().fovDeg(), 1e-9);
    }

    @Test
    void brokenBindingsDoNotDiscardTheRestOfTheFile() throws IOException {
        Files.writeString(settingsFile(), """
                {
                  "fovDeg": 80,
                  "keyBindings": {
                    "move_forward": "key:75",
                    "jump": "not-a-binding",
                    "an_action_removed_in_a_later_version": "key:88"
                  }
                }
                """, StandardCharsets.UTF_8);

        SettingsStore.LoadResult r = SettingsStore.load(settingsFile());

        assertEquals(SettingsStore.Status.LOADED, r.status());
        assertEquals(80.0, r.settings().fovDeg(), 1e-9);
        assertEquals("K", r.settings().keyBindings().get(Action.MOVE_FORWARD).display());
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_SPACE), r.settings().keyBindings().get(Action.JUMP),
                "坏的键位行只影响它自己，其余键位照常生效");
        String joined = String.join("\n", r.notes());
        assertTrue(joined.contains("jump"));
        assertTrue(joined.contains("an_action_removed_in_a_later_version"));
    }

    // ============================================================ 第三档：完全损坏

    @Test
    void unparseableFileIsBackedUpAndDefaultsAreUsed() throws IOException {
        Files.writeString(settingsFile(), "this is not json at all {{{", StandardCharsets.UTF_8);

        SettingsStore.LoadResult r = SettingsStore.load(settingsFile());

        assertEquals(SettingsStore.Status.RECOVERED_FROM_CORRUPT, r.status());
        assertTrue(r.settings().isAllDefaults());
        assertTrue(r.needsWriteBack());
        assertTrue(SettingsStore.backupExists(settingsFile()),
                "损坏的文件必须改名留证 —— 直接删除会销毁唯一的排查线索");
        assertFalse(Files.exists(settingsFile()), "原名应被腾出来，由调用方写入规范设置");
        assertEquals(1, countCorruptBackups(), "一次损坏只留一份备份，不要每启动一次就多一份");
    }

    @Test
    void jsonArrayInsteadOfObjectCountsAsCorrupt() throws IOException {
        Files.writeString(settingsFile(), "[1, 2, 3]", StandardCharsets.UTF_8);

        SettingsStore.LoadResult r = SettingsStore.load(settingsFile());

        assertEquals(SettingsStore.Status.RECOVERED_FROM_CORRUPT, r.status());
        assertTrue(SettingsStore.backupExists(settingsFile()));
    }

    @Test
    void fileWithNoRecognizableFieldIsBackedUp() throws IOException {
        Files.writeString(settingsFile(), "{\"unrelated\": 1, \"alsoUnrelated\": \"x\"}",
                StandardCharsets.UTF_8);

        SettingsStore.LoadResult r = SettingsStore.load(settingsFile());

        assertEquals(SettingsStore.Status.RECOVERED_FROM_CORRUPT, r.status(),
                "一个字段都不认识，说明这多半不是我们的设置文件，值得留证");
        assertTrue(String.join("\n", r.notes()).contains("unrelated"),
                "备注里要带上'看见了哪些键'，否则排查时不知道该去哪儿找线索");
    }

    @Test
    void corruptBackupNameCarriesATimestampAndOriginalName() throws IOException {
        Files.writeString(settingsFile(), "garbage", StandardCharsets.UTF_8);

        SettingsStore.LoadResult r = SettingsStore.load(settingsFile());

        String note = String.join("\n", r.notes());
        assertTrue(note.contains("settings.json.corrupt-"),
                "备份名必须能看出'哪个文件、什么时候'，实际备注=" + note);
        Path backup = onlyCorruptBackup();
        assertNotNull(backup);
        assertTrue(backup.getFileName().toString().startsWith("settings.json.corrupt-"));
    }

    @Test
    void emptyFileIsBackedUpRatherThanSilentlyAccepted() throws IOException {
        Files.createFile(settingsFile());

        SettingsStore.LoadResult r = SettingsStore.load(settingsFile());

        assertEquals(SettingsStore.Status.RECOVERED_FROM_CORRUPT, r.status(),
                "空文件是'上一次写入被打断'的典型痕迹，必须留证而不是当作合法配置");
        assertTrue(SettingsStore.backupExists(settingsFile()));
    }

    @Test
    void loadRecoveryIsIdempotentAcrossRepeatedStarts() throws IOException {
        Files.writeString(settingsFile(), "broken", StandardCharsets.UTF_8);

        for (int i = 0; i < 3; i++) {
            SettingsStore.LoadResult r = SettingsStore.load(settingsFile());
            SettingsStore.Status expected = i == 0
                    ? SettingsStore.Status.RECOVERED_FROM_CORRUPT
                    : SettingsStore.Status.LOADED;
            assertEquals(expected, r.status(),
                    "第 " + (i + 1) + " 次启动的状态不对（第一次抢救，之后读的是规范文件）");
            assertTrue(r.settings().isAllDefaults());
            SettingsStore.save(settingsFile(), r.settings());
        }

        assertEquals(1, countCorruptBackups(), "备份只应产生一次 —— 后续启动读到的是规范文件");
        assertEquals(SettingsStore.Status.LOADED, SettingsStore.load(settingsFile()).status());
    }

    // ============================================================ 落盘失败

    @Test
    void saveReportsFailureInsteadOfThrowing() throws IOException {
        // 让"父目录"是一个普通文件：createDirectories 必然抛 IOException，
        // 从而走到 SettingsStore.save 的失败分支。
        // （不能用"目标路径是目录"来构造失败：原子写会先把那个目录改名成 .bak。）
        Path blocker = dir.resolve("not-a-directory");
        Files.writeString(blocker, "x", StandardCharsets.UTF_8);
        Path target = blocker.resolve(SettingsStore.FILE_NAME);

        assertFalse(SettingsStore.save(target, new GameSettings()),
                "写盘失败必须返回 false 并让游戏继续跑 —— 设置写不出去不该让进程崩掉");
    }

    // ============================================================ 辅助

    private int countCorruptBackups() throws IOException {
        try (var stream = Files.list(dir)) {
            return (int) stream
                    .filter(p -> p.getFileName().toString().startsWith("settings.json.corrupt-"))
                    .count();
        }
    }

    private Path onlyCorruptBackup() throws IOException {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("settings.json.corrupt-"))
                    .findFirst()
                    .orElse(null);
        }
    }

    @Test
    void toJsonIsStableAcrossCalls() {
        GameSettings s = new GameSettings();
        assertEquals(SettingsStore.toJson(s), SettingsStore.toJson(s));

        GameSettings other = new GameSettings();
        other.setFovDeg(90);
        assertNotEquals(SettingsStore.toJson(s), SettingsStore.toJson(other));
    }
}
