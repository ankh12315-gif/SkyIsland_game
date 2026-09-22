package com.skyisland.game;

import com.skyisland.physics.RaycastHit;
import com.skyisland.player.Inventory;
import com.skyisland.player.Player;
import com.skyisland.player.PlayerIntent;
import com.skyisland.save.SaveFormat;
import com.skyisland.save.SaveManager;
import com.skyisland.save.SaveResult;
import com.skyisland.util.Coords;
import com.skyisland.util.Log;
import com.skyisland.world.World;
import com.skyisland.world.block.BlockRegistry;
import com.skyisland.world.gen.TestWorldGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * M1 进程内脚本化自测（TECH_DESIGN_v0.1.1 §T′.1）。
 *
 * <p><b>为什么必须是"进程内脚本化意图"而不是"模拟按键"：</b>
 * 本机实测合成键盘输入无法送达<u>任何</u>窗口（TR7：{@code SendInput} 走系统输入队列，
 * 已确认前台且持有键盘焦点的窗口仍然收不到 KeyDown；对照实验用自建 WinForms 窗口验证过）。
 * 因此"交互功能是否存在"只能这样举证：
 * <blockquote>
 *   被测对象是<u>同一条游戏逻辑链路</u>；被绕开的只有 {@code OS → GLFW} 这一段。
 * </blockquote>
 * 具体地，本类产出的 {@link PlayerIntent} 与 {@code InputMapper} 产出的完全是同一种对象，
 * 后续经过 {@code 玩家物理 → 射线 → World Mutation API → 网格重建 → 存档} 一步不少。
 *
 * <p><b>脚本是"设置初始条件 + 施加意图 + 断言结果"</b>：涉及 {@code teleport} 与
 * {@code setAngles} 的地方只用于把玩家摆到可复现的起手位置（否则断言会依赖
 * 上一阶段走了多远这种脆弱前提），它们<u>不跳过</u>任何被测逻辑。
 * 每一处都标注了原因。
 *
 * <p><b>为什么不用"超时即失败"以外的宽松判定：</b>M1 的门禁要求"闭环真的跑通"，
 * 而"挖了 1 个方块"与"挖了 100 个方块"是不同的结论。因此每阶段的断言都写明可观测的量
 * （位移、跳跃高度、背包增量、方块 ID、区块坐标、死亡计数、文件是否存在），
 * 失败时输出实测值而不是只说"失败"。
 */
public final class M1ScriptedSelfTest {

    /** 宿主：自测需要访问游戏的实际对象，但不该自己造一套。 */
    public interface Host {
        World world();

        Player player();

        SaveManager saveManager();

        /** 触发一次真实存档（走游戏用的同一条路径）。 */
        SaveResult requestSave();

        /** 请求在下一帧渲染阶段截图（GL 调用必须在渲染线程、两缓冲交换之前）。 */
        void requestScreenshot(String label);

        /**
         * 存档功能是否开启（{@code skyisland.noSave} 的反面）。
         *
         * <p><b>为什么自测需要知道这件事：</b>M1 的存档是<b>同步</b>写盘，一次约 200 ms。
         * 这让"功能闭环验证"与"性能测量"无法在同一次运行里同时成立（见 M1 报告）。
         * 因此需要一次 {@code noSave=true} 的对照运行：动作序列完全相同、唯独不做存档，
         * 用来把"存档造成的停顿"从"引擎稳态帧时间"里隔离出来。
         * 那种运行里存档断言必然失败，且失败原因与引擎无关 —— 所以显式跳过，
         * 而不是留下一条假失败污染结论。
         */
        boolean saveEnabled();
    }

    private enum Stage {
        SETTLE("静置并站稳"),
        WALK_FORWARD("第一人称前进"),
        JUMP("跳跃（1 格可上、2 格不可）"),
        LOOK("鼠标视角（向下）"),
        AIM_DOWN_AND_MINE("垂直下挖（真实挖掘管线）"),
        AIM_AND_PLACE("瞄准并放置（真实放置管线）"),
        HOTBAR_SLOT_2("数字键切槽到第 2 格"),
        HOTBAR_SLOT_1("数字键切回第 1 格"),
        CROSS_CHUNK("跨区块边界行走"),
        FALL_INTO_VOID("坠入虚空并重生"),
        SAVE("存档"),
        DONE("结束");

