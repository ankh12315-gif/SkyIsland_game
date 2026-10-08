package com.skyisland.render.ui;

import com.skyisland.craft.CraftingPanel;
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

    /**
     * 右侧合成栏的界面模型；{@code null} 表示本帧不画合成栏。
     *
     * <p><b>为什么持有模型而不是拷一份行数组：</b>与 {@link #inventory} 同一条理由 ——
     * 合成栏的状态（哪一行可合成）必须由"当前背包"与 {@link com.skyisland.craft.Crafting}
     * 现算，界面若持有一份快照，就会出现"刚合完、界面还显示能再合一次"。
     * 刷新时机由游戏层显式控制（背包变化时与合成后），见 {@code CraftingPanel}。
     */
    public CraftingPanel craftingPanel;

    /** 鼠标悬停的配方行号；{@code -1} 表示未悬停在任何行上。 */
    public int hoverCraftRow = -1;

    // ============================================================ M4-S7：标签页与创造面板

    /**
     * ★ M4-S7 背包窗口的标签页（PRD §5.1「背包界面（E）内的独立标签页『创造』」）。
     *
     * <p>★ <b>它只有两个值，而"没有标签条"用 {@link #tabs == 0} 表达而不是加第三个枚举值</b>：
     * 生存模式<b>不该</b>有一条只写着「背包」的标签条 —— 玩家会去找那个不存在的第二页，
     * 而点不到任何东西的标签是纯噪声。枚举第三个值 {@code NONE} 会让"切到 NONE"
     * 成为一个可达状态 —— 那个状态没有任何绘制点，只能表现为"标签条上的文字全没了"。
     */
    public enum Tab {
        /** 标签页 0：36 格背包 + 合成栏。 */
        BACKPACK,
        /** 标签页 1：创造方块面板。 */
        CREATIVE;

        /** 按序号取标签页；越界返回 {@code null}（调用点据此忽略这次点击）。 */
        public static Tab of(int index) {
            Tab[] all = values();
            return index >= 0 && index < all.length ? all[index] : null;
        }
    }

    /**
     * 本帧的标签页个数。
     *
     * <p>生存模式 = {@code 1}（不画标签条），创造模式 = {@code 2}。
     *
     * <p>★ <b>为什么"标签数在一局内不变"是可以依赖的性质</b>：
     * {@code gameMode} 由存档定死（PRD §4.3 模式锁定是双向的），
     * 因此标签条不会在游玩中途出现或消失 ⇒ 标签条把内容区下推 22px 这件事
     * 只在"打开背包的第一帧"就已经确定，不会造成"切标签时内容整体跳一下"。
     *
     * <p>★ <b>而"两个标签页之间切换会不会让面板变高"是另一件事</b>，
     * 由 {@code InventoryLayout} 取两个内容区的高者解决（见其 {@code mainAreaHeight}）。
     */
    public int tabs = 1;

    /** 当前激活的标签页。 */
    public Tab activeTab = Tab.BACKPACK;

    /** 鼠标悬停的标签页序号；{@code -1} 表示未悬停（本帧无标签条时恒为 {@code -1}）。 */
    public int hoverTab = -1;

    /**
     * 创造面板的分组视图；{@code null} = 本帧没有创造面板（生存模式）。
     *
     * <p>持有视图而不是持有 {@code CreativePalette}：面板<b>排版</b>（条目顺序、每个条目第几行）
     * 每帧要用一次，而排序与切段要遍历 20 个条目 ——
     * 让渲染层每帧重算是一份白给的开销，且"内容顺序"与"行结构"两处各算一次
     * 就等于没有单一事实来源（症状：第 12 格画在第 13 格的位置，不报错不崩溃）。
     */
    public com.skyisland.world.block.CreativePalette.CreativeView creativeView;

    /** 鼠标悬停的创造面板条目；{@code -1} 表示未悬停。 */
    public int hoverCreativeEntry = -1;

    /** 本帧是否显示创造面板（{@link #activeTab} 为创造 <b>且</b> 视图非空）。 */
    public boolean creativePanelActive() {
        return activeTab == Tab.CREATIVE && creativeView != null;
    }

    /** 光标位置（帧缓冲像素）。未获得指针时为 {@code Double.NaN}，渲染器据此不画跟随光标的那一堆。 */
    public double mouseX = Double.NaN;

    /** 光标位置（帧缓冲像素）。 */
    public double mouseY = Double.NaN;

    /** 是否有可用的指针位置（与 {@link com.skyisland.input.MenuNav#hasPointer()} 同口径）。 */
    public boolean hasPointer() {
        return !Double.isNaN(mouseX) && !Double.isNaN(mouseY);
    }
}
