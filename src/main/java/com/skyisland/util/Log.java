package com.skyisland.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 全工程唯一的日志入口（TECH_DESIGN_v0.1 §A.5 / §Q.1）。
 *
 * <p>底层使用 JDK 内置 {@code java.util.logging}（JUL），零依赖。
 * 业务代码<b>不得</b>直接调用 {@code java.util.logging.Logger} —— 一律经本类门面，
 * 使将来更换日志实现时的改动点收敛为「一个类」。
 *
 * <p>输出目标：
 * <ul>
 *   <li>控制台：由本类直接写 {@code System.out}（格式完全受控，避免 JUL 默认格式干扰）</li>
 *   <li>文件：{@code <logDir>/skyisland-YYYYMMDD.log}，始终记录 DEBUG 级</li>
 * </ul>
 * 写文件是 M0 的硬要求：M0 报告需要完整捕获告警清单，
 * 而控制台输出在进程被终止时容易丢失。
 */
public final class Log {

    private static final Logger LOGGER = Logger.getLogger("skyisland");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter TS_FULL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static volatile boolean fileLoggingEnabled = false;
    private static volatile boolean debugEnabled = true;
    private static volatile Path logFile;
    private static volatile int warningCount = 0;

    private Log() {
    }

    /**
     * 初始化日志系统。必须在任何日志输出之前调用，
     * 且在 GLFW 初始化之前调用（这样 GLFW 自身的告警也能落到文件）。
     */
    public static synchronized void init(String logDir, boolean debug) {
        debugEnabled = debug;
        LOGGER.setUseParentHandlers(false);   // 不要 JUL 默认的 stderr 输出
        LOGGER.setLevel(Level.ALL);
        for (var h : LOGGER.getHandlers()) {
            LOGGER.removeHandler(h);
        }

        try {
            Path dir = Paths.get(logDir).toAbsolutePath();
            Files.createDirectories(dir);
            String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            logFile = dir.resolve("skyisland-" + date + ".log");

            FileHandler file = new FileHandler(logFile.toString(), /* append = */ true);
            file.setLevel(Level.ALL);
            file.setFormatter(new FileFormatter());
            file.setEncoding(StandardCharsets.UTF_8.name());
            LOGGER.addHandler(file);
            fileLoggingEnabled = true;
        } catch (IOException e) {
            fileLoggingEnabled = false;
            // 此时 Logger 还没有 handler，只能直接写控制台
            System.out.println("[WARN ] 无法创建日志文件，仅输出到控制台: " + e.getMessage());
        }
    }

    public static Path logFile() {
        return logFile;
    }

    public static boolean isFileLoggingEnabled() {
        return fileLoggingEnabled;
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static int warningCount() {
        return warningCount;
    }

    // ---------------------------------------------------------------- 对外 API

    public static void info(String fmt, Object... args) {
        log(Level.INFO, "INFO ", fmt, args);
    }

    public static void warn(String fmt, Object... args) {
        warningCount++;
        log(Level.WARNING, "WARN ", fmt, args);
    }

    public static void error(String fmt, Object... args) {
        log(Level.SEVERE, "ERROR", fmt, args);
    }

    public static void error(String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(msg).append(System.lineSeparator());
        sb.append(stackTraceOf(t));
        LOGGER.log(Level.SEVERE, sb.toString());
        printToConsole("ERROR", sb.toString());
    }

    public static void debug(String fmt, Object... args) {
        if (!debugEnabled) {
            return;
        }
        log(Level.FINE, "DEBUG", fmt, args);
    }

    /**
     * 记录一条「需要被 M0 报告逐条摘录」的重要告警（JVM / GL / 资源）。
     * 与 {@link #warn} 的区别：本方法会进入警告计数器，便于汇总统计。
     */
    public static void noteWarning(String category, String text) {
        warningCount++;
        log(Level.WARNING, "WARN ", "[告警][%s] %s", new Object[]{category, text});
    }

    /** 输出原样文本（无格式化、无前缀追加），用于打印外部命令回传内容 */
    public static void raw(String line) {
        LOGGER.log(Level.INFO, line);
        System.out.println(line);
    }

    /** 把一段文本原样追加到日志文件（供 M0 报告收录外部命令输出） */
    public static void appendSectionToFile(String title, String body) {
        if (!fileLoggingEnabled || logFile == null) {
            return;
        }
        try {
            String nl = System.lineSeparator();
            String block = nl + "===== " + title + " =====" + nl
                    + body + nl
                    + "===== " + title + " 结束 =====" + nl;
            Files.writeString(logFile, block, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warning("追加日志段落失败: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 内部

    private static void log(Level level, String tag, String fmt, Object[] args) {
        String msg = format(fmt, args);
        LOGGER.log(level, msg);
        printToConsole(tag, msg);
    }

    private static void printToConsole(String tag, String msg) {
        String ts = LocalDateTime.now().format(TS);
        String[] lines = msg.split("\\R", -1);
        for (String line : lines) {
            System.out.println("[" + ts + "][" + tag + "] " + line);
        }
        System.out.flush();
    }

    private static String format(String fmt, Object[] args) {
        if (args == null || args.length == 0) {
            return String.valueOf(fmt);
        }
        try {
            return String.format(fmt, args);
        } catch (RuntimeException e) {
            // 格式化失败绝不能把异常抛到业务路径上
            return fmt + " <格式化失败: " + e.getMessage() + ">";
        }
    }

    private static String stackTraceOf(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /** 文件格式：完整时间戳 + 级别 */
    private static final class FileFormatter extends Formatter {
        @Override
        public String format(LogRecord r) {
            String level = levelName(r.getLevel());
            return "[" + LocalDateTime.now().format(TS_FULL) + "]"
                    + "[" + level + "] "
                    + r.getMessage()
                    + System.lineSeparator();
        }

        private static String levelName(Level l) {
            int v = l.intValue();
            if (v >= Level.SEVERE.intValue()) {
                return "ERROR";
            }
            if (v >= Level.WARNING.intValue()) {
                return "WARN ";
            }
            if (v <= Level.FINE.intValue()) {
                return "DEBUG";
            }
            return "INFO ";
        }
    }
}
