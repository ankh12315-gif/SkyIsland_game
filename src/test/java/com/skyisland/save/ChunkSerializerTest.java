package com.skyisland.save;

import com.skyisland.testutil.TestWorlds;
import com.skyisland.util.Coords;
import com.skyisland.world.Chunk;
import com.skyisland.world.World;
import com.skyisland.world.block.BlockRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区块稀疏增量序列化测试（TECH_DESIGN §N.4）。
 *
 * <p><b>本类测的是"存档能不能被信任"。</b>存档是唯一会跨越程序版本、跨越运行环境存活的数据，
 * 因此它的失败模式（读不出来、读出来是错的、被改一个字节就崩）必须在<u>单元测试</u>里
 * 穷举完，而不能依赖"手工试玩一次看起来正常"。
 *
 * <p>四条硬要求（§N.4 / §N.6）：
 * <ol>
 *   <li>与生成结果一致的区块必须返回 {@code null} —— 这是 §N.9"禁止保存整个世界"的落点；</li>
 *   <li>写入的是 palette 里的<b>稳定字符串 ID</b>，绝不是运行时 short；
 *       否则新增一个方块会让旧存档"读档后石头变成玻璃"；</li>
 *   <li>CRC 覆盖全部前置字节，且<b>先校验 CRC 再解析</b>；</li>
 *   <li>未登记的方块 ID 降级为空气并计数，而不是抛异常或静默错位。</li>
 * </ol>
 *
 * <p>其中第 2、3、4 条用<u>手工拼出的字节流</u>来测（{@code handBuilt*}）：
 * 如果只用 {@code serialize} 的产物去喂 {@code decode}，那么"格式理解错了"和
 * "实现错了"会同时错、同时通过。
 */
class ChunkSerializerTest {

    /**
     * 一个"改动过"的世界：地表上放一格木板、再把下方的草方块挖掉。
     *
     * <p><b>顺序不能反。</b>{@code placeBlock} 要求"六邻中至少一个实体方块"，
     * 而木板要放的位置 (3,64,3) 唯一的支撑就是下方的草方块 (3,63,3)。
     * 先挖后放会让放置被世界拒绝（{@code 缺少相邻支撑}），这个辅助方法
     * 就会在 assertTrue 上直接失败 —— 即"测试自己的场景搭错了"。
     */
    private static World modifiedWorld() {
        World world = TestWorlds.flatWorld(0, 0, 0, 0);
        assertTrue(world.placeBlock(3, TestWorlds.SURFACE_BLOCK_Y + 1, 3, TestWorlds.planks(),
                World.MutationCause.PLAYER_PLACE, null).success(), "先放置（需要下方支撑）");
        assertTrue(world.breakBlock(3, TestWorlds.SURFACE_BLOCK_Y, 3,
                World.MutationCause.PLAYER_BREAK).success(), "后破坏");
        return world;
    }

    private static byte[] serialize(World world, int cx, int cz) throws IOException {
        Chunk chunk = world.getOrLoadChunk(cx, cz);
        return ChunkSerializer.serialize(chunk, world.generator(), world.seed());
    }

    // ============================================================ 往返

