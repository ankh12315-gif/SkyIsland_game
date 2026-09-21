package com.skyisland.item;

import com.skyisland.player.Inventory;
import com.skyisland.player.ItemStack;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物品注册表测试（M2）。
 *
 * <p><b>这里保护的核心不变式是"方块物品的 item runtimeId == block runtimeId"。</b>
 * M1 的背包 / 存档 / 自测之所以能在 M2 引入物品注册表后一行不改，
 * 全靠这条对齐。它一旦被破坏，症状会是"背包里的石头变成了别的东西"，
 * 而且不会有任何异常抛出 —— 所以必须由测试守住。
 *
 * <p>另一组测试守的是 PRD 5.4.1 / 5.4.2 的手枪与弹药数值：
 * M2 的通过标准第 2 条是"手枪单发伤害 8（偏差 ≤ 5%）"，
 * 这条数字如果只写在 PRD 表格里而不写进测试，就会在某个重构后悄悄漂移。
 */
class ItemRegistryTest {

    // ============================================================ 前缀对齐

    /**
     * 方块物品的 runtimeId 必须与方块 runtimeId 严格相等。
     *
     * <p>这是 M1 → M2 兼容的<u>唯一</u>支点。破坏它不会报错，只会让老存档的背包物品错位。
     */
    @Test
    void blockItemsShareRuntimeIdWithTheirBlock() {
        for (Block block : BlockRegistry.all()) {
            if (block.runtimeId() == BlockRegistry.AIR_RUNTIME_ID) {
                continue; // 空气对应空槽，不是一种物品
            }
            Item item = ItemRegistry.byName(block.id());
            assertNotNull(item, "每个非空气方块都应有对应的方块物品: " + block.id());
            assertEquals(block.runtimeId(), item.runtimeId(),
                    "方块物品的 runtimeId 必须等于方块 runtimeId: " + block.id());
            assertEquals(block.runtimeId(), item.blockRuntimeId(),
                    "方块物品的 blockRuntimeId 必须等于其 runtimeId: " + block.id());
            assertEquals(ItemKind.BLOCK, item.kind(), "方块物品的 kind 必须是 BLOCK");
        }
    }

    @Test
    void runtimeIdEqualsRegistrationIndex() {
        for (int i = 0; i < ItemRegistry.size(); i++) {
            assertEquals(i, ItemRegistry.byRuntimeId(i).runtimeId(),
                    "物品注册下标与 runtimeId 必须严格一致（index=" + i + "）");
        }
    }

    @Test
    void stableIdsAreUniqueAndNamespaced() {
        Set<String> seen = new HashSet<>();
        for (Item item : ItemRegistry.all()) {
            assertNotNull(item.id());
            assertTrue(item.id().startsWith("skyisland:"), "物品 ID 必须带命名空间: " + item.id());
            assertTrue(seen.add(item.id()), "物品 stable ID 重复: " + item.id());
        }
    }

    @Test
    void emptySlotIsRuntimeIdZero() {
        assertEquals(0, ItemRegistry.EMPTY_RUNTIME_ID);
        assertEquals(ItemKind.EMPTY, ItemRegistry.empty().kind());
        assertTrue(ItemRegistry.byRuntimeId(0).isEmpty());
        assertEquals(0, ItemRegistry.maxStackOf(0), "空槽没有堆叠上限");
    }

    // ============================================================ 手枪（PRD 5.4.1）

    /**
     * M2 通过标准第 2 条：手枪单发伤害 8（偏差 ≤ 5%）。
     * 同时锁定弹匣 12、射速 4.0、有效射程 32。
     */
    @Test
    void pistolMatchesPrd541() {
        Item pistol = ItemRegistry.pistol();
        assertNotNull(pistol, "手枪必须在物品注册表中（MVP 开局装备）");
        assertEquals(ItemRegistry.PISTOL_ID, pistol.id());
        assertTrue(pistol.isGun(), "手枪必须是 GUN 类物品");
        assertFalse(pistol.isBlock(), "手枪没有对应方块，不可放置");

        GunSpec spec = pistol.gun();
        assertEquals(8, spec.damage(), "PRD 5.4.1：手枪单发伤害 8");
        assertEquals(12, spec.magazineSize(), "PRD 5.4.1：弹匣容量 12");
        assertEquals(4.0, spec.fireRate(), 1e-9, "PRD 5.4.1：射速 4.0 发/秒");
        assertEquals(32, spec.range(), "PRD 5.4.1：有效射程 32 格");
    }

    @Test
    void pistolShotIntervalFollowsFireRate() {
        assertEquals(0.25, ItemRegistry.pistol().gun().shotInterval(), 1e-9,
                "射速 4.0 发/秒 → 每发间隔 0.25 秒");
    }

    @Test
    void gunIsNotStackable() {
        assertEquals(1, ItemRegistry.pistol().maxStack(), "枪械不可堆叠");
    }

    // ============================================================ 弹药（PRD 5.4.2）

    @Test
    void pistolAmmoMatchesPrd542() {
        Item ammo = ItemRegistry.pistolAmmo();
        assertNotNull(ammo, "手枪弹必须在物品注册表中");
        assertEquals(ItemRegistry.PISTOL_AMMO_ID, ammo.id());
        assertEquals(ItemKind.AMMO, ammo.kind(), "手枪弹必须是 AMMO 类");
        assertEquals(128, ammo.maxStack(), "PRD 5.4.2：弹药堆叠上限 128");
        assertFalse(ammo.isBlock(), "弹药不是方块");
    }

