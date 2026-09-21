package com.skyisland.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个菜单屏（M1.5 规格第 3/5/9 条）：标题 + 若干 {@link MenuEntry} + 当前选中项。
 *
 * <p><b>选中项的三条不变式（由本类集中维护）：</b>
 * <ol>
 *   <li>选中项永远是<b>可选中</b>的行（分节标题与空行会被跳过）；</li>
 *   <li>上下移动是<b>环绕</b>的（到底部再按向下回到第一项）——
 *       否则在暂停菜单里连按向下键会"卡住不动"，看起来像输入失效；</li>
 *   <li>重建内容时按 <b>id 保留选中位置</b>。设置界面在每次改值后都要刷新
 *       （键名、数值都变了），若不按 id 保留，就会退回到第一项 ——
 *       表现为"改一次灵敏度，光标跳回顶部"，这是界面里最容易被察觉到的一类 bug。</li>
 * </ol>
 *
 * <p>本类不涉及任何绘制：布局与命中判定在渲染层（{@code MenuLayout}），
 * 它们读同一份 {@code entries}。
 */
public final class MenuScreen {

    private final String title;
    private final String subtitle;
    private final List<MenuEntry> entries = new ArrayList<>();
    private int selected;

    public MenuScreen(String title, String subtitle, List<MenuEntry> entries) {
        this.title = title;
        this.subtitle = subtitle;
        this.entries.addAll(entries);
        this.selected = firstSelectableIndex();
    }

    public String title() {
        return title;
    }

    public String subtitle() {
        return subtitle;
    }

    public List<MenuEntry> entries() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    public int selectedIndex() {
        return selected;
    }

    public MenuEntry selectedEntry() {
        return selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
    }

    public String selectedId() {
        MenuEntry e = selectedEntry();
        return e == null ? null : e.id();
    }

    public MenuEntry entry(String id) {
        int i = indexOf(id);
        return i < 0 ? null : entries.get(i);
    }

    public String valueOf(String id) {
        MenuEntry e = entry(id);
        return e == null ? null : e.value();
    }

    public int indexOf(String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    // ============================================================ 导航

    /** 向下移动一格（跳过不可选项，环绕）。 */
    public void moveDown() {
        move(+1);
    }

    /** 向上移动一格（跳过不可选项，环绕）。 */
    public void moveUp() {
        move(-1);
    }

    private void move(int direction) {
        int n = entries.size();
        if (n == 0) {
            selected = -1;
            return;
        }
        int index = selected;
        for (int i = 0; i < n; i++) {
            index = Math.floorMod(index + direction, n);
            if (entries.get(index).selectable()) {
                selected = index;
                return;
            }
        }
        // 一个可选项都没有：保持原位（这种情况说明菜单构造有问题，由测试兜住）
    }

    /** 鼠标悬停：只接受可选中行；返回是否发生了选中变化。 */
    public boolean hover(int index) {
        if (index < 0 || index >= entries.size() || !entries.get(index).selectable()) {
            return false;
        }
        if (selected == index) {
            return false;
        }
        selected = index;
        return true;
    }

    public boolean selectById(String id) {
        int i = indexOf(id);
        if (i < 0 || !entries.get(i).selectable()) {
            return false;
        }
        selected = i;
        return true;
    }

    // ============================================================ 内容更新

    /**
     * 用新内容替换（保持选中项 id）。返回"选中项是否因为原 id 消失而改变"。
     */
    public boolean rebuild(List<MenuEntry> next) {
        String keepId = selectedId();
        entries.clear();
        entries.addAll(next);
        if (keepId != null && selectById(keepId)) {
            return false;
        }
        selected = firstSelectableIndex();
        return true;
    }

    /** 就地替换某一行的值（不改变选中项）。 */
    public boolean setValue(String id, String value) {
        int i = indexOf(id);
        if (i < 0) {
            return false;
        }
        entries.set(i, entries.get(i).withValue(value));
        return true;
    }

    public boolean setLabel(String id, String label) {
        int i = indexOf(id);
        if (i < 0) {
            return false;
        }
        entries.set(i, entries.get(i).withLabel(label));
        return true;
    }

    private int firstSelectableIndex() {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).selectable()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "MenuScreen(" + title + ", " + entries.size() + " 行, 选中 "
                + (selectedId() == null ? "(无)" : selectedId()) + ")";
    }
}
