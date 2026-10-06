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

    /**
     * 几何形态（异形方块支持）。
     *
     * <p><b>它与 {@link #collision} 是两个正交的概念，必须分开：</b>
     * {@code collision} 是玩法开关（"这个方块挡不挡路"），
     * {@code shape} 是几何事实（"这个方块在空间里占多大、什么形状"）。
     * 两者在 {@link #blocksMovement()} 里相乘，得到唯一的"是否阻挡移动"口径。
     *
     * <p><b>为什么这样切分能防住"看不见的墙"：</b>
     * 作物若被误配成 {@code collision=true}，单靠布尔字段无法察觉；
     * 但只要它的 {@code shape} 是 {@link BlockShape#CROSS}（碰撞盒为空），
     * {@link #blocksMovement()} 仍然是 false —— 几何事实对玩法开关具有否决权。
     */
    private final BlockShape shape;

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
        this(runtimeId, id, solid, transparent, breakable, placeable, collision,
                hardness, lightEmission, renderType, colorR, colorG, colorB,
                dropItemId, dropCount, BlockShape.FULL);
    }

    /**
     * 完整构造器（可指定几何形态）。
     *
     * <p><b>为什么保留一个"形态 = FULL"的重载：</b>
     * 它让<b>既有满方块注册代码一行都不用改</b>就自动获得异形能力 ——
     * 这是"新增能力不破坏既有 17 种方块"的最强保证：不是靠测试证明没坏，
     * 而是靠<b>它们根本不经过新代码路径</b>。
     */
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
          int dropCount,
          BlockShape shape) {
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
        this.shape = shape == null ? BlockShape.FULL : shape;
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

    /**
     * <b>唯一的"是否阻挡移动"口径</b>（网格与物理都必须读这一个方法）。
     *
     * <p>判定 = 玩法开关 {@link #hasCollision()} <b>且</b> 形态有碰撞盒
     * {@link BlockShape#hasCollision()}。
     *
     * <p><b>为什么必须是两者的逻辑与，而不是直接返回 {@code collision}：</b>
     * 作物方块（PRD §3.2.2）若只靠布尔字段表达，会留下一个真实的失败模式 ——
     * 只要有人把它的 {@code collision} 误配成 {@code true}
     * （"作物应该挡路吧？"是个很自然的想法），
     * 玩家就会撞上一堵<b>看不见的墙</b>：网格是十字面（看得出是空的），
     * 碰撞却是满格（撞得到）。加上形态这一层否决权之后，
     * 十字面方块<b>无论布尔字段怎么配都不可能挡住玩家</b>。
     *
     * <p>反之（形态有碰撞盒但布尔开关为 false）仍然是通行的 ——
     * 那正是"玻璃/树叶这类可穿过方块"的表达方式。
     */
    public boolean blocksMovement() {
        return collision && shape.hasCollision();
    }

    /** 几何形态（满方块 / 十字面 / 半高台阶）。 */
    public BlockShape shape() {
        return shape;
    }

    /** 该方块的碰撞盒列表（局部坐标）；空数组 = 没有碰撞体。 */
    public BlockBox[] collisionBoxes() {
        return shape.collisionBoxes();
    }

    /**
     * 该方块与给定 AABB 是否相交（<b>已含"是否阻挡"语义</b>）。
     *
     * <p>这是碰撞查询的<b>单一入口</b>：调用方不必先问 {@code hasCollision()}
     * 再自己算相交 —— 那两件事分开写，就一定会出现某处忘了问开关、
     * 于是无碰撞体方块被当成满格去求交（PRD §7 R1 的失败模式）。
     *
     * @param minX/minY/minZ 查询盒下界
     * @param maxX/maxY/maxZ 查询盒上界
     * @param bx/by/bz 被查询方块的格坐标
     */
    public boolean intersects(double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ,
                              int bx, int by, int bz) {
        if (!blocksMovement()) {
            return false;
        }
        for (BlockBox box : shape.collisionBoxes()) {
            if (box.intersects(minX, minY, minZ, maxX, maxY, maxZ, bx, by, bz)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该方块所在格是否可作为"站立面"（脚下探测用）。
     *
     * <p>与 {@link #blocksMovement()} 的区别：站立面要求碰撞体<b>顶面</b>存在。
     * 半高台阶有碰撞体也有顶面，因此算站立面；
     * 十字面没有碰撞体，因此不算 —— 玩家不能"站在"一株小麦上，
     * 那会让作物变成隐形的半格台阶。
     */
    public boolean isStandable() {
        return blocksMovement() && shape.collisionTopY() > 0;
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
