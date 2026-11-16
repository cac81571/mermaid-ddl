package jp.mermaidddl.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javax.swing.SwingUtilities;
import jp.mermaidddl.AppLog;
import netscape.javascript.JSObject;

/**
 * Mermaid の ER 図を WebView で描画するパネル。
 * 同梱の mermaid.min.js（オフライン）を一時フォルダに展開して読み込む。
 */
public class MermaidPreview extends JFXPanel {

    /** 同梱している Mermaid のバージョン */
    public static final String MERMAID_VERSION = "12.3.0";

    private static final String[] ASSETS = {"er-preview.html", "mermaid.min.js", "LICENSE-mermaid.txt"};
    private static Path assetDir;

    private WebView view;
    private WebEngine engine;
    private volatile boolean ready;
    private String pending;
    private volatile String lastState = "init";
    private volatile String lastMessage = "";
    /** JS から呼ばれる。GC で消えないよう強参照を保持する */
    private final Bridge bridge = new Bridge();
    private BiConsumer<String, String> listener = (s, m) -> { };

    public MermaidPreview() {
        Platform.runLater(this::initFx);
    }

    /** 描画結果の通知先（Swing スレッドで呼ばれる。state = ok / error） */
    public void setRenderListener(BiConsumer<String, String> l) {
        this.listener = l;
    }

    public String getLastState() { return lastState; }

    public String getLastMessage() { return lastMessage; }

    public boolean isReady() { return ready; }

    static synchronized Path assets() throws IOException {
        if (assetDir == null) {
            Path dir = Files.createTempDirectory("mermaid-ddl-preview");
            for (String a : ASSETS) {
                try (InputStream in = MermaidPreview.class.getResourceAsStream("/jp/mermaidddl/web/mermaid/" + a)) {
                    if (in == null) throw new IOException("リソースがありません: " + a);
                    Files.copy(in, dir.resolve(a), StandardCopyOption.REPLACE_EXISTING);
                }
                dir.resolve(a).toFile().deleteOnExit();
            }
            dir.toFile().deleteOnExit();
            assetDir = dir;
        }
        return assetDir;
    }

    private void initFx() {
        view = new WebView();
        engine = view.getEngine();
        engine.setJavaScriptEnabled(true);
        engine.getLoadWorker().stateProperty().addListener((obs, old, st) -> {
            if (st == Worker.State.SUCCEEDED) {
                JSObject win = (JSObject) engine.executeScript("window");
                win.setMember("javaBridge", bridge);
                Object state = engine.executeScript("window.renderState");
                if (!"ready".equals(state)) {
                    Object msg = engine.executeScript("window.renderMessage");
                    fire("error", "mermaid.min.js を初期化できません: " + msg);
                    return;
                }
                ready = true;
                if (pending != null) {
                    String p = pending;
                    pending = null;
                    doRender(p);
                }
            } else if (st == Worker.State.FAILED) {
                fire("error", "プレビューを読み込めません");
            }
        });
        setScene(new Scene(view));
        try {
            engine.load(assets().resolve("er-preview.html").toUri().toString());
        } catch (IOException e) {
            AppLog.error("Mermaid のリソースを展開できません", e);
            fire("error", "Mermaid のリソースを展開できません: " + e.getMessage());
        }
    }

    /** ER 図を描画する（どのスレッドからでも可） */
    public void render(String code) {
        lastState = "rendering";
        Platform.runLater(() -> {
            if (!ready) {
                pending = code;
            } else {
                doRender(code);
            }
        });
    }

    private void doRender(String code) {
        try {
            engine.executeScript("renderEr(" + jsString(code) + ")");
        } catch (Exception e) {
            fire("error", e.getMessage());
        }
    }

    /** 表示倍率（SVG のサイズを変える） */
    public void setZoom(double z) {
        Platform.runLater(() -> {
            if (ready) engine.executeScript("setScale(" + z + ")");
        });
    }

    /** 描画済み SVG を取得する（未描画なら null） */
    public String svg() throws Exception {
        CompletableFuture<String> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Object o = ready ? engine.executeScript("svgText()") : null;
                f.complete(o instanceof String ? (String) o : null);
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }

    private void fire(String state, String message) {
        lastState = state;
        lastMessage = message == null ? "" : message;
        SwingUtilities.invokeLater(() -> listener.accept(state, lastMessage));
    }

    static String jsString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default: sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** JS → Java の通知 */
    public class Bridge {
        public void onRender(String state, String message) {
            fire(state, message);
        }
    }
}
