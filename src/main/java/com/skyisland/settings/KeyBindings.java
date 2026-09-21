package com.skyisland.settings;

import com.skyisland.util.Log;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逻辑动作 → 物理输入的映射表（M1.5 规格第 4/5 条）。
 *
 * <p><b>不变式：一个物理输入最多被一个动作占用。</b>这条不变式是冲突检测的全部依据，
 * 也是重绑界面存在的原因。它由 {@link #set} 与 {@link #findOwner} 共同维护：
 * 设置入口只做"赋值"，把"要不要抢"的决策留给调用方（重绑控制器），
 * 因为"替换 / 取消"是<u>用户</u>的选择，不是数据结构的默认行为。
 *
 * <p><b>默认表的唯一来源：</b>{@link #defaults()}。M1.5 的默认键位与 PRD 一致
 * （W/S/A/D 移动、SPACE 跳、LEFT_SHIFT 蹲、鼠标左右键为主副操作、R 换弹、E 背包、ESC 暂停），
 * 其中 {@code R} 在 M1 里刻意不被占用（强制重生放在 F9），正是为了今天不出现键位冲突。
 */
public final class KeyBindings {

    private final EnumMap<Action, InputBinding> map = new EnumMap<>(Action.class);

    private KeyBindings() {
    }

    /** 默认键位表。 */
    public static KeyBindings defaults() {
        KeyBindings kb = new KeyBindings();
        kb.map.put(Action.MOVE_FORWARD, InputBinding.key(GLFW.GLFW_KEY_W));
        kb.map.put(Action.MOVE_BACKWARD, InputBinding.key(GLFW.GLFW_KEY_S));
        kb.map.put(Action.MOVE_LEFT, InputBinding.key(GLFW.GLFW_KEY_A));
        kb.map.put(Action.MOVE_RIGHT, InputBinding.key(GLFW.GLFW_KEY_D));
        kb.map.put(Action.JUMP, InputBinding.key(GLFW.GLFW_KEY_SPACE));
        kb.map.put(Action.CROUCH, InputBinding.key(GLFW.GLFW_KEY_LEFT_SHIFT));
        kb.map.put(Action.PRIMARY_ACTION, InputBinding.mouse(GLFW.GLFW_MOUSE_BUTTON_LEFT));
        kb.map.put(Action.SECONDARY_ACTION, InputBinding.mouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
        kb.map.put(Action.RELOAD, InputBinding.key(GLFW.GLFW_KEY_R));
        kb.map.put(Action.INVENTORY, InputBinding.key(GLFW.GLFW_KEY_E));
        kb.map.put(Action.PAUSE, InputBinding.key(GLFW.GLFW_KEY_ESCAPE));
        return kb;
    }

    public InputBinding get(Action action) {
        InputBinding b = map.get(action);
        return b == null ? InputBinding.UNBOUND : b;
    }

    /** 直接赋值（不做冲突处理）。冲突决策属调用方，见类注释。 */
    public void set(Action action, InputBinding binding) {
        map.put(action, binding == null ? InputBinding.UNBOUND : binding);
    }

    /**
     * 查找该输入当前归属于哪个动作。
     *
     * @param except 忽略该动作自身（"重绑成自己原来的键"不算冲突）
     * @return 占用者；无人占用返回 {@code null}
     */
    public Action findOwner(InputBinding binding, Action except) {
        if (binding == null || !binding.isBound()) {
            return null;
        }
        for (Map.Entry<Action, InputBinding> e : map.entrySet()) {
            if (e.getKey() == except) {
                continue;
            }
            if (binding.sameAs(e.getValue())) {
                return e.getKey();
            }
        }
        return null;
    }

    public Action findOwner(InputBinding binding) {
        return findOwner(binding, null);
    }

    public boolean isDefault(Action action) {
        return defaults().get(action).sameAs(get(action));
    }

    public boolean allDefaults() {
        KeyBindings d = defaults();
        for (Action a : Action.values()) {
            if (!d.get(a).sameAs(get(a))) {
                return false;
            }
        }
        return true;
    }

    public void restoreDefaults() {
        map.clear();
        map.putAll(defaults().map);
    }

    /**
     * 整表覆盖（深拷贝用）。<b>只复制已存在的动作项</b>：
     * 允许"未绑定"表达为 {@code code = -1} 的显式项，而不是靠"表里没有这一项"来表达 ——
     * 后者会让 {@link #get} 的回落路径与真实状态无法区分。
     */
    public void setAll(KeyBindings source) {
        map.clear();
        map.putAll(source.map);
    }

    public boolean isBound(Action action) {
        return get(action).isBound();
    }

    /** 该动作是否被重绑过（默认之外）。 */
    public List<Action> customized() {
        List<Action> out = new ArrayList<>();
        for (Action a : Action.values()) {
            if (!isDefault(a)) {
                out.add(a);
            }
        }
        return out;
    }

    // ============================================================ 序列化

    /** 落盘用的映射：{@code {动作id: "key:87"}}。用 {@link LinkedHashMap} 让 JSON 顺序稳定可读。 */
    public Map<String, String> toMap() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Action a : Action.values()) {
            out.put(a.id(), get(a).serialize());
        }
        return out;
    }

    /**
     * 从落盘映射恢复。<b>逐项容错</b>：未知动作 id、非法绑定串都只告警并保留默认值，
     * 绝不让一行坏数据把整份键位配置打掉。
     *
     * @param notes 记录告警（供启动横幅与报告摘录）；可为 {@code null}
     */
    public static KeyBindings fromMap(Map<String, String> raw, List<String> notes) {
        KeyBindings kb = defaults();
        if (raw == null || raw.isEmpty()) {
            addNote(notes, "settings.json 中没有 keyBindings，全部使用默认键位");
            return kb;
        }
        int applied = 0;
        for (Map.Entry<String, String> e : raw.entrySet()) {
            Action action = Action.byId(e.getKey());
            if (action == null) {
                addNote(notes, "未知动作 id（已忽略，可能是更新后遗留）: " + e.getKey());
                continue;
            }
            try {
                kb.map.put(action, InputBinding.parse(e.getValue()));
                applied++;
            } catch (IllegalArgumentException ex) {
                addNote(notes, "动作 " + action.id() + " 的绑定无法解析，保留默认 "
                        + kb.get(action).display() + "：" + ex.getMessage());
            }
        }
        Log.info("[设置] 键位表已载入：%d/%d 项来自配置，其余为默认", applied, Action.values().length);
        return kb;
    }

    private static void addNote(List<String> notes, String text) {
        if (notes != null) {
            notes.add(text);
        }
        Log.noteWarning("设置", text);
    }

    public KeyBindings copy() {
        KeyBindings kb = defaults();
        kb.map.clear();
        kb.map.putAll(this.map);
        return kb;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof KeyBindings other)) {
            return false;
        }
        for (Action a : Action.values()) {
            if (!get(a).sameAs(other.get(a))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (Action a : Action.values()) {
            InputBinding b = get(a);
            h = 31 * h + b.kind().ordinal() * 131 + b.code();
        }
        return h;
    }

    /** 多行可读清单（启动横幅与报告摘录用）。 */
    public List<String> describeLines() {
        List<String> lines = new ArrayList<>();
        for (Action a : Action.values()) {
            lines.add(String.format("%-18s %-14s %-10s %s",
                    a.id(), a.label(), get(a).display(),
                    isDefault(a) ? "" : "(custom)"));
        }
        return lines;
    }
}
