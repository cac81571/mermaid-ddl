package jp.mermaidddl.config;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jp.mermaidddl.AppLog;

/**
 * 接続先一覧を JSON ファイル（~/.mermaid-ddl/connections.json）に保存・読込する。
 * パスワードは CryptoUtil で暗号化して保存する。
 */
public class ConnectionStore {

    private final Path file;
    private final CryptoUtil crypto;
    private final List<ConnectionInfo> connections = new ArrayList<>();
    private String lastSelected = "";

    public ConnectionStore(Path file, CryptoUtil crypto) {
        this.file = file;
        this.crypto = crypto;
    }

    /** ファイルから読み込む。ファイルが無ければサンプル H2 接続を作って保存する。 */
    @SuppressWarnings("unchecked")
    public void load() throws IOException {
        connections.clear();
        if (!Files.exists(file)) {
            connections.add(sampleH2());
            save();
            AppLog.info("サンプル接続先を作成しました: " + file);
            return;
        }
        Object parsed = new JsonSlurper().parseText(Files.readString(file, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map)) throw new IOException("接続先ファイルの形式が不正です: " + file);
        Map<String, Object> root = (Map<String, Object>) parsed;
        Object last = root.get("lastSelected");
        lastSelected = last == null ? "" : last.toString();
        Object list = root.get("connections");
        if (list instanceof List) {
            for (Object o : (List<Object>) list) {
                Map<String, Object> m = (Map<String, Object>) o;
                connections.add(new ConnectionInfo(
                        str(m.get("name")), str(m.get("url")), str(m.get("user")),
                        crypto.decrypt(str(m.get("password"))), str(m.get("driverClass"))));
            }
        }
    }

    public void save() throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ConnectionInfo c : connections) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getName());
            m.put("url", c.getUrl());
            m.put("user", c.getUser());
            m.put("password", crypto.encrypt(c.getPassword()));
            m.put("driverClass", c.getDriverClass());
            list.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("lastSelected", lastSelected);
        root.put("connections", list);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, JsonOutput.prettyPrint(JsonOutput.toJson(root)), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** サンプル用 H2 接続（~/.mermaid-ddl/sample.mv.db に作成される） */
    public static ConnectionInfo sampleH2() {
        return new ConnectionInfo("サンプル H2", "jdbc:h2:~/.mermaid-ddl/sample", "sa", "", "org.h2.Driver");
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    public List<ConnectionInfo> getConnections() { return connections; }

    public void setConnections(List<ConnectionInfo> list) {
        connections.clear();
        for (ConnectionInfo c : list) connections.add(c.copy());
    }

    public ConnectionInfo find(String name) {
        for (ConnectionInfo c : connections) if (c.getName().equals(name)) return c;
        return null;
    }

    public String getLastSelected() { return lastSelected; }
    public void setLastSelected(String name) { lastSelected = name == null ? "" : name; }

    /** 接続先を保存している JSON ファイルのパス。 */
    public Path getFile() { return file; }
}