    @Test
    void roundTripPreservesEveryDeltaExactly() throws IOException {
        World world = modifiedWorld();
        byte[] data = serialize(world, 0, 0);
        assertNotNull(data, "改动过的区块必须产生增量文件");

        ChunkSerializer.Decoded decoded = ChunkSerializer.decode(data);

        assertEquals(0, decoded.cx());
        assertEquals(0, decoded.cz());
        assertEquals(world.generator().generationVersion(), decoded.generatorVersion());
        assertEquals(2, decoded.deltaCount(), "挖一格 + 放一格 = 两条增量");
        assertEquals(0, decoded.unknownIdCount());

        // (3, 63, 3) → 空气；(3, 64, 3) → 木板
        int brokenIndex = Coords.chunkIndex(3, TestWorlds.SURFACE_BLOCK_Y, 3);
        int placedIndex = Coords.chunkIndex(3, TestWorlds.SURFACE_BLOCK_Y + 1, 3);
        assertTrue(decoded.indices()[0] == brokenIndex || decoded.indices()[1] == brokenIndex,
                "挖掉的那一格必须在增量里");
        assertTrue(decoded.indices()[0] == placedIndex || decoded.indices()[1] == placedIndex,
                "放上的那一格必须在增量里");

        for (int i = 0; i < decoded.deltaCount(); i++) {
            short id = decoded.runtimeIds()[i];
            if (decoded.indices()[i] == brokenIndex) {
                assertEquals(BlockRegistry.AIR_RUNTIME_ID, id, "挖掉 → 空气");
            } else {
                assertEquals((short) TestWorlds.planks(), id, "放上 → 木板");
            }
        }
    }

    @Test
    void deltasAreWrittenInAscendingIndexOrder() throws IOException {
        World world = modifiedWorld();
        ChunkSerializer.Decoded decoded = ChunkSerializer.decode(serialize(world, 0, 0));

        for (int i = 1; i < decoded.deltaCount(); i++) {
            assertTrue(decoded.indices()[i] > decoded.indices()[i - 1],
                    "第 " + i + " 条增量的 index 必须严格大于前一条（§N.4 要求升序，"
                            + "应用时无需去重就依赖这一点）");
        }
    }

    @Test
    void unmodifiedChunkProducesNoFileAtAll() throws IOException {
        World world = TestWorlds.flatWorld(0, 0, 0, 0);
        assertNull(serialize(world, 0, 0),
                "与生成结果完全一致的区块必须返回 null —— 否则等于把整个世界写进存档");
    }

