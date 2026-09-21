package com.skyisland.save;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原子写入测试（TECH_DESIGN §N.5）。
 *
 * <p><b>为什么这件事值得单独一个测试类：</b>"原子写入"是一个<u>看起来</u>已经被
 * {@code Files.move(ATOMIC_MOVE)} 解决、实际上很容易做成"假原子"的功能：
 * 少了 {@code force(true)} 时，正常关机下一切正常，只有掉电才会暴露 ——
 * 而那时玩家的存档已经没了。这类缺陷无法靠试玩发现，只能靠把
 * "写入顺序 + 备份存在性 + 回退行为"逐条断言下来。
 *
 * <p>三条契约：
 * <ol>
 *   <li>首次写入不留 {@code .bak}（没有旧版本可备份）也不留 {@code .tmp}；</li>
 *   <li>覆盖写入时，<b>上一版</b>成为 {@code .bak}，且只保留一版；</li>
 *   <li>主文件不可读时回退到 {@code .bak}；两者都不可用才返回 {@code null}。</li>
 * </ol>
 */
class AtomicFileWriterTest {

    @TempDir
    Path dir;

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ============================================================ 首次写入

    @Test
    void firstWriteLeavesNeitherBackupNorTempFile() throws IOException {
        Path target = dir.resolve("level.json");

        AtomicFileWriter.write(target, utf8("A"));

        assertEquals("A", Files.readString(target));
        assertFalse(AtomicFileWriter.backupExists(target), "首次写入时没有旧文件，不该凭空生成备份");
        assertFalse(Files.exists(dir.resolve("level.json.tmp")), "临时文件必须被改名消耗掉");
    }

    @Test
    void writeCreatesMissingParentDirectories() throws IOException {
        Path target = dir.resolve("chunks").resolve("c.3.-2.bin");

        AtomicFileWriter.write(target, new byte[]{1, 2, 3});

        assertTrue(Files.exists(target), "chunks/ 不存在时必须自动创建");
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target));
    }

    @Test
    void writeHandlesBinaryPayloadWithZerosAndHighBytes() throws IOException {
        Path target = dir.resolve("c.0.0.bin");
        byte[] payload = new byte[512];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xFF);   // 含 0x00 与 0x80..0xFF
        }

        AtomicFileWriter.write(target, payload);

        assertArrayEquals(payload, Files.readAllBytes(target),
                "必须按字节写入而不是按字符 —— 用 Writer 会把 0x00 之后的字节吃掉");
    }

    // ============================================================ 覆盖与备份

    @Test
    void secondWriteKeepsOnlyTheImmediatelyPreviousVersion() throws IOException {
        Path target = dir.resolve("level.json");

        AtomicFileWriter.write(target, utf8("v0"));
        AtomicFileWriter.write(target, utf8("v1"));

        assertEquals("v1", Files.readString(target));
        assertTrue(AtomicFileWriter.backupExists(target));
        assertEquals("v0", Files.readString(dir.resolve("level.json.bak")),
                "备份必须是上一版，而不是首版或随机某一版");
    }

    @Test
    void repeatedWritesSlideTheBackupWindow() throws IOException {
        Path target = dir.resolve("player.json");

        for (int i = 0; i < 5; i++) {
            AtomicFileWriter.write(target, utf8("v" + i));
        }

        assertEquals("v4", Files.readString(target));
        assertEquals("v3", Files.readString(dir.resolve("player.json.bak")),
                "反复保存只保留一版备份（否则存档目录会无限膨胀）");
        assertFalse(Files.exists(dir.resolve("player.json.bak.bak")));
    }

    @Test
    void writingEmptyPayloadIsAllowedAndLeavesAnEmptyFile() throws IOException {
        Path target = dir.resolve("empty.json");
        AtomicFileWriter.write(target, utf8("something"));
        AtomicFileWriter.write(target, new byte[0]);

        assertEquals(0, Files.size(target), "空负载必须真的写空，而不是保留旧内容");
    }

    // ============================================================ 读取与回退

    @Test
    void readPrefersThePrimaryFileOverTheBackup() throws IOException {
        Path target = dir.resolve("level.json");
        AtomicFileWriter.write(target, utf8("old"));
        AtomicFileWriter.write(target, utf8("new"));

        assertArrayEquals(utf8("new"), AtomicFileWriter.readWithBackup(target));
    }

    @Test
    void readFallsBackToBackupWhenPrimaryIsMissing() throws IOException {
        Path target = dir.resolve("player.json");
        AtomicFileWriter.write(target, utf8("old"));
        AtomicFileWriter.write(target, utf8("new"));

        Files.delete(target);

        assertArrayEquals(utf8("old"), AtomicFileWriter.readWithBackup(target),
                "主文件不见了必须回退到备份 —— 这正是备份存在的唯一理由");
    }

    @Test
    void readFallsBackToBackupWhenPrimaryCannotBeRead() throws IOException {
        Path target = dir.resolve("level.json");
        AtomicFileWriter.write(target, utf8("old"));
        AtomicFileWriter.write(target, utf8("new"));

        // 把主文件换成一个同名目录：Files.readAllBytes 会抛 IOException，
        // 从而走到文档里写明的"读取失败 → 尝试备份"这条分支。
        Files.delete(target);
        Files.createDirectory(target);

        assertArrayEquals(utf8("old"), AtomicFileWriter.readWithBackup(target),
                "主文件不可读（而非不存在）时同样必须回退");
    }

    @Test
    void readReturnsNullWhenNeitherFileExists() {
        assertNull(AtomicFileWriter.readWithBackup(dir.resolve("nothing.json")));
        assertFalse(AtomicFileWriter.backupExists(dir.resolve("nothing.json")));
    }

    // ============================================================ 临时文件清理

    @Test
    void cleanStaleTempRemovesOnlyTempFiles() throws IOException {
        Files.writeString(dir.resolve("c.0.0.bin.tmp"), "leftover");
        Files.writeString(dir.resolve("c.0.1.bin.tmp"), "leftover");
        Files.writeString(dir.resolve("c.0.0.bin"), "real");
        Files.writeString(dir.resolve("level.json"), "{}");

        AtomicFileWriter.cleanStaleTemp(dir);

        assertFalse(Files.exists(dir.resolve("c.0.0.bin.tmp")), "遗留 .tmp 必须被清掉");
        assertFalse(Files.exists(dir.resolve("c.0.1.bin.tmp")));
        assertTrue(Files.exists(dir.resolve("c.0.0.bin")), "真正的区块文件不得被误删");
        assertTrue(Files.exists(dir.resolve("level.json")));
    }

    @Test
    void cleanStaleTempOnMissingOrNonDirectoryPathIsANoOp() throws IOException {
        AtomicFileWriter.cleanStaleTemp(dir.resolve("not-created-yet"));

        Path file = dir.resolve("plain.txt");
        Files.writeString(file, "data");
        AtomicFileWriter.cleanStaleTemp(file);

        assertTrue(Files.exists(file), "传进来的是文件时不能抛异常，也不能删掉它");
    }

    @Test
    void successfulWriteNeverLeavesTempBehindEvenForLargePayload() throws IOException {
        Path target = dir.resolve("big.bin");
        byte[] payload = new byte[1 << 20];      // 1 MB，迫使 FileChannel 分多次 write
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }

        AtomicFileWriter.write(target, payload);

        assertArrayEquals(payload, Files.readAllBytes(target));
        assertFalse(Files.exists(dir.resolve("big.bin.tmp")));
    }
}
