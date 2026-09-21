package com.skyisland.save;

import com.skyisland.util.Coords;
import com.skyisland.util.Log;
import com.skyisland.world.Chunk;
import com.skyisland.world.World;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;
import com.skyisland.world.gen.ChunkWriter;
import com.skyisland.world.gen.WorldGenerator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * 区块稀疏增量序列化（TECH_DESIGN §N.4）。
 *
 * <p><b>核心洞察：世界生成是 seed 的纯函数，因此只需存"与生成结果不同的那些方块"。</b>
 * 玩家一次典型游戏改动几百个方块，每条约 4 字节 → 约 2 KB；
 * 而存完整区块是 64 KB → <b>节省约 30 倍</b>。
 *
 * <p><b>文件布局（逐字段对应 §N.4）：</b>
 * <pre>
 * 偏移  类型       字段
 * 0     char[4]    "SKIC"                    魔数
 * 4     uint16     formatVersion             = 1
 * 6     int32      cx
 * 10    int32      cz
 * 14    int32      generatorVersion          与本文件生成时的地形算法版本绑定
 * 18    uint16     paletteSize
 * 20    ...        paletteSize × { uint16 strLen; byte[strLen] utf8 }
 * ...   int32      deltaCount
 * ...   ...        deltaCount × { uint16 index; uint16 paletteIndex }   按 index 升序
 * 末尾  uint32     crc32                     覆盖全部前置字节
 * </pre>
 *
 * <p><b>为什么 palette 内嵌而不是每条增量跟一个字符串：</b>
 * 前者自包含、体积小、不依赖注册表顺序；后者每个方块要 20+ 字节，纯浪费。
 *
 * <p><b>绝不存运行时 ID（§N.4 的警告）：</b>运行时 ID 由注册顺序决定，
 * 新增一个方块就会让所有更大的 ID 位移 —— 旧存档会"读档后石头变成玻璃"。
 * 因此增量里存的是 palette 下标，palette 里存的是稳定字符串 ID。
 *
 * <p><b>为什么每个区块还要记 {@code generatorVersion}：</b>
 * 增量是"相对生成结果的差异"。如果地形算法变了而增量没变，
 * 差异就成了错误的数据。把版本写进文件头，加载时就能明确地说
 * "这个区块是在旧地形上改的"，而不是静默产生错位的地形。
 */
public final class ChunkSerializer {

    /** {@code uint16} 索引恰好覆盖 0..32767 的区块体积（§D.5 的 {@code chunkIndex} 值域）。 */
    private static final int MAX_VOLUME = Coords.CHUNK_SIZE * Coords.CHUNK_SIZE * Coords.CHUNK_HEIGHT;

    private ChunkSerializer() {
    }

    // ============================================================ 写

    /**
     * 把一个区块序列化为稀疏增量。
     *
     * @param chunk     要写的区块
     * @param generator 地形生成器（用于重算参考地形）
     * @param seed      世界种子
     * @return 文件字节；若该区块与生成结果<b>完全一致</b>则返回 {@code null}
     *         （调用方据此决定"不写文件"甚至"删除旧文件"）
     */
    public static byte[] serialize(Chunk chunk, WorldGenerator generator, long seed) throws IOException {
        short[] reference = referenceTerrain(generator, chunk.cx(), chunk.cz(), seed);

        List<int[]> deltas = new ArrayList<>();          // {index, runtimeId}
        Map<Short, Integer> paletteIndex = new HashMap<>();
        List<Short> palette = new ArrayList<>();

        // 迭代顺序 ly → lz → lx 恰好产生严格递增的 index（index = (ly<<8)|(lz<<4)|lx），
        // 因此不需要额外排序就能满足 §N.4 的"按 index 升序"要求。
        for (int ly = 0; ly < Coords.CHUNK_HEIGHT; ly++) {
            for (int lz = 0; lz < Coords.CHUNK_SIZE; lz++) {
                for (int lx = 0; lx < Coords.CHUNK_SIZE; lx++) {
                    int index = Coords.chunkIndex(lx, ly, lz);
                    short current = chunk.blockAt(lx, ly, lz);
                    if (current == reference[index]) {
                        continue;
                    }
                    Integer mapped = paletteIndex.get(current);
                    if (mapped == null) {
                        if (palette.size() >= 0xFFFF) {
                            throw new IOException("区块 (" + chunk.cx() + "," + chunk.cz()
                                    + ") 用到的方块种类超过 65535 —— 数据异常");
                        }
                        mapped = palette.size();
                        paletteIndex.put(current, mapped);
                        palette.add(current);
                    }
                    deltas.add(new int[]{index, mapped});
                }
            }
        }

        if (deltas.isEmpty()) {
            return null;
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + deltas.size() * 4);
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(SaveFormat.CHUNK_MAGIC);
        out.writeShort(SaveFormat.CHUNK_FORMAT_VERSION);
        out.writeInt(chunk.cx());
        out.writeInt(chunk.cz());
        out.writeInt(generator.generationVersion());
        out.writeShort(palette.size());
        for (Short runtimeId : palette) {
            String stableId = BlockRegistry.byRuntimeId(runtimeId).id();
            byte[] utf8 = stableId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (utf8.length > 0xFFFF) {
                throw new IOException("方块 stable ID 过长: " + stableId);
            }
            out.writeShort(utf8.length);
            out.write(utf8);
        }
        out.writeInt(deltas.size());
        for (int[] delta : deltas) {
            out.writeShort(delta[0]);
            out.writeShort(delta[1]);
        }
        out.flush();

        byte[] payload = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(payload, 0, payload.length);

        ByteArrayOutputStream withCrc = new ByteArrayOutputStream(payload.length + 4);
        withCrc.write(payload);
        DataOutputStream crcOut = new DataOutputStream(withCrc);
        crcOut.writeInt((int) crc.getValue());
        crcOut.flush();
        return withCrc.toByteArray();
    }

