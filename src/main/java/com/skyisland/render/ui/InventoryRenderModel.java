package com.skyisland.render.ui;

import com.skyisland.player.Inventory;

/**
 * 背包界面一帧的输入（渲染层的输入模型，与 {@link HudModel} 同构：可变对象、public 字段、每帧复用）。
 *
 * <h2>为什么字段里直接持有 {@link Inventory} 而不是拷一份数组</h2>
 * M2.2 的硬要求是"背包界面与 HUD 快捷栏<b>共用同一份模型</b>，不得有第二份数据"。
 * 若这里放 {@code int[36]} 的快照，就必然存在一个"什么时候把 Inventory 拷进数组"的时刻：
 * 拷早了界面显示旧数据，拷漏了界面不更新 —— 而"挖到一个方块后背包界面没变"
 * 正是 M2 已经付过学费的那类缺陷（当时是网格重建队列没人消费）。
 * 直接持有模型引用，这类漂移从"迟早发生"变成"不可能"。
 *
 * <p>同理，<b>渲染器需要的槽位内容一律从 {@code inventory} 现读</b>，
 * 本类不缓存任何槽位快照；{@link #hoverSlot} 与鼠标坐标是纯界面态，才放在这里。
 *
 * <h2>鼠标坐标的口径</h2>
 * {@link #mouseX} / {@link #mouseY} 必须是<b>帧缓冲像素</b>（左上原点、y 向下），
 * 与 {@link UiBatch} 的绘图坐标系一致。
 *
 * <p><b>这里有一条已经存在过的坑：</b>{@code InputMapper.pollMenuNav} 交给调用方的是
 * <b>窗口坐标</b>（GLFW cursor-pos 回调的原始值），而布局与命中判定用的是帧缓冲像素。
 * 在 DPI = 1 时两者恰好相等，问题不会暴露；一旦窗口尺寸 ≠ 帧缓冲尺寸，
 * "点第 3 格却命中第 5 格"就会变成真 bug。因此换算必须<b>发生在填充本类的地方</b>，
 * 而且只能发生一次 —— 换算两遍等于没换算。
 *
 * <h2>生命周期</h2>
 * 本对象每帧复用（与 {@link HudModel} 相同），由游戏层填充、渲染层只读。
 * 它不推进任何时间，也不持有 GL 资源。
 */
public final class InventoryRenderModel {

    /** 是否绘制背包界面。false 时渲染器直接跳过（不进入 GL 状态切换）。 */
    public boolean visible;

    /**
     * 背包模型本身（同一份引用，不是拷贝）。
     *
     * <p>槽位布局契约见 {@link Inventory}：绝对索引 0..26 = 主背包，27..35 = 快捷栏。
     */
    public Inventory inventory;

    /** 当前选中的快捷栏槽（0..8，快捷栏内相对索引，与 {@link Inventory#selectedSlot()} 同口径）。 */
    public int selectedHotbarSlot;

    /** 鼠标悬停的绝对槽位索引；{@code -1} 表示未悬停在任何格子上。 */
    public int hoverSlot = -1;

    /** 光标位置（帧缓冲像素）。未获得指针时为 {@code Double.NaN}，渲染器据此不画跟随光标的那一堆。 */
    public double mouseX = Double.NaN;

    /** 光标位置（帧缓冲像素）。 */
    public double mouseY = Double.NaN;

    /** 是否有可用的指针位置（与 {@link com.skyisland.input.MenuNav#hasPointer()} 同口径）。 */
    public boolean hasPointer() {
        return !Double.isNaN(mouseX) && !Double.isNaN(mouseY);
    }
}
