package jp.mermaidddl;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import jp.mermaidddl.config.ConnectionInfo;
import jp.mermaidddl.er.ErDdlService;
import jp.mermaidddl.schema.Dialect;
import jp.mermaidddl.ui.MainFrame;

/**
 * 動作確認用: java -jar mermaid-ddl-1.0.0-all.jar --smoke-test
 * メインウィンドウでサンプルの ER 図 → DDL、プレビュー描画（SVG 生成）、DDL → ER 図、
 * 生成した DDL をインメモリ H2 で実行 → DB から読込 までを確認して終了する（成功: 0 / 失敗: 2）。
 * システムプロパティ mermaid-ddl.smoke.screenshot にパスを指定すると、メインウィンドウ（プレビュー表示）の画像を、
 * mermaid-ddl.smoke.screenshot.editor を指定するとエディタ（ハイライト表示）の画像を保存する。
 */
final class SmokeTest {

    private SmokeTest() {}

    static void start(MainFrame frame) {
        Thread t = new Thread(() -> {
            int code = 2;
            try {
                code = run(frame);
                AppLog.info(code == 0 ? "[smoke] OK" : "[smoke] NG");
            } catch (Exception e) {
                AppLog.error("[smoke] 失敗", e);
            }
            System.exit(code);
        }, "smoke-test");
        t.setDaemon(true);
        t.start();
    }

