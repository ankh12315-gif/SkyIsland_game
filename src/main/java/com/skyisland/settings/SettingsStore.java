package com.skyisland.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyisland.save.AtomicFileWriter;
import com.skyisland.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code settings.json} 的读写（M1.5 规格第 6 条）。
 *
 * <p><b>为什么设置<u>不</u>放在世界存档里：</b>设置是关于"这台机器上的这个人"的
 * （鼠标灵敏度、FOV、音量、键位），不是关于"这个世界"的。
 * 放进世界存档会得到两个坏结果：删掉世界就把手感也删了；换个世界玩又要重设一遍。
 * 因此它落在存档根目录的<u>同级</u>：{@code <配置根>/settings.json}，与 {@code saves/} 并列。
 *
 * <p><b>为什么复用 {@link AtomicFileWriter}：</b>它对一条需求的回答与本场景完全一致 ——
 * "文件永远是旧版或新版，不会是半截"。设置文件比存档更小，但"半截文件"的后果同样是
 * 每次启动都读失败。它自带的 {@code .bak} 回退在这里同样有用。
 *
 * <p><b>三档容错，取向是"能起来"：</b>
 * <ol>
 *   <li><b>文件不存在</b> → 用默认值，并立刻写出（下次启动就有文件了）；</li>
 *   <li><b>能解析但个别字段坏了</b> → 逐字段回退默认 + 告警，<u>其余字段照常生效</u>；</li>
 *   <li><b>完全无法解析</b> → 把原文件改名成 {@code settings.json.corrupt-<时间戳>}
 *       留证据，然后用默认值继续跑 + 告警。
 *       注意是<b>改名</b>而不是删除，也不是"保留原文件继续报错" ——
 *       前者销毁了排查线索，后者会让每次启动都告警同一件事。</li>
 * </ol>
 */
public final class SettingsStore {

    public static final String FILE_NAME = "settings.json";

    /** 车载/便携模式的标记文件，与 {@code SaveFormat.PORTABLE_MARKER} 同名同义。 */
    private static final String PORTABLE_MARKER = "portable.txt";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final String BAK = ".bak";

    /** 载入结果的性质。它决定调用方是否要（以及要不要立刻）写盘，也决定日志级别。 */
    public enum Status {
        /** 文件存在且成功读取（可能有个别字段回退默认）。 */
        LOADED,
        /** 文件不存在，使用出厂默认值。 */
        DEFAULTS_CREATED,
        /** 文件损坏：已备份原文件，使用出厂默认值。 */
        RECOVERED_FROM_CORRUPT
    }

    /**
     * 载入结果。
     *
     * <p>{@code notes} 是"这次载入过程中发生过的、值得让人知道的事"清单
     * （字段缺失 / 值被夹取 / 未知动作 id / 损坏备份路径）。
     * 它必须被返回而不是只写进日志：设置问题的排查往往发生在事后，
     * 而日志是滚动的、报告是要摘录的，把事实带回调用方才可能被引用。
     */
    public record LoadResult(GameSettings settings, Status status, Path path, List<String> notes) {

        public boolean needsWriteBack() {
            return status != Status.LOADED || !notes.isEmpty();
        }

        public String oneLine() {
            return status + " @ " + path
                    + (notes.isEmpty() ? "" : "（" + notes.size() + " 条备注）");
        }
    }

    private SettingsStore() {
    }

    // ============================================================ 路径

    /**
     * 解析 {@code settings.json} 的位置。
     *
     * <p>规则与 {@code SaveFormat.resolveSaveRoot()} 同构（保持"配置都在同一处"的直觉）：
     * <ol>
     *   <li>{@code -Dskyisland.settingsFile=<路径>} 优先 ——
     *       自动化运行必须能把设置写到临时文件，否则每次跑测试都会覆盖用户的真实手感设置；</li>
     *   <li>工作目录存在 {@code portable.txt} → {@code <工作目录>/settings.json}；</li>
     *   <li>否则 {@code %APPDATA%/SkyIsland/settings.json}（非 Windows 回落用户主目录）。</li>
     * </ol>
     */
    public static Path resolvePath() {
        String override = System.getProperty("skyisland.settingsFile");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        return configRoot().resolve(FILE_NAME);
    }

