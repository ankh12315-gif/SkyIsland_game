package com.skyisland.world.block;

/**
 * 方块的<b>几何形态</b>（异形方块支持，PRD §3.2.2 / §3.2.5 / §7 R1）。
 *
 * <p><b>为什么需要它：</b>M1 隐含一个前提 —— 每个方块都占满整格。
 * 于是"网格是满方块的 6 个面"与"碰撞是满格的 1×1×1"这两件事都写死在
 * {@code ChunkMesher} 与 {@code AABB} 里，且<b>没有任何地方能表达例外</b>。
 * 本类把这个前提变成一个可枚举、可查询的显式属性，
 * 使网格生成与碰撞判定<b>读同一份真相</b>，而不是各写一套 if/else。
 *
 * <p><b>不变式（必须成立，否则渲染与碰撞会分叉）：</b>
 * <ol>
 *   <li><b>网格形态与碰撞形态由同一个枚举常量决定</b>，不允许"看起来是台阶、撞上去是满格"；</li>
 *   <li>{@link #collisionBoxes()} 为空的形态，网格也必须<b>不是</b>满方块
 *       ——否则就会出现 PRD §7 R1 点名的"看不见的墙"；</li>
 *   <li>碰撞盒永远不超出 [0,1]³ —— 越界会让相邻格的判定互相污染。</li>
 * </ol>
 *
 * <p><b>本轮只提供能力，不登记方块</b>（登记是 S5 的事，见 PRD §8）：
 * 这里定义形态与几何数据，具体哪些 stable ID 用哪种形态由
 * {@code BlockRegistry} 在后续步骤里指定。
 *
 * <p><b>为什么形态与"是否透明 / 是否可破坏"分开：</b>
 * 后两者是玩法属性（会影响掉落、合成、存档），
 * 而形态只回答"这个方块在空间里长什么样、占多大地方"。
 * 混在一起会出现"因为是台阶所以不能被打碎"这类错误推论。
 */
public enum BlockShape {

    /**
     * 满方块：1×1×1（占满整格）。
     *
     * <p><b>M1–M3 全部方块的形态</b>，也是本轮必须<b>逐字节保持行为不变</b>的基准路径。
     */
    FULL("满方块", new BlockBox[]{BlockBox.FULL}),

    /**
     * 十字交叉面：2 个对角面 × 双向可见 = <b>4 个竖直四边形</b>，无碰撞体。
     *
     * <p><b>几何</b>：两个面都从方块一角斜贯到对角（(0,0,0)→(1,0,1) 与
     * (1,0,0)→(0,0,1)），竖直贯穿整格高度。
     * 每个面都必须<b>双向</b>发射（正面 + 背面），否则从另一侧看会消失 ——
     * 而"某个面消失"与"某个面被剔除"在画面上完全一样，极难排查。
     *
     * <p><b>碰撞体为空</b>：作物必须能穿过，否则玩家会撞上一堵看不见的墙
     * （PRD §7 R1 明确点名的头号风险）。
     *
     * <p><b>面剔除规则与满方块不同</b>：作物彼此相邻时<b>不应</b>剔除
     * （田里两株小麦之间的面虽然被遮住，但剔除会让"一株"与"两株"的画面出现差别），
     * 且作物<b>永远不生成底面</b>（它悬空/长在土上，底面永远看不见）。
     */
    CROSS("十字交叉面", new BlockBox[0]),

    /**
     * 下半格台阶：占底部 1/2 格（y ∈ [0, 0.5]），<b>碰撞体与网格都是半高</b>。
     *
     * <p><b>关键点：网格必须是半高盒，而不是"满方块缩小"</b>。
     * 后者是常见错误 —— 顶面若留在 y=1，玩家会看到台阶上方浮着一层顶面，
     * 且站在台阶上会被判定为悬空。
     *
     * <p>本轮<b>只做下半形态</b>（PRD §3.5 的范围收敛裁定）：
     * 上半形态需要 block state 机制，与木门同属一类，不在 S1 范围内。
     */
    SLAB_BOTTOM("下半格台阶", new BlockBox[]{BlockBox.SLAB_BOTTOM});

    private final String displayName;
    private final BlockBox[] collisionBoxes;

    BlockShape(String displayName, BlockBox[] collisionBoxes) {
        this.displayName = displayName;
        this.collisionBoxes = collisionBoxes;
    }

    /** 中文名（供日志与调试输出使用）。 */
    public String displayName() {
        return displayName;
    }

    /**
     * 碰撞盒列表（方块局部坐标）。
     *
     * <p><b>返回的数组是共享常量，禁止调用方修改它。</b>
     * 空数组 = 该形态<b>没有碰撞体</b>（作物）；长度 1 = 常规单盒。
     *
     * <p>返回数组而非单个 {@code BlockBox}，是为了让"多盒形态"
     * （例如将来的栅栏、台阶上下两段）不需要再改这个 API 的形状。
     */
    public BlockBox[] collisionBoxes() {
        return collisionBoxes;
    }

    /** 该形态是否<b>参与碰撞</b>（即至少有一个碰撞盒）。 */
    public boolean hasCollision() {
        return collisionBoxes.length > 0;
    }

    /** 是否为占满整格的形态。 */
    public boolean isFull() {
        return this == FULL;
    }

    /** 是否需要走"十字面"专用发射路径（而非逐面剔除）。 */
    public boolean isCross() {
        return this == CROSS;
    }

    /**
     * 该形态最高的碰撞面上界（局部 y）。
     *
     * <p><b>无碰撞体时返回 0</b>，而不是抛异常或返回 1 ——
     * 站立高度求解（{@code Player.updateGroundState}）在无碰撞形态上
     * 本就不该被问到，返回 0 让它自然算出"脚下没有可站的东西"。
     */
    public double collisionTopY() {
        double top = 0;
        for (BlockBox box : collisionBoxes) {
            top = Math.max(top, box.maxY());
        }
        return top;
    }

    /**
     * 该形态的碰撞体是否<b>与满格 AABB 相交</b>（保守相交测试的快速路径）。
     *
     * <p>用于"玩家能不能站进这一格"的粗筛：若形态碰撞体与整格都不相交，
     * 那么任何 AABB 只要与整格相交就必然与它相交。
     * 反之不成立，所以这只是<b>单向的快速路径</b>，不能当作精确判定。
     */
    public boolean intersectsFullCell() {
        for (BlockBox box : collisionBoxes) {
            if (box.intersects(0, 0, 0, 1, 1, 1, 0, 0, 0)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return displayName;
    }
}