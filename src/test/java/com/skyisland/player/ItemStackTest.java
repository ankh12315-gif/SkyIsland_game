package com.skyisland.player;

import com.skyisland.testutil.TestWorlds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单格物品测试。
 *
 * <p>关注点是<b>"空"的表达方式</b>：{@code EMPTY} 用 runtimeId=0（空气）而不是 {@code null}。
 * 这条决定让整条挖-拾取-放置链路上没有任何一处需要判空，代价是
 * {@code isEmpty()} 必须同时看数量与 ID —— 这里把这个双条件钉住。
 */
class ItemStackTest {

    private static final int GRASS = TestWorlds.grass();

    @Test
    void emptyStackIsRecognisedInBothDimensions() {
        assertTrue(ItemStack.EMPTY.isEmpty());
        assertEquals(0, ItemStack.EMPTY.count());
        assertEquals(0, ItemStack.EMPTY.blockRuntimeId());
        assertTrue(new ItemStack(0, 5).isEmpty(), "空气 ID 即便带数量也算空");
        assertTrue(new ItemStack(GRASS, 0).isEmpty(), "数量 0 即空");
        assertTrue(new ItemStack(GRASS, -3).isEmpty());
        assertFalse(new ItemStack(GRASS, 1).isEmpty());
    }

    @Test
    void ofNormalisesInvalidInputsToEmpty() {
        assertSame(ItemStack.EMPTY, ItemStack.of(0, 10), "空气方块不能成为物品");
        assertSame(ItemStack.EMPTY, ItemStack.of(GRASS, 0));
        assertSame(ItemStack.EMPTY, ItemStack.of(GRASS, -1));
    }

    @Test
    void ofClampsToMaxStack() {
        assertEquals(ItemStack.MAX_STACK, ItemStack.of(GRASS, 999).count());
        assertEquals(ItemStack.MAX_STACK, ItemStack.of(GRASS, ItemStack.MAX_STACK).count());
        assertEquals(5, ItemStack.of(GRASS, 5).count());
    }

    @Test
    void grownAndShrunkRespectBounds() {
        ItemStack five = ItemStack.of(GRASS, 5);

        assertEquals(8, five.grown(3).count());
        assertEquals(2, five.shrunk(3).count());
        assertEquals(GRASS, five.grown(3).blockRuntimeId(), "增减数量不得改变方块种类");

        assertTrue(five.shrunk(5).isEmpty(), "减到 0 必须是空而不是负数");
        assertTrue(five.shrunk(100).isEmpty());
        assertEquals(ItemStack.MAX_STACK, five.grown(1000).count(), "超出上限必须夹住而不是溢出");
    }

    @Test
    void withCountReturnsEmptyForNonPositive() {
        assertSame(ItemStack.EMPTY, ItemStack.of(GRASS, 10).withCount(0));
        assertSame(ItemStack.EMPTY, ItemStack.of(GRASS, 10).withCount(-1));
    }

    @Test
    void freeSpaceReflectsEmptyAndFullStates() {
        assertEquals(ItemStack.MAX_STACK, ItemStack.EMPTY.freeSpace(),
                "空槽能容纳整整一堆（这是 Inventory 找空槽的直接判据）");
        assertEquals(ItemStack.MAX_STACK - 10, ItemStack.of(GRASS, 10).freeSpace());
        assertEquals(0, ItemStack.of(GRASS, ItemStack.MAX_STACK).freeSpace());
    }

    @Test
    void canMergeOnlyForSameBlockWithRoom() {
        ItemStack tenGrass = ItemStack.of(GRASS, 10);
        ItemStack tenDirt = ItemStack.of(TestWorlds.dirt(), 10);
        ItemStack fullGrass = ItemStack.of(GRASS, ItemStack.MAX_STACK);

        assertTrue(tenGrass.canMergeWith(tenGrass), "同种且未满 → 可合并");
        assertFalse(tenGrass.canMergeWith(tenDirt), "不同方块不可合并（不能让一堆里混两种方块）");
        assertFalse(tenGrass.canMergeWith(ItemStack.EMPTY), "空槽没有'种类'可言");
        assertFalse(ItemStack.EMPTY.canMergeWith(tenGrass), "空槽不能作为合并源");
        assertFalse(fullGrass.canMergeWith(tenGrass), "满堆没有余量");
    }

    @Test
    void recordsWithSameValuesAreEqual() {
        assertEquals(ItemStack.of(GRASS, 7), ItemStack.of(GRASS, 7));
        assertEquals(ItemStack.of(GRASS, 7).hashCode(), ItemStack.of(GRASS, 7).hashCode());
    }

    @Test
    void toStringDistinguishesEmptyFromHolding() {
        assertEquals("-", ItemStack.EMPTY.toString());
        assertFalse(ItemStack.of(GRASS, 3).toString().isEmpty());
        assertTrue(ItemStack.of(GRASS, 3).toString().endsWith("x3"));
    }
}
