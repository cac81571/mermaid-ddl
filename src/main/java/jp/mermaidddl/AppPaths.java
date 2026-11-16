package jp.mermaidddl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * アプリが使うフォルダの場所。
 * <ul>
 *   <li>設定フォルダ（connections.json, secret.key, settings.properties, logs/）: システムプロパティ mermaid-ddl.config、未指定なら ~/.mermaid-ddl</li>
 *   <li>作業フォルダ（ファイルを開く／保存するときの初期フォルダ）: 起動したカレントディレクトリ</li>
 * </ul>
 */
public final class AppPaths {

    /** 設定フォルダ名（ユーザーのホーム直下） */
    public static final String CONFIG_DIR_NAME = ".mermaid-ddl";

    private final Path home;
    private final Path configDir;

    public AppPaths(Path home, Path configDir) {
        this.home = home.toAbsolutePath().normalize();
        this.configDir = configDir.toAbsolutePath().normalize();
    }

    public static AppPaths fromSystem() {
        String c = System.getProperty("mermaid-ddl.config");
        Path config = c != null ? Paths.get(c) : Paths.get(System.getProperty("user.home"), CONFIG_DIR_NAME);
        return new AppPaths(Paths.get("").toAbsolutePath(), config);
    }

    public Path home() { return home; }
    public Path configDir() { return configDir; }
    public Path connectionsFile() { return configDir.resolve("connections.json"); }
    public Path keyFile() { return configDir.resolve("secret.key"); }
    public Path logFile() { return configDir.resolve("logs").resolve("app.log"); }
    public Path settingsFile() { return configDir.resolve("settings.properties"); }

    /** 設定フォルダを作成する。 */
    public void ensureConfigDir() throws IOException {
        Files.createDirectories(configDir);
    }
}
