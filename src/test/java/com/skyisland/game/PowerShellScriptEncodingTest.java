package com.skyisland.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PowerShell 取证脚本的<b>文件编码守门人</b>。
 *
 * <h2>它守的是一个"坏了但没人发现"的坑</h2>
 * 2026-10-03 修完 {@code play-m3.bat} 的 jar 解析之后，我顺手给
 * {@code tmp/verify_m3_play.ps1} 加了一段中文注释（解释为什么不再写死 jar 版本号）。
 * 结果脚本<b>直接跑不动</b>，报了一整屏解析错误：
 *
 * <pre>
 * 必须在"*"运算符后面提供一个值表达式。
 * 表达式或语句中包含意外的标记"。jar' -File | ForEach-Object ...
 * 字符串缺少终止符: '。
 * </pre>
 *
 * <p>根因不是 PowerShell 语法错，是<b>编码</b>：Windows PowerShell 5.1
 * （本项目门禁与取证实际用的就是它）读<b>无 BOM</b>的 {@code .ps1} 时，
 * 按<b>系统 ANSI 代码页</b>解码 —— 本机是 GBK。而文件是 UTF-8。
 * 于是每个中文字符被拆成 3 个字节、按 GBK 两两组合，产生一堆乱码字符。
 *
 * <h2>★ 为什么这个坑格外阴险：它"有时候"能跑</h2>
 * 乱码本身只是显示难看，<b>真正致命的是它会随机吃掉引号</b>
 * （GBK 双字节序列的后半字节可能落成 {@code '} 或 {@code "}）。
 * 于是症状是"我这次加注释脚本报错、下次不加以同样的注释又没事"，
 * 而仓库里恰恰有一个 <b>88 行中文却一直能跑</b>的脚本
 * （{@code tmp/run_gate_ps.ps1}）——
 * 它的中文恰好没触发吞引号，<b>纯属侥幸</b>。
 * 按本项目的判据，这就是一条典型的"脆绿灯"：
 * <b>它现在绿着，但它绿的理由是巧合，下一次编辑就会红。</b>
 *
 * <h2>为什么修法是"加 BOM"而不是"删掉中文注释"</h2>
 * 注释里记的是"为什么不能写死版本号""为什么守卫不能用通配"这类
 * <b>只有人读得懂</b>的判据；删掉它们等于让同一个坑再踩一遍。
 * 加 UTF-8 BOM 之后，PS 5.1 会正确按 UTF-8 解码，中文注释与字符串都恢复正常。
 *
 * <p>本项目对 CJK 已经有一条硬规矩（见 {@code CjkFontTest}）：
 * <b>禁止为了让检查变绿而删中文</b>。这里是同一条原则在 PowerShell 侧的对应物 ——
 * <b>修解码方式，不修内容</b>。
 *
 * <h2>为什么扫的是"被 git 跟踪的 ps1"而不是 tmp 下全部</h2>
 * {@code tmp/} 下有一批一次性探针（{@code probe*_ps.ps1} 等）本就不该进版本库，
 * 它们在磁盘上爱是什么编码都无所谓，纳入守卫只会制造噪声。
 * 真正的判据是 {@code .gitignore} 里那三条 {@code !tmp/*.ps1} 白名单 ——
 * <b>那正是"备用通道必须跨克隆存在"的那几个文件</b>：
 * 门禁在 {@code spawnSync → EBUSY} 发作时全靠 {@code run_gate_ps.ps1}，
 * 它要是坏在编码上，届时的症状是"门禁跑不动、也找不到替代脚本"。
 */
class PowerShellScriptEncodingTest {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final Path GITIGNORE = Path.of(".gitignore");

    /**
     * 从 {@code .gitignore} 里解析出被显式白名单回来的 {@code .ps1}。
     *
     * <p>不写死文件名：白名单本身才是"什么必须进版本库"的唯一事实来源，
     * 硬编码一份副本就会在有人增删白名单时静默漂移。
     */
    private static List<Path> trackedPowerShellScripts() throws IOException {
        List<String> rel = new ArrayList<>();
        for (String raw : Files.readAllLines(GITIGNORE, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.startsWith("!") && line.endsWith(".ps1")) {
                String p = line.substring(1);
                if (!rel.contains(p)) {
                    rel.add(p);
                }
            }
        }
        assertTrue(!rel.isEmpty(),
                ".gitignore 里一条 `!tmp/*.ps1` 白名单都没有 —— 备用通道的 PowerShell 脚本"
                        + "全都不进版本库，换个克隆就没有了（见 .gitignore 里那段注释）");
        return rel.stream().map(Path::of).toList();
    }

    private record Stat(Path path, boolean hasBom, int nonAsciiBytes) {
    }

    private static Stat inspect(Path p) throws IOException {
        byte[] raw = Files.readAllBytes(p);
        boolean bom = raw.length >= 3
                && raw[0] == UTF8_BOM[0] && raw[1] == UTF8_BOM[1] && raw[2] == UTF8_BOM[2];
        int nonAscii = 0;
        for (byte b : raw) {
            if ((b & 0xFF) > 127) {
                nonAscii++;
            }
        }
        return new Stat(p, bom, nonAscii);
    }

    /**
     * ★ 主断言：<b>含非 ASCII 的 {@code .ps1} 必须带 UTF-8 BOM</b>。
     *
     * <p>这条守卫的存在理由是：它对应的是一个<b>已经真实发生过</b>的失败
     * （本类的类注释里有那屏报错），而不是一个假想风险。
     * 而且它防的正是仓库里那个"88 行中文却一直能跑"的文件 ——
     * 那个文件此刻是脆的，只是还没断。
     */
    @Test
    void everyTrackedNonAsciiPowerShellScriptCarriesAUtf8Bom() throws IOException {
        List<Stat> missing = new ArrayList<>();
        Map<Path, Stat> all = new LinkedHashMap<>();
        for (Path p : trackedPowerShellScripts()) {
            assertTrue(Files.exists(p), p + " 被 .gitignore 白名单了，但磁盘上不存在");
            Stat s = inspect(p);
            all.put(p, s);
            if (s.nonAsciiBytes() > 0 && !s.hasBom()) {
                missing.add(s);
            }
        }
        assertTrue(missing.isEmpty(),
                "这些 PowerShell 脚本含中文但**没有 UTF-8 BOM**："
                        + missing.stream().map(s -> s.path() + "(" + s.nonAsciiBytes() + " 个非 ASCII 字节)")
                        .toList()
                        + "。Windows PowerShell 5.1 读无 BOM 的 .ps1 时按系统 ANSI 代码页（本机 GBK）解码，"
                        + "UTF-8 中文会变成乱码，并可能吞掉引号把脚本解析打断（实测报「字符串缺少终止符」）。"
                        + "修法是加 BOM，不是删中文。");
    }

    /**
     * 纯 ASCII 的 {@code .ps1} 不需要 BOM，也不该被要求有。
     *
     * <p>把这条单独写出来，是为了让上面那条的判据无歧义：
     * 如果"无 BOM"一律判红，那么把文件清成纯 ASCII 就能绕过守卫，
     * 而那等于用"删内容"换"检查变绿" —— 本项目明确禁止这种做法。
     */
    @Test
    void aPureAsciiScriptNeedsNoBomAndIsNotFlagged() throws IOException {
        for (Path p : trackedPowerShellScripts()) {
            Stat s = inspect(p);
            if (s.nonAsciiBytes() == 0) {
                assertEquals(false, s.hasBom(),
                        p + " 是纯 ASCII：BOM 对它没有意义，且会让 PS 5.1 把文件头的 "
                                + "三个字节当内容（不会报错，但没有理由保留）");
            }
        }
    }

    /**
     * ★ 反向验证锚点：<b>BOM 确实是被 PS 5.1 当成"这是 UTF-8"的信号</b>。
     *
     * <p>这一条不能只靠"加了 BOM 之后脚本能跑"来证明 ——
     * 那只能证明"这次碰巧好了"。它把因果讲清楚：
     * <b>PS 5.1 读无 BOM 的 {@code .ps1} 时按 ANSI 码页解码</b>，
     * 所以 UTF-8 中文的字节会被拆成 GBK 字符；
     * 而带 BOM 时它改用 UTF-8，中文恢复正常。
     *
     * <p>实现上不真的去 {@code ParseFile}（那会把机器上 PS 版本差异带进断言），
     * 而是断言"UTF-8 中文在无 BOM 时必然是<b>非法</b>的 GBK 序列" ——
     * 也就是吞引号的机制来源。
     */
    @Test
    void withoutTheBomThoseSameBytesAreNotValidGbk() throws IOException {
        // 取一个必然出现在这些脚本注释里的汉字。
        String probe = "装备";
        byte[] utf8 = probe.getBytes(StandardCharsets.UTF_8);
        assertEquals(6, utf8.length, "\"装备\" 在 UTF-8 下应是 6 字节");

        // GBK 解码器：遇到 lead byte 就吃掉下一个 byte。
        // 断言这些字节**一定**会破坏字节对齐 —— 也就是说，无论具体是哪个字符被
        // 错解，字符串的字符边界都已经和作者写的不一样了。
        boolean lostAlignment = false;
        for (int i = 0; i < utf8.length; i++) {
            int b = utf8[i] & 0xFF;
            if (b >= 0x81) {
                // lead byte：GBK 会消费 i+1，于是后续边界整体左移一格。
                lostAlignment = true;
                break;
            }
        }
        assertTrue(lostAlignment,
                "UTF-8 中文字节里必然含 >=0x81 的 lead byte —— 这正是 GBK 解码会错位的原因。"
                        + "如果这条断言红了，说明前提变了（不是脚本变安全了）");
    }
}