        final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    /** 各阶段的逻辑步预算。60 步 = 1 秒（逻辑步恒为 1/60 s）。 */
    private static final int[] STAGE_BUDGET = {
            30,   // SETTLE 0.5 s
            90,   // WALK_FORWARD 1.5 s（加速约 0.2 s，之后 4.317 格/秒）
            45,   // JUMP 0.75 s（★ 见下方注释）
            3,    // LOOK
            90,   // AIM_DOWN_AND_MINE（草硬度 0.6 s，余量充足）
            3,    // AIM_AND_PLACE
            3,    // HOTBAR_SLOT_2
            3,    // HOTBAR_SLOT_1
            150,  // CROSS_CHUNK 2.5 s
            420,  // FALL_INTO_VOID（自由落体 ≈ 2.27 s + 死亡倒计时 3 s，★ 见下方注释）
            3,    // SAVE
            1     // DONE
    };

    /*
     * 为什么 FALL_INTO_VOID 的预算从 320 步改成 420 步（M2 语义变更，不是"调绿"）：
     *
     * M1 时虚空是"踩空即重生"，deaths 在那一帧就 +1，所以预算只要覆盖自由落体。
     * M2 起按 PRD 5.3 改成"直接致死 → 倒下 3 秒 → 重生"，而本阶段的断言
     * （checkVoid 的 "坠入虚空触发了重生"）读的正是 deaths 增量 ——
     * 它现在只会在倒计时走完之后才 +1。也就是说阶段必须<b>撑过整个重生流程</b>
     * 才可能观测到自己要观测的东西。
     *
     * 320 步在此语义下只剩 3 步余量（自由落体 ~137 + 倒计时 180 = ~317），
     * 任何一个新参数（重力、终端速度、死亡延迟）的微调都会让它红。
     * 重算（起始 y = 72，g = 32 格/秒²，终端速度 60 格/秒）：
     *   加速段 t1 = 60/32 = 1.875 s，位移 v²/(2g) = 56.25 格；
     *   匀速段余 80 − 56.25 = 23.75 格 → t2 = 0.396 s；
     *   合计 ≈ 2.271 s ≈ 137 步；再加死亡倒计时 3.0 s = 180 步 → 约 317 步。
     * 取 420 步 = 7 秒，留 100 步余量，仍然远小于"卡住不动"的判定尺度。
     */

    /*
     * 为什么 JUMP 预算是 45 步而不是 30 步（M1 实测修正）：
     *
     * 跳跃滞空时间是 2 × JUMP_VELOCITY / g = 2 × 8.95 / 32 = 0.559 s，即 33.6 个逻辑步。
     * 首版预算写 30 步（0.5 s），断言"落地后重新站在地面"必然失败 —— 那个时刻玩家
     * 还在空中，onGround=false。实测就是这么暴露的：跳跃高度断言全过（已越过顶点），
     * 只有落地断言失败。
     *
     * 45 步 = 0.75 s，比滞空多出 11 步余量。余量必须留，因为起跳发生在阶段内第 1 步、
     * 落地判定又要等脚下出现实体方块，这两端各有一步量级的不确定性。
     *
     * 教训：凡断言"某个过程已经结束"，预算必须由该过程的时间常数推导，而不是取一个
     * "看起来够用"的整数。这条与 Frustum 那次一样，是"测试前提不成立"而非产品缺陷。
     */

    private final Host host;
    private final List<String> results = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    /**
     * 本次运行是否跳过了存档相关断言（即 {@code skyisland.noSave=true} 的性能对照运行）。
     *
     * <p>它决定 {@link #scope()}，进而决定这次"通过"能不能被当作 M1 功能闭环的证据。
     * 跳过必须被显式记录 —— 一个静默跳过的断言集与一个真正跑通的断言集
     * 在观测上不能长得一样（同 {@code TECH_DESIGN_v0.1.1 §A′.3 E-7} 的立场）。
     */
    private boolean saveAssertionsSkipped;

    private int stageIndex;
    private int stageStep;
    private boolean finished;

    // ---- 阶段内的观测量 ----
    private double stageStartX;
    private double stageStartY;
    private double stageStartZ;
    private double stageStartWalkDistance;
    private double stageMaxY;
    private int stageStartDeaths;
    private long stageStartBreaks;
    private long stageStartPlaces;
    private int stageStartInventory;
    private double stageStartPitch;

    // ---- 跨阶段记录（供读档校验引用） ----
    private int dugCellX;
    private int dugCellY;
    private int dugCellZ;
    private boolean dugCellRecorded;
    private int placedCellX;
    private int placedCellY;
    private int placedCellZ;
    private int placedRuntimeId = -1;
    private boolean placedCellRecorded;
    private int chunkBeforeCross;
    private int chunkAfterCross;
    private double savedPlayerX;
    private double savedPlayerY;
    private double savedPlayerZ;

