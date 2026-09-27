package com.skyisland.item;

/**
 * 枪械开火模式（WEAPON-DOC-001 v2 §3.2）。
 *
 * <p><b>为什么 M3 只有 SINGLE 与 AUTO：</b>目前 M3 的两把枪（手枪 SINGLE、
 * SMG AUTO）已经覆盖了「点一下发一发」与「按住连续发」这两种最基础的开火语义。
 * 原草案曾预留 {@code BURST}（三连发），但当前没有任何一把 M3 武器需要它 ——
 * 若提前写入枚举，战斗层就要为其写一条永远跑不到的分支，
 * 这正是项目明令禁止的「写了没调用的死代码」。
 * 等真正设计出需要三连发的武器时再加入，不提前造。
 *
 * <p>枚举放在本类独立文件中而非嵌进 {@link GunSpec}：开火模式是<b>战斗语义</b>，
 * 会被 {@code GunState}/{@code CombatController} 直接引用，独立顶层类型比
 * {@code GunSpec.FireMode} 更便于这些类引用，也让 §7 的输入链改造不必依赖 GunSpec。
 */
public enum FireMode {
    /** 单击一次只发射一发（手枪）。消费逻辑步的 attackPressed 边沿。 */
    SINGLE,
    /** 按住不放，按 fireRate 连续射击（SMG）。消费逻辑步的 attackHeld。 */
    AUTO
}
