package com.skyisland.input;

/**
 * 菜单导航的按键边沿（M1.5）。
 *
 * <p><b>为什么菜单按键<b>不</b>走可重绑的 Action 表：</p>
 * 可重绑的动作表描述的是"游戏内操作"（前进、挖掘、暂停）。菜单导航是
 * <b>进入游戏之前</b>就必须存在的通道：如果"确认"键被玩家绑到了某个奇怪的键上，
 * 或者玩家在设置界面把"确认"清空了，那么他将<u>无法操作菜单</u>——
 * 包括无法把设置改回去。这是典型的"可配置性把自己锁死"。
 * 因此菜单导航固定在方向键 / 回车 / 鼠标左键上，与动作表解耦。
 *
 * <p>ESC 是唯一的例外：它既是菜单的"返回"，又是可重绑动作 {@code PAUSE} 的默认键。
 * 处理方式是"ESC 始终保留为全局返回"（{@code InputMapper#globalBackPressed}），
 * 因此即使玩家把 PAUSE 改成别的键，也不会失去返回能力。
 *
 * 字段全部是"本帧是否发生过按下"，不是"当前是否按住"：
 * 菜单操作需要的是边沿，按住不放会让光标在菜单里疯狂滚动。
 */
public record MenuNav(
        boolean up,
        boolean down,
        boolean left,
        boolean right,
        boolean confirm,
        boolean back,
        boolean clicked,
        double mouseX,
        double mouseY,
        double scrollY
) {

    public static final MenuNav NONE = new MenuNav(
            false, false, false, false, false, false, false, Double.NaN, Double.NaN, 0);

    public boolean hasVertical() {
        return up || down;
    }

    public boolean hasHorizontal() {
        return left || right;
    }

    /** 纵向滚动方向（用于滚轮）：-1 = 向上滚（切到上一项），+1 = 向下。 */
    public int scrollSteps() {
        if (scrollY > 0) {
            return -1;
        }
        if (scrollY < 0) {
            return 1;
        }
        return 0;
    }

    /** 鼠标是否处于可用位置（第一帧的基准回调之前为 NaN）。 */
    public boolean hasPointer() {
        return !Double.isNaN(mouseX) && !Double.isNaN(mouseY);
    }
}