    /** 煤炭：MVP 第一种"方块掉非方块物品"，证明方块与物品已经是两套注册表。 */
    @Test
    void coalIsANonBlockMaterial() {
        Item coal = ItemRegistry.coal();
        assertNotNull(coal, "煤炭必须在物品注册表中（煤炭矿石的掉落物）");
        assertEquals(ItemRegistry.COAL_ID, coal.id());
        assertEquals(ItemKind.MATERIAL, coal.kind());
        assertFalse(coal.isBlock(), "煤炭不是方块");
        assertEquals(-1, coal.blockRuntimeId(), "非方块物品的 blockRuntimeId 必须是 -1，不能是 0（空气）");
    }

    // ============================================================ 堆叠语义

    /** 弹药的上限是 128，不能被 64 的默认上限夹住 —— 这是 M2 引入按物品查询上限的理由。 */
    @Test
    void ammoStackingUsesItsOwnLimitNotTheDefaultSixtyFour() {
        int ammoId = ItemRegistry.pistolAmmo().runtimeId();
        ItemStack stack = ItemStack.of(ammoId, 100);
        assertEquals(100, stack.count(), "100 发弹药必须能放进一格（上限 128）");
        assertEquals(128, stack.maxStack());
        assertEquals(64, ItemStack.MAX_STACK, "默认上限（方块）仍是 64");

        ItemStack full = ItemStack.of(ammoId, 500);
        assertEquals(128, full.count(), "超出 128 必须夹到 128 而不是 64");
    }

    @Test
    void blockItemsStillUseTheDefaultLimit() {
        int grassId = BlockRegistry.grass().runtimeId();
        assertEquals(64, ItemStack.of(grassId, 999).count(),
                "方块仍按 64 堆叠，M1 行为不变");
    }

    @Test
    void inventoryCanHoldAFullAmmoStackAndReportsWrongItem() {
        Inventory inv = new Inventory();
        int ammoId = ItemRegistry.pistolAmmo().runtimeId();
        assertEquals(0, inv.add(ammoId, 128), "128 发弹药应能全部放入一格");
        assertEquals(1, inv.usedSlotCount(), "128 发只占一格");
        assertEquals(128, inv.countOfItem(ItemRegistry.PISTOL_AMMO_ID));
        assertTrue(inv.hasItem(ItemRegistry.PISTOL_AMMO_ID));
        assertFalse(inv.hasItem(ItemRegistry.PISTOL_ID));
    }

    @Test
    void consumeItemRefusesWhenNotEnoughAndLeavesInventoryUntouched() {
        Inventory inv = new Inventory();
        int ammoId = ItemRegistry.pistolAmmo().runtimeId();
        inv.add(ammoId, 5);

        assertFalse(inv.consumeItem(ItemRegistry.PISTOL_AMMO_ID, 8),
                "不足时必须拒绝");
        assertEquals(5, inv.countOfItem(ItemRegistry.PISTOL_AMMO_ID),
                "被拒绝的消耗不得改动背包");

        assertTrue(inv.consumeItem(ItemRegistry.PISTOL_AMMO_ID, 3));
        assertEquals(2, inv.countOfItem(ItemRegistry.PISTOL_AMMO_ID));
    }

    @Test
    void drainAllEmptiesTheHotbarAndReturnsWhatWasThere() {
        Inventory inv = new Inventory();
        inv.add(BlockRegistry.grass().runtimeId(), 3);
        inv.add(ItemRegistry.pistolAmmo().runtimeId(), 8);

        var dropped = inv.drainAll();
        assertEquals(2, dropped.size());
        assertEquals(0, inv.totalItemCount());
        assertEquals(0, inv.usedSlotCount());
    }

    @Test
    void itemStackOfBlockUsesItemRuntimeId() {
        ItemStack s = ItemStack.of(BlockRegistry.stone(), 5);
        assertEquals(BlockRegistry.stone().runtimeId(), s.blockRuntimeId(),
                "用 Block 构造的格子应仍能读回方块 ID（M1 兼容）");
        assertEquals(5, s.count());
    }

    // ============================================================ 降级路径

    @Test
    void unknownStableIdDegradesToEmptySlotNotToAWrongItem() {
        assertEquals(ItemRegistry.EMPTY_RUNTIME_ID, ItemRegistry.runtimeIdOf("skyisland:no_such_item"),
                "未登记的物品 ID 必须降级为空槽，不能指到别的物品上");
        assertEquals(ItemRegistry.EMPTY_RUNTIME_ID, ItemRegistry.runtimeIdOf(null));
    }

    @Test
    void airIsNotAnItem() {
        assertEquals(ItemRegistry.EMPTY_RUNTIME_ID, ItemRegistry.runtimeIdOf("skyisland:air"),
                "空气是方块表的概念，不是物品");
    }

    @Test
    void itemIdentityIsByRuntimeId() {
        assertEquals(ItemRegistry.pistol(), ItemRegistry.pistol());
        assertSame(ItemRegistry.pistol(), ItemRegistry.byName(ItemRegistry.PISTOL_ID));
        assertNotSame(ItemRegistry.pistol(), ItemRegistry.pistolAmmo());
    }
}
