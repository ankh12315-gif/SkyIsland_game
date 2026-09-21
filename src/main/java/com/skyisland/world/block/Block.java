package com.skyisland.world.block;

/**
 * 方块定义（不可变）。
 *
 * <p><b>双层 ID 模型（TECH_DESIGN §F.1）：</b>
 * <ul>
 *   <li>{@link #id()} —— <b>stable string ID</b>，形如 {@code skyisland:stone}。
 *       这是<u>唯一允许写入存档</u>的标识，因为它的含义不随注册表扩容而改变。</li>
 *   <li>{@link #runtimeId()} —— 运行时 {@code short}，用于数组下标与网格顶点数据。
 *       它的数值由注册顺序决定，<b>禁止写入存档</b>（TECH_DESIGN §F.2）。</li>
 * </ul>
 *
 * <p><b>为什么禁止用 enum ordinal 当永久 ID：</b>ordinal 会随枚举常量顺序调整而整体位移，
 * 一旦写进存档就等于把"存档格式"绑死在源码行号上。本类因此显式持有 {@code runtimeId}，
 * 并且 {@link BlockRegistry} 在初始化末尾校验"注册顺序 == runtimeId"，
 * 使这个不变式由构建期行为保证而不是靠记忆。
 *
 * <p><b>为什么不用"每个体素一个 Java 对象"：</b>区块内是 {@code short[]} 连续数组
 * （32 768 个体素只有 64 KB），方块属性集中在本类单例里查询。
 * 每体素一个对象会让一个区块的内存从 64 KB 涨到 MB 级，并彻底破坏缓存局部性。
 *
 * <p>M1 使用逐方块顶点色作为占位美术（PRD 允许占位素材），
 * 因此这里带 {@code r/g/b} 三个颜色分量；贴图阶段引入 TextureArray 后这三个字段会退居备用。
 */
public final class Block {

    private final int runtimeId;
    private final String id;

    private final boolean solid;
    private final boolean transparent;
    private final boolean breakable;
    private final boolean placeable;
    private final boolean collision;

    /** 徒手破坏所需秒数；{@link Float#POSITIVE_INFINITY} 表示不可破坏。 */
    private final float hardness;

    private final int lightEmission;
    private final RenderType renderType;

    private final float colorR;
    private final float colorG;
    private final float colorB;

    /**
     * 破坏后掉落的物品 stable ID；{@code null} 表示<b>无掉落</b>（例如玻璃、树叶、系统方块）。
     *
     * <p><b>为什么用 stable string ID 而不是 {@code Item} 引用：</b>
     * {@code ItemRegistry} 在初始化时依赖 {@code BlockRegistry}（要为每个方块生成方块物品），
     * 若这里反向持有 {@code Item}，两个类的静态初始化会互相等待，
     * 形成难以诊断的类初始化死锁。字符串是单向引用的最小代价。
     *
     * <p>这也让掉落表可以直接对照 PRD 5.1 的「掉落物」列逐行核对 ——
     * 审计发现的 G13（掉落表完全未实现）之所以长期隐藏，
     * 正是因为掉落规则压根没有地方可写。
     */
    private final String dropItemId;

    /** 掉落数量（PRD 5.1 全部为 1；小麦的 1–2 属 Alpha）。 */
    private final int dropCount;

    Block(int runtimeId,
          String id,
          boolean solid,
          boolean transparent,
          boolean breakable,
          boolean placeable,
          boolean collision,
          float hardness,
          int lightEmission,
          RenderType renderType,
          float colorR,
          float colorG,
          float colorB,
          String dropItemId,
          int dropCount) {
        this.runtimeId = runtimeId;
        this.id = id;
        this.solid = solid;
        this.transparent = transparent;
        this.breakable = breakable;
        this.placeable = placeable;
        this.collision = collision;
        this.hardness = hardness;
        this.lightEmission = lightEmission;
        this.renderType = renderType;
        this.colorR = colorR;
        this.colorG = colorG;
        this.colorB = colorB;
        this.dropItemId = dropItemId;
        this.dropCount = dropItemId == null ? 0 : Math.max(1, dropCount);
    }

    // ------------------------------------------------------------ 标识

    /** 运行时 ID（数组下标口径，禁止写存档）。 */
    public int runtimeId() {
        return runtimeId;
    }

