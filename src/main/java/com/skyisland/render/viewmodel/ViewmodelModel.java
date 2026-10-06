package com.skyisland.render.viewmodel;

import com.skyisland.item.Item;
import com.skyisland.item.ItemKind;
import com.skyisland.player.ItemStack;

/**
 * 第一人称手持物（viewmodel）的输入数据。渲染层的传参包，与 {@code HudModel} 同一套取向。
 *
 * <p><b>为什么是"传参包"而不是让渲染器自己去玩家身上取：</b>
 * 依赖方向（§B.2）要求渲染层不去反向依赖游戏逻辑模块。渲染器拿到手的
 * 只应当是"这一帧要画成什么样"，而不是"去找玩家问它现在拿着什么"。
 * 于是本类的字段全部 public、无访问器 —— 包装成 getter 只会增加噪音。
 *
 * <p><b>它和 {@code HudModel} 为什么不是同一个对象：</b>
 * HUD 有三十来个字段，其中绝大部分与手持物无关；而手持物需要的
 * "开火次数""行走强度"两项目前并不在 HUD 里。合成一个会把两个不相干的
 * 关注点绑死 —— 改 HUD 就得改 viewmodel。分成两个，各自只长自己的字段。
 */
public final class ViewmodelModel {

    /**
     * 是否绘制。菜单 / 暂停 / 死亡期间必须为 false：
     * 手持物是"我正在操作这个世界"的提示，与准星、快捷栏同一性质。
     */
    public boolean visible = true;

    /** 当前快捷栏槽位（0..8）。变化时触发 {@code slot switch pop}。 */
    public int slot;

    /** 手持物形态。 */
    public ViewmodelKind kind = ViewmodelKind.EMPTY;

    /**
     * 枪械轮廓查表键（{@link ViewmodelKind#GUN} 用）；非枪为 {@code null}。
     *
     * <p><b>为什么 kind 之外还要一个键：</b>{@code kind} 只能回答"这是不是枪"，
     * 回答不了"是哪把枪"。M2 只有手枪时"所有枪共用一套 {@code GUN_PARTS}"没问题，
     * M3 加了 SMG 之后，若继续共用，玩家右手里就是一把一模一样的手枪 ——
     * 这正是 v2 §4.2 明令禁止的"逻辑上是 SMG，右手仍是一模一样的手枪模型"。
     *
     * <p>取值来自 {@code item.presentation().viewmodelId()}（与图标 / 音效 / 后坐同一套键的解析方式），
     * 而不是从 item stable id 现推：表现资源统一由 {@code GunPresentationSpec} 指路。
     * {@code ViewmodelGeometry} 按本键选轮廓数组。
     */
    public String gunViewmodelId;

    /**
     * 后坐档案查表键（{@link ViewmodelKind#GUN} 用）；非枪为 {@code null}。
     *
     * <p><b>为什么它必须和 {@link #gunViewmodelId} 分开传：</b>
     * 两者是 {@code GunPresentationSpec} 上两个<b>互不相干</b>的键
     * （轮廓 vs 后坐手感）。从轮廓键现推后坐键等于把两件事绑死 ——
     * 那时"换一套轮廓"会顺手改掉后坐，而改的人并不知道。
     *
     * <p><b>2026-10-03：</b>它让手持物的后坐动画（{@code ViewmodelPose}）第一次有了
     * 数据来源 —— 在那之前，后坐的三个数写死在 {@code ViewmodelPose} 里，
     * 于是 {@code GunPresentationSpec.recoilProfileId} 是一条死键：
     * 拿步枪开一枪，右手那把枪抬起来的幅度与手枪逐位相同。
     */
    public String gunRecoilProfileId;

    /**
     * 手持物主色（{@link ViewmodelKind#BLOCK} 用）。
     *
     * <p>枪械与空手有自己的配色表（见 {@code ViewmodelGeometry}），
     * 只有方块/弹药/材料需要外部给色。
     */
    public float colorR = 1f;
    public float colorG = 1f;
    public float colorB = 1f;

    /** 是否在瞄准（ADS）。 */
    public boolean aiming;

    /** 是否在换弹。 */
    public boolean reloading;

    /** 换弹进度 0..1（用于"下压再回位"的曲线）。 */
    public double reloadProgress01;

    /** 是否在挖掘（用于持续的挥动）。 */
    public boolean mining;

    /**
     * 游戏内累计时间（秒）。idle bob 的相位来源。
     *
     * <p>用秒而不是帧数：同一个"1.1 Hz 的摆动"在 60 Hz 与 3000 Hz 下
     * 用帧计数会得到两种完全不同的观感（后者快到看不见）。
     */
    public double timeSeconds;

    /** 走动强度 0..1（bob 的幅度系数；0 = 站住不动）。 */
    public double moveSpeed01;

    /**
     * 累计开火次数。
     *
     * <p><b>为什么是"累计次数"而不是一个"刚开火"的事件标志：</b>
     * 渲染帧率（上千）远高于逻辑帧率（60），一个布尔事件标志在两帧之间
     * 会被漏读或被重复读。累计计数则只需要比较前后两帧的差值，
     * 既不会漏也不会重 —— 这是"渲染层读游戏状态"唯一稳的接法。
     */
    public int shotCount;

    /**
     * 从快捷栏同步一次手持物。
     *
     * @param stack 当前选中的格子；允许为 null（视作空手）
     * @param slot  选中的槽位
     */
    public void apply(ItemStack stack, int slot) {
        this.slot = slot;
        Item item = stack == null ? null : stack.item();
        this.kind = ViewmodelKind.of(item);
        // 枪械轮廓键：只有枪有 presentation（Item 的不变式保证非枪携带它会在构造期抛异常）。
        // 非枪一律置 null —— 否则"上一帧拿的是 SMG、这一帧换成方块"会留下一个陈旧的键。
        this.gunViewmodelId = item != null && item.isGun() && item.presentation() != null
                ? item.presentation().viewmodelId()
                : null;
        // 后坐档案键：与上面同一条口径（只有枪有 presentation），非枪一律置 null。
        // 置 null 而不是留旧值 —— 否则"上一帧拿步枪、这一帧换成方块"会留下一个
        // 步枪的后坐档案，让挥舞一块石头也带上步枪的抬枪幅度。
        this.gunRecoilProfileId = item != null && item.isGun() && item.presentation() != null
                ? item.presentation().recoilProfileId()
                : null;
        if (kind != ViewmodelKind.BLOCK || item == null) {
            return;
        }
        if (item.isBlock()) {
            var block = item.block();
            colorR = block.colorR();
            colorG = block.colorG();
            colorB = block.colorB();
            return;
        }
        // 非方块的 BLOCK 形态（弹药 / 材料）：与 HUD 快捷栏图标同色，
        // 于是"栏里那一格"和"手里那一坨"是同一个颜色，玩家能对上号。
        float[] c = item.kind() == ItemKind.AMMO
                ? new float[]{0.78f, 0.66f, 0.28f}
                : new float[]{0.22f, 0.22f, 0.24f};
        colorR = c[0];
        colorG = c[1];
        colorB = c[2];
    }
}
