package jp.mermaidddl;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 簡易ロガー。標準エラー・ログファイル（~/.mermaid-ddl/logs/app.log）に出力する。 */
public final class AppLog {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Path logFile;

    private AppLog() {}

    public static void setLogFile(Path file) {
        logFile = file;
    }

    public static void addListener(Consumer<String> listener) {
        LISTENERS.add(listener);
    }

    public static void info(String msg) {
        write("INFO ", msg);
    }

    public static void warn(String msg) {
        write("WARN ", msg);
    }

    public static void error(String msg, Throwable t) {
        String text = msg;
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            text = msg + System.lineSeparator() + sw;
        }
        write("ERROR", text);
    }

    private static synchronized void write(String level, String msg) {
        String line = LocalDateTime.now().format(FMT) + " " + level + " " + msg;
        System.err.println(line);
        for (Consumer<String> l : LISTENERS) {
            try {
                l.accept(line);
            } catch (RuntimeException ignore) {
                // リスナーの例外はログ出力を止めない
            }
        }
        Path f = logFile;
        if (f != null) {
            try {
                Files.createDirectories(f.getParent());
                Files.writeString(f, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignore) {
                // ファイルに書けなくても続行
            }
        }
    }
}
