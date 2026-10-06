package com.skyisland.world.block;

/**
 * 方块碰撞盒（<b>方块局部坐标系</b>，[0,1]³ 内的子区间）。
 *
 * <p><b>为什么需要它：</b>M1 的碰撞判定把"一个方块"硬编码成"占满整格"，
 * 判定式写在 {@code AABB.intersectsBlock} 里：
 * <pre>
 *   maxX &gt; bx &amp;&amp; minX &lt; bx + 1   // 恒等于 [bx, bx+1]
 * </pre>
 * 这对满方块成立，对<b>非满方块</b>一律不成立 ——
 * 半高台阶若按满格判，玩家会撞上台阶上方那半格的空气（"隐形墙"反过来变成"空气墙"）；
 * 作物方块若按满格判，则会得到一堵看不见的墙（PRD §7 R1 明确点名的失败模式）。
 *
 * <p><b>为什么坐标是局部的：</b>碰撞盒必须能像常量一样被复用
 * （每个 {@link BlockShape} 只有一份不可变定义，所有同类方块共享），
 * 而世界坐标每格都不同。局部盒 + 格坐标在求交时相加即可，
 * 这样"半高"这类比例参数只需要写一次，不会散落到判定式里。
 *
 * <p><b>为什么是 record 而不是可变对象：</b>碰撞盒会被缓存并跨帧复用
 * （每次网格化、每次物理步都读它），可变就意味着"某一帧被谁改了"无法追溯。
 *
 * @param minX 下界 x（局部）
 * @param minY 下界 y（局部）
 * @param minZ 下界 z（局部）
 * @param maxX 上界 x（局部）
 * @param maxY 上界 y（局部）
 * @param maxZ 上界 z（局部）
 */
public record BlockBox(double minX, double minY, double minZ,
                       double maxX, double maxY, double maxZ) {

    /** 满格碰撞盒（占满 [0,1]³）。 */
    public static final BlockBox FULL = new BlockBox(0, 0, 0, 1, 1, 1);

    /**
     * 半高碰撞盒：占底部一半（y ∈ [0,0.5]），x/z 仍是满格。
     *
     * <p>用于台阶一类"只填了下半格"的方块。
     */
    public static final BlockBox SLAB_BOTTOM = new BlockBox(0, 0, 0, 1, 0.5, 1);

    /** 体积（仅用于测试与调试取证；判定一律用 {@link #intersects}）。 */
    public double volume() {
        return (maxX - minX) * (maxY - minY) * (maxZ - minZ);
    }

    /**
     * 本盒（平移到格坐标 {@code bx,by,bz}）是否与给定 AABB 区间相交。
     *
     * <p><b>刻意用严格不等式而不是 epsilon：</b>
     * {@code maxA > minB && minA < maxB} 让"玩家脚底 y 恰好等于台阶顶面 y"
     * 天然判定为不相交（{@code 64.0 < 64.0} 为假），
     * 正好站在台阶上不会被判为"嵌进去"。
     *
     * <p>本方法只做几何判定，<b>不包含"该方块是否参与碰撞"这一层语义</b> ——
     * 那是 {@link Block#blocksMovement()} 的事。两者必须分开：
     * 混在一起会让"空碰撞盒的方块"无法表达（它既不参与碰撞，也没有盒）。
     */
    public boolean intersects(double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ,
                              int bx, int by, int bz) {
        return maxX > bx + this.minX && minX < bx + this.maxX
                && maxY > by + this.minY && minY < by + this.maxY
                && maxZ > bz + this.minZ && minZ < bz + this.maxZ;
    }
}