    /** stable string ID（存档与数据文件口径）。 */
    public String id() {
        return id;
    }

    /** 是否为空气（唯一的"空"方块）。 */
    public boolean isAir() {
        return runtimeId == BlockRegistry.AIR_RUNTIME_ID;
    }

    // ------------------------------------------------------------ 属性

    /** 是否为完整实体方块（用于支撑判定与网格剔除的取向判断）。 */
    public boolean isSolid() {
        return solid;
    }

    /** 是否透明（透明方块不遮挡相邻面，且走透明渲染 pass）。 */
    public boolean isTransparent() {
        return transparent;
    }

    /** 能否被破坏。 */
    public boolean isBreakable() {
        return breakable;
    }

    /** 能否被放置。 */
    public boolean isPlaceable() {
        return placeable;
    }

    /** 是否阻挡玩家移动（与 {@link #isSolid()} 分开定义，见 TECH_DESIGN §F.4）。 */
    public boolean hasCollision() {
        return collision;
    }

    /** 徒手破坏所需秒数；{@code POSITIVE_INFINITY} = 不可破坏。 */
    public float hardness() {
        return hardness;
    }

    /** 自发光等级（0–15）。M1 仅登记，光照引擎按固定径向衰减使用。 */
    public int lightEmission() {
        return lightEmission;
    }

    public RenderType renderType() {
        return renderType;
    }

    public float colorR() {
        return colorR;
    }

    public float colorG() {
        return colorG;
    }

    public float colorB() {
        return colorB;
    }

    // ------------------------------------------------------------ 掉落

    /**
     * 破坏后掉落的物品 stable ID；{@code null} 表示无掉落。
     *
     * <p>对照 PRD 5.1 的「掉落物」列：草方块掉泥土、石头掉圆石、玻璃与树叶无掉落、
     * 其余掉落自身。破坏结算必须先查这里，不能再像 M1 那样一律 {@code add(自身, 1)}。
     */
    public String dropItemId() {
        return dropItemId;
    }

    /** 掉落数量；{@link #hasDrop()} 为 false 时为 0。 */
    public int dropCount() {
        return dropCount;
    }

    /** 破坏后是否产生掉落物。 */
    public boolean hasDrop() {
        return dropItemId != null;
    }

    /** 掉落物是否就是自身（多数方块如此；草 / 石 / 玻璃 / 树叶不是）。 */
    public boolean dropsSelf() {
        return id.equals(dropItemId);
    }

    // ------------------------------------------------------------ 语义

    /**
     * 该方块能否被"放置"操作覆盖掉。
     *
     * <p>只有空气可以被覆盖。M1 不做"水/草替换"这类特例。
     */
    public boolean isReplaceable() {
        return isAir();
    }

    /**
     * 面剔除判定（TECH_DESIGN §G.4 的判定表，逐行实现）。
     *
     * <p>规则（从左到右命中即返回）：
     * <ol>
     *   <li>自身不渲染（空气）→ 不生成；</li>
     *   <li>邻居是空气 → 生成；</li>
     *   <li>邻居不透明 → <b>不</b>生成（被完全遮挡）；</li>
     *   <li>邻居与自己<u>同种</u>且自己透明 → <b>不</b>生成（玻璃贴玻璃，共享面两边都看不到）；</li>
     *   <li>其余（不同种透明相邻）→ 生成。</li>
     * </ol>
     *
     * <p><b>第 4 条容易被漏掉</b>：如果只按"邻居透明就生成"来写，
     * 一块玻璃紧贴另一块玻璃时会生成两个重合的面 —— 视觉上表现为
     * "两格厚的玻璃看起来比一格亮"（同一处被混合两次），
     * 这是透明体渲染里最典型也最难解释的一类瑕疵。
     */
    public boolean rendersFaceAgainst(Block neighbor) {
        if (renderType == RenderType.INVISIBLE) {
            return false;
        }
        if (neighbor.isAir()) {
            return true;
        }
        if (!neighbor.isTransparent()) {
            return false;
        }
        return neighbor.runtimeId != runtimeId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Block b && b.runtimeId == runtimeId;
    }

    @Override
    public int hashCode() {
        return runtimeId;
    }

    @Override
    public String toString() {
        return id + "#" + runtimeId;
    }
}
