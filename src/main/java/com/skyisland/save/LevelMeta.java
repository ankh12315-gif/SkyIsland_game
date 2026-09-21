package com.skyisland.save;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code level.json} 的数据形状（TECH_DESIGN §N.2 的 M1 子集）。
 *
 * <p><b>为什么是"可变 POJO + public 字段"而不是 record：</b>
 * Gson 反序列化 record 依赖构造器参数名（需要 {@code -parameters} 编译参数）
 * 或 {@code @JsonAdapter}，而 POJO 直接按字段名映射，最不容易在打包后失效。
 * 存档格式的解析路径上"少一个不确定因素"比"代码更现代"重要得多。
 *
 * <p><b>M1 未纳入的字段（§N.2 有、M1 不写）：</b>{@code worldTimeSeconds} /
 * {@code dayPhase} / {@code dayCount} / {@code dayFactor}（M1 无昼夜循环）、
 * {@code resourceCores}（资源核心的累积产出属 M2）、{@code settingsSnapshot}
 * （设置系统属 M3）。这些字段在 M2/M3 追加时<b>不需要提升 {@code saveVersion}</b> ——
 * 新增可选字段对旧存档是向后兼容的。这条判断写在这里，避免将来误升版本号。
 */
public final class LevelMeta {

    public int saveVersion = SaveFormat.SAVE_VERSION;

    public String worldName = SaveFormat.DEFAULT_WORLD_NAME;

    public long worldSeed;

    /** 生成器稳定 ID，用于判断"同一个世界的存档是否被别的生成器接管"。 */
    public String generatorId;

    /** 生成算法版本；与当前不符时加载必须<u>警告</u>而不是静默继续（§N.2）。 */
    public int generatorVersion;

    public long createdAtMillis;

    public long savedAtMillis;

    /** 写出该存档的程序版本（诊断用，不参与兼容判定）。 */
    public String productVersion;

    /** 增量文件数量（便于不打开目录就知道"这个存档动过几个区块"）。 */
    public int modifiedChunkCount;

    private static final List<String> DEFERRED = new ArrayList<>(List.of(
            "worldTimeSeconds", "dayPhase", "dayCount", "dayFactor",
            "resourceCores", "settingsSnapshot"));

    /** 供报告引用：本版本刻意未写入的 §N.2 字段。 */
    public static List<String> deferredFields() {
        return List.copyOf(DEFERRED);
    }

    /** 逐字段自检，供单元测试断言"存档元数据完整"。 */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (saveVersion <= 0) {
            problems.add("saveVersion 非法: " + saveVersion);
        }
        if (generatorId == null || generatorId.isBlank()) {
            problems.add("generatorId 为空");
        }
        if (generatorVersion <= 0) {
            problems.add("generatorVersion 非法: " + generatorVersion);
        }
        if (worldName == null || worldName.isBlank()) {
            problems.add("worldName 为空");
        }
        return problems;
    }

    @Override
    public String toString() {
        return "LevelMeta(v" + saveVersion + " seed=" + worldSeed
                + " generator=" + generatorId + "#" + generatorVersion
                + " modifiedChunks=" + modifiedChunkCount + ")";
    }
}
