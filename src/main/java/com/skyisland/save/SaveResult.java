package com.skyisland.save;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 存档操作的结果。
 *
 * <p><b>为什么不用 {@code boolean} 或抛异常：</b>
 * 存档的关键要求是"降级而非崩溃"（TECH_DESIGN §N.6）——
 * 一个区块 CRC 失败要跳过它继续加载，一个玩家位置非法要修正后继续。
 * 这些都不是"成功/失败"能表达的，也不该让整个流程抛出异常。
 * 因此结果里既有成败，也有<u>逐条事件</u>（警告列表），供日志与报告引用。
 */
public final class SaveResult {

    private final boolean success;
    private final String summary;
    private final List<String> warnings = new ArrayList<>();

    private int chunksWritten;
    private int chunksLoaded;
    private int blocksApplied;
    private int blocksSkipped;
    private int filesRecoveredFromBackup;
    private int filesCorrupted;

    private SaveResult(boolean success, String summary) {
        this.success = success;
        this.summary = summary;
    }

    public static SaveResult ok(String summary) {
        return new SaveResult(true, summary);
    }

    public static SaveResult failed(String summary) {
        return new SaveResult(false, summary);
    }

    public boolean success() {
        return success;
    }

    public String summary() {
        return summary;
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public void warn(String message) {
        warnings.add(message);
    }

    public int chunksWritten() {
        return chunksWritten;
    }

    public void addChunkWritten() {
        chunksWritten++;
    }

    public int chunksLoaded() {
        return chunksLoaded;
    }

    public void addChunkLoaded() {
        chunksLoaded++;
    }

    public int blocksApplied() {
        return blocksApplied;
    }

    public void addBlocksApplied(int count) {
        blocksApplied += count;
    }

    public int blocksSkipped() {
        return blocksSkipped;
    }

    public void addBlocksSkipped(int count) {
        blocksSkipped += count;
    }

    public int filesRecoveredFromBackup() {
        return filesRecoveredFromBackup;
    }

    public void addRecoveredFromBackup() {
        filesRecoveredFromBackup++;
    }

    public int filesCorrupted() {
        return filesCorrupted;
    }

    public void addCorrupted() {
        filesCorrupted++;
    }

    /** 一行可摘录的统计。 */
    public String oneLine() {
        return String.format(
                "%s | 写入区块=%d 读取区块=%d 应用方块=%d 跳过方块=%d 备份恢复=%d 损坏文件=%d 警告=%d",
                summary, chunksWritten, chunksLoaded, blocksApplied, blocksSkipped,
                filesRecoveredFromBackup, filesCorrupted, warnings.size());
    }

    @Override
    public String toString() {
        return oneLine();
    }
}