    /** 配置根目录：{@code settings.json} 的父目录。 */
    public static Path configRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.exists(cwd.resolve(PORTABLE_MARKER))) {
            return cwd;
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData).resolve("SkyIsland");
        }
        return Path.of(System.getProperty("user.home", ".")).resolve(".skyisland");
    }

    // ============================================================ 载入

    public static LoadResult loadDefault() {
        return load(resolvePath());
    }

    public static LoadResult load(Path path) {
        List<String> notes = new ArrayList<>();

        if (!Files.exists(path)) {
            notes.add("未找到设置文件，使用出厂默认值并写出：" + path);
            Log.info("[设置] 未找到设置文件 %s —— 使用默认设置并写出。", path);
            return new LoadResult(GameSettings.defaults(), Status.DEFAULTS_CREATED, path, notes);
        }

        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            notes.add("读取失败（" + e.getMessage() + "），使用出厂默认值");
            Log.noteWarning("设置", "设置文件读取失败：" + path + " —— " + e.getMessage());
            return new LoadResult(GameSettings.defaults(), Status.RECOVERED_FROM_CORRUPT, path, notes);
        }

        JsonObject root = parseObject(text);
        if (root == null) {
            Path backup = backupCorrupt(path);
            notes.add("设置文件不是合法 JSON 对象，已备份为 " + (backup == null ? "(备份失败)" : backup.getFileName())
                    + "，本次使用出厂默认值");
            Log.noteWarning("设置", "设置文件损坏，已备份并恢复默认：" + path
                    + (backup == null ? "（备份失败）" : " → " + backup));
            return new LoadResult(GameSettings.defaults(), Status.RECOVERED_FROM_CORRUPT, path, notes);
        }

        GameSettings settings = GameSettings.defaults();
        int recognized = readInto(root, settings, notes);

        if (recognized == 0) {
            Path backup = backupCorrupt(path);
            notes.add("设置文件中没有任何可识别的字段（已见键：" + root.keySet() + "），"
                    + "已备份为 " + (backup == null ? "(备份失败)" : backup.getFileName())
                    + "，本次使用出厂默认值");
            Log.noteWarning("设置", "设置文件无可识别字段，已备份并恢复默认：" + path);
            return new LoadResult(GameSettings.defaults(), Status.RECOVERED_FROM_CORRUPT, path, notes);
        }

        for (String note : notes) {
            Log.noteWarning("设置", note);
        }
        Log.info("[设置] 已载入 %s（识别 %d 个字段%s）", path, recognized,
                notes.isEmpty() ? "" : "，" + notes.size() + " 条备注");
        return new LoadResult(settings, Status.LOADED, path, notes);
    }

    /** 解析成 JsonObject；不是 JSON 或不是对象时返回 {@code null}。 */
    private static JsonObject parseObject(String text) {
        try {
            JsonElement el = JsonParser.parseString(text);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 逐字段读入。返回"识别到的字段数"。
     *
     * <p><b>为什么逐个字段读而不是整体反序列化到 DTO：</b>
     * 整体反序列化在"少一个字段"时给不出任何信息 —— 结构体里那个字段就是 0 / false，
     * 而 0 与"用户真的想设成 0"无法区分。逐个读之后，"字段缺失"与"字段值为 0"
     * 是两条不同的分支，可以分别记录、分别回退。
     */
    private static int readInto(JsonObject o, GameSettings s, List<String> notes) {
        int recognized = 0;

        if (has(o, "mouseSensitivity")) {
            double raw = o.get("mouseSensitivity").getAsDouble();
            s.setMouseSensitivity(raw);
            recognized++;
            noteClamp(notes, "mouseSensitivity", raw, s.mouseSensitivity());
        }
        if (has(o, "invertMouseY")) {
            s.setInvertMouseY(o.get("invertMouseY").getAsBoolean());
            recognized++;
        }
        if (has(o, "fovDeg")) {
            double raw = o.get("fovDeg").getAsDouble();
            s.setFovDeg(raw);
            recognized++;
            noteClamp(notes, "fovDeg", raw, s.fovDeg());
        }
        if (has(o, "vsync")) {
            s.setVsync(o.get("vsync").getAsBoolean());
            recognized++;
        }
        if (has(o, "showFps")) {
            s.setShowFps(o.get("showFps").getAsBoolean());
            recognized++;
        }
        if (has(o, "masterVolume")) {
            int raw = o.get("masterVolume").getAsInt();
            s.setMasterVolume(raw);
            recognized++;
            noteClamp(notes, "masterVolume", raw, s.masterVolume());
        }
        if (has(o, "sfxVolume")) {
            int raw = o.get("sfxVolume").getAsInt();
            s.setSfxVolume(raw);
            recognized++;
            noteClamp(notes, "sfxVolume", raw, s.sfxVolume());
        }

        if (has(o, "keyBindings") && o.get("keyBindings").isJsonObject()) {
            Map<String, String> rawMap = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("keyBindings").entrySet()) {
                JsonElement v = e.getValue();
                rawMap.put(e.getKey(), v.isJsonPrimitive() ? v.getAsString() : String.valueOf(v));
            }
            s.keyBindings().setAll(KeyBindings.fromMap(rawMap, notes));
            recognized++;
        }

        // 明确记录"少了什么"，而不是安静地用默认值
        for (String field : new String[]{"mouseSensitivity", "invertMouseY", "fovDeg", "vsync",
                "showFps", "masterVolume", "sfxVolume", "keyBindings"}) {
            if (!has(o, field)) {
                notes.add("字段缺失，使用默认值: " + field);
            }
        }
        return recognized;
    }

    private static boolean has(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull();
    }

    private static void noteClamp(List<String> notes, String field, double raw, double applied) {
        if (Double.compare(raw, applied) != 0) {
            notes.add(field + " 的值 " + raw + " 超出允许范围，已夹取为 " + applied);
        }
    }

    private static void noteClamp(List<String> notes, String field, int raw, int applied) {
        if (raw != applied) {
            notes.add(field + " 的值 " + raw + " 超出允许范围，已夹取为 " + applied);
        }
    }

    /** 把损坏的设置文件改名留证。返回新路径；失败返回 {@code null}。 */
    public static Path backupCorrupt(Path path) {
        String stamp = LocalDateTime.now().format(STAMP);
        Path backup = path.resolveSibling(path.getFileName() + ".corrupt-" + stamp);
        try {
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException e) {
            Log.noteWarning("设置", "损坏设置文件备份失败：" + e.getMessage());
            return null;
        }
    }

    // ============================================================ 写出

    /**
     * 把设置写成 {@code settings.json}。
     *
     * <p><b>为什么每次都全量写：</b>文件只有二十来行，"只写变化项"省不下任何东西，
     * 却会引入"文件里到底有哪些键"这种不确定性 —— 而这份文件的主要用途之一
     * 就是给人手工检查与修改。
     *
     * @return 是否写出成功
     */
    public static boolean save(Path path, GameSettings settings) {
        String json = toJson(settings);
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            AtomicFileWriter.write(path, bytes);
            Log.info("[设置] 已写出 %s（%d 字节）", path, bytes.length);
            return true;
        } catch (IOException e) {
            Log.error("[设置] 写出失败：" + path + " —— " + e.getMessage(), e);
            return false;
        }
    }

    /** 序列化成 JSON 文本（单独暴露，便于单元测试逐字段比对）。 */
    public static String toJson(GameSettings s) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_comment\": \"SkyIsland settings - generated, safe to edit by hand\",\n");
        sb.append(String.format(java.util.Locale.ROOT, "  \"mouseSensitivity\": %.2f,%n", s.mouseSensitivity()));
        sb.append("  \"invertMouseY\": ").append(s.invertMouseY()).append(",\n");
        sb.append(String.format(java.util.Locale.ROOT, "  \"fovDeg\": %.1f,%n", s.fovDeg()));
        sb.append("  \"vsync\": ").append(s.vsync()).append(",\n");
        sb.append("  \"showFps\": ").append(s.showFps()).append(",\n");
        sb.append("  \"masterVolume\": ").append(s.masterVolume()).append(",\n");
        sb.append("  \"sfxVolume\": ").append(s.sfxVolume()).append(",\n");
        sb.append("  \"keyBindings\": {\n");
        Map<String, String> map = s.keyBindings().toMap();
        int i = 0;
        for (Map.Entry<String, String> e : map.entrySet()) {
            sb.append("    \"").append(e.getKey()).append("\": \"").append(e.getValue()).append('"');
            sb.append(++i < map.size() ? ",\n" : "\n");
        }
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** 备份文件是否存在（供自测断言"确实产生过备份"）。 */
    public static boolean backupExists(Path settingsPath) {
        Path parent = settingsPath.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return false;
        }
        String prefix = settingsPath.getFileName().toString() + ".corrupt-";
        try (var stream = Files.list(parent)) {
            return stream.anyMatch(p -> p.getFileName().toString().startsWith(prefix));
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code .bak} 是否存在（原子写留下的上一版）。 */
    public static boolean bakExists(Path settingsPath) {
        return Files.exists(settingsPath.resolveSibling(settingsPath.getFileName() + BAK));
    }
}
