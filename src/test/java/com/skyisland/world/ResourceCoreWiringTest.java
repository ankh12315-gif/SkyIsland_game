package com.skyisland.world;

import com.skyisland.util.Coords;
import com.skyisland.world.gen.IslandWorldGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源核心再生的<b>接线</b>守卫（PRD 4.6）。
 *
 * <h2>为什么单立一个类</h2>
 * {@link ResourceCoreRegenTest} 验的是<b>行为</b>（跑多久长几格、落在哪、覆不覆盖）。
 * 本类验的是<b>接线</b> —— 那条从系统属性到产品世界、到逻辑步的路径上，
 * 有没有哪一环没接上。
 * <p>两者必须分开，理由是本项目反复付过的那个学费：
 * <b>行为测试全绿而接线是断的</b>，症状是"功能存在但玩家永远看不到"。
 * M2.1 一次抓出 8 处「已定义、从未被调用」的特效方法 ——
 * 编译通过、815 单测全绿、三门禁全绿，而屏幕上一样都没有。
 *
 * <h2>判据一律读"去掉注释后的源码"</h2>
 * 因为注释里会解释这些行为，于是"全文件 contains"会被<b>说明文字</b>满足：
 * 把真正的调用删掉，断言照样全绿。去掉注释后再找，才问的是"它真的被调用了吗"。
 */
class ResourceCoreWiringTest {

    private static final Path GAME =
            Path.of("src", "main", "java", "com", "skyisland", "game", "SkyIslandGame.java");
    private static final Path REGEN =
            Path.of("src", "main", "java", "com", "skyisland", "world", "ResourceCoreRegen.java");

