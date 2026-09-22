package com.skyisland.save;

import com.skyisland.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code player.json} 的数据形状（TECH_DESIGN §N.3 的 M1 子集）。
 *
 * <p><b>背包用"稀疏数组 + 显式 slot 索引"（§N.3 的两个易漏字段之一）：</b>
 * 不依赖数组顺序。M1 只有 9 格快捷栏，但存档格式按"槽位索引显式给出"来写 ——
 * 将来背包扩成 27+9 格时，旧存档里的物品仍然落在它原来的格子里，
 * 而不是因为"数组长度变了"整体串位。
 *
 * <p><b>M2.2：这个"将来"已经到了，而它比当初预想的更危险。</b>
 * slot 的<b>语义</b>变了：v1 里 0..8 表示快捷栏；v2 起 0..26 是主背包、27..35 是快捷栏。
 * 同一个数字 3，在 v1 存档里是"快捷栏第 4 格"，在 v2 存档里是"主背包第 4 格"。
 * 因此读取侧必须先看 {@code saveVersion} 再决定怎么翻译 ——
 * 迁移规则见 {@link SaveFormat#SAVE_VERSION_INVENTORY_36} 与
 * {@code SaveManager#applyPlayerState}。
 *
 * <p><b>物品用 stable string ID：</b>{@code "skyisland:stone"}，不是运行时 short。
 * 运行时 ID 只由注册顺序决定，新增一个方块就会让大于它的 ID 整体位移 ——
 * 旧存档里存的数值 ID 会指向错误的方块（表现为"读档后石头变成玻璃"）。
 *
 * <p><b>M1 未纳入的字段：</b>{@code health} / {@code fallDistance}
 * （M1 的唯一死亡是坠入虚空，不扣血、不计算坠落伤害）、{@code magazineAmmo}
 * （M1 无枪械）。同 {@link LevelMeta}，这些是<b>可选新增字段，不需要升 saveVersion</b>。
 */
public final class PlayerState {

    public int saveVersion = SaveFormat.SAVE_VERSION;

    public double x;
    public double y;
    public double z;

    public double yaw;
    public double pitch;

    public boolean onGround;

    public int deaths;

    public double lastSafeX;
    public double lastSafeY;
    public double lastSafeZ;

    public int selectedSlot;

    /** 稀疏物品列表；只写非空槽位。 */
    public List<Slot> inventory = new ArrayList<>();

    /** 一个槽位。{@code slot} 显式给出，不依赖列表顺序。 */
    public static final class Slot {
        public int slot;
        public String item;
        public int count;

        public Slot() {
        }

        public Slot(int slot, String item, int count) {
            this.slot = slot;
            this.item = item;
            this.count = count;
        }

        @Override
        public String toString() {
            return "Slot(" + slot + " " + item + " x" + count + ")";
        }
    }

    /** 逐字段自检。 */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (saveVersion <= 0) {
            problems.add("saveVersion 非法: " + saveVersion);
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            problems.add("坐标为 NaN/Inf: " + x + "," + y + "," + z);
        }
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) {
            problems.add("视向为 NaN/Inf: " + yaw + "," + pitch);
        }
        if (selectedSlot < 0 || selectedSlot > 8) {
            problems.add("selectedSlot 越界: " + selectedSlot);
        }
        for (Slot slot : inventory) {
            // M2.2：上限取自 Inventory.SLOT_COUNT（36），不再写死 8。
            // 写死数字的代价在本次扩容里已经兑现过一次：背包从 9 格变 36 格时，
            // 这行校验如果不跟着改，就会把"合法的主背包物品"判成越界 ——
            // 症状是读档后背包凭空少东西，而日志里只有一句"槽位越界"。
            // 上限必须有唯一来源，否则"扩容"这件事每次都要靠人记得改第二处。
            if (slot.slot < 0 || slot.slot >= Inventory.SLOT_COUNT) {
                problems.add("物品槽位越界: " + slot.slot);
            }
            if (slot.item == null || slot.item.isBlank()) {
                problems.add("物品 ID 为空（槽 " + slot.slot + "）");
            }
            if (slot.count <= 0) {
                problems.add("物品数量非正（槽 " + slot.slot + "）: " + slot.count);
            }
        }
        return problems;
    }

    @Override
    public String toString() {
        return String.format("PlayerState(%.3f,%.3f,%.3f yaw=%.2f pitch=%.2f 死亡=%d 物品=%d 类)",
                x, y, z, yaw, pitch, deaths, inventory.size());
    }
}
