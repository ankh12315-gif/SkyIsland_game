package com.skyisland.save;

import com.skyisland.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 原子写入（TECH_DESIGN §N.5）。
 *
 * <p><b>为什么必须 {@code force(true)}：</b>{@code rename} 只是<b>元数据</b>操作。
 * 若文件内容仍在页缓存里没有落盘，一次断电会让"重命名后的新文件"内容为空或半截 ——
 * 少了这一步的"原子写入"是<b>假原子</b>，而且它在正常关机时完全看不出来。
 *
 * <p><b>为什么还要留 {@code .bak}：</b>{@code ATOMIC_MOVE} 只保证
 * "要么是旧文件、要么是新文件"，<u>不</u>保证新文件可解析（可能内容本身写错了）。
 * {@code .bak} 是第二道防线：读取失败时回退到上一版。
 *
 * <p><b>写入顺序：</b>先写 {@code .tmp} 并 flush → 把现有目标改名成 {@code .bak}
 * → 把 {@code .tmp} 原子改名为目标。任何一步失败，目标文件要么是旧的、要么是新的，
 * 不会出现"半截文件"。
 */
public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    public static void write(Path target, byte[] data) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Path bak = target.resolveSibling(target.getFileName() + ".bak");

        try (FileChannel channel = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);      // ★ 必须：把内容真正刷到存储设备
        }

        if (Files.exists(target)) {
            Files.move(target, bak, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // 某些文件系统（网络盘、部分 FUSE）不支持原子改名。此时退化为普通改名 ——
            // 安全性略降，但比"整个存档写不进去"好。必须记录，不能静默降级。
            Log.noteWarning("Save", "原子改名不受支持，退化为普通改名（" + target.getFileName()
                    + "）：" + atomicUnsupported.getMessage());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 读取文件；失败或不存在时尝试同目录的 {@code .bak}。
     *
     * @return 文件内容；主文件与备份都不可用时返回 {@code null}
     */
    public static byte[] readWithBackup(Path target) {
        Path bak = target.resolveSibling(target.getFileName() + ".bak");
        if (Files.exists(target)) {
            try {
                return Files.readAllBytes(target);
            } catch (IOException e) {
                Log.noteWarning("Save", "读取失败，尝试备份：" + target.getFileName()
                        + " —— " + e.getMessage());
            }
        }
        if (Files.exists(bak)) {
            try {
                byte[] data = Files.readAllBytes(bak);
                Log.noteWarning("Save", "主文件不可用，已从备份读取：" + bak.getFileName());
                return data;
            } catch (IOException e) {
                Log.error("读取备份也失败: " + bak.getFileName(), e);
            }
        }
        return null;
    }

    /** 备份文件是否存在（供自测断言"确实产生过备份"）。 */
    public static boolean backupExists(Path target) {
        return Files.exists(target.resolveSibling(target.getFileName() + ".bak"));
    }

    /** 清理遗留的 {@code .tmp}（上次写入被中断时留下）。 */
    public static void cleanStaleTemp(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var stream = Files.list(directory)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                    Log.noteWarning("Save", "清理上次未完成的临时文件: " + p.getFileName());
                } catch (IOException e) {
                    Log.noteWarning("Save", "临时文件清理失败: " + p.getFileName());
                }
            });
        } catch (IOException e) {
            Log.noteWarning("Save", "扫描临时文件失败: " + e.getMessage());
        }
    }
}
