package com.skyisland.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坐标数学的固化测试（TECH_DESIGN_v0.1 §D.4 / §R 第 3 条）。
 *
 * <p><b>为什么这些测试是 M0 的硬要求：</b>负数坐标处理是体素引擎最容易被
 * "看起来正确"的代码掩盖的缺陷来源。{@code blockX = -1} 时
 * {@code -1 / 16 == 0}、{@code -1 % 16 == -1}，两者都不会抛异常，
 * 但会产生错误的区块归属和负的数组下标。
 *
 * <p>把规则写成断言，意味着这个约定不依赖开发者记忆，而依赖构建结果。
 * 本测试在 M0 就会运行（此时 {@link Coords} 尚无生产调用方），
 * 目的是让 M1 一落地就站在已经被证明的坐标口径上。
 */
class CoordsTest {

    // ==================================================== 世界坐标 → 方块坐标

    @ParameterizedTest(name = "toBlock({0}) = {1}")
    @CsvSource({
            "0.0,        0",
            "0.999,      0",
            "1.0,        1",
            "-0.001,    -1",   // ★ 关键：不能用 (int) 截断，否则得 0
            "-1.0,      -1",
            "-1.5,      -2",   // ★ 地板除法
            "63.999,    63",
            "64.0,      64",
    })
    @DisplayName("世界坐标转方块坐标必须使用 floor 语义")
    void toBlockMustFloor(double world, int expected) {
        assertEquals(expected, Coords.toBlock(world));
    }

    @Test
    @DisplayName("toBlock 与 (int) 强转在负数区间必须产生不同结果（反例锚定）")
    void toBlockDiffersFromNaiveCastForNegatives() {
        double x = -0.5;
        assertEquals(-1, Coords.toBlock(x), "floor 语义");
        assertEquals(0, (int) x, "向零截断语义");
        assertNotEquals(Coords.toBlock(x), (int) x,
                "若两者相等，说明 toBlock 被误改成了强转");
    }

    // ==================================================== 方块坐标 → 区块 / 局部

    @Test
    @DisplayName("★ 用户指定用例：blockX = -1 → chunkX = -1, localX = 15")
    void negativeBlockMapsToPreviousChunkAndTopLocalSlot() {
        assertEquals(-1, Coords.toChunk(-1));
        assertEquals(15, Coords.toLocal(-1));
        assertEquals(15, Coords.localFast(-1),
                "localFast 是 block & 15，负数也必须落在 [0,15]，绝不能返回负下标");
        assertEquals(Coords.toLocal(-1), Coords.localFast(-1));
    }

    @ParameterizedTest(name = "block {0} → chunk {1} / local {2}")
    @CsvSource({
            "   0,   0,  0",
            "  15,   0, 15",
            "  16,   1,  0",
            "  31,   1, 15",
            "  32,   2,  0",
            "  -1,  -1, 15",
            "  -16,  -1,  0",
            "  -17,  -2, 15",
            "-100,  -7, 12",     // -100 = -7*16 + 12
    })
    @DisplayName("区块划分必须使用 floorDiv / floorMod")
    void chunkDivisionMustUseFloorSemantics(int block, int chunk, int local) {
        assertEquals(chunk, Coords.toChunk(block));
        assertEquals(local, Coords.toLocal(block));
        assertEquals(chunk, block < 0 ? Math.floorDiv(block, 16) : block / 16);
        assertEquals(local, Math.floorMod(block, 16));
    }

    @Test
    @DisplayName("toLocal 与 localFast 必须在同一取值域内完全一致")
    void localFastMustAgreeWithToLocal() {
        for (int b = -1024; b <= 1024; b++) {
            assertEquals(Coords.toLocal(b), Coords.localFast(b),
                    "block=" + b);
        }
    }

    @Test
    @DisplayName("localFast 必须恒落在 [0,15]，绝不产生负下标")
    void localFastIsAlwaysNonNegative() {
        for (int b = -4096; b <= 4096; b++) {
            int lx = Coords.localFast(b);
            assertTrue(lx >= 0 && lx < Coords.CHUNK_SIZE, "block=" + b + " local=" + lx);
        }
    }

    @Test
    @DisplayName("负坐标下朴素 / 与 % 会产生错误结果（缺陷可复现性证明）")
    void naiveArithmeticIsProvablyWrong() {
        int bx = -1;
        assertNotEquals(Coords.toChunk(bx), bx / 16,
                "朴素除法给出 0，正确区块是 -1");
        assertNotEquals(Coords.toLocal(bx), bx % 16,
                "朴素取模给出 -1，会让数组下标为负");
    }

    // ==================================================== 往返一致性

    @Test
    @DisplayName("chunk/local 分解与 toWorld 必须构成往返恒等")
    void decompositionRoundTrips() {
        for (int b = -2048; b <= 2048; b++) {
            int cx = Coords.toChunk(b);
            int lx = Coords.toLocal(b);
            assertEquals(b, Coords.toWorld(cx, lx), "block=" + b);
        }
    }

    // ==================================================== 区块键

    @Test
    @DisplayName("chunkKey 必须对 (cx, cz) 双射，且负 cz 不得污染 cx 位域")
    void chunkKeyIsBijectiveWithNegativeCoords() {
        // 这两组坐标在「cz 不做掩码」的实现下会产生相同的键
        assertEquals(Coords.chunkKey(0, -1), Coords.chunkKey(0, -1));
        assertNotEquals(Coords.chunkKey(0, -1), Coords.chunkKey(-1, 0),
                "掩码缺失时这两个键会碰撞");

        Set<Long> keys = new HashSet<>();
        for (int cx = -8; cx <= 8; cx++) {
            for (int cz = -8; cz <= 8; cz++) {
                long k = Coords.chunkKey(cx, cz);
                assertTrue(keys.add(k), "键碰撞: (" + cx + "," + cz + ")");
            }
        }
        assertEquals(17 * 17, keys.size());
    }

