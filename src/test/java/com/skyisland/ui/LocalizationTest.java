package com.skyisland.ui;

import com.skyisland.item.Item;
import com.skyisland.item.ItemRegistry;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRD 6.7「术语统一与 Display Name 来源」的护栏。
 *
 * <p>PRD v0.3.2 §6.7 把下面四条写成了<b>硬约束</b>，本类把其中可机械检查的部分钉死：
 * <ol>
 *   <li>玩家可见名称必须有统一的 Display Name 来源（本类断言
 *       {@code BlockRegistry} / {@code ItemRegistry} 里的<b>每一条</b>都有中文名）；</li>
 *   <li>「禁止在 Java UI 代码里散落中文字符串字面量」（本类用反射断言
 *       {@code Localization} 里声明的<b>每一个</b> key 常量都真的登记了文案 ——
 *       漏登记时 {@code text(key)} 会把 key 原样显示到屏幕上）；</li>
 *   <li>内部 stable ID 保持英文（本类断言显示名里不含 {@code skyisland:} 前缀）；</li>
 *   <li>「不得因为当前字体渲染能力而把产品规格降级为英文」——
 *       本类断言关键文案确实为中文（否则有人"顺手改回英文"时没人拦得住）。</li>
 * </ol>
 *
 * <p><b>为什么用反射而不是逐条列举：</b>逐条列举的测试只能证明"我列的那些是对的"，
 * 而这里要防的是"<u>新增</u>了一条方块/文案却忘了登记显示名"。反射让新增条目
 * <b>自动</b>进入断言范围 —— 这是"以后不会悄悄坏掉"与"今天是对的"之间的区别。
 */
class LocalizationTest {

    /** 不是文案 key 的公开常量（语言标签本身不是一句文案）。 */
    private static final String NOT_A_TEXT_KEY = "LANG";

    // ============================================================ 显示名覆盖

    @Test
    void everyRegisteredBlockHasAChineseDisplayName() {
        for (Block block : BlockRegistry.all()) {
            assertTrue(Localization.hasDisplayName(block.id()),
                    "方块 " + block.id() + " 没有中文显示名 —— 玩家会在准星读数里看到一串英文 id");
            assertFalse(Localization.displayName(block.id()).contains("skyisland:"),
                    "显示名不得包含内部 stable ID 前缀：" + block.id());
        }
    }

    @Test
    void everyRegisteredItemHasAChineseDisplayName() {
        for (Item item : ItemRegistry.all()) {
            // 空气是"没有物品"，不需要名字（空槽不显示任何文字，PRD 6.7「空状态」）
            if (item.isEmpty()) {
                continue;
            }
            assertTrue(Localization.hasDisplayName(item.id()),
                    "物品 " + item.id() + " 没有中文显示名");
        }
    }

    @Test
    void theFourNamesNamedExplicitlyByThePrdAreExactlyRight() {
        // PRD 6.7 的原文示例：pistol → 手枪、pistol_ammo → 手枪弹、
        // iron_ore → 铁矿石、coal → 煤炭。这四条是产品规格，不是实现细节。
        assertEquals("手枪", Localization.displayName("skyisland:pistol"));
        assertEquals("手枪弹", Localization.displayName("skyisland:pistol_ammo"));
        assertEquals("铁矿石", Localization.displayName("skyisland:iron_ore"));
        assertEquals("煤炭", Localization.displayName("skyisland:coal"));
    }

    @Test
    void theOnlyGunInMvpIsShownAsChineseName() {
        // PRD 6.1「当前枪械名」：MVP 唯一枪械显示为「手枪」。
        // 这条断言守的是"枪械名走了 Display Name 来源，而不是把 stable ID 印在 HUD 上"。
        assertEquals("手枪", Localization.displayName(ItemRegistry.pistol().id()));
        assertEquals("skyisland:pistol", ItemRegistry.pistol().id(),
                "显示名变了，内部 stable ID 不许跟着变（它是落盘与存档的契约）");
    }

    // ============================================================ 未知 id 的回落

    @Test
    void unknownIdsFallBackToTheIdItselfWithAVisibleMarker() {
        String unknown = "skyisland:does_not_exist";

        String shown = Localization.displayName(unknown);

        assertTrue(shown.startsWith(unknown),
                "回落到原样显示 stable ID，而不是空串（空串看起来像渲染坏了）");
        assertTrue(shown.contains("未本地化"),
                "必须带一个显眼的标记，否则开发者永远发现不了自己忘了登记：" + shown);
        assertFalse(Localization.hasDisplayName(unknown));
    }

    @Test
    void nullIdIsHandledWithoutThrowing() {
        assertEquals("", Localization.displayName(null));
        assertFalse(Localization.hasDisplayName(null));
    }

    // ============================================================ 文案表完整性

    @Test
    void everyDeclaredTextKeyIsActuallyRegistered() throws Exception {
        int checked = 0;
        for (Field field : Localization.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || field.getType() != String.class
                    || !Modifier.isPublic(field.getModifiers())) {
                continue;
            }
            if (NOT_A_TEXT_KEY.equals(field.getName())) {
                continue;
            }
            String key = (String) field.get(null);
            assertNotNull(key, "文案 key 常量不得为 null：" + field.getName());
            assertTrue(!key.isEmpty(), "文案 key 常量不得为空：" + field.getName());
            assertTrue(Localization.hasText(key),
                    "文案 key「" + key + "」（常量 " + field.getName()
                            + "）没有登记文案 —— 漏登记时 text() 会把 key 原样印在屏幕上");
            checked++;
        }
        assertTrue(checked >= 20, "反射应当扫到全部文案 key（实际 " + checked + " 条）");
    }

    @Test
    void ammoCounterUsesTheFormatRequiredByThePrd() {
        // PRD 6.7「数值展示」：弹药显示为「弹匣 / 后备」，如「12 / 36」
        assertEquals("12 / 36", Localization.text(Localization.HUD_AMMO_FORMAT, 12, 36));
        assertEquals("0 / 0", Localization.text(Localization.HUD_AMMO_FORMAT, 0, 0));
    }

    @Test
    void playerFacingMessagesAreChineseNotEnglish() {
        // 「不得因为当前字体渲染能力而把产品规格降级为英文」——
        // 这四条是 PRD 6.7 点名的即时提示，必须真的是中文。
        assertEquals("弹药不足", Localization.text(Localization.MSG_OUT_OF_AMMO));
        assertEquals("背包已满", Localization.text(Localization.MSG_INVENTORY_FULL));
        assertEquals("右键瞄准，R 换弹", Localization.text(Localization.MSG_AIM_HINT));
        assertEquals("你倒下了", Localization.text(Localization.DEATH_TITLE));
        // 首次进入提示（PRD 6.7「首次进入提示」）
        assertTrue(Localization.text(Localization.MSG_FIRST_JOIN).contains("WASD"));
        assertTrue(Localization.text(Localization.MSG_FIRST_JOIN).contains("左键挖掘"));
    }

    @Test
    void noPlayerFacingTextStartsWithTheInternalIdPrefix() throws Exception {
        // stable ID 出现在玩家可见文案里，等于把内部标识漏给了玩家
        for (Field field : Localization.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !Modifier.isPublic(field.getModifiers())
                    || field.getType() != String.class
                    || NOT_A_TEXT_KEY.equals(field.getName())) {
                continue;
            }
            String key = (String) field.get(null);
            String text = Localization.rawText(key);
            assertNotNull(text, "key 必须已登记：" + key);
            assertFalse(text.startsWith("skyisland:"),
                    "文案 " + field.getName() + " 看起来是 stable ID 而不是文案：" + text);
        }
    }
}
