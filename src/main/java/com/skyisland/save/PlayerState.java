package com.skyisland.save;

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
            if (slot.slot < 0 || slot.slot > 8) {
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