    private static int run(MainFrame f) throws Exception {
        AtomicReference<Boolean> ok = new AtomicReference<>();
        // 1. ER 図 → DDL（H2 / Oracle）
        for (Dialect d : new Dialect[]{Dialect.ORACLE, Dialect.H2}) {
            SwingUtilities.invokeAndWait(() -> { f.setDialect(d); ok.set(f.mermaidToDdl()); });
            String ddl = f.getDdlText();
            AppLog.info("[smoke] ER図→DDL（" + d + "）: " + ok.get() + "\n" + ddl);
            if (!ok.get() || !ddl.contains("CREATE TABLE ORDER_ITEM") || !ddl.contains("FOREIGN KEY")) return 2;
            if (d == Dialect.ORACLE && !ddl.contains("VARCHAR2(")) return 2;
        }
        // 2. プレビュー
        SwingUtilities.invokeAndWait(f::showPreviewTab);
        waitUntil(() -> "ok".equals(f.getPreview().getLastState()) || "error".equals(f.getPreview().getLastState()), 90);
        String svg = f.getPreview().svg();
        AppLog.info("[smoke] プレビュー: state=" + f.getPreview().getLastState() + " " + f.getPreview().getLastMessage()
                + " svg=" + (svg == null ? 0 : svg.length()) + " 文字");
        if (svg == null || !svg.contains("<svg") || !svg.contains("ORDER_ITEM")) return 2;
        // 3. DDL → ER 図（プレビューも更新される）。表示名は「論理名 (物理名)」
        SwingUtilities.invokeAndWait(() -> { f.setPhysicalNameInAlias(true); ok.set(f.ddlToMermaid()); });
        AppLog.info("[smoke] DDL→ER図: " + ok.get() + "\n" + f.getMermaidText());
        if (!ok.get() || !f.getMermaidText().contains("CUSTOMER ||--o{ ORDERS")) return 2;
        if (!f.getMermaidText().contains("CUSTOMER[\"顧客 (CUSTOMER)\"]")) return 2;
        // 併記した ER 図から DDL に戻しても、テーブルコメントは論理名だけ
        String named = f.getMermaidText();
        ErDdlService.Result back = ErDdlService.mermaidToDdl(named, new jp.mermaidddl.er.ErToSchemaOptions(), new jp.mermaidddl.er.DdlOptions());
        AppLog.info("[smoke] 論理名(物理名) → COMMENT: " + back.getText().contains("COMMENT ON TABLE CUSTOMER IS '顧客';"));
        if (!back.getText().contains("COMMENT ON TABLE CUSTOMER IS '顧客';")) return 2;
        waitUntil(() -> "ok".equals(f.getPreview().getLastState()), 60);
        String shot = System.getProperty("mermaid-ddl.smoke.screenshot");
        if (shot != null) {
            f.getPreview().setZoom(0.8);
            Thread.sleep(1500);
            AtomicReference<Rectangle> b = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> b.set(f.getBounds()));
            BufferedImage img = new Robot().createScreenCapture(b.get());
            ImageIO.write(img, "png", new File(shot));
            AppLog.info("[smoke] スクリーンショット: " + shot);
        }
        // 3b. シンタックスハイライト（DDL: Oracle/H2 の型・キーワード、Mermaid: 多重度・PK・タグ）
        AtomicReference<String> hl = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            f.showMermaidTab();
            hl.set("DDL CREATE=" + f.tokenTypeAt(false, "CREATE") + " INTEGER=" + f.tokenTypeAt(false, "INTEGER")
                    + " Mermaid erDiagram=" + f.tokenTypeAt(true, "erDiagram") + " ||--o{=" + f.tokenTypeAt(true, "||--o{")
                    + " PK=" + f.tokenTypeAt(true, " PK ") + " [NOT NULL]=" + f.tokenTypeAt(true, "[NOT NULL]"));
        });
        AppLog.info("[smoke] ハイライト: " + hl.get());
        if (!hl.get().equals("DDL CREATE=" + org.fife.ui.rsyntaxtextarea.Token.RESERVED_WORD
                + " INTEGER=" + org.fife.ui.rsyntaxtextarea.Token.DATA_TYPE
                + " Mermaid erDiagram=" + org.fife.ui.rsyntaxtextarea.Token.RESERVED_WORD
                + " ||--o{=" + org.fife.ui.rsyntaxtextarea.Token.OPERATOR
                + " PK=" + org.fife.ui.rsyntaxtextarea.Token.RESERVED_WORD_2
                + " [NOT NULL]=" + org.fife.ui.rsyntaxtextarea.Token.ANNOTATION)) return 2;
        String shot2 = System.getProperty("mermaid-ddl.smoke.screenshot.editor");
        if (shot2 != null) {
            Thread.sleep(800);
            AtomicReference<Rectangle> b = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> b.set(f.getBounds()));
            ImageIO.write(new Robot().createScreenCapture(b.get()), "png", new File(shot2));
            AppLog.info("[smoke] スクリーンショット（エディタ）: " + shot2);
        }
        // 4. 引用符付きの名前・日本語・特殊な型を含む ER 図も描画できること
        String tricky = String.join("\n", "erDiagram",
                "    \"LINE ITEM\"[\"明細\"] {",
                "        varchar2(50_char) `Mixed Case` \"[DEFAULT 'N']\"",
                "        number(10,2) AMOUNT PK, FK \"金額 [NOT NULL]\"",
                "        timestamp(6)_with_local_time_zone UPDATED_AT",
                "        number(*,0) N",
                "    }",
                "    顧客 {",
                "        integer 顧客ID PK \"番号\"",
                "    }",
                "    顧客 ||--o| \"LINE ITEM\" : \"x\"",
                "    顧客 |o..o{ 顧客 : \"\"",
                "");
        f.getPreview().render(tricky);
        waitUntil(() -> "ok".equals(f.getPreview().getLastState()) || "error".equals(f.getPreview().getLastState()), 60);
        AppLog.info("[smoke] 特殊な名前の描画: " + f.getPreview().getLastState() + " " + f.getPreview().getLastMessage());
        if (!"ok".equals(f.getPreview().getLastState())) return 2;
        // 5. 生成した DDL をインメモリ H2 で実行し、DB から読込
        ConnectionInfo mem = new ConnectionInfo("スモークテスト", "jdbc:h2:mem:smoke;DB_CLOSE_DELAY=-1", "sa", "", "");
        try (Connection con = f.getConnectionFactory().open(mem)) {
            ErDdlService.ExecReport rep = ErDdlService.executeDdl(con, f.getDdlText());
            AppLog.info("[smoke] DDL 実行: " + rep.summary());
            if (!rep.isOk()) return 2;
        }
        f.loadTables(mem, null, List.of("CUSTOMER", "ORDERS"));
        waitUntil(() -> f.getMermaidText().contains("CUSTOMER ||--o{ ORDERS") && !f.getMermaidText().contains("ORDER_ITEM"), 30);
        AppLog.info("[smoke] DBから読込:\n" + f.getMermaidText());
        return 0;
    }

    private static void waitUntil(BooleanSupplier cond, int seconds) throws Exception {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(300);
        }
        throw new IllegalStateException("タイムアウトしました");
    }
}