    @Test
    void emptyChunkAlsoProducesNoFile() throws IOException {
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0);
        assertNull(serialize(world, 0, 0), "未生成任何内容的区块与参考地形一致，同样不写文件");
    }

    @Test
    void applyToRestoresTheBlocksIntoAFreshWorld() throws IOException {
        World source = modifiedWorld();
        byte[] data = serialize(source, 0, 0);

        World target = TestWorlds.flatWorld(0, 0, 0, 0);
        assertFalse(target.isAirAt(3, TestWorlds.SURFACE_BLOCK_Y, 3), "前提：新世界的这一格是草方块");

        SaveResult result = SaveResult.ok("test");
        int applied = ChunkSerializer.applyTo(ChunkSerializer.decode(data), target, result);

        assertEquals(2, applied);
        assertTrue(target.isAirAt(3, TestWorlds.SURFACE_BLOCK_Y, 3), "挖掉的格子必须变回空气");
        assertEquals((short) TestWorlds.planks(),
                target.blockIdAt(3, TestWorlds.SURFACE_BLOCK_Y + 1, 3), "放上的木板必须回来");
    }

    // ============================================================ 稳定 ID（手工字节流）

    @Test
    void paletteStoresStableStringIdNotRuntimeId() throws IOException {
        World world = modifiedWorld();
        byte[] data = serialize(world, 0, 0);

        // 直接把序列化结果当文本搜：稳定 ID 必须原样出现在字节流里。
        // 若写成运行时 short，这段搜索会失败 —— 而那正是"新增方块后旧存档错位"的根因。
        String asLatin1 = new String(data, StandardCharsets.ISO_8859_1);
        assertTrue(asLatin1.contains("skyisland:oak_planks"),
                "palette 必须内嵌稳定字符串 ID（实际内容=" + asLatin1.replaceAll("[^\\x20-\\x7E]", ".") + "）");
    }

    @Test
    void unknownPaletteIdDegradesToAirAndIsCounted() throws IOException {
        // 手工构造：palette 里放一个注册表里不存在的 ID
        byte[] data = handBuilt(0, 0, 1, List.of("skyisland:does_not_exist"),
                new int[]{Coords.chunkIndex(1, 64, 1)},
                new short[]{0});

        ChunkSerializer.Decoded decoded = ChunkSerializer.decode(data);

        assertEquals(1, decoded.unknownIdCount(),
                "未登记的 ID 必须被计数（调用方据此给出「已替换为空气」的告警）");
        assertEquals(BlockRegistry.AIR_RUNTIME_ID, decoded.runtimeIds()[0],
                "未知方块必须降级为空气 —— 丢一格可以接受，整档打不开不可接受");
    }

    @Test
    void paletteEntryWithNonAsciiCharactersIsRejectedByRegistryNotCrashed() throws IOException {
        byte[] data = handBuilt(0, 0, 1, List.of("非法方块名"),
                new int[]{Coords.chunkIndex(1, 64, 1)},
                new short[]{0});

        ChunkSerializer.Decoded decoded = ChunkSerializer.decode(data);
        assertEquals(1, decoded.unknownIdCount(), "非 ASCII 的未知 ID 同样走降级路径而不是抛异常");
        assertEquals(BlockRegistry.AIR_RUNTIME_ID, decoded.runtimeIds()[0]);
    }

    // ============================================================ 校验与容错

    @Test
    void flippedByteFailsTheCrcCheck() throws IOException {
        byte[] data = serialize(modifiedWorld(), 0, 0);
        // 取中段的字节翻转：既避开文件头也避开末尾 4 字节 CRC
        data[data.length / 2] ^= 0x5A;

        IOException e = assertThrows(IOException.class, () -> ChunkSerializer.decode(data));
        assertTrue(e.getMessage().contains("CRC32"),
                "必须先报 CRC 而不是报某个字段离谱 —— 说明校验发生在解析<u>之前</u>，实际=" + e.getMessage());
    }

    @Test
    void truncatedFileIsRejected() throws IOException {
        byte[] data = serialize(modifiedWorld(), 0, 0);
        byte[] cut = new byte[data.length - 3];
        System.arraycopy(data, 0, cut, 0, cut.length);

        IOException e = assertThrows(IOException.class, () -> ChunkSerializer.decode(cut));
        assertTrue(e.getMessage().contains("CRC32"), "截断会连带破坏 CRC，实际=" + e.getMessage());
    }

    @Test
    void tooShortFileIsRejectedBeforeAnythingElse() {
        IOException e = assertThrows(IOException.class,
                () -> ChunkSerializer.decode(new byte[]{'S', 'K', 'I', 'C'}));
        assertTrue(e.getMessage().contains("过短"), "实际=" + e.getMessage());
        assertFalse(ChunkSerializer.isValid(new byte[]{'S', 'K', 'I', 'C'}));
        assertFalse(ChunkSerializer.isValid(null));
        assertFalse(ChunkSerializer.isValid(new byte[0]));
    }

    @Test
    void wrongMagicIsRejected() throws IOException {
        byte[] data = serialize(modifiedWorld(), 0, 0);
        data[0] = 'X';                       // 破坏魔数
        byte[] repaired = withRecomputedCrc(data);

        IOException e = assertThrows(IOException.class, () -> ChunkSerializer.decode(repaired));
        assertTrue(e.getMessage().contains("魔数"), "实际=" + e.getMessage());
    }

    @Test
    void unsupportedFormatVersionIsRejected() throws IOException {
        byte[] data = serialize(modifiedWorld(), 0, 0);
        // formatVersion 是魔数之后的 uint16，位于偏移 4..5
        data[4] = 0;
        data[5] = 99;
        byte[] repaired = withRecomputedCrc(data);

        IOException e = assertThrows(IOException.class, () -> ChunkSerializer.decode(repaired));
        assertTrue(e.getMessage().contains("格式版本"), "实际=" + e.getMessage());
    }

    @Test
    void structureTruncationIsReportedAsSuch() throws IOException {
        // 手工构造：声称有 5 条增量，实际只给 1 条 → 结构被截断
        byte[] data = handBuilt(0, 0, 5, List.of("skyisland:stone"),
                new int[]{Coords.chunkIndex(1, 64, 1)},
                new short[]{0});

        IOException e = assertThrows(IOException.class, () -> ChunkSerializer.decode(data));
        assertTrue(e.getMessage().contains("截断"), "实际=" + e.getMessage());
    }

    @Test
    void negativeDeltaCountIsRejected() throws IOException {
        byte[] data = handBuilt(0, 0, -1, List.of("skyisland:stone"), new int[0], new short[0]);
        IOException e = assertThrows(IOException.class, () -> ChunkSerializer.decode(data));
        assertTrue(e.getMessage().contains("增量条数非法"), "实际=" + e.getMessage());
    }

    @Test
    void outOfRangePaletteIndexIsRejected() throws IOException {
        // palette 只有 1 项，却引用下标 9
        byte[] data = handBuilt(0, 0, 1, List.of("skyisland:stone"),
                new int[]{Coords.chunkIndex(1, 64, 1)},
                new short[]{9});

        IOException e = assertThrows(IOException.class, () -> ChunkSerializer.decode(data));
        assertTrue(e.getMessage().contains("palette 下标越界"), "实际=" + e.getMessage());
    }

    @Test
    void isValidDistinguishesGoodFromGarbage() throws IOException {
        assertTrue(ChunkSerializer.isValid(serialize(modifiedWorld(), 0, 0)));

        byte[] garbage = new byte[64];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = (byte) (i * 7 + 3);
        }
        assertFalse(ChunkSerializer.isValid(garbage), "随机字节不得被判为合法增量");
    }

    // ============================================================ 手工构造工具

    /**
     * 按 §N.4 的布局手工拼一个区块文件。
     *
     * <p>{@code declaredDeltaCount} 与 {@code indices} 的长度可以故意不一致 ——
     * 这是构造"结构被截断"用例的手段。
     */
    private static byte[] handBuilt(int cx, int cz, int declaredDeltaCount,
                                    List<String> palette, int[] indices, short[] paletteIndices)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.write(SaveFormat.CHUNK_MAGIC);
        out.writeShort(SaveFormat.CHUNK_FORMAT_VERSION);
        out.writeInt(cx);
        out.writeInt(cz);
        out.writeInt(1);                            // generatorVersion
        out.writeShort(palette.size());
        for (String id : palette) {
            byte[] utf8 = id.getBytes(StandardCharsets.UTF_8);
            out.writeShort(utf8.length);
            out.write(utf8);
        }
        out.writeInt(declaredDeltaCount);
        for (int i = 0; i < indices.length; i++) {
            out.writeShort(indices[i]);
            out.writeShort(paletteIndices[i]);
        }
        out.flush();
        return withRecomputedCrc(buffer.toByteArray());
    }

    /** 把末尾 4 字节重算为正确的 CRC（用于"改了内容但仍希望走到后续分支"的用例）。 */
    private static byte[] withRecomputedCrc(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload, 0, payload.length);
        byte[] out = new byte[payload.length + 4];
        System.arraycopy(payload, 0, out, 0, payload.length);
        int value = (int) crc.getValue();
        out[payload.length] = (byte) (value >>> 24);
        out[payload.length + 1] = (byte) (value >>> 16);
        out[payload.length + 2] = (byte) (value >>> 8);
        out[payload.length + 3] = (byte) value;
        return out;
    }

    /** 便于将来扩展：收集一份可读的 index → 坐标摘要（当前仅在失败信息里用）。 */
    @SuppressWarnings("unused")
    private static List<String> describe(int[] indices) {
        List<String> out = new ArrayList<>();
        for (int index : indices) {
            out.add("(" + Coords.indexX(index) + "," + Coords.indexY(index)
                    + "," + Coords.indexZ(index) + ")");
        }
        return out;
    }
}
