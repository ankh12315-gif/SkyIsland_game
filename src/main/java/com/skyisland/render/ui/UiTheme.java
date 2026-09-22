package com.skyisland.render.ui;

/**
 * UI 视觉语言基座（M2.2）：全部界面配色的<b>唯一来源</b>。
 *
 * <p><b>为什么要有这一个类：</b>M2.2 之前，HUD 与菜单各自在文件顶部藏了一套
 * {@code private static final float[]} 色值（{@code HudRenderer} 与 {@code MenuRenderer} 各一套），
 * 两者数值逐渐漂移，导致"准星白"和"菜单标题白"读起来不是同一种白。
 * 这里把两处的全部颜色集中成同一份常量，渲染器只引用 {@code UiTheme.*}，
 * 配色再无"第二份真相"可言。
 *
 * <p><b>数值口径：</b>每个常量是 {@code float[] {r, g, b, a}}，分量都在 0..1。
 * 全部数值<b>照抄</b>自原两个渲染器，本里程碑<b>不改任何观感</b> ——
 * 它只是把散落的常量收拢到一个文件，不是重新设计配色。
 *
 * <p><b>命名规则：</b>原两处没有重名的色值（除 {@code PANEL_BG} 两处数值完全一致，合为一份），
 * 因此不加前缀直接沿用原名。若将来出现"同名但数值不同"的色，
 * 才用 {@code HUD_*} / {@code MENU_*} 前缀区分，<u>绝不</u>把数值不同的同义色合并成一份。
 */
public final class UiTheme {

    private UiTheme() {
    }

    // ============================================================ 来自 HudRenderer

    /** 普通准星色（白）。 */
    public static final float[] CROSSHAIR = {1.00f, 1.00f, 1.00f, 0.85f};
    /** 瞄准可交互方块时的准星色（暖黄）。 */
    public static final float[] CROSSHAIR_TARGET = {1.00f, 0.86f, 0.35f, 0.95f};
    /** M2.1 命中标记的准星色（红，与白/黄都拉开距离）。 */
    public static final float[] CROSSHAIR_HIT = {1.00f, 0.28f, 0.26f, 1.00f};

    /** 主文本色。 */
    public static final float[] TEXT_PRIMARY = {0.94f, 0.96f, 0.98f, 1.00f};
    /** 次要 / 暗文本色。 */
    public static final float[] TEXT_DIM = {0.72f, 0.76f, 0.82f, 1.00f};
    /** 警示文本色（换弹进度、低生命等）。 */
    public static final float[] TEXT_WARN = {1.00f, 0.78f, 0.35f, 1.00f};

    /** 文本底色 / 面板底色（HUD 读数行与菜单列底板共用，数值一致合并）。 */
    public static final float[] PANEL_BG = {0.03f, 0.04f, 0.06f, 0.62f};

    /** 快捷栏 / 背包槽位的底色。 */
    public static final float[] SLOT_BG = {0.06f, 0.07f, 0.09f, 0.66f};
    /** 普通槽位边框（中性灰）。 */
    public static final float[] SLOT_BORDER = {0.34f, 0.37f, 0.42f, 0.90f};
    /** 选中槽位边框（暖白，原快捷栏选中态用 outset 描边，见 SlotRenderer）。 */
    public static final float[] SLOT_SELECTED = {1.00f, 0.98f, 0.90f, 1.00f};

    /** 一颗心的实心色。 */
    public static final float[] HEART_FULL = {0.90f, 0.20f, 0.24f, 1.00f};
    /** 一颗心的空槽色。 */
    public static final float[] HEART_EMPTY = {0.16f, 0.14f, 0.16f, 0.85f};

    /** 非方块物品：枪械代表色。 */
    public static final float[] ITEM_GUN_COLOR = {0.28f, 0.30f, 0.34f, 1.00f};
    /** 非方块物品：弹药代表色。 */
    public static final float[] ITEM_AMMO_COLOR = {0.78f, 0.66f, 0.28f, 1.00f};
    /** 非方块物品：材料（煤炭等）代表色。 */
    public static final float[] ITEM_MATERIAL_COLOR = {0.22f, 0.22f, 0.24f, 1.00f};

