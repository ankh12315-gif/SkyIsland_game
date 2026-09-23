package com.skyisland.game;

import com.skyisland.combat.CombatController;
import com.skyisland.combat.GunState;
import com.skyisland.entity.Entity;
import com.skyisland.entity.MeleeMonster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link M2CombatSelfTest#tee} 的转发完整性测试。
 *
 * <p><b>为什么这件小事值得一个测试文件：</b>
 * M2 战斗自测的全部"事件类"断言（onDryFire / onBlockHit / onEntityHit /
 * onReloadCompleted 的计数）都建立在"自测那份 Listener 确实收到了
 * 每一次事件"之上。而 {@code tee} 是手写的逐方法转发 —— 这个形状的失败模式非常隐蔽：
 * 将来 {@code CombatController.Listener} 新增一个方法时，接口会给出默认实现（或编译报错被随手补上），
 * 而 {@code tee} 里漏掉的那一个会<b>静默丢掉</b>那一类事件。
 * 丢掉之后的表现不是"测试变红"，而是"测试照常变绿、只是那条断言永远看不到事件"——
 * 与 M1 报告里"观测点选错比测试变红更危险"是同一类问题。
 *
 * <p>因此这里对 7 个方法逐个断言：<b>两边都收到、实参逐一相同、且 first 先于 second</b>。
 * 顺序也要测，因为 {@link M2CombatSelfTest#tee} 的契约是"先产品反馈、后自测记录"
 * （自测记录可能写断言，必须描述产品已经做完事之后的同一时刻状态）。
 *
 * <p><b>M2.2 计数变化：</b>Listener 从 8 个方法减到 7 个 ——
 * {@code onReloadCancelled} 随"移动打断换弹"一齐废止（见 PRD 5.4.3 修订）。
 * 本文件里的组数与清单同步收紧：<b>漏转发一个方法仍然会指名道姓地变红</b>，
 * 只是现在清单里没有那一条了。
 */
class M2CombatSelfTestTeeTest {

    /** 记录"哪个接收方收到了哪个事件、参数是什么"，用于逐项比对。 */
    private static final class Recorder implements CombatController.Listener {

        private final String tag;
        private final List<String> log;

        Recorder(String tag, List<String> log) {
            this.tag = tag;
            this.log = log;
        }

        private void note(String event, Object... args) {
            StringBuilder sb = new StringBuilder(tag).append(':').append(event);
            for (Object arg : args) {
                sb.append(' ').append(arg);
            }
            log.add(sb.toString());
        }

        @Override
        public void onShotFired(double muzzleX, double muzzleY, double muzzleZ,
                                double endX, double endY, double endZ, boolean hitAnything) {
            note("onShotFired", muzzleX, muzzleY, muzzleZ, endX, endY, endZ, hitAnything);
        }

        @Override
        public void onBlockHit(double x, double y, double z,
                               double nx, double ny, double nz, int blockRuntimeId) {
            note("onBlockHit", x, y, z, nx, ny, nz, blockRuntimeId);
        }

        @Override
        public void onEntityHit(Entity entity, int damage, double distance) {
            // 传引用的一致性由下面的 assertSame 单独断言，这里只记身份标识
            note("onEntityHit", System.identityHashCode(entity), damage, distance);
        }

        @Override
        public void onDryFire() {
            note("onDryFire");
        }

        @Override
        public void onReloadRequest(GunState.ReloadOutcome outcome) {
            note("onReloadRequest", outcome);
        }

        @Override
        public void onReloadCompleted(int magazineAmmo, int magazineSize) {
            note("onReloadCompleted", magazineAmmo, magazineSize);
        }

        @Override
        public void onMessage(String textKey, Object... args) {
            note("onMessage", textKey, java.util.Arrays.toString(args));
        }
    }

    /** 把 tee 串起来，跑一遍全部 7 个事件，返回两侧的记录。 */
    private static List<String> runAllEvents(Entity entity) {
        List<String> log = new ArrayList<>();
        CombatController.Listener product = new Recorder("product", log);
        CombatController.Listener recorder = new Recorder("selftest", log);
        CombatController.Listener tee = M2CombatSelfTest.tee(product, recorder);

        tee.onShotFired(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, true);
        tee.onBlockHit(7.0, 8.0, 9.0, 0.0, 1.0, 0.0, 42);
        tee.onEntityHit(entity, 8, 3.25);
        tee.onDryFire();
        tee.onReloadRequest(GunState.ReloadOutcome.STARTED);
        tee.onReloadCompleted(12, 12);
        tee.onMessage("combat.msg.reload.done", 12, 12);
        return log;
    }

    @Test
    void everyListenerEventReachesBothSidesWithIdenticalArguments() {
        Entity entity = new MeleeMonster(1.5, 64.0, -2.5);
        List<String> log = runAllEvents(entity);

        // 7 个事件 × 2 个接收方 = 14 条记录。少一条就说明有事件被吞了。
        assertEquals(14, log.size(),
                "7 个事件各应到达 2 个接收方（共 14 条），实际记录=" + log);

        for (int i = 0; i < log.size(); i += 2) {
            String fromProduct = log.get(i);
            String fromSelftest = log.get(i + 1);
            assertEquals("product", fromProduct.substring(0, fromProduct.indexOf(':')),
                    "第 " + (i / 2 + 1) + " 个事件应先到达产品反馈（顺序即契约）：" + log);
            assertEquals(fromProduct.substring(fromProduct.indexOf(':') + 1),
                    fromSelftest.substring(fromSelftest.indexOf(':') + 1),
                    "第 " + (i / 2 + 1) + " 个事件两侧收到的实参必须完全一致：" + log);
        }
    }

    @Test
    void allSevenListenerMethodsAreForwarded() {
        List<String> log = runAllEvents(new MeleeMonster(0.5, 64.0, 0.5));
        List<String> events = log.stream()
                .filter(line -> line.startsWith("product:"))
                .map(line -> line.substring(line.indexOf(':') + 1)
                        .split(" ", 2)[0])
                .toList();

        // 逐方法点名，而不是只数总数：漏掉任意一个都会在这里被指名道姓地报出来。
        assertEquals(List.of(
                        "onShotFired", "onBlockHit", "onEntityHit", "onDryFire",
                        "onReloadRequest", "onReloadCompleted", "onMessage"),
                events,
                "tee 必须把 Listener 的全部 7 个事件都转发出去，实际=" + events);
    }

    @Test
    void entityReferenceIsPassedThroughWithoutCopying() {
        Entity entity = new MeleeMonster(2.5, 64.0, 0.5);
        List<Entity> seen = new ArrayList<>();
        CombatController.Listener tee = M2CombatSelfTest.tee(
                CombatController.Listener.NONE,
                new CombatController.Listener() {
                    @Override
                    public void onShotFired(double muzzleX, double muzzleY, double muzzleZ,
                                            double endX, double endY, double endZ,
                                            boolean hitAnything) {
                    }

                    @Override
                    public void onBlockHit(double x, double y, double z,
                                           double nx, double ny, double nz, int blockRuntimeId) {
                    }

                    @Override
                    public void onEntityHit(Entity hit, int damage, double distance) {
                        seen.add(hit);
                    }

                    @Override
                    public void onDryFire() {
                    }

                    @Override
                    public void onReloadRequest(GunState.ReloadOutcome outcome) {
                    }

                    @Override
                    public void onReloadCompleted(int magazineAmmo, int magazineSize) {
                    }

                    @Override
                    public void onMessage(String textKey, Object... args) {
                    }
                });

        tee.onEntityHit(entity, 8, 3.0);

        assertEquals(1, seen.size(), "onEntityHit 应当到达第二接收方");
        // 必须是同一个对象：自测要靠它读 entity.health()，复制一份就等于读到了别的状态。
        assertSame(entity, seen.get(0), "tee 不得复制 / 包装被命中的实体对象");
    }

    @Test
    void teeWithNoneOnOneSideStillDeliversToTheOther() {
        // 产品侧不关心反馈（NONE）时，自测侧仍必须收到全部事件 —— 这是 M2 自测的默认接法。
        List<String> log = new ArrayList<>();
        CombatController.Listener tee =
                M2CombatSelfTest.tee(CombatController.Listener.NONE, new Recorder("only", log));

        tee.onDryFire();
        tee.onReloadCompleted(12, 12);

        assertEquals(List.of("only:onDryFire", "only:onReloadCompleted 12 12"), log,
                "NONE 侧不得吞掉事件");
        assertTrue(log.size() == 2, "NONE 侧不得吞掉事件");
    }
}
