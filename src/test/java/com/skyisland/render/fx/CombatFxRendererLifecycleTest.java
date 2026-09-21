package com.skyisland.render.fx;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 战斗特效叠加层的<b>缓冲生命周期</b>护栏（M2.1）。
 *
 * <h2>为什么需要这个文件</h2>
 * M2.1 加入"枪口闪光"时，编辑把曳光那一组缓冲的分配整段顶掉了：
 * {@code init()} 里只剩 {@code particleStaging} 与 {@code flashStaging}，
 * 而 {@code tracerStaging} 保持 null。编译通过、单测全绿、M1 与 UI 门禁也全绿 ——
 * 直到真正开了一枪，{@code upload()} 里 {@code staging.clear()} 才抛 NPE。
 *
 * <p>那个 NPE 的堆栈指向 {@code CombatFxRenderer.render} → {@code Renderer.renderWorld}
 * → 渲染循环，离真正的原因（少了一行分配）隔了三层。更糟的是它的<b>症状</b>是
 * "门禁跑 5.6 秒就崩、自测只跑到第 31 条断言"，看上去像是战斗逻辑或自测脚本坏了。
 *
 * <h2>这个文件断言的是什么</h2>
 * 不是"某个缓冲非空"这种一次性补丁，而是三条<b>与具体组数无关</b>的结构性质，
 * 于是以后再加第四组特效时它们仍然有效：
 * <ol>
 *   <li><b>有几组 VBO，就必须有几组暂存缓冲</b> —— 反射数一遍 {@code *Vao} 字段与
 *       {@code FloatBuffer} 字段的个数。只加特效忘了加缓冲，这条就红；</li>
 *   <li><b>分配后一组都不能缺</b> —— 遍历所有 {@code FloatBuffer} 字段，
 *       有一个 null 就报出它的名字；</li>
 *   <li><b>容量必须来自它自己那条常量</b> —— {@code particleStaging} 的容量必须等于
 *       {@code PARTICLE_CAPACITY_FLOATS}。这条防的是另一半错误：
 *       把粒子的容量错写成曳光的（缓冲区大小对不上时不会有任何报错，
 *       只会在某个特效数量达到上限时静默截断）。</li>
 * </ol>
 *
 * <p><b>为什么这些断言能跑在无头环境里：</b>分配与释放暂存缓冲是纯堆外内存操作，
 * 不碰 GL。这正是把它们从 {@code init()} / {@code dispose()} 里拆出来的理由 ——
 * 留在那两个方法里，"缓冲是否配齐"就永远只能靠"开个真窗口打一枪"来发现。
 *
 * <p>命名约定：字段 {@code <组名>Staging} 对应常量 {@code <组名>_CAPACITY_FLOATS}
 * （大写下划线）。新增一组时不遵守它，第 3 条会带着这条说明变红，而不是静默放过。
 */
class CombatFxRendererLifecycleTest {

    /** 暂存缓冲字段的命名后缀。 */
    private static final String STAGING_SUFFIX = "Staging";

    /** 容量常量的命名后缀。 */
    private static final String CAPACITY_SUFFIX = "_CAPACITY_FLOATS";

    // ============================================================ 结构不变量

    /**
     * 顶点数组对象的组数必须与暂存缓冲的组数一致。
     *
     * <p>这条是三条里唯一"不需要调用任何方法"就能红的 —— 它只看类结构。
     * 如果哪天有人复制一段 {@code glGenVertexArrays} 加了第四种特效而忘了加缓冲，
     * 失败信息会直接指出差额。
     */
    @Test
    void vertexArrayCountMustMatchStagingBufferCount() {
        int vaoCount = 0;
        for (Field f : CombatFxRenderer.class.getDeclaredFields()) {
            if (f.getName().endsWith("Vao")) {
                vaoCount++;
            }
        }
        List<Field> buffers = stagingBufferFields();
        assertEquals(vaoCount, buffers.size(),
                "VAO 组数（" + vaoCount + "）与暂存缓冲组数（" + buffers.size() + "）不一致。"
                        + "每加一组特效就必须为它加一个暂存缓冲 —— "
                        + "少了的那一组会在第一次渲染该特效时抛 NullPointerException。"
                        + " 当前缓冲字段：" + names(buffers));
    }

    // ============================================================ 分配

