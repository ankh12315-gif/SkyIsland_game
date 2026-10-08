package com.skyisland.settings;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动作 → 物理输入的映射表（M1.5 规格第 4/5 条）。
 *
 * <p><b>本类守住的核心不变式：一个物理输入最多被一个动作占用。</b>
 * 冲突检测、重绑对话框、"替换后谁会失去键位"全都建立在这一条上。
 *
 * <p><b>为什么默认表要被断言，而不是"看一眼代码就知道"：</b>
 * 默认键位是<u>玩家契约</u>，也是 M1 既有断言的前提 ——
 * 例如 M1 的 LOOK 断言假定灵敏度 1.0 时手感不变，
 * 而"W 是前进"若在重构中被改掉，M1 的移动断言会以一种与键位无关的方式失败。
 * 把它写下来之后，"改了默认键位"必然引起本类失败，而不是若干轮之后才被察觉。
 */
class KeyBindingsTest {

    // ============================================================ 默认表

    @Test
    void defaultsMatchThePrdContract() {
        KeyBindings kb = KeyBindings.defaults();

        assertEquals(InputBinding.key(GLFW.GLFW_KEY_W), kb.get(Action.MOVE_FORWARD));
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_S), kb.get(Action.MOVE_BACKWARD));
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_A), kb.get(Action.MOVE_LEFT));
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_D), kb.get(Action.MOVE_RIGHT));
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_SPACE), kb.get(Action.JUMP));
        // ★ CROUCH（飞行下降）默认键 2026-10-08 从左 SHIFT 改为左 CTRL。
        //   理由是机器实测（见 docs/testing/M4_IME_DETACH_REPORT.md §9）：
        //   中文输入法的 Shift 中英切换发生在**窗口消息派发之上**，
        //   IMM32 摘上下文、窗口子类拦 WM_IME_SETCONTEXT 一律无效 ——
        //   而左 Ctrl 是输入法基本不碰的键。
        //   想用 Shift 的玩家可在设置里改回（该动作本来就是可重绑的），
        //   或关掉输入法的「用 Shift 键切换中/英文」。
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_LEFT_CONTROL), kb.get(Action.CROUCH));
        assertEquals(InputBinding.mouse(GLFW.GLFW_MOUSE_BUTTON_LEFT), kb.get(Action.PRIMARY_ACTION));
        assertEquals(InputBinding.mouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT), kb.get(Action.SECONDARY_ACTION));
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_R), kb.get(Action.RELOAD));
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_E), kb.get(Action.INVENTORY));
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_ESCAPE), kb.get(Action.PAUSE));
    }

    @Test
    void everyActionHasABindingAndNoTwoShareOne() {
        KeyBindings kb = KeyBindings.defaults();

        for (Action a : Action.values()) {
            assertTrue(kb.get(a).isBound(), "动作 " + a.id() + " 必须有默认键位，否则玩家看不到它");
        }
        for (Action a : Action.values()) {
            assertNull(kb.findOwner(kb.get(a), a),
                    "默认表里 " + a.id() + " 的键被别的动作占用了 —— 默认表本身存在冲突");
        }
    }

    @Test
    void allDefaultsAndCustomizedAgreeOnAFreshTable() {
        KeyBindings kb = KeyBindings.defaults();

        assertTrue(kb.allDefaults());
        assertTrue(kb.customized().isEmpty());
        for (Action a : Action.values()) {
            assertTrue(kb.isDefault(a));
        }
    }

    // ============================================================ 冲突检测

    @Test
    void findOwnerReportsTheOccupyingAction() {
        KeyBindings kb = KeyBindings.defaults();

        assertEquals(Action.MOVE_FORWARD, kb.findOwner(InputBinding.key(GLFW.GLFW_KEY_W)));
    }

    @Test
    void findOwnerIgnoresTheAskingActionItself() {
        KeyBindings kb = KeyBindings.defaults();

        assertNull(kb.findOwner(InputBinding.key(GLFW.GLFW_KEY_W), Action.MOVE_FORWARD),
                "重绑成自己原来的键不算冲突，否则'点回车又改回 W'会弹出一个多余的确认框");
    }

    @Test
    void findOwnerOfUnboundIsAlwaysNull() {
        KeyBindings kb = KeyBindings.defaults();

        assertNull(kb.findOwner(InputBinding.UNBOUND, Action.MOVE_FORWARD),
                "未绑定不占用任何键；否则会与'另一个也未绑定的动作'互相冲突");
        assertNull(kb.findOwner(null, null));
    }

    @Test
    void unboundActionStopsOccupyingItsOldKey() {
        KeyBindings kb = KeyBindings.defaults();
        kb.set(Action.MOVE_FORWARD, InputBinding.UNBOUND);

        assertNull(kb.findOwner(InputBinding.key(GLFW.GLFW_KEY_W)),
                "把动作解绑后，原来的键必须重新变成空闲 —— 否则'替换'腾不出键位");
        assertFalse(kb.isBound(Action.MOVE_FORWARD));
    }

    // ============================================================ 序列化

    @Test
    void toMapThenFromMapRoundTripsAFullyCustomizedTable() {
        KeyBindings kb = KeyBindings.defaults();
        kb.set(Action.MOVE_FORWARD, InputBinding.key(GLFW.GLFW_KEY_I));
        kb.set(Action.JUMP, InputBinding.mouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
        kb.set(Action.RELOAD, InputBinding.UNBOUND);

        List<String> notes = new ArrayList<>();
        KeyBindings back = KeyBindings.fromMap(kb.toMap(), notes);

        assertTrue(notes.isEmpty(), "合法的整表往返不该产生任何告警，实际=" + notes);
        assertEquals(kb, back);
        assertTrue(back.get(Action.RELOAD).equals(InputBinding.UNBOUND));
    }

    @Test
    void toMapHasAStableKeyOrderForEveryAction() {
        Map<String, String> map = KeyBindings.defaults().toMap();

        assertEquals(Action.values().length, map.size());
        int i = 0;
        for (Action a : Action.values()) {
            assertEquals(a.id(), map.keySet().toArray()[i], "落盘顺序必须稳定，否则每次保存都在改文件 diff");
            i++;
        }
    }

    @Test
    void fromMapKeepsDefaultsForUnknownOrBrokenEntries() {
        Map<String, String> raw = new HashMap<>();
        raw.put("move_forward", "key:75");            // 合法：K
        raw.put("this_action_was_removed", "key:87"); // 旧版本遗留
        raw.put("jump", "keyboard:32");               // 类别名写错
        raw.put("pause", "key:not_a_number");         // 键码不是整数
        List<String> notes = new ArrayList<>();

        KeyBindings kb = KeyBindings.fromMap(raw, notes);

        assertEquals("K", kb.get(Action.MOVE_FORWARD).display(), "合法项必须生效");
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_SPACE), kb.get(Action.JUMP),
                "坏的一行只影响它自己，不能把整张表打掉");
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_ESCAPE), kb.get(Action.PAUSE));
        assertEquals(3, notes.size(), "三条坏数据应产生三条备注，实际=" + notes);
    }

    @Test
    void fromMapOnEmptyInputWarnsAndUsesDefaults() {
        List<String> notes = new ArrayList<>();

        KeyBindings kb = KeyBindings.fromMap(Map.of(), notes);
        assertTrue(kb.allDefaults());
        assertEquals(1, notes.size());

        List<String> notes2 = new ArrayList<>();
        assertTrue(KeyBindings.fromMap(null, notes2).allDefaults());
        assertFalse(notes2.isEmpty());
    }

    @Test
    void fromMapAcceptsNullNoteSink() {
        KeyBindings kb = KeyBindings.fromMap(Map.of("not_an_action", "key:1"), null);
        assertNotNull(kb);
        assertTrue(kb.allDefaults());
    }

    // ============================================================ 整表操作

    @Test
    void restoreDefaultsUndoesEveryCustomization() {
        KeyBindings kb = KeyBindings.defaults();
        kb.set(Action.MOVE_FORWARD, InputBinding.UNBOUND);
        kb.set(Action.JUMP, InputBinding.key(GLFW.GLFW_KEY_J));
        assertFalse(kb.allDefaults());

        kb.restoreDefaults();

        assertTrue(kb.allDefaults());
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_SPACE), kb.get(Action.JUMP));
    }

    @Test
    void restoreDefaultsAlsoRestoresExplicitlyUnboundEntries() {
        KeyBindings kb = KeyBindings.defaults();
        kb.set(Action.INVENTORY, InputBinding.UNBOUND);

        kb.restoreDefaults();

        assertEquals(InputBinding.key(GLFW.GLFW_KEY_E), kb.get(Action.INVENTORY));
    }

    @Test
    void copyIsIndependent() {
        KeyBindings kb = KeyBindings.defaults();
        KeyBindings copy = kb.copy();

        copy.set(Action.MOVE_FORWARD, InputBinding.key(GLFW.GLFW_KEY_I));

        assertEquals(InputBinding.key(GLFW.GLFW_KEY_W), kb.get(Action.MOVE_FORWARD),
                "复制出来的表必须是独立的，否则'预览改动'会直接改到真实设置");
    }

    @Test
    void setAllReplacesTheWholeTable() {
        KeyBindings a = KeyBindings.defaults();
        a.set(Action.MOVE_FORWARD, InputBinding.key(GLFW.GLFW_KEY_I));
        a.set(Action.JUMP, InputBinding.UNBOUND);

        KeyBindings b = KeyBindings.defaults();
        b.setAll(a);

        assertEquals(a, b);
        assertEquals(InputBinding.UNBOUND, b.get(Action.JUMP));
        assertEquals(2, b.customized().size());
    }

    @Test
    void equalsAndHashCodeFollowEveryAction() {
        KeyBindings a = KeyBindings.defaults();
        KeyBindings b = KeyBindings.defaults();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        b.set(Action.MOVE_LEFT, InputBinding.key(GLFW.GLFW_KEY_J));
        assertFalse(a.equals(b));
    }

    @Test
    void getOnAnUnknownActionFallsBackToUnboundInsteadOfThrowing() {
        KeyBindings kb = KeyBindings.defaults();
        kb.set(Action.CROUCH, null);

        assertEquals(InputBinding.UNBOUND, kb.get(Action.CROUCH),
                "null 绑定必须是未绑定，而不是让后续的 isBound() 抛 NPE");
    }

    @Test
    void describeLinesCoversEveryAction() {
        List<String> lines = KeyBindings.defaults().describeLines();

        assertEquals(Action.values().length, lines.size());
        for (String line : lines) {
            assertFalse(line.isBlank());
        }
    }
}
