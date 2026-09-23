package com.skyisland.ui;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文案 key 的<b>消费方</b>守门人 —— 抓的是"登记了一句话，但没有任何代码画它"。
 *
 * <h2>为什么这条测试必须存在</h2>
 * M2.2 收尾时实测抓到一个：{@code INV_CURSOR_HINT}（"左键取放，Shift+左键快速移动，E/Esc 关闭"）
 * 在 {@link Localization} 里登记得好好的，但<b>没有任何绘制点读它</b> ——
 * 面板下方画的是另一条只说"怎么关"的 {@code HINT_INVENTORY}。
 * 结果是：背包能用，但玩家从界面上学不到怎么用，只能靠猜。
 *
 * <p>这与本项目已经付过学费的三种形态同构：
 * <ul>
 *   <li>M2：「枪在存档里静默消失」—— 字段齐全、序列化往返正常，但按错了表；</li>
 *   <li>M2：「右键放不下方块」—— 代码路径齐全，但 95% 的点击被丢在错误的载体上；</li>
 *   <li>M2.2：「继续游戏 / 新建世界」—— 菜单行存在，但 {@code activateEntry} 没有分支。</li>
 * </ul>
 * 共同点：<b>没有任何东西变红</b>。{@code LocalizationTest} 只断言"每个 key 都登记了文案"
 * （它管方向相反的那一半：有词可用），而"这句话有没有人画"此前无人负责。
 *
 * <h2>它为什么必须带正向对照</h2>
 * 这条测试的做法是"扫源码找常量名"。一个坏掉的扫描器会报告<b>所有</b> key 都没有消费方 ——
 * 那看起来像一次"抓到了 64 个死 key"的大丰收，实际是仪器坏了。
 * （收尾时我真的写出过这样一个：正则里的 {@code \\b} 被双层转义成了退格字符，
 * 于是 64 个 key 全部"无消费方"。）因此这里先用三条已知有消费方的 key 做正向对照：
 * <b>扫描器必须先证明自己认得出"有人用"</b>，它的"没人用"才可信。
 *
 * <h2>已知欠债</h2>
 * {@link #KNOWN_DEAD_KEYS} 里是 M2.2 之前就存在、且本里程碑不在范围内修的死 key。
 * 把它们列出来而不是让测试变红，是为了让"欠债"这件事保持<b>有登记</b>：
 * 新的死 key 会被立刻抓住，旧的死 key 不会被偷偷忘掉。
 */
class DeadLocalizationKeyTest {

    /** 消费方所在的源码树。 */
    private static final Path MAIN_SOURCES = Paths.get("src/main/java");

    /**
     * 已知的、M2.2 之前就存在的死 key（有登记，不在本里程碑范围内修）。
     *
     * <p>逐条说明为什么留着：
     * <ul>
     *   <li>{@code HUD_HEALTH} —— 生命条的文本标签。HUD 走的是图形化心形，不用文字；</li>
     *   <li>{@code HUD_DEAD} —— 死亡提示。死亡遮罩用的是 {@code DEATH_TITLE}；</li>
     *   <li>{@code MSG_SAVE_OK / MSG_SAVE_FAILED} —— 保存结果提示。存档路径目前只打日志；</li>
     *   <li>{@code DEATH_NO_DROP} —— 死亡不掉落的开关提示，M3 才有掉落物实体；</li>
     *   <li>{@code DBG_MODE} —— F3 调试浮层的标签。调试浮层是<b>有意保持 ASCII</b> 的
     *       （见 HudRenderer 的类注释），所以这个中文 key 与设计自相矛盾。</li>
     * </ul>
     */
    private static final Set<String> KNOWN_DEAD_KEYS = new LinkedHashSet<>(List.of(
            "HUD_HEALTH", "HUD_DEAD", "MSG_SAVE_OK", "MSG_SAVE_FAILED",
            "DEATH_NO_DROP", "DBG_MODE"));

    /** 正向对照：这三条一定有消费方。扫描器认不出它们，就说明扫描器坏了。 */
    private static final List<String> POSITIVE_CONTROLS =
            List.of("INV_TITLE", "MENU_MAIN_CONTINUE", "HINT_MAIN");

    /**
     * 取出 Localization 里所有"看起来是文案 key"的 public static String 常量。
     *
     * <p><b>为什么要按值筛形状：</b>{@code LANG} 也是 public static String，
     * 但它是语言标识（{@code "zh-CN"}），不是查表的 key —— 它本来就不该有文案。
     * 用"dotted name"这个形状把两者分开，比往名单里塞一个例外更干净，
     * 也让"文案 key 长什么样"这件事有了唯一的、可断言的表述。
     */
    private static List<String> declaredKeys() {
        List<String> names = new ArrayList<>();
        for (Field f : Localization.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (f.getType() != String.class || !Modifier.isStatic(m) || !Modifier.isPublic(m)) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object v = f.get(null);
                if (v instanceof String s && s.matches("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$")) {
                    names.add(f.getName());
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("读取字段 " + f.getName() + " 失败", e);
            }
        }
        return names;
    }

    /** src/main/java 下除 Localization 自身以外的全部源码文本（已去掉 import 行）。 */
    private static String consumerBlob() throws Exception {
        StringBuilder sb = new StringBuilder();
        try (var paths = Files.walk(MAIN_SOURCES)) {
            for (Path p : paths.filter(x -> x.toString().endsWith(".java")).toList()) {
                if (p.getFileName().toString().equals("Localization.java")) {
                    continue;
                }
                String text = Files.readString(p, StandardCharsets.UTF_8);
                // 去掉 import：只 import 了类名不算"有人用这个常量"
                for (String line : text.split("\\R")) {
                    if (!line.stripLeading().startsWith("import ")) {
                        sb.append(line).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    private static boolean isConsumed(String key, String blob) {
        // 词边界匹配：避免 HUD_HEALTH 命中 HUD_HEALTH_BAR 之类的不同常量
        return blob.matches("(?s).*\\b" + key + "\\b.*");
    }

    @Test
    void theScannerRecognisesKeysThatAreDefinitelyUsed() throws Exception {
        String blob = consumerBlob();
        for (String control : POSITIVE_CONTROLS) {
            assertTrue(isConsumed(control, blob),
                    "正向对照失败：" + control + " 明明有消费方，扫描器却没认出来 —— "
                            + "仪器坏了，本文件的其它结论都不可信（先修扫描器，不是改判据）");
        }
    }

    @Test
    void noNewlyDeclaredKeyIsLeftWithoutAConsumer() throws Exception {
        String blob = consumerBlob();

        List<String> dead = new ArrayList<>();
        for (String key : declaredKeys()) {
            if (!isConsumed(key, blob)) {
                dead.add(key);
            }
        }

        List<String> unregistered = new ArrayList<>();
        for (String key : dead) {
            if (!KNOWN_DEAD_KEYS.contains(key)) {
                unregistered.add(key);
            }
        }

        assertTrue(unregistered.isEmpty(),
                "以下文案 key 登记了却没有任何代码画它（" + unregistered + "）。"
                        + "要么补上绘制点，要么删掉这个 key —— 但不要只是把它加进 KNOWN_DEAD_KEYS："
                        + "那份名单是给 M2.2 之前的历史欠债用的，不是垃圾桶。"
                        + "（M2.2 实测：INV_CURSOR_HINT 就这样躺了一轮，"
                        + "而面板下面画的是另一条只说怎么关的提示。）");
    }

    /**
     * 已知欠债名单不得"顺手扩张"。
     *
     * <p>若名单里出现了源码中<b>根本不存在</b>的 key，说明有人在名单里留了残留
     * （删了常量却忘了删名单），那会让"欠债还剩几条"这个数字失真。
     */
    @Test
    void theKnownDebtListDoesNotRot() throws Exception {
        Set<String> declared = new LinkedHashSet<>(declaredKeys());
        for (String k : KNOWN_DEAD_KEYS) {
            assertTrue(declared.contains(k),
                    "已知欠债名单里的 " + k + " 在 Localization 里已经不存在了 —— "
                            + "清掉这条，否则欠债条数会虚高");
        }
    }

    /**
     * 欠债名单必须保持"每条都真的有登记文案"。
     *
     * <p>{@code LocalizationTest} 已经断言了"每个 key 都登记了文案"，
     * 这条只是把范围收窄到欠债名单上 —— 它保证名单里的每一条都还处在
     * "准备好了但没人用"的状态，而不是"半删干净"的状态。
     */
    @Test
    void everyKnownDebtKeyStillHasTextRegistered() {
        for (String k : KNOWN_DEAD_KEYS) {
            assertTrue(Localization.hasText(keyValue(k)),
                    "已知欠债 key " + k + " 没有登记文案 —— "
                            + "它应该要么被完整删除，要么留着完整文案，不要停在中间状态");
        }
    }

    private static String keyValue(String fieldName) {
        try {
            Field f = Localization.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return (String) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读取 " + fieldName + " 失败", e);
        }
    }

    /**
     * 反向自证：本文件的核心判据必须能真的变红。
     *
     * <p>做法是在一个<b>临时改写的副本</b>上跑同一套判据 —— 而不是往真实源码里注入。
     * 真实的注入式反向验证在收尾阶段做过（见 M2.2 报告第 10 节），
     * 但那会往仓库里写文件、有漏恢复的风险；这里用内存副本达到同样的证明力：
     * 把 {@code INV_TITLE} 从扫描结果里划掉，{@link #noNewlyDeclaredKeyIsLeftWithoutAConsumer()}
     * 用的那段逻辑必须把它报成新的死 key。
     */
    @Test
    void theCoreAssertionActuallyFiresWhenAKeyLosesItsConsumer() throws Exception {
        String blob = consumerBlob();

        // 造一个"INV_TITLE 没有人用"的世界：把它的出现全部抹掉
        String withoutInvTitle = blob.replaceAll("\\bINV_TITLE\\b", "INV_TITLE_REMOVED_FOR_TEST");

        boolean stillConsumed = isConsumed("INV_TITLE", withoutInvTitle);
        assertFalse(stillConsumed,
                "抹掉 INV_TITLE 的全部引用之后，判据仍然认为它有消费方 —— "
                        + "那这条守门人就是恒真的，等于没写");
        // 而对照组在原件上必须仍然为真
        assertTrue(isConsumed("INV_TITLE", blob), "对照组：原件里 INV_TITLE 必须有消费方");
    }
}