    public M1ScriptedSelfTest(Host host) {
        this.host = host;
        Log.info("[自测] M1 脚本化自测已装载：%d 个阶段（进程内意图注入，不依赖 OS 输入）",
                Stage.values().length);
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean allPassed() {
        return failures.isEmpty() && finished;
    }

    /** {@code true} 表示本次运行覆盖了全部断言（含存档），可作为 M1 功能闭环的证据。 */
    public boolean isFullScope() {
        return !saveAssertionsSkipped;
    }

    /** 本次运行的断言范围，用于摘要与门禁判定。 */
    public String scope() {
        return saveAssertionsSkipped ? "no_save_control" : "full";
    }

    public List<String> failures() {
        return List.copyOf(failures);
    }

    public List<String> results() {
        return List.copyOf(results);
    }

    // ============================================================ 主入口（每个逻辑步调用一次）

    public PlayerIntent nextIntent(double dt) {
        if (finished) {
            return PlayerIntent.NONE;
        }
        if (stageStep == 0) {
            onStageBegin();
        }
        PlayerIntent intent = intentFor(Stage.values()[stageIndex]);
        stageStep++;
        if (stageStep >= STAGE_BUDGET[stageIndex]) {
            onStageEnd();
        }
        return intent;
    }

    /** 观测最大高度：跳跃高度只靠"阶段结束时的 y"是无法验证的（那时已经落地）。 */
    public void observeAfterStep(Player player) {
        stageMaxY = Math.max(stageMaxY, player.position().y);

        // ---- "挖掘进行中"的画面证据（M1.5 收尾阶段新增，不改变任何断言）----
        // 为什么需要：挖掘的进度反馈是一条 140×5 px 的 HUD 条（1.5 秒的硬度里慢慢填满），
        // 只在阶段<u>结束时</u>截图拍到的永远是"已经挖完了"的空画面，
        // 于是"到底有没有进度反馈"这件事在证据里完全不可见。
        // 人工试玩反馈"长按挖掘没有反馈"时，正因如此无法立刻分辨是"没画"还是"太小看不见"。
        if (!miningShotTaken && Stage.values()[stageIndex] == Stage.AIM_DOWN_AND_MINE) {
            double p = player.miningProgressFraction();
            if (p > 0.05 && p < 1.0) {
                miningShotTaken = true;
                host.requestScreenshot("aim_down_and_mine-mining");
            }
        }
    }

    /** 挖掘中的截图是否已经请求过（每轮自测只拍一张）。 */
    private boolean miningShotTaken;

    private void onStageBegin() {
        Player player = host.player();
        stageStartX = player.position().x;
        stageStartY = player.position().y;
        stageStartZ = player.position().z;
        stageStartWalkDistance = player.walkDistance();
        stageMaxY = player.position().y;
        stageStartDeaths = player.deaths();
        stageStartBreaks = player.blocksBroken();
        stageStartPlaces = player.blocksPlaced();
        stageStartInventory = player.inventory().totalItemCount();
        stageStartPitch = player.camera().pitchDeg();
        Stage stage = Stage.values()[stageIndex];
        Log.info("[自测] ▶ 阶段 %d/%d %s（预算 %d 步）",
                stageIndex + 1, Stage.values().length, stage.label, STAGE_BUDGET[stageIndex]);
    }

    /**
     * 快捷栏里第一个"不是枪"的槽位。
     *
     * <p>为什么需要它：M2 起手枪是开局装备并占住第 1 格，而手持枪械时左键是开火、
     * 不是挖掘。脚本的挖掘阶段必须先声明自己手里是什么。写成"扫描内容"而不是
     * 硬编码槽号，是因为开局装备的格子布局与数量都可能变 —— 硬编码的失效方式
     * 是"挖掘阶段失败"，而真正的原因在别处。
     */
    // M2.2：背包扩成 27+9，"快捷栏"是绝对索引 27..35 的 9 格。这两个辅助函数扫描快捷栏、
    // 返回<b>快捷栏相对槽位（0..8）</b> —— 因为它们的结果直接喂给 selectSlot（只认 0..8 相对索引）。
    // 在 M1 时整包就是快捷栏、绝对==相对，所以扫 size() 没问题；现在必须显式扫快捷栏区，
    // 否则会扫到主背包并把绝对索引当相对索引传给 selectSlot（等于没切槽，挖掘/放置断言会假绿或真红）。
    private int firstNonGunSlot(Player player) {
        for (int h = 0; h < Inventory.HOTBAR_SIZE; h++) {
            if (!player.inventory().hotbarSlot(h).item().isGun()) {
                return h;
            }
        }
        return 0;   // 9 格全是枪：不可能，但返回 0 比返回 -1 更不容易把调用方带进坑
    }

    /** 快捷栏里第一个"方块物品"的槽位（相对索引）；没有则返回 −1（放置阶段用，见那边的说明）。 */
    private int firstBlockSlot(Player player) {
        for (int h = 0; h < Inventory.HOTBAR_SIZE; h++) {
            if (player.inventory().hotbarSlot(h).isBlockItem()) {
                return h;
            }
        }
        return -1;
    }

    private PlayerIntent intentFor(Stage stage) {
        Player player = host.player();
        return switch (stage) {
            case SETTLE -> PlayerIntent.NONE;

            case WALK_FORWARD -> PlayerIntent.moving(1f, 0f, false);

            case JUMP -> PlayerIntent.moving(0f, 0f, stageStep < 6);

            case LOOK -> {
                // 一次给足位移量：pitch 从 0 到 -89.5 需要 745.8 px（0.12 度/像素）。
                // 分多次小位移没有额外信息量，反而让断言依赖"分几次"。
                yield stageStep == 0
                        ? PlayerIntent.of(0f, 0f, false, 0, 746, false, false)
                        : PlayerIntent.NONE;
            }

            case AIM_DOWN_AND_MINE -> {
                if (stageStep == 0) {
                    // 设置初始条件（不跳过被测逻辑）：回到出生点正上方、视线垂直向下。
                    // 为什么需要它：前面的 WALK/JUMP 让玩家位置不确定，而"脚下那一格"必须是确定的，
                    // 否则读档校验没有可引用的坐标。
                    player.teleport(TestWorldGenerator.spawnX(), TestWorldGenerator.spawnY(),
                            TestWorldGenerator.spawnZ());
                    player.camera().setAngles(0, -89.5);
                    // ★ M2：手枪是开局装备，会占住第 1 格。手持枪械时左键是"开火"而不是
                    //   "挖掘"（PRD 5.4），因此这一步必须先切到"不是枪"的格子。
                    //   脚本必须显式声明自己手里是什么 —— 否则它的挖掘断言会因为
                    //   "手里有把枪"而失败，而失败信息看起来像挖掘功能坏了。
                    yield PlayerIntent.selectSlot(firstNonGunSlot(player));
                }
                yield PlayerIntent.of(0f, 0f, false, 0, 0, true, false);
            }

            case AIM_AND_PLACE -> {
                if (stageStep == 0) {
                    // 初始条件：站到 (0.5, 64, -5.5) 并向下 60° 看，射线会落在前方地面顶面。
                    // 为什么选 -60°：水平前伸量 = 眼高 1.62 / tan60° ≈ 0.94 格，
                    // 命中格子离玩家一格以上，因此"放置到的相邻格"不会被玩家自己的碰撞箱占住。
                    player.teleport(0.5, 64.0, -5.5);
                    player.camera().setAngles(0, -60);
                    // ★ M2：放置需要手持<b>方块物品</b>。开局装备占掉前两格之后，
                    //   "刚挖到的泥土"落在哪一格不再可预测，因此按内容去找，
                    //   而不是写死槽号。找不到就保持原槽位（放置会失败并给出原因）。
                    int blockSlot = firstBlockSlot(player);
                    if (blockSlot >= 0) {
                        yield PlayerIntent.selectSlot(blockSlot);
                    }
                }
                if (stageStep == 1) {
                    RaycastHit hit = player.currentTarget();
                    if (hit != null && hit.hasFace()) {
                        placedCellX = hit.adjacentX();
                        placedCellY = hit.adjacentY();
                        placedCellZ = hit.adjacentZ();
                        placedRuntimeId = player.inventory().selectedStack().blockRuntimeId();
                        placedCellRecorded = true;
                    }
                    yield PlayerIntent.of(0f, 0f, false, 0, 0, false, true);
                }
                yield PlayerIntent.NONE;
            }

            case HOTBAR_SLOT_2 -> PlayerIntent.selectSlot(1);

            case HOTBAR_SLOT_1 -> PlayerIntent.selectSlot(0);

            case CROSS_CHUNK -> {
                if (stageStep == 0) {
                    /*
                     * 初始条件：站到区块边界（z = -17）前方 3.5 格的空旷平台，视线回到水平。
                     *
                     * 这里的两处设定都不是"跳过被测逻辑"，而是消除两个与"能否跨区块"无关的干扰：
                     *
                     * ① 必须避开上一个阶段自己放下的方块。AIM_AND_PLACE 在 (0, 64, -7) 放了一格草方块，
                     *    它正好落在出生点向 −Z 的必经之路上：玩家碰撞箱前缘在 z = −6.00 与该方块相切，
                     *    于是被卡在 z = −5.70 —— 实测就是这么失败的（"cz −1 → −1（z=−5.70）"）。
                     *    这不是产品缺陷，地形本身是开阔的，是测试路线与自己的产物撞了。
                     *
                     * ② 从出生点出发到跨界需要走约 17 格，而 2.5 s 的预算只能走约 10.4 格 ——
                     *    裕量根本不够，"跨过边界"会退化成"断言刚好依赖距离够近"。改从边界前
                     *    3.5 格出发后，只需走 3.5 格即可跨界，实测能走出 10 格以上，裕量约 7 格。
                     */
                    player.teleport(0.5, TestWorldGenerator.spawnY(), -13.5);
                    player.camera().setAngles(0, 0);
                    // onStageBegin() 在本方法之前跑过，那时玩家还在上一个阶段的落点。
                    // 本阶段的位移必须从传送后的位置起算，否则 Δz 会把上阶段的位置也算进去。
                    stageStartZ = player.position().z;
                    chunkBeforeCross = Coords.toChunk((int) Math.floor(player.position().z));
                }
                yield PlayerIntent.moving(1f, 0f, false);
            }

            case FALL_INTO_VOID -> {
                if (stageStep == 0) {
                    // 初始条件：站到虚空坑（x,z ∈ [3,6]）正上方 72 格高。
                    // 走过去的路径依赖地形细节，而这里要验证的是"重力 + 虚空判定 + 重生"，
                    // 不是"能不能走到那里"（后者由 CROSS_CHUNK 阶段覆盖）。
                    player.teleport(4.5, 72.0, 4.5);
                }
                yield PlayerIntent.NONE;
            }

            case SAVE -> PlayerIntent.NONE;

            case DONE -> PlayerIntent.NONE;
        };
    }

    private void onStageEnd() {
        Stage stage = Stage.values()[stageIndex];
        switch (stage) {
            case SETTLE -> checkSettle();
            case WALK_FORWARD -> checkWalk();
            case JUMP -> checkJump();
            case LOOK -> checkLook();
            case AIM_DOWN_AND_MINE -> checkMine();
            case AIM_AND_PLACE -> checkPlace();
            case HOTBAR_SLOT_2 -> checkHotbar(1);
            case HOTBAR_SLOT_1 -> checkHotbar(0);
            case CROSS_CHUNK -> checkCrossChunk();
            case FALL_INTO_VOID -> checkVoid();
            case SAVE -> checkSave();
            case DONE -> {
                finished = true;
                host.requestScreenshot("final");
                Log.info("[自测] 脚本执行完毕：%d 项断言，%d 项失败", results.size(), failures.size());
            }
        }
        host.requestScreenshot(stage.name().toLowerCase(java.util.Locale.ROOT));
        stageIndex++;
        stageStep = 0;
    }

    // ============================================================ 断言

    private void checkSettle() {
        Player player = host.player();
        boolean grounded = player.onGround();
        boolean atSurface = Math.abs(player.position().y - TestWorldGenerator.spawnY()) < 0.05;
        record("静置后站在地面", grounded, "onGround=" + grounded);
        record("静置后脚底仍在地表高度", atSurface,
                String.format("y=%.4f 期望 %.1f", player.position().y, TestWorldGenerator.spawnY()));
    }

    private void checkWalk() {
        Player player = host.player();
        double travelled = player.walkDistance() - stageStartWalkDistance;
        double deltaZ = player.position().z - stageStartZ;
        record("前进位移 > 3 格", travelled > 3.0, String.format("实际 %.3f 格", travelled));
        record("前进方向为 -Z（yaw=0 时前向即 -Z）", deltaZ < -3.0,
                String.format("Δz=%.3f", deltaZ));
    }

    private void checkJump() {
        Player player = host.player();
        double height = stageMaxY - stageStartY;
        record("跳跃高度 ≥ 1.0 格（能上 1 格台阶）", height >= 1.0,
                String.format("最高上升 %.4f 格（理论 v²/2g = %.4f）",
                        height, Player.JUMP_HEIGHT));
        record("跳跃高度 < 1.45 格（跳不上 2 格）", height < 1.45,
                String.format("最高上升 %.4f 格", height));
        record("落地后重新站在地面", player.onGround(), "onGround=" + player.onGround());
    }

    private void checkLook() {
        Player player = host.player();
        double delta = player.camera().pitchDeg() - stageStartPitch;
        record("鼠标向下位移使 pitch 变小", delta < -15,
                String.format("pitch %.3f° → %.3f°（Δ=%.3f）",
                        stageStartPitch, player.camera().pitchDeg(), delta));
        record("pitch 被夹在 -89.5° 以内（不出现退化矩阵）",
                player.camera().pitchDeg() >= -Camera_MAX_PITCH,
                String.format("pitch=%.3f", player.camera().pitchDeg()));
    }

    /** 与 {@code Camera.MAX_PITCH_DEG} 同义，避免为读一个常量而暴露整个相机。 */
    private static final double Camera_MAX_PITCH = 89.5;

    private void checkMine() {
        World world = host.world();
        Player player = host.player();
        long brokenDelta = player.blocksBroken() - stageStartBreaks;
        int inventoryDelta = player.inventory().totalItemCount() - stageStartInventory;

        record("挖掘成功至少 1 个方块（走 World Mutation API）", brokenDelta >= 1,
                "破坏计数增量=" + brokenDelta);
        record("挖到的方块进入背包（M1 无掉落物实体，直接入包）", inventoryDelta >= 1,
                "背包总数增量=" + inventoryDelta);
        // 出生点脚下的那一格：它属于"可复现的确定位置"，因此可以作为读档校验的引用点
        boolean dug = world.isAirAt((int) Math.floor(TestWorldGenerator.spawnX()),
                Coords.WORLD_SURFACE_BLOCK_Y,
                (int) Math.floor(TestWorldGenerator.spawnZ()));
        record("脚下方块 (0,63,0) 确实变成空气", dug, "isAirAt(0,63,0)=" + dug);
        if (dug) {
            dugCellX = 0;
            dugCellY = Coords.WORLD_SURFACE_BLOCK_Y;
            dugCellZ = 0;
            dugCellRecorded = true;
        }
        record("射线目标指向脚下方块（不是空气）", player.currentTarget() != null,
                "currentTarget=" + player.currentTarget());
    }

    private void checkPlace() {
        World world = host.world();
        Player player = host.player();
        long placedDelta = player.blocksPlaced() - stageStartPlaces;
        record("放置成功至少 1 个方块", placedDelta >= 1, "放置计数增量=" + placedDelta
                + "（若为 0，最近一次反馈：" + player.lastPlacementMessage() + "）");
        if (placedCellRecorded) {
            int actual = world.blockIdAt(placedCellX, placedCellY, placedCellZ);
            record(String.format("放置位置 (%d,%d,%d) 的方块 ID 与手持一致",
                            placedCellX, placedCellY, placedCellZ),
                    actual == placedRuntimeId,
                    "实际=" + BlockRegistry.byRuntimeId(actual).id()
                            + " 期望=" + BlockRegistry.byRuntimeId(placedRuntimeId).id());
        } else {
            record("放置前捕获到有效瞄准面", false, "currentTarget 为空或没有可用面");
        }
    }

    private void checkHotbar(int expected) {
        int actual = host.player().inventory().selectedSlot();
        record("数字键切槽 → 选中第 " + (expected + 1) + " 格", actual == expected,
                "selectedSlot=" + actual);
    }

    private void checkCrossChunk() {
        Player player = host.player();
        chunkAfterCross = Coords.toChunk((int) Math.floor(player.position().z));
        double deltaZ = player.position().z - stageStartZ;

        record("行走跨越了区块边界", chunkBeforeCross != chunkAfterCross,
                String.format("cz %d → %d（z %.2f → %.2f）",
                        chunkBeforeCross, chunkAfterCross, stageStartZ, player.position().z));

        record("跨界位移足够大（不是被挡在边界前）", Math.abs(deltaZ) > 8.0,
                String.format("Δz=%.2f 格（预算 2.5 s 约合 10 格）", deltaZ));

        /*
         * 这条替代了首版的"跨越时相邻区块的网格被标记重建（neighborMarkCount > 0）"。
         *
         * 首版那条是<b>假阳性断言</b>：neighborMarkCount 在挖掘阶段就已经涨到 29，
         * 与"玩家是否真的跨过区块边界"没有任何关系 —— 实测中它在"跨界失败"的同一时刻
         * 被判为 PASS。一条在失败场景下依然通过的断言不提供任何验证力，必须换掉。
         *
         * 换成什么才有力：跨区块边界最可能出的事故是"世界数据在第 16 格处断裂"，
         * 其可观测后果是玩家一脚踩空掉下去。因此直接断言"跨界后仍站在地面上、
         * 且 y 仍保持地表高度"—— 这是跨边界数据连续性与碰撞一致性的直接后果，而不是代理指标。
         */
        boolean stillStanding = player.onGround()
                && Math.abs(player.position().y - TestWorldGenerator.spawnY()) < 0.05;
        record("跨区块后仍站在地表（世界数据跨边界连续）", stillStanding,
                String.format("onGround=%s y=%.4f 期望 %.1f",
                        player.onGround(), player.position().y, TestWorldGenerator.spawnY()));
    }

    private void checkVoid() {
        Player player = host.player();
        int deathsDelta = player.deaths() - stageStartDeaths;
        record("坠入虚空触发了重生", deathsDelta >= 1, "死亡计数增量=" + deathsDelta);
        boolean legalSpot = player.isStandingSpotValid(host.world(),
                player.position().x, player.position().y, player.position().z);
        record("重生落点是合法落脚点", legalSpot,
                String.format("位置 (%.2f, %.2f, %.2f)", player.position().x,
                        player.position().y, player.position().z));
        record("重生后速度已清零（不会继续下坠）",
                Math.abs(player.velocity().y) < 0.5, "vy=" + player.velocity().y);
        savedPlayerX = player.position().x;
        savedPlayerY = player.position().y;
        savedPlayerZ = player.position().z;
    }

    private void checkSave() {
        if (!host.saveEnabled()) {
            /*
             * 性能对照运行（skyisland.noSave=true）：存档被产品开关关掉了。
             * 此时"存档成功 / level.json 存在"这类断言必然失败，而失败原因与引擎无关，
             * 因此显式跳过并记录范围 —— 不让它变成一条假失败，也不让它伪装成真通过。
             */
            saveAssertionsSkipped = true;
            Log.info("[自测] 存档已被 skyisland.noSave=true 关闭，跳过存档相关断言。"
                    + "本次运行仅用于无存档的性能对照，不得单独作为 M1 功能闭环证据。");
            return;
        }
        SaveResult result = host.requestSave();
        record("存档返回成功", result != null && result.success(),
                result == null ? "null" : result.oneLine());
        Path worldDir = host.saveManager().worldDirectory();
        record("level.json 已写出", Files.exists(worldDir.resolve(SaveFormat.LEVEL_FILE)),
                worldDir.resolve(SaveFormat.LEVEL_FILE).toString());
        record("player.json 已写出", Files.exists(worldDir.resolve(SaveFormat.PLAYER_FILE)),
                worldDir.resolve(SaveFormat.PLAYER_FILE).toString());
        long chunkFiles = 0;
        if (Files.isDirectory(host.saveManager().chunkDirectory())) {
            try (var stream = Files.list(host.saveManager().chunkDirectory())) {
                chunkFiles = stream.filter(f -> f.getFileName().toString().endsWith(".bin")).count();
            } catch (Exception ignored) {
                // 目录列举失败会在下面的断言里表现为"0 个区块文件"，不需要额外处理
            }
        }
        record("至少写出了 1 个区块增量文件", chunkFiles >= 1, "区块文件数=" + chunkFiles);
        if (result != null && !result.warnings().isEmpty()) {
            for (String warning : result.warnings()) {
                Log.noteWarning("自测", "存档警告: " + warning);
            }
        }
    }

    private void record(String name, boolean passed, String detail) {
        String line = (passed ? "PASS" : "FAIL") + " · " + name + " — " + detail;
        results.add(line);
        if (passed) {
            Log.info("[自测] %s", line);
        } else {
            failures.add(name);
            Log.error("[自测] %s", line);
        }
    }

    // ============================================================ 循环之外的读档校验

    /**
     * 独立世界里重放存档，验证"改动确实落盘并可恢复"。
     *
     * <p><b>为什么在循环之外做：</b>{@code §C.4′ 第 7 条} ——
     * 测试脚手架不得在 game loop 里制造假卡顿。造一个世界、重跑一次存档加载是纯 CPU 工作，
     * 但耗时以十毫秒计，放进逻辑步会被记进帧时间统计。放在收尾阶段则完全不影响测量。
     *
     * <p><b>为什么是"另造一个世界"而不是"清空当前世界再读"：</b>
     * 后者会破坏"当前世界"这一被测对象（自测结束时世界已被改过），
     * 而且"重新加载"与"首次加载"走的是同一条代码路径，另造世界不影响验证力。
     */
    public List<String> verifyReload() {
        List<String> lines = new ArrayList<>();
        if (!host.saveEnabled()) {
            // 没有存档文件可读，读档校验整体无意义（见 checkSave 的说明）。
            saveAssertionsSkipped = true;
            lines.add("读档校验: 已跳过（skyisland.noSave=true，本次为性能对照运行）");
            Log.info("==================== M1 自测：读档校验 ====================");
            Log.info("  %s", lines.get(0));
            Log.info("==========================================================");
            return lines;
        }
        long seed = host.world().seed();
        World reloaded = new World(seed, new TestWorldGenerator());
        reloaded.ensureAreaLoaded(TestWorldGenerator.MIN_CHUNK, TestWorldGenerator.MIN_CHUNK,
                TestWorldGenerator.MAX_CHUNK, TestWorldGenerator.MAX_CHUNK);
        Player reloadedPlayer = new Player(TestWorldGenerator.spawnX(),
                TestWorldGenerator.spawnY(), TestWorldGenerator.spawnZ());

        SaveResult result = host.saveManager().loadInto(reloaded, reloadedPlayer);
        lines.add("读档结果: " + result.oneLine() + (result.success() ? "  [PASS]" : "  [FAIL]"));
        if (!result.success()) {
            failures.add("读档成功");
        }

        if (dugCellRecorded) {
            boolean air = reloaded.isAirAt(dugCellX, dugCellY, dugCellZ);
            addVerification(lines, "被挖掉的方块 (" + dugCellX + "," + dugCellY + "," + dugCellZ
                    + ") 读档后仍是空气", air, "isAirAt=" + air);
        }
        if (placedCellRecorded) {
            int actual = reloaded.blockIdAt(placedCellX, placedCellY, placedCellZ);
            String expectedName = BlockRegistry.byRuntimeId(placedRuntimeId).id();
            boolean same = actual == placedRuntimeId;
            addVerification(lines, "放置的方块 (" + placedCellX + "," + placedCellY + ","
                            + placedCellZ + ") 读档后仍存在",
                    same, "实际=" + BlockRegistry.byRuntimeId(actual).id() + " 期望=" + expectedName);
        }
        double distance = Math.sqrt(
                Math.pow(reloadedPlayer.position().x - savedPlayerX, 2)
                        + Math.pow(reloadedPlayer.position().y - savedPlayerY, 2)
                        + Math.pow(reloadedPlayer.position().z - savedPlayerZ, 2));
        addVerification(lines, "玩家位置读档一致（误差 < 0.05 格）", distance < 0.05,
                String.format("误差 %.4f 格（存档 %.2f,%.2f,%.2f → 读回 %.2f,%.2f,%.2f）",
                        distance, savedPlayerX, savedPlayerY, savedPlayerZ,
                        reloadedPlayer.position().x, reloadedPlayer.position().y,
                        reloadedPlayer.position().z));
        addVerification(lines, "读档后背包非空（挖到的方块仍在）",
                reloadedPlayer.inventory().totalItemCount() >= 1,
                "物品总数=" + reloadedPlayer.inventory().totalItemCount());

        Log.info("==================== M1 自测：读档校验 ====================");
        for (String line : lines) {
            Log.info("  %s", line);
        }
        Log.info("==========================================================");
        return lines;
    }

    private void addVerification(List<String> lines, String name, boolean passed, String detail) {
        String line = (passed ? "PASS" : "FAIL") + " · " + name + " — " + detail;
        lines.add(line);
        if (!passed) {
            failures.add(name);
        }
    }

    /** 结构化摘要（写入日志与 M1 报告）。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("自测阶段数      = ").append(Stage.values().length).append('\n');
        sb.append("断言总数        = ").append(results.size()).append('\n');
        sb.append("失败数          = ").append(failures.size()).append('\n');
        sb.append("断言范围        = ").append(scope()).append('\n');
        sb.append("整体结果        = ").append(allPassed() ? "PASS" : "FAIL").append('\n');
        sb.append("--\n");
        for (String line : results) {
            sb.append(line).append('\n');
        }
        if (!failures.isEmpty()) {
            sb.append("--\n失败项:\n");
            for (String name : failures) {
                sb.append("  · ").append(name).append('\n');
            }
        }
        return sb.toString();
    }

    /** 供 HUD/日志：当前阶段标签。 */
    public String currentStageLabel() {
        return finished ? "完成" : Stage.values()[stageIndex].label;
    }

    /** 供库存工具（测试辅助）：当前快捷栏可读字符串。 */
    public static String inventoryLine(Inventory inventory) {
        return inventory.toString();
    }

    /**
     * 供其他测试复用：M1 期望的世界出生点（与 {@link TestWorldGenerator} 一致）。
     */
    public static double[] expectedSpawn() {
        return new double[]{TestWorldGenerator.spawnX(), TestWorldGenerator.spawnY(),
                TestWorldGenerator.spawnZ()};
    }
}
