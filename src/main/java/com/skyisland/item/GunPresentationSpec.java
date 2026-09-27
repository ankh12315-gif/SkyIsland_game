package com.skyisland.item;

/**
 * 枪械<b>表现规格</b>（WEAPON-DOC-001 v2 §4.1）。
 *
 * <p><b>为什么表现必须与战斗逻辑分离：</b>{@link GunSpec} 描述的是"这把枪怎么打人"
 * ——伤害、射速、弹匣、换弹时长。这些都是<u>有对错的战斗规则</u>，改动它们会改变平衡、
 * 会让验收基线漂移。而 Viewmodel 用哪套几何、图标长什么样、枪口火光从哪个点冒出来、
 * 开火放哪一声，属于<u>表现层资源</u>：改这些<b>不该动到一行战斗逻辑</b>，
 * 也不该让"伤害变了"与"图标换了"在同一个字段上互相牵扯。
 * 把两者混进一个 record，会导致"美术换一版枪模型"变成"要重新跑一遍战斗回归"。
 *
 * <p><b>本类只存"键"，不存资源本体。</b>所有 {@code xxxId} 字段都是<b>查表键</b>
 * （stable key），不是模型、纹理或音频数据本身：
 * <ul>
 *   <li>{@code viewmodelId} 由第一人称渲染层解析成具体几何
 *       （当前 {@code render.viewmodel} 包；Story 6 起按枪种取不同轮廓）；</li>
 *   <li>{@code iconId} 由 HUD / 背包图标层解析（当前 {@code render.ui.ItemIcon}）；</li>
 *   <li>{@code fireSoundId}/{@code emptySoundId}/{@code reloadSoundId} 由音频层解析成
 *       {@code AudioEvent}（当前 {@code audio} 包）；</li>
 *   <li>{@code recoilProfileId} 由第一人称后坐表现层解析成一条曲线 / 一组幅度。</li>
 * </ul>
 * 因此本类<b>不依赖 GL、不依赖 OpenAL、不依赖 joml</b>，可以被物品注册表和纯单测直接构造。
 *
 * <p><b>muzzleOffset 为什么是三个 double 而不是一个新的 Vec3 类型：</b>
 * 项目里没有现成的三维矢量值类型（几何计算一律直接用 joml 的 {@code Vector3fc}/
 * {@code Vector3d}，那是引擎依赖）。为一个"眼睛相对枪口的偏移"引入新类型或新依赖，
 * 收益只是写起来短一点，代价是给 {@code item} 包（纯数据、不碰 GL）凭空挂上渲染依赖。
 * 因此按 v2 §4.1 允许的 (a) 方案拆成三个 double：
 * <ul>
 *   <li>{@code muzzleForward} —— 沿视线<b>前向</b>的偏移（格）；</li>
 *   <li>{@code muzzleRight}   —— 沿视线<b>右向</b>的偏移（格）；</li>
 *   <li>{@code muzzleDown}    —— 沿视线<b>下向</b>的偏移（格）。</li>
 * </ul>
 * 这三个轴的语义与现状 {@code SkyIslandGame.MUZZLE_FORWARD/RIGHT/DOWN} 一一对应，
 * 于是"火光的落点"从一个写在游戏主类里的全局常量，变成"数据挂在拿的那把枪上"。
 *
 * @param viewmodelId    第一人称手持物模型查表键（如手枪 = {@code "pistol"}）
 * @param iconId         HUD / 背包图标查表键
 * @param muzzleForward  枪口相对眼睛沿<b>前向</b>的偏移，格
 * @param muzzleRight    枪口相对眼睛沿<b>右向</b>的偏移，格
 * @param muzzleDown     枪口相对眼睛沿<b>下向</b>的偏移，格
 * @param fireSoundId    开火音效查表键（对应 {@code AudioEvent} 的稳定 id）
 * @param emptySoundId   空仓音效查表键（弹匣为空仍按左键）
 * @param reloadSoundId  换弹音效查表键
 * @param recoilProfileId 视觉后坐表现查表键（幅度 / 曲线）
 */
public record GunPresentationSpec(
        String viewmodelId,
        String iconId,
        double muzzleForward,
        double muzzleRight,
        double muzzleDown,
        String fireSoundId,
        String emptySoundId,
        String reloadSoundId,
        String recoilProfileId
) {

    /**
     * 构造期校验。
     *
     * <p>与 {@link GunSpec} 同一条理由：这些值全部来自注册表常量，写错属于<b>开发期错误</b>，
     * 应当<b>立刻崩</b>在启动/构造时，而不是在若干分钟后表现为"手里那把枪画成了别的样子"
     * 或"枪口火光糊在眼睛上"。所有非法输入一律抛 {@link IllegalArgumentException}。
     */
    public GunPresentationSpec {
        requireKey(viewmodelId, "viewmodelId");
        requireKey(iconId, "iconId");
        requireKey(fireSoundId, "fireSoundId");
        requireKey(emptySoundId, "emptySoundId");
        requireKey(reloadSoundId, "reloadSoundId");
        requireKey(recoilProfileId, "recoilProfileId");
        requireFinite(muzzleForward, "muzzleForward");
        requireFinite(muzzleRight, "muzzleRight");
        requireFinite(muzzleDown, "muzzleDown");
    }

    private static void requireKey(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空: " + value);
        }
    }

    /**
     * 偏移必须是<b>有限</b>值。
     *
     * <p>只禁 {@code NaN} / 无穷，不设正负上下限：偏移的符号与大小是表现调参的自由度
     * （例如把枪口调高一点、偏左一点都合法），但 {@code NaN} 会让枪口坐标污染整个
     * 粒子系统（一个 NaN 顶点能糊掉整批绘制），无穷同理。这类值绝不会是"有意为之"。
     */
    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " 必须是有限值: " + value);
        }
    }
}
