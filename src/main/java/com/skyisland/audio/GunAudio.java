package com.skyisland.audio;

import com.skyisland.item.GunPresentationSpec;

/**
 * {@link GunPresentationSpec} 的三个音效键 → {@link AudioEvent} 的解析（2026-10-03）。
 *
 * <h2>它关闭的是哪一条死接线</h2>
 * {@code GunPresentationSpec} 从 M3 Story 5 起就带着 {@code fireSoundId} /
 * {@code emptySoundId} / {@code reloadSoundId} 三个键，但 {@code AudioFeedback}
 * 一直在直接写 {@code AudioEvent.GUN_FIRE} / {@code GUN_EMPTY} / {@code RELOAD}
 * 三个枚举常量。结果是：三把枪的音效在数据里各有一套键，而实际响的永远是同一条
 * 硬编码分支 —— 把步枪的 {@code fireSoundId} 改成任何值，都不会有任何一件事发生变化。
 *
 * <p>本类把"键 → 事件"这一步单独拎出来，于是它可以被<b>直接</b>举证，
 * 而不必依赖"真的开一枪、用耳朵听"。
 *
 * <h2>为什么三个方法而不是一个 {@code resolve(spec, kind)}</h2>
 * 三个键的语义完全不同（击发 / 空仓 / 换弹），"取第 n 个"这种写法需要调用方
 * 记住顺序 —— 而顺序错位正是 {@code PlayerIntentCopyTest} 专门在防的那一类错误
 * （相邻字段写反且编译器沉默）。三个具名方法把顺序从调用方手里拿走。
 *
 * <h2>★ 关于"三把枪共用同一条音轨"</h2>
 * 主理人裁定第 12 条：<b>共用同一个音轨是允许的，但<b>路径必须真的经过 id</b>。</b>
 * 因此当前三把枪的三个键确实指向同一组事件（{@code gun_fire} / {@code gun_empty} /
 * {@code reload}），这不是偷懒，而是"还没有为它们各自录/合成音轨"这一事实的诚实表达。
 *
 * <p>但共用一个值立刻带来本项目最熟悉的那个陷阱（<b>同值巧合</b>）：
 * 三把枪的音效 id 全都相同时，"读了数据"与"读了常量"在行为上不可区分。
 * 因此本类的可证伪性<b>不</b>由"三把枪听起来不同"承担，而由下面这条承担：
 * <b>换掉其中一把枪的键，只有那一把的解析结果跟着变</b>
 * （见 {@code GunPresentationRoutingTest}）。这就是裁定第 12 条要求的那条证据。
 */
public final class GunAudio {

    private GunAudio() {
    }

    /** 击发音：{@link GunPresentationSpec#fireSoundId()}。 */
    public static AudioEvent fireEventOf(GunPresentationSpec pres) {
        return AudioEvent.byId(require(pres).fireSoundId());
    }

    /** 空仓音：{@link GunPresentationSpec#emptySoundId()}。 */
    public static AudioEvent emptyEventOf(GunPresentationSpec pres) {
        return AudioEvent.byId(require(pres).emptySoundId());
    }

    /** 换弹音：{@link GunPresentationSpec#reloadSoundId()}。 */
    public static AudioEvent reloadEventOf(GunPresentationSpec pres) {
        return AudioEvent.byId(require(pres).reloadSoundId());
    }

    private static GunPresentationSpec require(GunPresentationSpec pres) {
        if (pres == null) {
            throw new IllegalArgumentException(
                    "取枪械音效必须给出 GunPresentationSpec（非枪械物品没有音效键）");
        }
        return pres;
    }
}