    /**
     * 重算"未被改动的参考地形"。
     *
     * <p>走 {@link ChunkWriter} 门面而不是直接写区块 —— 与运行时生成路径用<u>同一个</u>接口，
     * 保证"参考地形"与"实际地形"的定义不会漂移。
     */
    private static short[] referenceTerrain(WorldGenerator generator, int cx, int cz, long seed) {
        short[] reference = new short[MAX_VOLUME];
        generator.generate(new RecordingWriter(reference, cx, cz), cx, cz, seed);
        return reference;
    }

    // ============================================================ 读

    /** 解码结果。 */
    public record Decoded(int cx, int cz, int generatorVersion, int deltaCount,
                          int[] indices, short[] runtimeIds, int unknownIdCount) {
    }

    /**
     * 校验并解码一个区块增量文件。
     *
     * @throws IOException 魔数不符、版本不认识、CRC 不符、结构截断
     */
    public static Decoded decode(byte[] data) throws IOException {
        if (data == null || data.length < 4 + 2 + 4 + 4 + 4 + 4) {
            throw new IOException("区块文件过短（" + (data == null ? 0 : data.length) + " 字节）");
        }
        // ---- CRC 先校验，再解析内容 ----
        int payloadLength = data.length - 4;
        CRC32 crc = new CRC32();
        crc.update(data, 0, payloadLength);
        long expected = ((long) (data[payloadLength] & 0xFF) << 24)
                | ((long) (data[payloadLength + 1] & 0xFF) << 16)
                | ((long) (data[payloadLength + 2] & 0xFF) << 8)
                | (data[payloadLength + 3] & 0xFF);
        if (crc.getValue() != expected) {
            throw new IOException(String.format("CRC32 校验失败（文件 0x%08X，实算 0x%08X）",
                    expected, crc.getValue()));
        }

        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(data, 0, payloadLength));
        try {
            byte[] magic = new byte[4];
            in.readFully(magic);
            for (int i = 0; i < 4; i++) {
                if (magic[i] != SaveFormat.CHUNK_MAGIC[i]) {
                    throw new IOException("魔数不符（不是 SkyIsland 区块文件）");
                }
            }
            int formatVersion = in.readUnsignedShort();
            if (formatVersion != SaveFormat.CHUNK_FORMAT_VERSION) {
                throw new IOException("不支持的区块格式版本: " + formatVersion
                        + "（当前 " + SaveFormat.CHUNK_FORMAT_VERSION + "）");
            }
            int cx = in.readInt();
            int cz = in.readInt();
            int generatorVersion = in.readInt();

            int paletteSize = in.readUnsignedShort();
            short[] palette = new short[paletteSize];
            int unknown = 0;
            for (int i = 0; i < paletteSize; i++) {
                int length = in.readUnsignedShort();
                byte[] utf8 = new byte[length];
                in.readFully(utf8);
                String stableId = new String(utf8, java.nio.charset.StandardCharsets.UTF_8);
                Block block = BlockRegistry.byName(stableId);
                if (block == null) {
                    unknown++;
                    // runtimeIdOf 内部会记一条含 stableId 的 WARN，这里按 §N.4 替换为空气
                }
                palette[i] = BlockRegistry.runtimeIdOf(stableId);
            }

            int deltaCount = in.readInt();
            if (deltaCount < 0 || deltaCount > MAX_VOLUME) {
                throw new IOException("增量条数非法: " + deltaCount);
            }
            int[] indices = new int[deltaCount];
            short[] runtimeIds = new short[deltaCount];
            for (int i = 0; i < deltaCount; i++) {
                int index = in.readUnsignedShort();
                int paletteIndex = in.readUnsignedShort();
                if (paletteIndex >= paletteSize) {
                    throw new IOException("palette 下标越界: " + paletteIndex + " (种类 " + paletteSize + ")");
                }
                indices[i] = index;
                runtimeIds[i] = palette[paletteIndex];
            }
            return new Decoded(cx, cz, generatorVersion, deltaCount, indices, runtimeIds, unknown);
        } catch (EOFException e) {
            throw new IOException("区块文件结构被截断", e);
        }
    }

    /**
     * 解码并应用到世界。
     *
     * <p><b>"按 index 升序"带来的一个约束：</b>应用顺序就是文件里的顺序，
     * 因此即便将来同一格被写了两次，最后写入的也是文件中靠后的那条 ——
     * 而序列化时按 index 升序且同一格只出现一次，所以这里不需要去重。
     *
     * @return 实际应用的方块数
     */
    public static int applyTo(Decoded decoded, World world, SaveResult result) {
        Chunk chunk = world.getOrLoadChunk(decoded.cx(), decoded.cz());
        if (decoded.generatorVersion() != world.generator().generationVersion()) {
            result.warn(String.format("区块 (%d,%d) 的地形版本 %d 与当前 %d 不符，"
                            + "未改动区域可能与存档不同",
                    decoded.cx(), decoded.cz(), decoded.generatorVersion(),
                    world.generator().generationVersion()));
        }
        if (decoded.unknownIdCount() > 0) {
            result.warn(String.format("区块 (%d,%d) 含 %d 处未登记的方块 ID，已替换为空气",
                    decoded.cx(), decoded.cz(), decoded.unknownIdCount()));
            result.addBlocksSkipped(decoded.unknownIdCount());
        }
        int applied = 0;
        int originX = chunk.originX();
        int originZ = chunk.originZ();
        for (int i = 0; i < decoded.deltaCount(); i++) {
            int index = decoded.indices()[i];
            int lx = Coords.indexX(index);
            int ly = Coords.indexY(index);
            int lz = Coords.indexZ(index);
            if (world.applySavedBlock(originX + lx, ly, originZ + lz, decoded.runtimeIds()[i])) {
                applied++;
            }
        }
        return applied;
    }

    /** 生成期只写记录器：把生成结果写进一个独立数组，用于差异比对。 */
    private static final class RecordingWriter implements ChunkWriter {

        private final short[] target;
        private final int cx;
        private final int cz;

        RecordingWriter(short[] target, int cx, int cz) {
            this.target = target;
            this.cx = cx;
            this.cz = cz;
        }

        @Override
        public int chunkX() {
            return cx;
        }

        @Override
        public int chunkZ() {
            return cz;
        }

        @Override
        public int originX() {
            return cx * Coords.CHUNK_SIZE;
        }

        @Override
        public int originZ() {
            return cz * Coords.CHUNK_SIZE;
        }

        @Override
        public void set(int lx, int ly, int lz, int runtimeId) {
            if (lx < 0 || lx >= Coords.CHUNK_SIZE || lz < 0 || lz >= Coords.CHUNK_SIZE
                    || ly < 0 || ly >= Coords.CHUNK_HEIGHT) {
                return;
            }
            target[Coords.chunkIndex(lx, ly, lz)] = (short) runtimeId;
        }
    }

    /** 供自测断言：某文件是否为合法的区块增量。 */
    public static boolean isValid(byte[] data) {
        try {
            decode(data);
            return true;
        } catch (IOException e) {
            Log.debug("[Save] 区块增量校验失败: %s", e.getMessage());
            return false;
        }
    }
}
