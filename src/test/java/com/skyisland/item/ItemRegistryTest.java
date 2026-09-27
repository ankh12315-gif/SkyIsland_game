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

    /**
     * 手枪的 runtimeId 必须稳定（WEAPON-DOC-001 v2 §2.4 / §11.2）。
     *
     * <p>M3 给手枪挂上了 {@code GunPresentationSpec}，这属于"改物品的构造"，很容易被
     * 顺手改成"重新排一遍注册顺序"。runtimeId 一旦位移，所有把 runtimeId 写进
     * 存档/顶点缓存的地方都会静默错位。因此这里把它的值钉死：15（方块 0..14 之后，
     * 煤炭 15 / 手枪弹 16 / 手枪 17 中的最后一个）。
     */
    @Test
    void pistolRuntimeIdIsStable() {
        assertEquals(17, ItemRegistry.pistol().runtimeId(),
                "手枪 runtimeId 必须保持 17 —— 挂上 presentation 不得改变注册顺序");
    }

    // ============================================================ SMG（v2 §10 / §11.1 / §11.2）

    /**
     * SMG 必须存在、是枪、且逐字段等于 v2 §10 武器表（★ 值照抄，不在此处微调）。
     *
     * <p>这条断言是"武器表被写进代码"的可执行形式：伤害改变了、弹匣变成 30 发、
     * 射速写成 6.0 —— 任何一处都说明代码与冻结的武器表脱钩，必须立刻变红。
     */
    @Test
    void smgMatchesTheV2WeaponTable() {
        Item smg = ItemRegistry.smg();
        assertNotNull(smg, "SMG 必须在物品注册表中（v2 §10 / §17 Story 6）");
        assertEquals(ItemRegistry.SMG_ID, smg.id());
        assertEquals("skyisland:smg", smg.id(), "stable ID 是 v2 冻结的存档权威标识");
        assertTrue(smg.isGun(), "SMG 必须是 GUN 类物品");
        assertFalse(smg.isBlock(), "SMG 没有对应方块，不可放置");
        assertEquals(1, smg.maxStack(), "枪械不可堆叠");

        GunSpec spec = smg.gun();
        assertEquals(FireMode.AUTO, spec.fireMode(), "v2 §10：SMG 是 AUTO（按住连发）");
        assertEquals(5, spec.damage(), "v2 §10：SMG 单发伤害 5");
        assertEquals(24, spec.magazineSize(), "v2 §10：SMG 弹匣容量 24");
        assertEquals(10.0, spec.fireRate(), 1e-9, "v2 §10：SMG 射速 10.0 发/秒");
        assertEquals(24, spec.range(), "v2 §10：SMG 有效射程 24 格");
        assertEquals(1.5, spec.reloadSeconds(), 1e-9, "v2 §10：SMG 换弹 1.5 秒");
        assertEquals(1, spec.pelletCount(), "v2 §8.1：M3 两把枪均为单弹丸");
        assertEquals(0.0, spec.spreadRad(), 1e-9, "v2 §8.1：M3 两把枪散布均为 0");
        assertEquals(48.0, spec.aimFovDeg(), 1e-9, "v2 §9.2 / §10：SMG ADS 48°");
        assertEquals(0.65, spec.aimMoveSpeedMult(), 1e-9, "v2 §9.2 / §10：SMG ADS 移速 ×0.65");
        // v2 §10 武器表未给 SMG 单独的衰减值 → 沿用与手枪一致的口径（见注册处注释）
        assertEquals(0.90, spec.falloffPerUnit(), 1e-9);
        assertEquals(0.20, spec.falloffFloor(), 1e-9);
        assertEquals(ItemRegistry.PISTOL_AMMO_ID, spec.ammoId(),
                "v2 §6.1：手枪与 SMG 共用 skyisland:pistol_ammo —— 不新增弹药 Item");
    }

    /** SMG 的射速 10 发/秒 → 每发间隔 0.1 秒（与手枪的 0.25 秒不同，这是可感差异之一）。 */
    @Test
    void smgShotIntervalFollowsItsFireRate() {
        assertEquals(0.1, ItemRegistry.smg().gun().shotInterval(), 1e-9,
                "射速 10.0 发/秒 → 每发间隔 0.1 秒");
        assertEquals(0.25, ItemRegistry.pistol().gun().shotInterval(), 1e-9,
                "手枪仍是 0.25 秒 —— SMG 的加入不得改变它");
    }

    /**
     * v2 §11.2：{@code smg.runtimeId > pistol.runtimeId}，且手枪的 runtimeId 逐值不变。
     *
     * <p><b>为什么这条不是形式主义：</b>把 SMG 插在手枪<b>前面</b>（例如"两把枪放一起更好读"）
     * 会让手枪从 17 变成 18。runtimeId 被写进存档与顶点缓存，位移一次就是
     * "老存档里的手枪变成别的东西"——而不会有任何异常抛出。
     */
    @Test
    void smgIsAppendedAfterThePistolWithoutShiftingIt() {
        assertEquals(17, ItemRegistry.pistol().runtimeId(),
                "手枪 runtimeId 必须逐值不变（v2 §11.2）");
        assertEquals(18, ItemRegistry.smg().runtimeId(),
                "SMG 必须紧接在手枪之后（表尾追加，v2 §11.1「只追加，不插队」）");
        assertTrue(ItemRegistry.smg().runtimeId() > ItemRegistry.pistol().runtimeId(),
                "SMG 的 runtimeId 必须大于手枪");
    }

    /**
     * v2 §11.2：{@code runtimeId == BY_RUNTIME_ID 下标}，且 registry size 只增加预期数量（+1）。
     *
     * <p>M3 之前的物品表长度是 18（空槽 1 + 方块 13 + 煤炭 1 + 弹药 1 + 手枪 1 + ... 见下），
     * 新增 SMG 后只 +1。写成字面量 19 而不是 {@code size()-1}，是为了让"有人又悄悄多注册一件物品"
     * 也必然变红 —— 那是本 Story 明确禁止的（v2 §18：禁止第 3 把枪）。
     */
    @Test
    void registryGrewByExactlyOneItemAndRuntimeIdsStillMatchTheirIndex() {
        assertEquals(19, ItemRegistry.size(),
                "M3 只允许新增 SMG 一件物品（18 → 19）；多一件即违反 v2 §18 的扩枪禁令");
        for (int i = 0; i < ItemRegistry.size(); i++) {
            assertEquals(i, ItemRegistry.byRuntimeId(i).runtimeId(),
                    "runtimeId 必须等于注册下标（v2 §11.2；index=" + i + "）");
        }
        assertSame(ItemRegistry.smg(), ItemRegistry.byName(ItemRegistry.SMG_ID),
                "按 stable ID 必须能取回同一件物品（存档读回走这条路径）");
    }

    /** SMG 不接受非枪的堆叠口径：它和手枪一样是每格 1 件的装备。 */
    @Test
    void smgIsNotStackable() {
        assertEquals(1, ItemRegistry.smg().maxStack());
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