    @Test
    @DisplayName("chunkKey 分解必须还原原始区块坐标")
    void chunkKeyDecompositionRoundTrips() {
        for (int cx = -5; cx <= 5; cx++) {
            for (int cz = -5; cz <= 5; cz++) {
                long k = Coords.chunkKey(cx, cz);
                assertEquals(cx, Coords.keyCx(k));
                assertEquals(cz, Coords.keyCz(k));
            }
        }
    }

    // ==================================================== 区块内线性索引

    @Test
    @DisplayName("chunkIndex 必须为 16×128×16 双射，值域 [0,32767]")
    void chunkIndexIsBijective() {
        Set<Integer> seen = new HashSet<>(32768);
        for (int ly = 0; ly < Coords.CHUNK_HEIGHT; ly++) {
            for (int lz = 0; lz < Coords.CHUNK_SIZE; lz++) {
                for (int lx = 0; lx < Coords.CHUNK_SIZE; lx++) {
                    int idx = Coords.chunkIndex(lx, ly, lz);
                    assertTrue(idx >= 0 && idx < 32768, "越界 idx=" + idx);
                    assertTrue(seen.add(idx), "索引碰撞 idx=" + idx);
                    assertEquals(lx, Coords.indexX(idx));
                    assertEquals(lz, Coords.indexZ(idx));
                    assertEquals(ly, Coords.indexY(idx));
                }
            }
        }
        assertEquals(32768, seen.size());
    }

    @Test
    @DisplayName("同一 y 层的 16×16 平面在索引空间中必须连续")
    void sameLayerIsContiguous() {
        int base = Coords.chunkIndex(0, 40, 0);
        for (int lz = 0; lz < Coords.CHUNK_SIZE; lz++) {
            for (int lx = 0; lx < Coords.CHUNK_SIZE; lx++) {
                assertEquals(base + lz * 16 + lx, Coords.chunkIndex(lx, 40, lz));
            }
        }
    }

    // ==================================================== 高度口径（发现 D1）

    @Test
    @DisplayName("表面块 y = 63、顶面标高 = 64（TECH_DESIGN §D.6 裁定）")
    void surfaceHeightsAreConsistent() {
        assertEquals(63, Coords.WORLD_SURFACE_BLOCK_Y);
        assertEquals(64.0, Coords.WORLD_SURFACE_FEET_Y, 1e-9);
        assertEquals(Coords.WORLD_SURFACE_BLOCK_Y + 1, (int) Coords.WORLD_SURFACE_FEET_Y,
                "脚底 y 必须等于表面块顶面标高");
    }

    @Test
    @DisplayName("可放置高度区间与区块高度区间的边界语义")
    void heightRangeChecks() {
        assertTrue(Coords.isPlaceableY(1));
        assertTrue(Coords.isPlaceableY(127));
        assertFalse(Coords.isPlaceableY(0), "y=0 为基岩层，不可放置");
        assertFalse(Coords.isPlaceableY(128));

        assertTrue(Coords.isInWorldY(0));
        assertTrue(Coords.isInWorldY(127));
        assertFalse(Coords.isInWorldY(128));
        assertFalse(Coords.isInWorldY(-1));
    }

    @Test
    @DisplayName("虚空致死线为 y < -8")
    void voidDeathThreshold() {
        assertTrue(Coords.isVoidDeath(-8.000001));
        assertFalse(Coords.isVoidDeath(-8.0), "边界值 -8.0 本身不致死");
        assertFalse(Coords.isVoidDeath(0));
    }

    // ==================================================== 边界掩码

    @Test
    @DisplayName("boundaryMask 必须正确标识四条边")
    void boundaryMaskFlags() {
        assertEquals(0, Coords.boundaryMask(5, 5), "内部点无边界");
        assertEquals(1, Coords.boundaryMask(0, 5), "−X 边");
        assertEquals(2, Coords.boundaryMask(15, 5), "+X 边");
        assertEquals(4, Coords.boundaryMask(5, 0), "−Z 边");
        assertEquals(8, Coords.boundaryMask(5, 15), "+Z 边");
        assertEquals(5, Coords.boundaryMask(0, 0), "最小角同时命中 −X 与 −Z");
        assertEquals(10, Coords.boundaryMask(15, 15), "最大角同时命中 +X 与 +Z");
        assertEquals(1 | 2 | 4 | 8, Coords.boundaryMask(0, 0) | Coords.boundaryMask(15, 15),
                "两个对角必须覆盖全部四位");
    }

    // ==================================================== 常量自检

    @Test
    @DisplayName("区块尺寸必须为 2 的幂（localFast 的位与实现依赖该性质）")
    void chunkSizeIsPowerOfTwo() {
        assertEquals(0, Coords.CHUNK_SIZE & (Coords.CHUNK_SIZE - 1));
        assertEquals(16, Coords.CHUNK_SIZE);
        assertEquals(128, Coords.CHUNK_HEIGHT);
        assertEquals(32768, Coords.CHUNK_SIZE * Coords.CHUNK_SIZE * Coords.CHUNK_HEIGHT,
                "区块立方体元素数必须为 32768");
    }
}
