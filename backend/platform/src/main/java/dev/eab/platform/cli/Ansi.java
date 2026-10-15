package dev.eab.platform.cli;

/** 终端配色，与界面的来源配色一致：标品靛紫、租户青绿、平台石板灰。设置 NO_COLOR 时不输出颜色 */
final class Ansi {

    private Ansi() {}

    private static final boolean ENABLED = System.getenv("NO_COLOR") == null;

    /** 命令行只显示告警及以上的日志，数据库连接池和迁移的启动信息不打扰输出 */
    static void quietLogs() {
        var root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root instanceof ch.qos.logback.classic.Logger logback) logback.setLevel(ch.qos.logback.classic.Level.WARN);
    }

    static String color(String code, String text) {
        return ENABLED ? "\u001b[" + code + "m" + text + "\u001b[0m" : text;
    }

    static String red(String text) {
        return color("31", text);
    }

    static String yellow(String text) {
        return color("33", text);
    }

    static String green(String text) {
        return color("32", text);
    }

    static String magenta(String text) {
        return color("35", text);
    }

    static String cyan(String text) {
        return color("36", text);
    }

    static String gray(String text) {
        return color("90", text);
    }

    static String dim(String text) {
        return color("2", text);
    }

    static String bold(String text) {
        return color("1", text);
    }
}