    // ============================================================ 来自 MenuRenderer

    /** 菜单背景压暗层。 */
    public static final float[] DIM = {0.02f, 0.03f, 0.05f, 0.66f};
    /** 主标题色。 */
    public static final float[] TITLE = {0.95f, 0.97f, 1.00f, 1.00f};
    /** 副标题色。 */
    public static final float[] SUBTITLE = {0.62f, 0.68f, 0.76f, 1.00f};
    /** 分节标题色（冷色强调）。 */
    public static final float[] HEADER = {0.55f, 0.78f, 0.98f, 1.00f};
    /** 菜单普通条目的标签色。 */
    public static final float[] ITEM = {0.86f, 0.89f, 0.93f, 1.00f};
    /** 选中条目的标签色（纯白）。 */
    public static final float[] ITEM_SELECTED = {1.00f, 1.00f, 1.00f, 1.00f};
    /** 数值色。 */
    public static final float[] VALUE = {0.72f, 0.86f, 1.00f, 1.00f};
    /** 需要关注的数值（未绑定 / 端点）：暖橙告警。 */
    public static final float[] VALUE_CUSTOM = {1.00f, 0.85f, 0.45f, 1.00f};
    /** 说明文字色。 */
    public static final float[] INFO = {0.52f, 0.57f, 0.64f, 1.00f};
    /** 选中行的高亮底条。 */
    public static final float[] SELECT_BAR = {0.24f, 0.50f, 0.78f, 0.55f};
    /** 选中行左侧的强调边。 */
    public static final float[] SELECT_EDGE = {0.60f, 0.84f, 1.00f, 0.95f};
    /** 分节标题下的分隔线。 */
    public static final float[] SEPARATOR = {0.30f, 0.36f, 0.44f, 0.80f};
    /** 对话框底色。 */
    public static final float[] DIALOG_BG = {0.06f, 0.07f, 0.10f, 0.94f};
    /** 对话框边框。 */
    public static final float[] DIALOG_EDGE = {0.72f, 0.82f, 0.95f, 1.00f};
    /** 右下角版本行色。 */
    public static final float[] VERSION_COLOR = {0.45f, 0.50f, 0.58f, 1.00f};

    // ============================================================ M2.2 新增

    /**
     * 鼠标悬停的槽位边框色：介于 {@link #SLOT_BORDER}（中性灰）与
     * {@link #SLOT_SELECTED}（暖白）之间的<b>冷色</b>高亮，
     * 让"鼠标停在哪一格"一眼可辨，又明显区别于已选中的暖白。
     */
    public static final float[] SLOT_HOVER = {0.50f, 0.74f, 0.92f, 0.95f};

    /** 物品提示框（tooltip）底色。 */
    public static final float[] TOOLTIP_BG = {0.05f, 0.06f, 0.09f, 0.95f};
    /** 物品提示框边框。 */
    public static final float[] TOOLTIP_BORDER = {0.55f, 0.62f, 0.72f, 0.96f};
    /** 物品提示框文本色。 */
    public static final float[] TOOLTIP_TEXT = {0.90f, 0.93f, 0.97f, 1.00f};

    /** 面板标题强调色（背包等界面的标题条）。 */
    public static final float[] PANEL_HEADER = {0.42f, 0.64f, 0.88f, 1.00f};

    /** 跟随光标那一堆物品的半透明着色（让"手里拿着的东西"浮在界面之上）。 */
    public static final float[] CURSOR_STACK_TINT = {1.00f, 1.00f, 1.00f, 0.45f};

    /** 禁用态文本色（灰且半透明）。 */
    public static final float[] DISABLED_TEXT = {0.40f, 0.42f, 0.46f, 0.70f};
}
