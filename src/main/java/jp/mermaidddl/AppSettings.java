package jp.mermaidddl;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * 画面のオプション（チェックボックスなど）の保存先（~/.mermaid-ddl/settings.properties）。
 * 値を変えるたびに保存する。読めない・書けない場合はログに出して既定値で続行する。
 */
public final class AppSettings {

    private final Path file;
    private final Properties props = new Properties();

    public AppSettings(Path file) {
        this.file = file;
        if (file != null && Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException e) {
                AppLog.warn("設定ファイルを読めません（既定値で起動します）: " + file + " - " + e.getMessage());
            }
        }
    }

    public boolean getBoolean(String key, boolean def) {
        String v = props.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    public int getInt(String key, int def) {
        try {
            String v = props.getProperty(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public String get(String key, String def) {
        return props.getProperty(key, def);
    }

    public void put(String key, Object value) {
        String v = String.valueOf(value);
        if (v.equals(props.getProperty(key))) return;
        props.setProperty(key, v);
        save();
    }

    private synchronized void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                props.store(w, "mermaid-ddl settings");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            AppLog.warn("設定を保存できません: " + file + " - " + e.getMessage());
        }
    }
}