    /** 去掉注释后的源码（剥掉 // 与块注释）。 */
    private static String codeWithoutComments(Path file) throws IOException {
        String src = Files.readString(file, StandardCharsets.UTF_8);
        return src
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    @Test
    void theGameAssemblyActuallyCallsAttachResourceCores() throws IOException {
        String code = codeWithoutComments(GAME);

        // ★ 判据是"装配路径里真的有这个调用"，不是"文件里有这个名字"。
        //   attachResourceCores 由 attachStreaming 调用，而 attachStreaming
        //   在 start() 与"新建世界"两条路径上各出现一次 —— 少一条，
        //   玩家新建世界后核心就静默停止再生（症状：核心还在、就是不长矿）。
        assertTrue(code.contains("attachStreaming(spawnX(), spawnZ())"),
                "start() 里应通过 attachStreaming 走装配路径");
        assertTrue(code.contains("attachStreaming(TestWorldGenerator.spawnX(), TestWorldGenerator.spawnZ())"),
                "「新建世界」也必须重走装配路径 —— 否则新建世界后核心停止再生");

        assertTrue(code.contains("attachResourceCores()"),
                "attachStreaming 内必须调用 attachResourceCores —— "
                        + "★ 这是本条最关键的判据：核心若只被「注册」而从未被装配，"
                        + "行为测试会全绿（它自己 new 一个 regen 就能跑），"
                        + "而玩家永远看不到任何矿长出来");
    }

    @Test
    void theRegenIsTickedInsideStepLogicWithTheFixedDelta() throws IOException {
        String code = codeWithoutComments(GAME);

        assertTrue(code.contains("coreRegen.tick(fixedDt)"),
                "★ 必须用**固定步长的 fixedDt** 驱动 —— 用帧间隔的话，"
                        + "同一段游戏时长会因负载抖动产出不同的矿量，速率判据会随机地红");

        // 必须排在物理之前：新矿石必须在同一逻辑步的碰撞判定里已存在
        int tickAt = code.indexOf("coreRegen.tick(fixedDt)");
        int playerStepAt = code.indexOf("player.step(world, intent, fixedDt)");
        assertTrue(tickAt > 0 && playerStepAt > 0 && tickAt < playerStepAt,
                "coreRegen.tick 必须在 player.step 之前 —— 它会改方块，"
                        + "而物理要读脚下与周围的方块");
    }

    @Test
    void regenIsNotAssembledInTheSelfTestWorld() throws IOException {
        String code = codeWithoutComments(GAME);

        assertTrue(code.contains("useProductWorld()"),
                "装配判据必须复用 M1Config.useProductWorld() —— "
                        + "写 `selfTest == null` 这种散判会在新增自测时被漏掉"
                        + "（本项目已因此吃过亏：流式卸载的判据因此补过一条守卫）");

        assertTrue(code.contains("if (!config.useProductWorld())"),
                "★ 自测世界（TestWorldGenerator 平台上也有一个 resource_core）"
                        + "绝不能装配再生 —— 否则它会周期长出矿石，"
                        + "而 M1 自测断言的「挖掉之后仍然是空气」这类状态会被后台改写");
    }

    @Test
    void theNewWorldPathResetsTheRegenBeforeReassembling() throws IOException {
        String code = codeWithoutComments(GAME);

        // 「新建世界」把 world 整个换掉 ⇒ 旧的 regen 持有的是旧 World 引用
        assertTrue(code.contains("coreRegen = null;"),
                "★ 「新建世界」必须先 coreRegen = null 再重新装配 —— "
                        + "否则旧 regen 仍指向已丢弃的 World，"
                        + "症状是「核心还在、就是不再长矿」，而画面上一切正常");
    }

    @Test
    void theMeasurementSummaryExposesTheRegenCounters() throws IOException {
        String code = codeWithoutComments(GAME);

        // ★ "亮必须能解释自己为什么是亮"：装配了却恒为 0 次是本项目最常见的
        //   死接线形态，而测量摘要是唯一能一眼看出它的取证通道。
        // ★ 判据只认 key 名，**不认对齐空格**。
        //   上一版写死 `contains("\"core_count = \"")`，而源码是
        //   `append("core_count         = ")`（为了测量摘要里那一列对齐）——
        //   于是断言红在一个与功能毫无关系的地方：改对齐就红。
        //   那类断言的真正代价是它会训练人忽略红灯。⇒ 只查 key。
        for (String key : new String[]{
                "core_count", "regen_runs", "regen_ores",
                "regen_skip_occup", "regen_skip_nochunk", "regen_skip_nocand"}) {
            assertTrue(code.contains("\"" + key),
                    "测量摘要必须含 " + key + " —— 缺了它，「装配了但没在跑」"
                            + "就只能靠人肉盯着看有没有矿长出来");
        }
    }

    @Test
    void theGeneratorDoesNotKeepASecondCopyOfTheRegenConstants() throws IOException {
        String gen = codeWithoutComments(
                Path.of("src", "main", "java", "com", "skyisland", "world", "gen",
                        "IslandWorldGenerator.java"));

        // 唯一事实源是 ResourceCoreRegen。生成器里曾经有一份 CORE_REGEN_PERIOD_SECONDS，
        // 当时**没有任何消费者** —— 那正是这个项目明令禁止的死代码
        //（「定义了参数却没有消费者」）。
        assertFalse(gen.contains("CORE_REGEN_PERIOD_SECONDS"),
                "★ 生成器里不得再留 CORE_REGEN_PERIOD_SECONDS —— 速率是规格值"
                        + "（≤ 采矿速率的 1/50），两处各有一份就意味着"
                        + "速率判据只能验到其中一处");

        assertTrue(gen.contains("ResourceCoreRegen.periodSecondsOf"),
                "生成器可以保留一个**转发**的查询口（它需要知道各岛参数），"
                        + "但必须转发而不是另写一份数值");
    }

    @Test
    void theRegenModuleItselfIsNotDead() throws IOException {
        String code = codeWithoutComments(REGEN);
        String game = codeWithoutComments(GAME);

        // 反向确认：这个类不是"只被自己测到、没被产品用到"
        assertTrue(game.contains("new ResourceCoreRegen(world)"),
                "★ 产品路径必须真的 new 出它 —— 否则 ResourceCoreRegenTest "
                        + "会全绿（它自己 new 一个实例就能跑），而游戏里没有任何核心再生");
        assertFalse(code.contains("private ResourceCoreRegen()"),
                "构造器必须是包内可用的 public/package，不能被私有化成死类");
    }

    @Test
    void coresSitOnTheirIslandsAndRegenReachesThem() throws IOException {
        // 行为侧的一道自证：装配表里的核心位置，真的就是生成器放核心的位置
        World world = new World(20260919L, new IslandWorldGenerator());
        world.ensureAreaLoaded(-4, -4, 3, 3);

        int core = com.skyisland.world.block.BlockRegistry.resourceCore().runtimeId();
        int found = 0;
        for (IslandWorldGenerator.Island island : IslandWorldGenerator.ISLANDS) {
            if (island.kind() == IslandWorldGenerator.Kind.MAIN) {
                continue;
            }
            int y = Coords.WORLD_SURFACE_BLOCK_Y + 1;
            assertTrue(world.blockAt(island.centerX(), y, island.centerZ()).runtimeId() == core,
                    island.key() + " 的中心 (" + island.centerX() + "," + y + "," + island.centerZ()
                            + ") 应是资源核心，但实测是 "
                            + world.blockAt(island.centerX(), y, island.centerZ()).id()
                            + " —— 装配表与生成器位置不一致，再生会刷在虚空里");
            found++;
        }
        assertTrue(found == 4, "应有 4 座资源岛各 1 个核心，实测 " + found);
    }

    @Test
    void theReverseVerificationScriptsAreTracked() throws IOException {
        String gitignore = Files.readString(Path.of(".gitignore"), StandardCharsets.UTF_8);

        // ★ 与 NativeLauncherWiringTest 同一类守卫，理由也一样：
        //   `tmp/*` 把这些脚本整体忽略了。不在 .gitignore 里显式放行的话，
        //   换个克隆就**没有反向验证能力**，而症状是"全绿"——
        //   没有工具去注入断线，自然没人知道断言还能不能变红。
        for (String script : new String[]{"mvn_test_one.js", "rv_core_regen.js"}) {
            Path file = Path.of("tmp", script);
            assertTrue(Files.exists(file), "找不到 tmp/" + script);
            assertTrue(gitignore.contains("!tmp/" + script),
                    "tmp/" + script + " 必须在 .gitignore 的白名单里"
                            + "（tmp/* 忽略直接子项，不写 ! 就等于不在库里）");
        }

        // 反向验证脚本本身必须真的在做"注入 → 期望变红 → 逐字节还原"，
        // 而不是只跑一遍基线就报绿。判据是那三件事的字面存在。
        String rv = codeWithoutComments(Path.of("tmp", "rv_core_regen.js"));
        assertTrue(rv.contains("originalSha"), "反向验证必须比对还原后的 sha256");
        assertTrue(rv.contains("RV-INJECT"), "必须检查注入残渣清零");
        assertTrue(rv.contains("turnedRed"), "必须真的判红，不能只看退出码之外的东西");
        assertTrue(rv.contains("pattern not found"),
                "★ 注入片段找不到时必须**计入失败** —— 一个「注入没生效却仍报绿」"
                        + "的脚本比没有脚本更危险：它给出虚假的确信。"
                        + "本轮实测注入 A 就因锚点字符串不符被静默跳过");
    }
}