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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
     * 存档/顶点缓存的地方都会静默错位。
     *
     * <p><b>为什么现在是 24（2026-10-07 的 +5）：</b>
     * {@code ItemRegistry} 先按 {@code BlockRegistry} 的顺序生成<b>方块物品</b>，
     * 再排非方块物品。M4-S5 补齐 PRD_BLOCK_CREATIVE §3.1 的 5 种方块后，
     * 方块物品由 16 变21，其后所有非方块物品的 runtimeId 整体 <b>+5</b>。
     *
     * <p><b>位移沿革（每次都有人显式改，数字来自实测不是推算）：</b>
     * <ul>
     *   <li>原始：煤炭 13、手枪弹 14、手枪 <b>15</b>、SMG 16；</li>
     *   <li>2026-10-02（+2 铜/晶体矿石）：煤炭 15、手枪弹 16、手枪 <b>17</b>、SMG 18；</li>
     *   <li>2026-10-02（+7 非方块：木棍/铁锭/铜锭/晶体/火药/步枪弹/步枪）：手表尾追加到 27；</li>
     *   <li>2026-10-07（+5 方块，M4-S5）：煤炭 22、手枪弹 23、手枪 <b>24</b>、SMG 25、步枪 32。</li>
     * </ul>
     *
     * <p>这条位移是<b>允许且安全</b>的，但必须显式登记：PRD 12.3 规定存档只写
     * stable string ID，运行期一律不把 runtimeId 写进存档 —— 所以位移不会污染老存档。
     * 把数字钉死在这里，是为了让"以后又有人加方块"这件事必然被看见并想一遍。
     */
    @Test
    void pistolRuntimeIdIsStable() {
        assertEquals(24, ItemRegistry.pistol().runtimeId(),
                "手枪 runtimeId 必须保持 24 —— 2026-10-07 因M4-S5 补5 种方块而整体 +5。"
                        + "（上一轮 2026-10-02 因 2 种矿石方块 +2 时是 19，17→19→24）");
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
        assertEquals(24, ItemRegistry.pistol().runtimeId(),
                "手枪 runtimeId 必须逐值不变（v2 §11.2；2026-10-07 S5 起为 24，见上一条说明）");
        assertEquals(25, ItemRegistry.smg().runtimeId(),
                "SMG 必须紧接在手枪之后（表尾追加，v2 §11.1「只追加，不插队」）");
        assertTrue(ItemRegistry.smg().runtimeId() > ItemRegistry.pistol().runtimeId(),
                "SMG 的 runtimeId 必须大于手枪");
    }

    /**
     * 物品表长度被钉死，且 {@code runtimeId == BY_RUNTIME_ID 下标}。
     *
     * <p><b>33 是怎么来的：</b>空槽 1 + 方块物品 21（13 MVP 常规 + 2 Alpha 矿石
     * + 3 Alpha 内容 + 2 后续迭代 + 1 系统）+ 非方块物品 11
     *（煤炭 / 手枪弹 / 手枪 / SMG / 木棍 / 铁锭 / 铜锭 / 晶体 / 火药 /
     * 步枪弹 / 步枪）。
     *
     * <p>写成字面量而不是 {@code size()-1}，是为了让"有人又悄悄多注册一件物品"
     * 也必然变红 —— 本项目的口径是"新增物品必须有人显式改这个数字并想一遍"。
     * M3 时代这条断言写的是 19（只准多一件 SMG，v2 §18 的扩枪禁令）；
     * M4 前置（步枪材料链）把预期的增量显式改成了 +9（2 方块 + 7 物品），
     * 于是"扩枪"这件事本身也变成了一个必须有人签字确认的动作；
     * <b>2026-10-07（M4-S5 补 5 种方块）再+5</b> —— 方块物品段 16 → 21，
     * 连带<b>所有非方块物品 runtimeId 整体 +5</b>（手枪 19→24、SMG 20→25、步枪 27→32）。
     *
     * <p>★ 顺带说明为什么这里<b>没有小麦种</b>：主理人 2026-10-07 裁定本轮不登记
     * {@code skyisland:wheat_seeds}（项目内不存在种植系统，登记即死接线）。
     * 若将来农业闭环落地，这条数字要改成 34。
     */
    @Test
    void registrySizeIsPinnedAndRuntimeIdsStillMatchTheirIndex() {
        assertEquals(33, ItemRegistry.size(),
                "物品表长度必须正好 33（空槽 1 + 方块物品 21 + 非方块物品 11）；"
                        + "多一件或少一件都说明有人改动了注册集却没有同步这条断言");
        for (int i = 0; i < ItemRegistry.size(); i++) {
            assertEquals(i, ItemRegistry.byRuntimeId(i).runtimeId(),
                    "runtimeId 必须等于注册下标（v2 §11.2；index=" + i + "）");
        }
        assertSame(ItemRegistry.smg(), ItemRegistry.byName(ItemRegistry.SMG_ID),
                "按 stable ID 必须能取回同一件物品（存档读回走这条路径）");
        assertSame(ItemRegistry.rifle(), ItemRegistry.byName(ItemRegistry.RIFLE_ID),
                "步枪同样必须能按 stable ID 取回");
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

    // ============================================================ 步枪与材料链（2026-10-02）

    /**
     * 步枪本体必须逐值等于 PRD 5.4.1 / 5.4.3，并满足 v2 §6.2 的扩展规则。
     *
     * <p>PRD 5.4.1 的表给了四个数：伤害 14 / 弹匣 10 / 射速 2.0 / 有效射程 48；
     * 5.4.3 给换弹 2.0 秒。另外三个字段（开火模式 / ADS FOV / ADS 移速）
     * PRD 与 v2 <b>都没有给</b>，是本轮新定的 ★ 值，这里把取值钉住并在报告里登记待确认。
     */
    @Test
    void rifleMatchesPrd541AndTheV2ExtensionRule() {
        Item rifle = ItemRegistry.rifle();
        assertNotNull(rifle, "步枪必须在物品注册表中（主理人 2026-10-02 显式放行，v2 §6.2）");
        assertEquals("skyisland:rifle", rifle.id(), "stable ID 必须是 PRD 5.4.1 的 skyisland:rifle");
        assertTrue(rifle.isGun(), "步枪必须是 GUN 类物品");
        assertFalse(rifle.isBlock(), "步枪没有对应方块，不可放置");
        assertEquals(1, rifle.maxStack(), "枪械不可堆叠");

        GunSpec spec = rifle.gun();
        assertEquals(14, spec.damage(), "PRD 5.4.1：步枪单发伤害 14");
        assertEquals(10, spec.magazineSize(), "PRD 5.4.1：步枪弹匣容量 10");
        assertEquals(2.0, spec.fireRate(), 1e-9, "PRD 5.4.1：步枪射速 2.0 发/秒");
        assertEquals(48, spec.range(), "PRD 5.4.1：步枪有效射程 48 格");
        assertEquals(2.0, spec.reloadSeconds(), 1e-9, "PRD 5.4.3：步枪换弹 2.0 秒");
        assertEquals(0.5, spec.shotInterval(), 1e-9, "射速 2.0 发/秒 → 每发间隔 0.5 秒");

        assertEquals(FireMode.SINGLE, spec.fireMode(),
                "★ 2026-10-03 主理人裁定并冻结：步枪是 SINGLE —— 每次真实 PRIMARY_ACTION press "
                        + "最多发射 1 发，按住左键不得自动连发（SMG 才是 AUTO）");
        assertEquals(1, spec.pelletCount(), "v2 §8.1 口径：单弹丸（霰弹枪才 >1）");
        assertEquals(0.0, spec.spreadRad(), 1e-9, "单弹丸枪散布为 0，准星指向即命中");

        assertEquals(ItemRegistry.RIFLE_AMMO_ID, spec.ammoId(),
                "v2 §6.2：步枪必须指向真实注册的 skyisland:rifle_ammo，不得复用或写字符串标签");
        assertEquals(0.90, spec.falloffPerUnit(), 1e-9,
                "衰减沿用基线：PRD 与 v2 均未给步枪的衰减值，不自行发明未冻结的数值");
        assertEquals(0.20, spec.falloffFloor(), 1e-9);
    }

    /**
     * v2 §6.2 的硬性要求：{@code skyisland:rifle_ammo} 必须注册为<b>真实 Item</b>，禁止只加字符串标签。
     *
     * <p>它同时要满足 PRD 5.4.2 的堆叠口径（128）与"每把枪有自己的弹药"这条数据关系。
     */
    @Test
    void rifleAmmoIsARealRegisteredItemNotAStringLabel() {
        Item ammo = ItemRegistry.rifleAmmo();
        assertNotNull(ammo, "v2 §6.2：skyisland:rifle_ammo 必须注册为 Item");
        assertEquals(ItemRegistry.RIFLE_AMMO_ID, ammo.id());
        assertEquals(ItemKind.AMMO, ammo.kind(), "步枪弹必须是 AMMO 类");
        assertEquals(128, ammo.maxStack(), "PRD 5.4.2：弹药堆叠上限 128");
        assertFalse(ammo.isBlock(), "弹药不是方块");
        assertNotSame(ItemRegistry.pistolAmmo(), ammo, "步枪弹与手枪弹必须是两件不同的物品");

        // 反向：步枪的 ammoId 必须能在注册表里查回来（否则就是"字符串标签"）。
        assertSame(ammo, ItemRegistry.byName(ItemRegistry.rifle().gun().ammoId()),
                "步枪的 ammoId 必须能在注册表里查回同一件物品");
    }

    /** 5 种新材料必须是 MATERIAL 类、按默认上限 64 堆叠。 */
    @Test
    void theFiveRifleChainMaterialsAreRegisteredAsMaterials() {
        String[] ids = {
                ItemRegistry.STICK_ID, ItemRegistry.IRON_INGOT_ID, ItemRegistry.COPPER_INGOT_ID,
                ItemRegistry.CRYSTAL_ID, ItemRegistry.GUNPOWDER_ID};
        for (String id : ids) {
            Item item = ItemRegistry.byName(id);
            assertNotNull(item, "材料必须在物品注册表中: " + id);
            assertEquals(ItemKind.MATERIAL, item.kind(), "必须是 MATERIAL 类: " + id);
            assertEquals(64, item.maxStack(), "材料按默认上限 64 堆叠: " + id);
            assertFalse(item.isBlock(), "材料不是方块: " + id);
            assertEquals(-1, item.blockRuntimeId(), "非方块物品的 blockRuntimeId 必须是 -1: " + id);
        }
    }

    /**
     * ★ 三把枪的 ADS 口径必须<b>两两不同</b>。
     *
     * <p>这条断言的来历是本项目最贵的一课（2026-10-02"同值巧合"）：
     * 只要注册表里的值恰好等于某处写死的常量，"读了数据"与"读了常量"在行为上就
     * <b>再也分不出来</b>。当时手枪 45 / SMG 48 与旧常量 45 撞了一次，
     * 结果接线断了测试照样全绿。步枪落地时把它的 ADS 取成第三组互不相同的值，
     * 于是"ADS 数据真的在起作用"这件事第一次能被<b>真实内容</b>证伪，
     * 而不必依赖合成出来的假枪。
     */
    @Test
    void theThreeGunsHavePairwiseDistinctAdsValues() {
        double[] fov = {
                ItemRegistry.pistol().gun().aimFovDeg(),
                ItemRegistry.smg().gun().aimFovDeg(),
                ItemRegistry.rifle().gun().aimFovDeg()};
        double[] mult = {
                ItemRegistry.pistol().gun().aimMoveSpeedMult(),
                ItemRegistry.smg().gun().aimMoveSpeedMult(),
                ItemRegistry.rifle().gun().aimMoveSpeedMult()};

        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                assertNotEquals(fov[i], fov[j], 1e-9,
                        "第 " + i + " 把与第 " + j + " 把枪的 ADS FOV 不得相同 —— "
                                + "同值会让『读数据』与『读常量』在测试上不可区分");
                assertNotEquals(mult[i], mult[j], 1e-9,
                        "第 " + i + " 把与第 " + j + " 把枪的 ADS 移速倍率不得相同 —— 同上");
            }
        }
        assertEquals(40.0, ItemRegistry.rifle().gun().aimFovDeg(), 1e-9,
                "步枪 ADS 目标 FOV = 40°（★ 本轮新定，三把中最紧）");
        assertEquals(0.55, ItemRegistry.rifle().gun().aimMoveSpeedMult(), 1e-9,
                "步枪 ADS 移速 ×0.55（★ 本轮新定，三把中最重的移动代价）");
    }

    /**
     * ★ 三把枪的 Viewmodel / 图标 / 枪口偏移必须两两不同（v2 §4.2 第①②③项、§14.6）。
     *
     * <p>这一条同时是"步枪必须有自己的剪影与图标"的注册表侧证据 ——
     * 渲染层是否真的按它取几何，由 {@code ViewmodelRendererTest} /
     * {@code ItemIconTest} 的端到端断言负责。
     */
    @Test
    void theThreeGunsHaveDistinctPresentationKeysAndMuzzles() {
        var pistol = ItemRegistry.pistol().presentation();
        var smg = ItemRegistry.smg().presentation();
        var rifle = ItemRegistry.rifle().presentation();

        assertNotNull(rifle, "步枪必须携带 GunPresentationSpec");
        assertEquals(ItemRegistry.RIFLE_VIEWMODEL_ID, rifle.viewmodelId(),
                "步枪的 viewmodelId 必须是 rifle —— 否则渲染层会静默回退成手枪轮廓");
        assertEquals(ItemRegistry.RIFLE_ICON_ID, rifle.iconId());

        String[] vm = {pistol.viewmodelId(), smg.viewmodelId(), rifle.viewmodelId()};
        String[] icon = {pistol.iconId(), smg.iconId(), rifle.iconId()};
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                assertNotEquals(vm[i], vm[j], "三把枪的 viewmodelId 必须两两不同（v2 §4.2 第①项）");
                assertNotEquals(icon[i], icon[j], "三把枪的 iconId 必须两两不同（v2 §4.2 第②项）");
                assertNotEquals(pistol.recoilProfileId(), smg.recoilProfileId());
            }
        }

        // 枪口偏移：v2 §14.6 要求两把枪必须不同；三把时要求两两不同。
        double[][] muzzle = {
                {pistol.muzzleForward(), pistol.muzzleRight(), pistol.muzzleDown()},
                {smg.muzzleForward(), smg.muzzleRight(), smg.muzzleDown()},
                {rifle.muzzleForward(), rifle.muzzleRight(), rifle.muzzleDown()}};
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                boolean same = Math.abs(muzzle[i][0] - muzzle[j][0]) < 1e-9
                        && Math.abs(muzzle[i][1] - muzzle[j][1]) < 1e-9
                        && Math.abs(muzzle[i][2] - muzzle[j][2]) < 1e-9;
                assertFalse(same, "第 " + i + " 把与第 " + j + " 把枪的枪口偏移三元组不得完全相同（v2 §14.6）");
            }
        }
    }
}