    /**
     * 分配之后，每一个暂存缓冲字段都必须非空。
     *
     * <p>这条断言的就是 M2.1 那个缺陷本身。它不检查"有没有 3 个"，
     * 而是检查"每一个都配好了" —— 组数由上面那条结构断言保证。
     */
    @Test
    void allocatingMustFillEveryStagingBuffer() {
        CombatFxRenderer renderer = new CombatFxRenderer();
        renderer.allocateStagingBuffers();
        try {
            List<String> missing = new ArrayList<>();
            for (Field f : stagingBufferFields()) {
                if (read(f, renderer) == null) {
                    missing.add(f.getName());
                }
            }
            if (!missing.isEmpty()) {
                fail("以下暂存缓冲在 allocateStagingBuffers() 之后仍为 null：" + missing
                        + "。它们在 render() 里被直接当作已分配的缓冲使用，"
                        + "因此这里少一个就等于「第一次出现该特效时必定崩溃」。");
            }
        } finally {
            renderer.freeStagingBuffers();
        }
    }

    /**
     * 容量必须来自与本组同名的那条常量。
     *
     * <p>反过来说：把 {@code tracerStaging} 用 {@code PARTICLE_CAPACITY_FLOATS} 分配，
     * 这条会红。
     */
    @Test
    void everyStagingBufferSizeMustComeFromItsOwnCapacityConstant() throws Exception {
        CombatFxRenderer renderer = new CombatFxRenderer();
        renderer.allocateStagingBuffers();
        try {
            for (Field f : stagingBufferFields()) {
                FloatBuffer buffer = read(f, renderer);
                assertNotNull(buffer, f.getName() + " 未被分配，无法检查容量");

                String constantName = capacityConstantNameFor(f.getName());
                Field constant;
                try {
                    constant = CombatFxRenderer.class.getField(constantName);
                } catch (NoSuchFieldException e) {
                    fail("找不到与字段 " + f.getName() + " 对应的容量常量 " + constantName
                            + "。约定是：字段 <组名>Staging 对应常量 <组名>" + CAPACITY_SUFFIX
                            + "（大写下划线）。请补上常量，或让新字段遵守这条约定 —— "
                            + "否则「缓冲是否配齐」这件事就没有任何断言看得见。");
                    return;
                }
                assertEquals(constant.getInt(null), buffer.capacity(),
                        f.getName() + " 的容量应当等于 " + constantName
                                + "。容量偏小不会报错，只会在特效数量到上限时静默截断");
            }
        } finally {
            renderer.freeStagingBuffers();
        }
    }

    // ============================================================ 释放

    /**
     * 释放之后，每一个暂存缓冲字段都必须回到 null。
     *
     * <p>与分配那条成对：只检查"分配齐了"会让"释放漏了"变成堆外内存泄漏 ——
     * 它不会崩，只会让每次开关世界都多留一块 native 内存。这种漏在试玩里
     * 完全看不出来，所以只能在这里断言。
     */
    @Test
    void freeingMustClearEveryStagingBuffer() {
        CombatFxRenderer renderer = new CombatFxRenderer();
        renderer.allocateStagingBuffers();
        renderer.freeStagingBuffers();

        List<String> stillHeld = new ArrayList<>();
        for (Field f : stagingBufferFields()) {
            if (read(f, renderer) != null) {
                stillHeld.add(f.getName());
            }
        }
        if (!stillHeld.isEmpty()) {
            fail("以下暂存缓冲在 freeStagingBuffers() 之后仍被持有：" + stillHeld
                    + "。暂存缓冲是堆外内存（MemoryUtil.memAllocFloat），"
                    + "GC 不会回收它们 —— 释放路径漏掉一个就是一处永久泄漏。");
        }
    }

    // ============================================================ 工具

    /** 类里所有 {@code FloatBuffer} 类型的字段，即全部暂存缓冲。 */
    private static List<Field> stagingBufferFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : CombatFxRenderer.class.getDeclaredFields()) {
            if (!FloatBuffer.class.isAssignableFrom(f.getType())) {
                continue;
            }
            if (!f.getName().endsWith(STAGING_SUFFIX)) {
                fail("发现一个 FloatBuffer 字段 " + f.getName() + " 不以 \"" + STAGING_SUFFIX
                        + "\" 结尾，本测试无法把它与容量常量对应起来。"
                        + "请遵守 <组名>" + STAGING_SUFFIX + " 的命名约定。");
            }
            f.setAccessible(true);
            out.add(f);
        }
        return out;
    }

    /** {@code particleStaging} → {@code PARTICLE_CAPACITY_FLOATS}。 */
    private static String capacityConstantNameFor(String fieldName) {
        String stem = fieldName.substring(0, fieldName.length() - STAGING_SUFFIX.length());
        return stem.toUpperCase(Locale.ROOT) + CAPACITY_SUFFIX;
    }

    private static FloatBuffer read(Field f, CombatFxRenderer target) {
        try {
            return (FloatBuffer) f.get(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError("无法读取字段 " + f.getName(), e);
        }
    }

    private static List<String> names(List<Field> fields) {
        List<String> out = new ArrayList<>();
        for (Field f : fields) {
            out.add(f.getName());
        }
        return out;
    }
}
