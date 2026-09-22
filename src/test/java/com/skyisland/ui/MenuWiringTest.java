package com.skyisland.ui;

import com.skyisland.settings.GameSettings;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 菜单行的<b>接线</b>测试 —— 抓的是"界面上有一个能点的行，但没有任何代码处理它"。
 *
 * <h2>为什么这条测试必须存在</h2>
 * M2.2 给主菜单加了「继续游戏 / 新建世界」两个 id。菜单屏、文案、布局、渲染全都是对的，
 * 单元测试也全绿 —— 但如果 {@code SkyIslandGame.activateEntry} 没有对应分支，
 * 玩家点下去只会走进 {@code default} 打一行 WARN 日志。那是一个<b>点了没反应的按钮</b>：
 * 它不会让编译失败，不会让任何既有断言变红，也不会让帧率下降。
 * 本项目把这种形态明确列为禁止项，而"改菜单"这件事每次都可能重新制造它。
 *
 * <h2>判据为什么不扫字符串字面量</h2>
 * 激活分支写的是 {@code Menus.ID_CONTINUE.equals(entryId)}，用的是<b>常量名</b>而不是
 * id 的字面量 {@code "continue"}。因此扫字面量恒为假阴性。这里先用反射把
 * {@code id 字符串 → 常量名} 的对应关系建出来，再断言常量名出现在激活文件里 ——
 * 于是"加了菜单行却忘了接线"会在这一条上立刻变红。
 *
 * <h2>它不做什么</h2>
 * 它不证明分支的行为正确（那由 MenuScreenTest / UiStateMachineTest / 人事试玩负责），
 * 只证明"这条行有主"。
 */
class MenuWiringTest {

    /** 处理主菜单 / 暂停菜单激活的文件。 */
    private static final Path GAME_SOURCE =
            Paths.get("src/main/java/com/skyisland/game/SkyIslandGame.java");

    /** 处理设置界面激活的文件。 */
    private static final Path SETTINGS_SOURCE =
            Paths.get("src/main/java/com/skyisland/ui/SettingsMenuController.java");

    /** id 字符串 → {@link Menus} 里那个常量的名字。 */
    private static Map<String, String> idToConstantName() throws Exception {
        Path source = Paths.get("src/main/java/com/skyisland/ui/Menus.java");
        assertTrue(Files.isDirectory(Paths.get("src/main/java")),
                "测试的工作目录必须是项目根（找不到 src/main/java）");

        Map<String, String> map = new HashMap<>();
        for (Field f : Menus.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())
                    || !Modifier.isPublic(f.getModifiers())
                    || f.getType() != String.class) {
                continue;
            }
            String name = f.getName();
            if (!name.startsWith("ID_")) {
                continue;
            }
            Object value = f.get(null);
            if (value instanceof String s && !s.isEmpty()) {
                map.put(s, name);
            }
        }
        assertTrue(map.size() >= 8,
                "反射应当扫到 Menus 的全部 ID_* 常量（实际 " + map.size() + "，源文件 " + source + "）");
        return map;
    }

    private static List<String> selectableIds(MenuScreen screen) {
        List<String> ids = new ArrayList<>();
        for (MenuEntry e : screen.entries()) {
            if (e.selectable()) {
                ids.add(e.id());
            }
        }
        return ids;
    }

    @Test
    void everySelectableMainAndPauseRowIsHandledBySomeBranch() throws Exception {
        Map<String, String> names = idToConstantName();
        String game = Files.readString(GAME_SOURCE, StandardCharsets.UTF_8);

        // hasSave 两种取值都要查：有存档时多出「继续游戏」，无存档时它变成不可选的说明行
        List<MenuScreen> screens = List.of(
                Menus.mainMenu(true), Menus.mainMenu(false), Menus.pauseMenu());

        int checked = 0;
        for (MenuScreen screen : screens) {
            for (String id : selectableIds(screen)) {
                String constant = names.get(id);
                assertNotNull(constant,
                        "菜单项 id 没有对应的 Menus.ID_* 常量，接线测试无法追踪它: " + id);
                assertTrue(game.contains("Menus." + constant),
                        "菜单项「" + id + "」在界面上可点，但 " + GAME_SOURCE.getFileName()
                                + " 里没有任何分支处理它（期望出现 Menus." + constant + "）"
                                + " —— 这正是「点了没反应的按钮」");
                checked++;
            }
        }
        assertTrue(checked >= 6,
                "应当扫到主菜单与暂停菜单的全部可点行（实际 " + checked + "）");
    }

    @Test
    void everySelectableSettingsRowIsHandledByTheSettingsController() throws Exception {
        Map<String, String> names = idToConstantName();
        String controller = Files.readString(SETTINGS_SOURCE, StandardCharsets.UTF_8);

        int checked = 0;
        for (MenuEntry e : Menus.settingsMenu(new GameSettings()).entries()) {
            if (!e.selectable()) {
                continue;
            }
            if (e.kind() == MenuEntry.Kind.BINDING) {
                // 键位行的 id 是 bind_<action>，由 actionOfBindId 反解，不走常量表
                assertNotNull(Menus.actionOfBindId(e.id()),
                        "键位行 id 必须能被 actionOfBindId 反解: " + e.id());
                continue;
            }
            String constant = names.get(e.id());
            assertNotNull(constant,
                    "设置行 id 没有对应的 Menus.ID_* 常量，接线测试无法追踪它: " + e.id());
            assertTrue(controller.contains("Menus." + constant),
                    "设置行「" + e.id() + "」可点，但 " + SETTINGS_SOURCE.getFileName()
                            + " 里没有分支处理它（期望出现 Menus." + constant + "）");
            checked++;
        }
        assertTrue(checked >= 8,
                "应当扫到设置界面的全部可点项（实际 " + checked + "）");
    }

    /**
     * 「继续游戏」的可选性必须由<b>调用方</b>根据磁盘上有没有存档决定，
     * 而不是靠无参重载的默认值。
     *
     * <p>{@link Menus#mainMenu()} 保留无参重载是为了兼容既有调用点，
     * 它的默认是 {@code hasSave = true}。装配期若继续用它，
     * 空存档目录（门禁每次都从空目录起跑）下玩家就会看到一个可点的「继续游戏」，
     * 点下去只有一句 WARN。这条断言把"必须传实参"钉死。
     */
    @Test
    void mainMenuIsBuiltFromTheRealSaveStateNotTheDefaultOverload() throws Exception {
        String game = Files.readString(GAME_SOURCE, StandardCharsets.UTF_8);

        // 注意判据必须落到"装配点那一行"，不能只扫整文件里有没有这个子串：
        // refreshMainMenu() 里也有一模一样的调用，于是"整文件包含"式的断言
        // 会在装配点被改回无参重载时依旧通过 —— 一个假的绿灯。
        // （这条也是写完之后靠反向验证才发现的。）
        assertTrue(game.contains("mainMenuScreen = Menus.mainMenu(saveManager.worldExists())"),
                "装配期必须用 Menus.mainMenu(saveManager.worldExists()) 建主菜单；"
                        + "用无参重载会让「继续游戏」在空存档下依旧可点");
    }
}
