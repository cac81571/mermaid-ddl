package jp.mermaidddl;

import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Font;
import java.util.Arrays;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javafx.application.Platform;
import jp.mermaidddl.config.ConnectionFactory;
import jp.mermaidddl.config.ConnectionStore;
import jp.mermaidddl.config.CryptoUtil;
import jp.mermaidddl.ui.MainFrame;

/**
 * エントリポイント。
 * ※ fat JAR から JavaFX を起動するため、javafx.application.Application を継承しないクラスを main にしている。
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        AppPaths paths = AppPaths.fromSystem();
        AppLog.setLogFile(paths.logFile());
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> AppLog.error("予期しないエラー（" + t.getName() + "）", e));

        // プレビュー（JFXPanel）の都合で JavaFX ランタイムを勝手に終了させない（終了はウィンドウを閉じたとき）
        Platform.setImplicitExit(false);

        SwingUtilities.invokeLater(() -> {
            try {
                FlatLightLaf.setup();
                UIManager.put("defaultFont", UIManager.getFont("defaultFont").deriveFont(Font.PLAIN, 13f));
                paths.ensureConfigDir();
                CryptoUtil crypto = new CryptoUtil(paths.keyFile());
                ConnectionStore store = new ConnectionStore(paths.connectionsFile(), crypto);
                store.load();
                ConnectionFactory factory = new ConnectionFactory();
                MainFrame frame = new MainFrame(paths, store, factory);
                frame.setVisible(true);
                AppLog.info("起動しました（設定フォルダ: " + paths.configDir() + "）");
                if (Arrays.asList(args).contains("--smoke-test")) {
                    SmokeTest.start(frame);
                }
            } catch (Exception e) {
                AppLog.error("起動に失敗しました", e);
                JOptionPane.showMessageDialog(null, "起動に失敗しました:\n" + e, "mermaid-ddl", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
