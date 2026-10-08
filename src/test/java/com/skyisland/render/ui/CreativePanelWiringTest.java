package com.skyisland.render.ui;

import com.skyisland.testutil.SourceScan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ M4-S7 创造面板<b>接线层</b>的源码扫描守卫。
 *
 * <h2>为什么这一层必须有守卫（数据层全绿也不够）</h2>
 * {@code CreativePaletteTest} 有 18 条断言把面板<b>内容</b>钉得很死
 * （20 格、不含资源核心、四类分组、{@code ∞} 标记），
 * 但它们全部只 new 一个 {@code CreativePalette}，<b>从不打开背包</b>。
 * 于是下面这四种断线可以让 18 条断言<b>全部保持绿</b>：
 * <ul>
 *   <li>标签条从未被画（{@code tabs} 永远是 1）；</li>
 *   <li>点击面板格什么都不发生（命中判定没接）；</li>
 *   <li>取出落到了 {@code Inventory.add()} 而不是光标堆（于是"占背包格"，违反 §5.1）；</li>
 *   <li>门控读的是 {@code config.gameMode()} 而不是存档定死的模式（于是旧创造存档失效）。</li>
 * </ul>
 * 这与 S6 撞到的那一形状完全同构：单测全都直接 {@code new SaveManager}，
 * 从不走 {@code SkyIslandGame.start()} ⇒「启动顺序」零覆盖。
 *
 * <h2>★ 判据为什么落在方法体上</h2>
 * 全部走 {@link SourceScan#methodBody}（已剥注释）。
 * 两条理由，缺一不可：
 * <ol>
 *   <li><b>剥注释</b>：这些文件里有大量解释性注释，而注释里必然写着被断言的代码
 *       （例如"落点是 setCursorStack，不是 add"这句就在注释里）。
 *       不剥注释的话，把真正的调用删掉断言依然绿 —— 本项目已中过三次。</li>
 *   <li><b>锚方法体</b>：钉全文会被"同一串文本出现在另一个方法里"满足。
 *       例如 {@code ensureLayout} 与 {@code render} 都要用到 tabCount，
 *       只钉"文件里出现过 tabCount"对两者都成立。</li>
 * </ul>
 *
 * <h2>★ 为什么"渲染层不许自己算几何"是一条守卫</h2>
 * 记忆里的同一条纪律（{@code MenuRendererTextGeometryWiringTest}）：
 * 布局预算好数组、渲染器<b>只读不算</b>。
 * 破例的症状极难查：格子整体偏移 3px，不报错不崩溃，
 * 玩家只会觉得"这个界面有点歪"，而截图对比看不出根因。
 */
class CreativePanelWiringTest {

    private static String renderer() {
        return SourceScan.readMain("com/skyisland/render/ui/InventoryRenderer.java");
    }

    private static String game() {
        return SourceScan.readMain("com/skyisland/game/SkyIslandGame.java");
    }

    private static String model() {
        return SourceScan.readMain("com/skyisland/render/ui/InventoryRenderModel.java");
    }

    // ============================================================ 门控

    /**
     * ★ 面板门控必须读 {@code effectiveGameMode()}（存档定死），不是配置值。
     *
     * <p>配置值只对"尚未定死"的世界有效。用它门控 ⇒ 「创造存档 + {@code -Dskyisland.gameMode=survival}」
     * 启动时创造面板消失，而 {@code effectiveGameMode()} 明明还是 CREATIVE
     * ⇒ 两处真相不一致，且症状是"昨天还能用的存档今天面板没了"。
     */
    @Test
    void thePanelGateReadsThePersistedModeNotTheConfigValue() {
        String start = SourceScan.withoutComments(game());
        int at = start.indexOf("CreativePalette.build()");
        assertTrue(at >= 0, "找不到 CreativePalette.build() 的调用点：创造面板从未被构建");
        // 取该调用点之前 600 字符（覆盖 if 条件那一行）
        String window = start.substring(Math.max(0, at - 600), at);
        assertTrue(window.contains("effectiveGameMode()"),
                "构建面板的门控没有读 effectiveGameMode()；"
                        + "必须由存档定死的模式门控（PRD 4.3 模式锁定），否则配置开关能绕过它");
        assertFalse(window.contains("config.gameMode()"),
                "构建面板的门控读了 config.gameMode()：那只是「尚未定死时的缺省来源」，"
                        + "用它门控会让已定死为创造的世界凭空失去面板");
    }

    /** 对照：banner 阶段<b>确实</b>读的是配置值。两条断言合起来才证明"两个取值器没有合并"。 */
    @Test
    void theStartupBannerStillLogsTheConfigValueAsTheCounterpart() {
        String body = SourceScan.methodBody(game(), "private void logStartupBanner(");
        assertTrue(body.contains("config.gameMode()"),
                "启动横幅不再打配置值：它必须与 logEffectiveGameMode 的生效值成对出现，"
                        + "少任何一行，玩家与排查者都无法判断本局到底按哪个口径在跑");
    }

    // ============================================================ 标签页接线

    /**
     * ★ 渲染器必须真的按标签页分流（背包区 / 创造面板两个绘制路径）。
     *
     * <p>没有它，"点标签切了页但画面不变"是可绿的失败。
     */
    @Test
    void theRendererBranchesOnTheActiveTab() {
        String body = SourceScan.methodBody(renderer(), "public void render(ShaderProgram uiShader");
        assertTrue(body.contains("creativePanelActive()"),
                "render 方法体里没有按标签页分流：创造面板永远不会被画（或永远会画）");
        assertTrue(body.contains("drawBackpackArea("),
                "render 方法体没有把 36 格背包拆成独立绘制路径");
        assertTrue(body.contains("drawCreativePanel("),
                "render 方法体没有调用创造面板绘制");
    }

    /**
     * ★ 标签条必须被画，且<b>只在多标签时</b>画。
     *
     * <p>生存模式必须跳过整段 —— 否则画面上会出现一条只写着「背包」的标签条，
     * 玩家会去找那个不存在的第二页。
     */
    @Test
    void theTabStripIsDrawnOnlyWhenThereIsMoreThanOneTab() {
        String body = SourceScan.methodBody(renderer(), "private void drawTabs(");
        assertTrue(body.contains("Localization.INV_TAB_BACKPACK")
                        && body.contains("Localization.INV_TAB_CREATIVE"),
                "标签条没有走 Localization 的两个标签文案（PRD 6.7 禁止 UI 里散落中文字面量）");
        String render = SourceScan.methodBody(renderer(), "public void render(ShaderProgram uiShader");
        assertTrue(render.contains("l.tab().count() > 1"),
                "标签条没有按「标签数 > 1」设门：生存模式会显示一条只有「背包」的标签条");
    }

    /**
     * ★ 切换标签页后必须清掉另外两页的悬停态。
     *
     * <p>缺 {@code hoverSlot} 清零的症状：切到创造页仍带着背包的 tooltip，
     * 画在一个不属于背包的页面上。
     */
    @Test
    void switchingTabsClearsTheOtherPagesHoverState() {
        String body = SourceScan.methodBody(game(), "private void handleInventoryInput(");
        int at = body.indexOf("activeTab = target");
        assertTrue(at >= 0, "找不到 activeTab 的赋值点：点标签不会切页");
        String window = body.substring(at, Math.min(body.length(), at + 700));
        assertTrue(window.contains("hoverSlot = -1"),
                "切页后没清 hoverSlot：背包 tooltip 会画到创造页上");
        assertTrue(window.contains("hoverCreativeEntry = -1"),
                "切页后没清 hoverCreativeEntry：创造面板的悬停会漏到背包页上");
    }

    /**
     * ★ 标签页命中后必须 {@code return}，不穿透到内容区。
     *
     * <p>标签条紧贴内容区上沿，若不 return，一次点击会「切页 + 点中切页后那一页的第一格」。
     * 症状是玩家在创造页单击标签「背包」，同时手上凭空多出一组方块。
     */
    @Test
    void aTabClickDoesNotFallThroughIntoTheContentArea() {
        String body = SourceScan.methodBody(game(), "private void handleInventoryInput(");
        int at = body.indexOf("if (hoveredTab >= 0)");
        assertTrue(at >= 0, "找不到标签页命中的判定：点标签不会切页");
        // 该 if 块内必须有一个 return：取块内第一个 "return;" 到块结束
        int returnAt = body.indexOf("return;", at);
        assertTrue(returnAt > at, "标签页命中后没有 return：点击会穿透到内容区");
        assertTrue(returnAt - at < 600,
                "标签页命中后的 return 距离过远（>600 字符）："
                        + "很可能 return 在内容区判定之后，点击仍然会穿透");
    }

    // ============================================================ 取出接线

    /**
     * ★ 取出必须落在<b>光标堆</b>，不能是 {@code Inventory.add()}。
     *
     * <p>PRD §5.1「取出」行：「不占用背包格，直接从面板进手持」。
     * 用 {@code add()} 会让 64 个方块占掉 1~2 格，而创造模式的卖点正是"背包无限"——
     * 于是玩家会以为"背包满了就不能拿了"，把创造模式误当成生存模式。
     */
    @Test
    void takingFromThePaletteLandsOnTheCursorStackNotTheInventory() {
        String body = SourceScan.methodBody(game(), "private void takeFromCreativePalette(");
        assertTrue(body.contains("setCursorStack("),
                "取出没有落到 setCursorStack：它必须直接进手持（PRD 5.1「不占用背包格」）");
        // ★ 判据写成「方法体里不得出现 inv.add(」而不是一个 && 组合：
        //   组合式（add && !setCursorStack）在"既 add 又 setCursorStack"时会通过，
        //   而那恰恰是最坏的一种：取出既占了背包格又改了光标堆，物品凭空多出来。
        assertFalse(body.contains("inv.add("),
                "取出方法体里出现了 Inventory.add()：那会占用背包格，违反 PRD 5.1「不占用背包格」");
    }

    /**
     * ★ 手上已有<b>不同种</b>物品时必须拒绝并说出来，不能静默覆盖。
     *
     * <p>静默覆盖等于凭空销毁玩家刚搬出来的东西。
     * M2 已定铁律：物品不得凭空消失或复制（无掉落物实体时尤其致命）。
     */
    @Test
    void takingOverADifferentHeldStackIsRefusedLoudly() {
        String body = SourceScan.methodBody(game(), "private void takeFromCreativePalette(");
        assertTrue(body.contains("held.itemRuntimeId() != entry.itemRuntimeId()"),
                "取出没有区分「手上是同种」与「手上是别的东西」："
                        + "不同种时必须拒绝，否则会静默销毁玩家手上的物品");
        assertTrue(body.contains("MSG_CREATIVE_HANDS_BUSY"),
                "拒绝时没有给出提示：症状是「点了没反应」与「我东西没了」长得一模一样");
        assertTrue(body.contains("AudioEvent.UI_DENIED"),
                "拒绝时没有播拒绝音：玩家据此会以为已经取到了");
    }

    // ============================================================ 几何单一来源

    /**
     * ★ 渲染层<b>不许</b>自己算创造格子的位置。
     *
     * <p>与 {@code MenuRendererTextGeometryWiringTest} 同一纪律：布局预算好数组、渲染器只读不算。
     * 破例的症状是格子整体偏移几像素 —— 不报错不崩溃，截图里才看得见。
     */
    @Test
    void theRendererOnlyReadsCreativeGeometryItNeverComputesIt() {
        String body = SourceScan.methodBody(renderer(), "private void drawCreativePanel(");
        assertTrue(body.contains("grid.entryX(") && body.contains("grid.entryY("),
                "创造格子坐标没有从布局读：渲染层必须只读不算（布局是唯一几何来源）");
        // 不许出现按索引递推的算术：形如 "i * 30" / "y + i *"
        assertFalse(body.contains("* 30 +") || body.contains("y + i *"),
                "创造面板里出现了按索引递推的坐标算术：那是渲染层自己算几何的形态");
        // 分类标题同样必须读布局给的行顶边
        assertTrue(body.contains("grid.rowTop("),
                "分类标题的 y 没有读 grid.rowTop：标题与方块会错开");
    }

    // ============================================================ 缓存判据

    /**
     * ★ 布局缓存判据必须同时含 {@code craftRowCount}、{@code tabCount}、{@code cachedRows}。
     *
     * <p>漏判一项的症状是"画面对、点不到"：命中判定拿着旧布局去接新画面。
     * 而这一条<b>不报错</b>，因此只能靠源码扫描钉。
     */
    @Test
    void theLayoutCacheKeyIncludesEverythingThatChangesTheGeometry() {
        String body = SourceScan.methodBody(renderer(),
                "public InventoryLayout ensureLayout(int fbWidth, int fbHeight, int craftRowCount,\n");
        assertTrue(body.contains("layout.craftRowCount() != craftRowCount"),
                "缓存判据漏了 craftRowCount（既有判据，被破坏）：切合成栏时命中判定会拿旧布局");
        assertTrue(body.contains("layout.tab().count() != tabCount"),
                "缓存判据漏了 tabCount：切到创造页时命中判定会拿旧布局（症状：点不到格子）");
        assertTrue(body.contains("cachedRows != creativeRows"),
                "缓存判据漏了 creativeRows：面板内容变化后布局不重算，格子会画在旧位置");
    }

    /** 对照：{@code cachedRows} 必须与 {@code layout} 在<b>同两句</b>里赋值。 */
    @Test
    void theCacheMissAssignsTheLayoutAndItsKeyTogether() {
        String body = SourceScan.methodBody(renderer(),
                "public InventoryLayout ensureLayout(int fbWidth, int fbHeight, int craftRowCount,\n");
        int layoutAt = body.indexOf("layout = InventoryLayout.compute(");
        int keyAt = body.indexOf("cachedRows = creativeRows");
        assertTrue(layoutAt >= 0 && keyAt >= 0,
                "缓存未命中分支里必须同时给 layout 与 cachedRows 赋值");
        assertTrue(Math.abs(layoutAt - keyAt) < 200,
                "layout 与 cachedRows 的赋值相距过远："
                        + "它们是同一份事实的两个投影，分开放会长出「判据没变、布局是旧的」");
    }

    // ============================================================ 模型字段的读者

    /**
     * ★ 模型里的标签页字段必须<b>每一个</b>都有读者。
     *
     * <p>本项目最贵的一次教训（S6）：1267 条单测全绿 + 三档门禁全崩，
     * 因为没人检查"启动时序"。字段级的同构形态是
     * 「{@code hoverCreativeEntry} 有写有读，唯独没有一处读取它去做高亮」——
     * 于是悬停反馈永远不出现，而没有任何东西变红。
     */
    @Test
    void everyTabFieldInTheModelHasAReader() {
        String all = SourceScan.allMainCodeExcept("render/ui/InventoryRenderModel.java");
        for (String field : new String[]{"activeTab", "hoverTab", "creativeView", "hoverCreativeEntry"}) {
            int reads = countReads(all, field);
            assertTrue(reads >= 2,
                    "字段 " + field + " 在生产代码里只出现 " + reads + " 次（写入不算读取）："
                            + "一个没有读者的字段会带着全绿的测试活下来");
        }
    }

    /** 数"读"的粗略口径：出现在赋值号左侧的不算。 */
    private static int countReads(String code, String field) {
        int n = 0;
        int at = 0;
        while (true) {
            int i = code.indexOf(field, at);
            if (i < 0) {
                return n;
            }
            int lineStart = code.lastIndexOf('\n', i) + 1;
            String prefix = code.substring(lineStart, i);
            boolean isWrite = prefix.matches("(?s).*[\\w.\\]]\\s*=\\s*$")
                    || prefix.trim().endsWith("=");
            if (!isWrite) {
                n++;
            }
            at = i + field.length();
        }
    }

    // ============================================================ 关闭时复位

    /**
     * ★ 关背包时把标签页拉回背包页。
     *
     * <p>不重置的症状：玩家在创造页按 E 关闭、再按 E 打开时直接落在创造页 ——
     * 而多数人 reopen 的目的是"看一眼背包还剩多少材料"。
     */
    @Test
    void closingTheInventoryResetsToTheBackpackTab() {
        String body = SourceScan.methodBody(game(), "private void closeInventoryScreen(");
        assertTrue(body.contains("activeTab = InventoryRenderModel.Tab.BACKPACK"),
                "关背包时没有把标签页复位到背包页：重开背包会直接落在创造页");
        assertTrue(body.contains("hoverCreativeEntry = -1"),
                "关背包时没清创造面板悬停态：下次打开会凭空高亮一个不存在的格子");
    }

    // ============================================================ 视图的生命周期

    /**
     * ★ 创造视图必须<b>只建一次</b>并复用同一个引用。
     *
     * <p>每帧 {@code CreativePalette.build()} 有两个后果：
     * ① 遍历整个注册表并排序，纯浪费；
     * ② 更要紧的是渲染层的布局缓存<b>每帧失效</b> ——
     * 而缓存失效的失败模式是<b>看不出来</b>的（只是变慢，不是变错），
     * 那种"悄悄劣化"比崩溃更难在事后追认。
     */
    @Test
    void theCreativeViewIsBuiltOnceAtStartupAndReusedByReference() {
        String code = SourceScan.withoutComments(game());
        int builds = countOccurrences(code, "palette.view(");
        assertTrue(builds == 1,
                "palette.view(...) 出现了 " + builds + " 次："
                        + "必须只在启动时建一次并复用同一引用（每帧重建会让布局缓存每帧失效）");
        // 填充处必须是赋值而不是现建
        String fill = SourceScan.methodBody(game(), "private void updateHud(");
        assertTrue(fill.contains("inventoryModel.creativeView = creativeView;"),
                "updateHud 没有把同一个引用交给渲染层："
                        + "若在这里现 build，每帧都会产生新对象并让布局缓存失效");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int at = 0;
        while (true) {
            int i = haystack.indexOf(needle, at);
            if (i < 0) {
                return n;
            }
            n++;
            at = i + needle.length();
        }
    }

    // ============================================================ 提示文案随标签页切换

    /**
     * ★ 底部操作提示必须<b>随标签页换一句</b>。
     *
     * <p>在创造页上写「左键取放，Shift+左键快速移动」是错的：那一页没有"放"，
     * 单击是从面板直接进手持。写错的后果不是"不好看"，
     * 而是玩家按提示去按 Shift 然后发现完全不是那回事。
     */
    @Test
    void theBottomHintSwitchesWithTheTab() {
        String body = SourceScan.methodBody(renderer(), "public void render(ShaderProgram uiShader");
        assertTrue(body.contains("Localization.INV_CREATIVE_HINT"),
                "创造页没有自己的操作提示：在创造页上画背包的取放提示是错的（那一页没有「放」）");
        assertTrue(body.contains("Localization.INV_CURSOR_HINT"),
                "背包页的操作提示消失了：它是玩家唯一的操作知识来源（M2.2 实测过删掉的后果）");
        assertTrue(body.contains("creativePanelActive()") && body.indexOf("INV_CREATIVE_HINT")
                        > body.indexOf("creativePanelActive()"),
                "提示的选择没有由标签页决定：两条文案必须由同一个条件分流");
    }
}
