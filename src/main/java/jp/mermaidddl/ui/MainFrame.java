package jp.mermaidddl.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Desktop;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import javafx.application.Platform;
import jp.mermaidddl.AppLog;
import jp.mermaidddl.AppPaths;
import jp.mermaidddl.AppSettings;
import jp.mermaidddl.ui.editor.Editors;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import jp.mermaidddl.config.ConnectionFactory;
import jp.mermaidddl.config.ConnectionInfo;
import jp.mermaidddl.config.ConnectionStore;
import jp.mermaidddl.core.SqlScript;
import jp.mermaidddl.er.DdlOptions;
import jp.mermaidddl.er.DdlWriter;
import jp.mermaidddl.er.ErDdlService;
import jp.mermaidddl.er.ErToSchemaOptions;
import jp.mermaidddl.er.SchemaToErOptions;
import jp.mermaidddl.schema.Dialect;
import jp.mermaidddl.schema.Identifiers;
import jp.mermaidddl.schema.MetadataReader;

/**
 * メインウィンドウ（ER図⇔DDL変換）。
 * 上: 接続先・方言・変換ボタン・オプション、左: Mermaid erDiagram（エディタ / プレビュー）、右: DDL、
 * 下: エラー・警告（ダブルクリックで該当行へ）。
 */
public class MainFrame extends JFrame {

    private static final long serialVersionUID = 1L;
    public static final String APP_NAME = "mermaid-ddl";

    public static final String SAMPLE = String.join("\n",
            "erDiagram",
            "    CUSTOMER[\"顧客\"] {",
            "        int customer_id PK \"顧客ID\"",
            "        string(100) name \"氏名 [NOT NULL]\"",
            "        varchar2(200) email UK \"メールアドレス\"",
            "        date birthday \"生年月日\"",
            "    }",
            "    ORDERS[\"注文\"] {",
            "        int order_id PK \"注文ID [IDENTITY]\"",
            "        int customer_id FK \"顧客ID\"",
            "        datetime ordered_at \"注文日時 [DEFAULT CURRENT_TIMESTAMP]\"",
            "        number(10,2) amount \"金額\"",
            "    }",
            "    ORDER_ITEM[\"注文明細\"] {",
            "        int order_id PK, FK \"注文ID\"",
            "        int line_no PK \"行番号\"",
            "        string(20) product_code FK \"商品コード\"",
            "        int quantity \"数量 [NOT NULL]\"",
            "    }",
            "    PRODUCT[\"商品\"] {",
            "        string(20) product_code PK \"商品コード\"",
            "        string(200) product_name \"商品名\"",
            "        decimal(10,2) price \"単価\"",
            "    }",
            "    CUSTOMER ||--o{ ORDERS : \"注文する\"",
            "    ORDERS ||--|{ ORDER_ITEM : \"含む\"",
            "    PRODUCT ||--o{ ORDER_ITEM : \"明細に載る\"",
            "");

    private static final Pattern LINE_REF = Pattern.compile("^(\\d+) 行目");

    private final transient AppPaths paths;
    private final transient ConnectionStore store;
    private final transient ConnectionFactory factory;
    private final JComboBox<ConnectionInfo> connectionCombo = new JComboBox<>();
    /** バックグラウンド処理から参照するため volatile */
    private volatile ConnectionInfo activeConnection;
    private java.io.File lastDir;

    private final JComboBox<Dialect> dialectCombo = new JComboBox<>(new Dialect[]{Dialect.H2, Dialect.ORACLE});
    private final JComboBox<String> oracleLimit = new JComboBox<>(new String[]{"128 バイト（12.2 以降）", "30 バイト（12.1 以前）"});
    private final JCheckBox fkAlter = new JCheckBox("FK を ALTER TABLE で出力");
    private final JCheckBox drop = new JCheckBox("DROP 文を出力");
    private final JCheckBox comments = new JCheckBox("COMMENT 文", true);
    private final JCheckBox upper = new JCheckBox("名前を大文字に", true);
    private final JCheckBox identityPk = new JCheckBox("整数 PK を IDENTITY に");
    private final JCheckBox genericTypes = new JCheckBox("ER 図は汎用型で（string, int...）");
    private final JCheckBox tags = new JCheckBox("[NOT NULL] などのタグ", true);
    private final JCheckBox distinguish = new JCheckBox("非識別は .. で");
    private final JCheckBox physicalNames = new JCheckBox("論理名(物理名)で表示", true);
    private final transient AppSettings settings;

    final RSyntaxTextArea mermaidArea = Editors.create(Editors.SYNTAX_MERMAID);
    final RSyntaxTextArea ddlArea = Editors.create(Editors.SYNTAX_SQL);
    final MermaidPreview preview = new MermaidPreview();
    final JTabbedPane leftTabs = new JTabbedPane();
    private final DefaultListModel<Msg> messages = new DefaultListModel<>();
    private final JList<Msg> messageList = new JList<>(messages);
    final JLabel status = new JLabel(" ");
    private final JLabel previewStatus = new JLabel(" ");
    private double zoom = 1.0;

    /** メッセージ 1 件 */
    static final class Msg {
        final String text;
        final boolean error;
        final JTextArea target;
        final int line;

        Msg(String text, boolean error, JTextArea target) {
            this.text = text;
            this.error = error;
            this.target = target;
            Matcher m = LINE_REF.matcher(text);
            this.line = m.find() ? Integer.parseInt(m.group(1)) : 0;
        }

        @Override
        public String toString() { return (error ? "エラー: " : "注意: ") + text; }
    }

    public MainFrame(AppPaths paths, ConnectionStore store, ConnectionFactory factory) {
        super(APP_NAME + " - ER図（Mermaid）⇔ DDL 変換（H2 / Oracle）");
        this.paths = paths;
        this.store = store;
        this.factory = factory;
        this.lastDir = paths.home().toFile();
        this.settings = new AppSettings(paths.settingsFile());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                Platform.exit();
                System.exit(0);
            }
        });
        setLayout(new BorderLayout());
        setJMenuBar(createMenu());
        add(createToolbars(), BorderLayout.NORTH);

        // 左: Mermaid
        JPanel left = new JPanel(new BorderLayout());
        left.add(fileBar("Mermaid ER 図", mermaidArea, true), BorderLayout.NORTH);
        leftTabs.addTab("Mermaid", Editors.scroll(mermaidArea));
        JPanel previewPanel = new JPanel(new BorderLayout());
        JPanel pbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        pbar.add(small("プレビュー更新", e -> refreshPreview()));
        pbar.add(small("拡大", e -> zoom(1.2)));
        pbar.add(small("縮小", e -> zoom(1 / 1.2)));
        pbar.add(small("SVG 保存...", e -> saveSvg()));
        pbar.add(previewStatus);
        previewPanel.add(pbar, BorderLayout.NORTH);
        previewPanel.add(preview, BorderLayout.CENTER);
        leftTabs.addTab("プレビュー", previewPanel);
        leftTabs.addChangeListener(e -> { if (leftTabs.getSelectedIndex() == 1) refreshPreview(); });
        left.add(leftTabs, BorderLayout.CENTER);

        // 右: DDL
        JPanel right = new JPanel(new BorderLayout());
        right.add(fileBar("DDL", ddlArea, false), BorderLayout.NORTH);
        right.add(Editors.scroll(ddlArea), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.5);

        messageList.setVisibleRowCount(5);
        messageList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                Component c = super.getListCellRendererComponent(list, value, index, sel, focus);
                if (!sel && value instanceof Msg) c.setForeground(((Msg) value).error ? new Color(0xC62828) : new Color(0x8D6E00));
                return c;
            }
        });
        messageList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) jumpTo(messageList.getSelectedValue());
            }
        });
        JScrollPane msgScroll = new JScrollPane(messageList);
        msgScroll.setBorder(BorderFactory.createTitledBorder("エラー・注意（ダブルクリックで該当行へ）"));
        JSplitPane vsplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, split, msgScroll);
        vsplit.setResizeWeight(0.85);
        add(vsplit, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        add(status, BorderLayout.SOUTH);

        preview.setRenderListener((state, msg) -> {
            previewStatus.setText("ok".equals(state) ? "描画しました" : "描画エラー: " + firstLine(msg));
            previewStatus.setForeground("ok".equals(state) ? Color.DARK_GRAY : new Color(0xC62828));
        });

        mermaidArea.setText(SAMPLE);
        mermaidArea.setCaretPosition(0);
        refreshConnections();
        dialectCombo.addActionListener(e -> oracleLimit.setEnabled(dialectCombo.getSelectedItem() == Dialect.ORACLE));
        oracleLimit.setEnabled(dialectCombo.getSelectedItem() == Dialect.ORACLE);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1280, screen.width - 40), Math.min(860, screen.height - 60));
        vsplit.setDividerLocation(getHeight() - 230);
        split.setDividerLocation(getWidth() / 2 - 20);
        setLocationRelativeTo(null);
    }

    // ================================================================ 画面部品

    private JComponent createToolbars() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(new JLabel(" 方言: "));
        dialectCombo.setMaximumSize(new Dimension(110, 28));
        tb.add(dialectCombo);
        tb.addSeparator();
        tb.add(button("ER図→DDL", "Mermaid の erDiagram から DDL を生成します", e -> mermaidToDdl()));
        tb.add(button("DDL→ER図", "DDL（CREATE TABLE / ALTER TABLE / COMMENT ON）から erDiagram を生成します", e -> ddlToMermaid()));
        tb.add(button("DBから読込...", "接続中の DB のテーブルから erDiagram と DDL を生成します", e -> loadFromDb()));
        tb.addSeparator();
        tb.add(button("DDLを実行...", "右の DDL を接続中の DB で実行します（確認あり）", e -> executeDdl()));
        tb.addSeparator();
        tb.add(button("サンプル", "サンプルの ER 図を表示します", e -> showSample()));
        tb.addSeparator();
        tb.add(new JLabel(" 接続先: "));
        connectionCombo.setPrototypeDisplayValue(new ConnectionInfo("XXXXXXXXXXXXXXXXXXXX", "", "", "", ""));
        connectionCombo.setMaximumSize(new Dimension(240, 28));
        connectionCombo.setToolTipText("「DBから読込」「DDLを実行」で使う接続先");
        connectionCombo.addActionListener(e -> onConnectionSelected());
        tb.add(connectionCombo);
        tb.add(button("管理...", "接続先の追加・編集・削除・接続テスト", e -> openConnectionDialog()));
        tb.add(button("接続テスト", "選択中の接続先に接続できるか確認します", e -> testActiveConnection()));

        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        opts.add(new JLabel("DDL 出力:"));
        opts.add(fkAlter);
        opts.add(drop);
        opts.add(comments);
        opts.add(upper);
        opts.add(identityPk);
        opts.add(new JLabel("Oracle 識別子上限"));
        opts.add(oracleLimit);
        JPanel opts2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        opts2.add(new JLabel("ER 図出力:"));
        opts2.add(genericTypes);
        opts2.add(tags);
        opts2.add(distinguish);
        opts2.add(physicalNames);
        physicalNames.setToolTipText("DDL→ER図 / DBから読込 でエンティティの表示名を「顧客 (CUSTOMER)」のように論理名（テーブルコメント）と物理名の併記にします");
        bindSettings();
        upper.setToolTipText("customer → CUSTOMER、LINE-ITEM → LINE_ITEM。オフにすると書いたとおり（引用符付き）");
        identityPk.setToolTipText("単一の整数主キー（FK でないもの）を GENERATED BY DEFAULT AS IDENTITY にします");
        tags.setToolTipText("NOT NULL / DEFAULT / IDENTITY をコメント末尾の [ ] タグで残します（往復変換用）");
        distinguish.setToolTipText("FK が主キーの一部なら --（識別）、それ以外は ..（非識別）で出力します");

        JPanel rows = new JPanel(new BorderLayout());
        rows.add(opts, BorderLayout.NORTH);
        rows.add(opts2, BorderLayout.SOUTH);
        JPanel p = new JPanel(new BorderLayout());
        p.add(tb, BorderLayout.NORTH);
        p.add(rows, BorderLayout.SOUTH);
        return p;
    }

    /** チェックボックス等の状態を settings.properties に保存・復元する */
    private void bindSettings() {
        Object[][] boxes = {
            {"ddl.fkAlter", fkAlter}, {"ddl.drop", drop}, {"ddl.comments", comments}, {"ddl.upperCase", upper},
            {"ddl.identityPk", identityPk}, {"er.genericTypes", genericTypes}, {"er.tags", tags},
            {"er.distinguishIdentifying", distinguish}, {"er.physicalNameInAlias", physicalNames}};
        for (Object[] b : boxes) {
            String key = (String) b[0];
            JCheckBox cb = (JCheckBox) b[1];
            cb.setSelected(settings.getBoolean(key, cb.isSelected()));
            cb.addActionListener(e -> settings.put(key, cb.isSelected()));
        }
        oracleLimit.setSelectedIndex(Math.max(0, Math.min(1, settings.getInt("ddl.oracleLimit", 0))));
        oracleLimit.addActionListener(e -> settings.put("ddl.oracleLimit", oracleLimit.getSelectedIndex()));
    }

    /** オプション「論理名(物理名)で表示」（スモークテスト用） */
    public void setPhysicalNameInAlias(boolean on) { physicalNames.setSelected(on); }

    private JPanel fileBar(String title, JTextArea area, boolean mermaid) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        p.add(l);
        p.add(small("開く...", e -> openFile(area, mermaid)));
        p.add(small("保存...", e -> saveFile(area, mermaid)));
        p.add(small("コピー", e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(area.getText()), null);
            status.setText(title + " をクリップボードにコピーしました");
        }));
        p.add(small("クリア", e -> area.setText("")));
        return p;
    }

    private static JButton button(String text, String tip, java.awt.event.ActionListener a) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.addActionListener(a);
        return b;
    }

    private static JButton small(String text, java.awt.event.ActionListener a) {
        JButton b = new JButton(text);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.addActionListener(a);
        return b;
    }

    private JMenuBar createMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("ファイル");
        file.add(item("Mermaid を開く...", KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), e -> openFile(mermaidArea, true)));
        file.add(item("Mermaid を保存...", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), e -> saveFile(mermaidArea, true)));
        file.add(item("DDL を開く...", null, e -> openFile(ddlArea, false)));
        file.add(item("DDL を保存...", null, e -> saveFile(ddlArea, false)));
        file.add(item("プレビューを SVG で保存...", null, e -> saveSvg()));
        file.addSeparator();
        file.add(item("終了", null, e -> dispose()));
        bar.add(file);

        JMenu conv = new JMenu("変換");
        conv.add(item("ER図→DDL", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), e -> mermaidToDdl()));
        conv.add(item("DDL→ER図", KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), e -> ddlToMermaid()));
        conv.add(item("プレビューを表示", KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), e -> { showPreviewTab(); refreshPreview(); }));
        conv.addSeparator();
        conv.add(item("DBから読込...", null, e -> loadFromDb()));
        conv.add(item("DDLを実行...", null, e -> executeDdl()));
        conv.addSeparator();
        conv.add(item("サンプルを表示", null, e -> showSample()));
        bar.add(conv);

        JMenu conn = new JMenu("接続");
        conn.add(item("接続先の管理...", null, e -> openConnectionDialog()));
        conn.add(item("選択中の接続先をテスト", null, e -> testActiveConnection()));
        bar.add(conn);

        JMenu help = new JMenu("ヘルプ");
        help.add(item("Mermaid erDiagram の書き方", null, e -> showSyntaxHelp()));
        help.add(item("ログフォルダを開く", null, e -> openFolder(paths.logFile().getParent())));
        help.addSeparator();
        help.add(item(APP_NAME + " について", null, e -> showAbout()));
        bar.add(help);
        return bar;
    }

    private static JMenuItem item(String text, KeyStroke key, java.awt.event.ActionListener action) {
        JMenuItem mi = new JMenuItem(text);
        if (key != null) mi.setAccelerator(key);
        mi.addActionListener(action);
        return mi;
    }

    private void showSample() {
        mermaidArea.setText(SAMPLE);
        mermaidArea.setCaretPosition(0);
        if (leftTabs.getSelectedIndex() == 1) refreshPreview();
    }

    // ================================================================ 接続先

    public ConnectionInfo getActiveConnection() { return activeConnection; }

    public ConnectionFactory getConnectionFactory() { return factory; }

    public AppPaths getPaths() { return paths; }

    private void refreshConnections() {
        String keep = activeConnection != null ? activeConnection.getName() : store.getLastSelected();
        DefaultComboBoxModel<ConnectionInfo> model = new DefaultComboBoxModel<>();
        for (ConnectionInfo c : store.getConnections()) model.addElement(c);
        connectionCombo.setModel(model);
        ConnectionInfo sel = store.find(keep);
        if (sel == null && model.getSize() > 0) sel = model.getElementAt(0);
        connectionCombo.setSelectedItem(sel);
        onConnectionSelected();
    }

    private void onConnectionSelected() {
        ConnectionInfo prev = activeConnection;
        activeConnection = (ConnectionInfo) connectionCombo.getSelectedItem();
        if (activeConnection == null) return;
        if (!activeConnection.getName().equals(store.getLastSelected())) {
            store.setLastSelected(activeConnection.getName());
            try {
                store.save();
            } catch (IOException ex) {
                AppLog.error("接続先の保存に失敗しました", ex);
            }
        }
        // 接続先を切り替えたら方言も合わせる（Oracle / H2 の Oracle 互換モード → Oracle）
        if (prev == null || !prev.getName().equals(activeConnection.getName())) {
            String url = activeConnection.getUrl() == null ? "" : activeConnection.getUrl().toLowerCase();
            dialectCombo.setSelectedItem(url.startsWith("jdbc:oracle") || url.contains("mode=oracle") ? Dialect.ORACLE : Dialect.H2);
        }
    }

    private void openConnectionDialog() {
        ConnectionDialog dlg = new ConnectionDialog(this, store, factory);
        dlg.setVisible(true);
        refreshConnections();
    }

    private void testActiveConnection() {
        ConnectionInfo info = activeConnection;
        if (info == null) {
            JOptionPane.showMessageDialog(this, "接続先が選択されていません");
            return;
        }
        status.setText("接続テスト中: " + info.getName());
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return factory.test(info);
            }

            @Override
            protected void done() {
                try {
                    String v = get();
                    AppLog.info("接続成功: " + info.getName() + " (" + v + ")");
                    status.setText("接続成功: " + info.getName() + "（" + v + "）");
                } catch (Exception ex) {
                    AppLog.error("接続失敗: " + info.getName() + " - " + rootMessage(ex), null);
                    JOptionPane.showMessageDialog(MainFrame.this, "接続に失敗しました:\n" + rootMessage(ex),
                            "接続テスト", JOptionPane.ERROR_MESSAGE);
                    status.setText("接続失敗: " + info.getName());
                }
            }
        }.execute();
    }

    private void openFolder(Path dir) {
        try {
            Files.createDirectories(dir);
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            AppLog.warn("フォルダを開けません: " + dir + "（" + e.getMessage() + "）");
            JOptionPane.showMessageDialog(this, dir.toString(), "フォルダ", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void showSyntaxHelp() {
        String text = String.join("\n",
                "erDiagram",
                "    CUSTOMER[\"顧客 (CUSTOMER)\"] {            ← エンティティ（[ ] は表示名 → COMMENT ON TABLE）",
                "        int customer_id PK \"顧客ID\"            ← 型 名前 キー \"コメント\"",
                "        string(100) name \"氏名 [NOT NULL]\"     ← コメント末尾のタグ",
                "        varchar2(200) email UK",
                "    }",
                "    ORDERS {",
                "        int order_id PK \"[IDENTITY]\"",
                "        int customer_id FK",
                "    }",
                "    CUSTOMER ||--o{ ORDERS : \"注文する\"       ← リレーション（1 側が参照先）",
                "",
                "キー: PK / FK / UK（複数は PK, FK）",
                "多重度: |o（0 か 1）  ||（1）  }o（0 以上）  }|（1 以上）　※右側は o| || o{ |{",
                "線: --（識別）  ..（非識別）",
                "タグ: [NOT NULL]  [DEFAULT 式]  [IDENTITY]  [IDENTITY ALWAYS]  [IDENTITY ON NULL]  [UNIQUE]",
                "型: string(n) int bigint decimal(p,s) date datetime timestamptz bool blob clob uuid json ...",
                "    DB 固有の型（varchar2(50)、number(10,2) など）もそのまま書けます。空白は _ で（varchar2(50_char)）。",
                "",
                "FK 列: リレーションの 1 側の主キーと同じ名前、または <親テーブル>_<主キー> の列を FK にします。",
                "",
                "表示名: [\"顧客\"] でも [\"顧客 (CUSTOMER)\"] でも可。括弧内がエンティティ名と同じなら、",
                "        COMMENT ON TABLE には論理名「顧客」だけを出力します（DDL→ER図 では既定で「論理名 (物理名)」を出力）。");
        JTextArea ta = new JTextArea(text);
        ta.setEditable(false);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JOptionPane.showMessageDialog(this, new JScrollPane(ta), "Mermaid erDiagram の書き方", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showAbout() {
        String version = MainFrame.class.getPackage().getImplementationVersion();
        JOptionPane.showMessageDialog(this, String.join("\n",
                APP_NAME + (version != null ? " " + version : ""),
                "Mermaid の ER 図（erDiagram）と DDL（H2 / Oracle）の相互変換ツール",
                "",
                "ER 図の描画: Mermaid " + MermaidPreview.MERMAID_VERSION + "（MIT License）",
                "エディタ: RSyntaxTextArea（BSD 3-Clause License）",
                "設定フォルダ: " + paths.configDir(),
                "Java " + System.getProperty("java.version")), APP_NAME + " について", JOptionPane.INFORMATION_MESSAGE);
    }

    // ================================================================ オプション

    Dialect dialect() { return (Dialect) dialectCombo.getSelectedItem(); }

    public void setDialect(Dialect d) { dialectCombo.setSelectedItem(d); }

    DdlOptions ddlOptions() {
        DdlOptions o = new DdlOptions();
        o.setDialect(dialect());
        o.setForeignKeysAsAlter(fkAlter.isSelected());
        o.setIncludeDrop(drop.isSelected());
        o.setIncludeComments(comments.isSelected());
        o.setOracleMaxIdentifierBytes(oracleLimit.getSelectedIndex() == 1 ? Identifiers.ORACLE_LEGACY_MAX_BYTES : Identifiers.ORACLE_MAX_BYTES);
        return o;
    }

    private ErToSchemaOptions schemaOptions() {
        ErToSchemaOptions o = new ErToSchemaOptions();
        o.setDialect(dialect());
        o.setUpperCaseNames(upper.isSelected());
        o.setIdentityForIntegerPk(identityPk.isSelected());
        return o;
    }

    private SchemaToErOptions erOptions() {
        SchemaToErOptions o = new SchemaToErOptions();
        o.setGenericTypes(genericTypes.isSelected());
        o.setConstraintTags(tags.isSelected());
        o.setDistinguishIdentifying(distinguish.isSelected());
        o.setPhysicalNameInAlias(physicalNames.isSelected());
        return o;
    }

    // ================================================================ 変換

    /** ER 図 → DDL。成功したら true */
    public boolean mermaidToDdl() {
        clearMessages();
        ErDdlService.Result r = ErDdlService.mermaidToDdl(mermaidArea.getText(), schemaOptions(), ddlOptions());
        r.getErrors().forEach(m -> addMessage(m, true, mermaidArea));
        r.getWarnings().forEach(m -> addMessage(m, false, mermaidArea));
        markErrors(mermaidArea, r.getErrors());
        if (!r.isOk()) {
            status.setText("ER 図にエラーが " + r.getErrors().size() + " 件あります（DDL は更新していません）");
            jumpTo(messages.isEmpty() ? null : messages.get(0));
            return false;
        }
        ddlArea.setText(r.getText());
        ddlArea.setCaretPosition(0);
        status.setText(dialect() + " の DDL を生成しました（" + r.getModel().getTables().size() + " テーブル、注意 " + r.getWarnings().size() + " 件）");
        if (leftTabs.getSelectedIndex() == 1) refreshPreview();
        return true;
    }

    /** DDL → ER 図。成功したら true */
    public boolean ddlToMermaid() {
        clearMessages();
        ErDdlService.Result r = ErDdlService.ddlToMermaid(ddlArea.getText(), dialect(), erOptions());
        r.getErrors().forEach(m -> addMessage(m, true, ddlArea));
        r.getWarnings().forEach(m -> addMessage(m, false, ddlArea));
        List<String> lined = new ArrayList<>(r.getErrors());
        lined.addAll(r.getWarnings());
        markErrors(ddlArea, lined);
        if (!r.isOk()) {
            status.setText("DDL を解析できません");
            return false;
        }
        setMermaid(r.getText());
        status.setText("ER 図を生成しました（" + r.getModel().getTables().size() + " テーブル、リレーション "
                + r.getDiagram().getRelationships().size() + " 件）");
        return true;
    }

    private void setMermaid(String text) {
        mermaidArea.setText(text);
        mermaidArea.setCaretPosition(0);
        Editors.clearErrors(mermaidArea);
        refreshPreview();
    }

    public String getDdlText() { return ddlArea.getText(); }

    /** エディタ上で最初に現れる word のトークン種別（スモークテスト用。無ければ -1） */
    public int tokenTypeAt(boolean mermaid, String word) {
        RSyntaxTextArea area = mermaid ? mermaidArea : ddlArea;
        int i = area.getText().indexOf(word);
        if (i < 0) return -1;
        i += word.length() - word.stripLeading().length(); // 前の空白は検索の手がかりにだけ使う
        org.fife.ui.rsyntaxtextarea.Token t = area.modelToToken(i);
        return t == null ? -1 : t.getType();
    }

    /** 左側を Mermaid（テキスト）タブにする（スモークテスト用） */
    public void showMermaidTab() { leftTabs.setSelectedIndex(0); }

    public String getMermaidText() { return mermaidArea.getText(); }

    public MermaidPreview getPreview() { return preview; }

    public void showPreviewTab() { leftTabs.setSelectedIndex(1); }

    public void refreshPreview() {
        String code = mermaidArea.getText();
        if (code.trim().isEmpty()) return;
        previewStatus.setText("描画中...");
        previewStatus.setForeground(Color.DARK_GRAY);
        preview.render(code);
    }

    private void zoom(double f) {
        zoom = Math.max(0.3, Math.min(4.0, zoom * f));
        preview.setZoom(zoom);
    }

    private void saveSvg() {
        try {
            String svg = preview.svg();
            if (svg == null) {
                JOptionPane.showMessageDialog(this, "先にプレビューを表示してください");
                return;
            }
            JFileChooser fc = new JFileChooser(lastDir);
            fc.setFileFilter(new FileNameExtensionFilter("SVG 画像", "svg"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            lastDir = fc.getCurrentDirectory();
            Path p = withExt(fc.getSelectedFile().toPath(), ".svg");
            Files.writeString(p, svg, StandardCharsets.UTF_8);
            status.setText("SVG を保存しました: " + p);
        } catch (Exception ex) {
            showError("SVG を保存できません", ex);
        }
    }

    // ================================================================ DB から読込

    private void loadFromDb() {
        ConnectionInfo ci = activeConnection;
        if (ci == null) {
            JOptionPane.showMessageDialog(this, "ツールバーの「接続先」で接続先を選択してください（「管理...」で追加できます）", "DBから読込", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JTextField schemaField = new JTextField(16);
        DefaultListModel<MetadataReader.TableEntry> model = new DefaultListModel<>();
        JList<MetadataReader.TableEntry> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setVisibleRowCount(14);
        JLabel info = new JLabel(" ");
        Dialect[] dbDialect = new Dialect[1];
        Runnable load = () -> {
            info.setText("テーブル一覧を取得中...");
            String input = schemaField.getText().trim();
            new SwingWorker<List<MetadataReader.TableEntry>, Void>() {
                String schema;

                @Override
                protected List<MetadataReader.TableEntry> doInBackground() throws Exception {
                    try (Connection con = factory.open(ci)) {
                        MetadataReader r = MetadataReader.forConnection(con);
                        dbDialect[0] = r.getDialect();
                        schema = input.isEmpty() ? r.defaultSchema(con) : input;
                        return r.listTables(con, schema);
                    }
                }

                @Override
                protected void done() {
                    try {
                        List<MetadataReader.TableEntry> rows = get();
                        schemaField.setText(schema);
                        model.clear();
                        rows.stream().filter(t -> !"VIEW".equals(t.getType())).forEach(model::addElement);
                        list.setSelectionInterval(0, model.size() - 1);
                        info.setText(ci.getName() + "（" + dbDialect[0] + "）: " + model.size() + " テーブル（ビューは除外）");
                    } catch (Exception ex) {
                        info.setText("取得できません: " + firstLine(errorMessage(ex)));
                    }
                }
            }.execute();
        };
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("スキーマ"));
        top.add(schemaField);
        JButton reload = new JButton("一覧を取得");
        reload.addActionListener(e -> load.run());
        top.add(reload);
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(top, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(420, 300));
        p.add(sp, BorderLayout.CENTER);
        p.add(info, BorderLayout.SOUTH);
        load.run();
        int ans = JOptionPane.showConfirmDialog(this, p, "DBから読込（テーブルを選択。Ctrl / Shift で複数選択）",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ans != JOptionPane.OK_OPTION) return;
        List<MetadataReader.TableEntry> sel = list.getSelectedValuesList();
        if (sel.isEmpty()) return;
        loadTables(ci, schemaField.getText().trim(), sel.stream().map(MetadataReader.TableEntry::getName).toList());
    }

    /** 指定テーブルを読み込んで ER 図と DDL にする（スモークテストからも使う） */
    public void loadTables(ConnectionInfo ci, String schema, List<String> tables) {
        status.setText(tables.size() + " テーブルを読み込み中...");
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                try (Connection con = factory.open(ci)) {
                    MetadataReader r = MetadataReader.forConnection(con);
                    String s = schema == null || schema.isEmpty() ? r.defaultSchema(con) : schema;
                    return new Object[]{r.getDialect(), ErDdlService.dbToMermaid(con, s, tables, erOptions())};
                }
            }

            @Override
            protected void done() {
                try {
                    Object[] res = get();
                    clearMessages();
                    dialectCombo.setSelectedItem(res[0]);
                    ErDdlService.Result r = (ErDdlService.Result) res[1];
                    r.getWarnings().forEach(m -> addMessage(m, false, mermaidArea));
                    setMermaid(r.getText());
                    ddlArea.setText(DdlWriter.write(r.getModel(), ddlOptions()).getSql());
                    ddlArea.setCaretPosition(0);
                    status.setText(ci.getName() + " から " + tables.size() + " テーブルを読み込みました（ER 図と DDL）");
                } catch (Exception ex) {
                    showError("テーブル定義を読み込めません", ex);
                }
            }
        }.execute();
    }

    // ================================================================ DDL 実行

    private void executeDdl() {
        ConnectionInfo ci = activeConnection;
        if (ci == null) {
            JOptionPane.showMessageDialog(this, "ツールバーの「接続先」で接続先を選択してください（「管理...」で追加できます）", "DDLを実行", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String ddl = ddlArea.getText();
        List<SqlScript.Statement> sts = SqlScript.splitWithLines(ddl);
        if (sts.isEmpty()) {
            JOptionPane.showMessageDialog(this, "DDL が空です");
            return;
        }
        long drops = sts.stream().filter(s -> "DROP".equals(SqlScript.firstWord(s.getSql()))).count();
        StringBuilder msg = new StringBuilder();
        msg.append("接続先「").append(ci.getName()).append("」で ").append(sts.size()).append(" 文の DDL を実行します。\n");
        msg.append("方言の選択: ").append(dialect()).append("\n\n");
        if (drops > 0) msg.append("⚠ DROP 文が ").append(drops).append(" 件あります。既存のテーブルとデータは削除されます。\n");
        msg.append("DDL は自動コミットされ、元に戻せません。実行しますか？");
        int ans = JOptionPane.showConfirmDialog(this, msg.toString(), "DDLを実行", JOptionPane.OK_CANCEL_OPTION,
                drops > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE);
        if (ans != JOptionPane.OK_OPTION) return;
        status.setText("DDL を実行中...");
        new SwingWorker<ErDdlService.ExecReport, Void>() {
            @Override
            protected ErDdlService.ExecReport doInBackground() throws Exception {
                try (Connection con = factory.open(ci)) {
                    return ErDdlService.executeDdl(con, ddl);
                }
            }

            @Override
            protected void done() {
                try {
                    ErDdlService.ExecReport rep = get();
                    clearMessages();
                    if (rep.isOk()) {
                        status.setText(rep.summary());
                        AppLog.info("DDL を実行しました（" + ci.getName() + "）: " + rep.summary());
                        JOptionPane.showMessageDialog(MainFrame.this, rep.summary(), "DDLを実行", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        addMessage(rep.getFailedLine() + " 行目: " + rep.getFailure(), true, ddlArea);
                        markErrors(ddlArea, List.of(rep.getFailedLine() + " 行目"));
                        status.setText(rep.summary());
                        jumpTo(messages.get(0));
                    }
                } catch (Exception ex) {
                    showError("DDL を実行できません", ex);
                }
            }
        }.execute();
    }

    // ================================================================ ファイル

    private void openFile(JTextArea area, boolean mermaid) {
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setFileFilter(mermaid ? new FileNameExtensionFilter("Mermaid（.mmd / .mermaid / .md / .txt）", "mmd", "mermaid", "md", "txt")
                : new FileNameExtensionFilter("SQL / DDL（.sql / .ddl / .txt）", "sql", "ddl", "txt"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        lastDir = fc.getCurrentDirectory();
        try {
            String text = readText(fc.getSelectedFile().toPath());
            if (mermaid) text = extractMermaidBlock(text);
            area.setText(text);
            area.setCaretPosition(0);
            status.setText("読み込みました: " + fc.getSelectedFile());
            if (mermaid && leftTabs.getSelectedIndex() == 1) refreshPreview();
        } catch (IOException ex) {
            showError("ファイルを読み込めません", ex);
        }
    }

    private void saveFile(JTextArea area, boolean mermaid) {
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setFileFilter(mermaid ? new FileNameExtensionFilter("Mermaid（.mmd）", "mmd", "mermaid", "md")
                : new FileNameExtensionFilter("SQL（.sql）", "sql", "ddl"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            lastDir = fc.getCurrentDirectory();
        Path p = withExt(fc.getSelectedFile().toPath(), mermaid ? ".mmd" : ".sql");
        if (Files.exists(p) && JOptionPane.showConfirmDialog(this, p.getFileName() + " を上書きしますか？", "保存",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            Files.writeString(p, area.getText(), StandardCharsets.UTF_8);
            status.setText("保存しました: " + p);
        } catch (IOException ex) {
            showError("保存できません", ex);
        }
    }

    private static Path withExt(Path p, String ext) {
        String n = p.getFileName().toString();
        return n.contains(".") ? p : p.resolveSibling(n + ext);
    }

    /** UTF-8（BOM 可）で読めなければ Shift_JIS（MS932） */
    static String readText(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
            return s.startsWith("\uFEFF") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("MS932"));
        }
    }

    /** Markdown の ```mermaid ... ``` ブロックがあれば中身だけ取り出す */
    static String extractMermaidBlock(String text) {
        Matcher m = Pattern.compile("```\\s*mermaid\\s*\\n(.*?)```", Pattern.DOTALL).matcher(text);
        return m.find() ? m.group(1) : text;
    }

    // ================================================================ メッセージ

    private void clearMessages() {
        messages.clear();
        Editors.clearErrors(mermaidArea);
        Editors.clearErrors(ddlArea);
    }

    private void addMessage(String text, boolean error, JTextArea target) {
        messages.addElement(new Msg(text, error, target));
    }

    public List<String> messageTexts() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) out.add(messages.get(i).toString());
        return out;
    }

    private void markErrors(RSyntaxTextArea area, List<String> lines) {
        Set<Integer> set = new HashSet<>();
        for (String t : lines) {
            Matcher m = LINE_REF.matcher(t);
            if (m.find()) set.add(Integer.parseInt(m.group(1)));
        }
        Editors.markErrorLines(area, set);
    }

    private void jumpTo(Msg m) {
        if (m == null || m.line <= 0) return;
        JTextArea a = m.target;
        if (a == mermaidArea) leftTabs.setSelectedIndex(0);
        try {
            int line = Math.min(m.line, a.getLineCount());
            a.setCaretPosition(a.getLineStartOffset(line - 1));
            a.requestFocusInWindow();
        } catch (BadLocationException ignore) {
            // 無視
        }
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        String t = s.strip();
        int i = t.indexOf('\n');
        return i < 0 ? t : t.substring(0, i);
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    /**
     * 表示用のエラーメッセージ。SwingWorker などの包み（ExecutionException）だけを外し、
     * どのテーブル・どの問い合わせで失敗したかを含む一番外側のメッセージを使う。
     */
    static String errorMessage(Throwable t) {
        while ((t instanceof java.util.concurrent.ExecutionException
                || t instanceof java.lang.reflect.InvocationTargetException
                || t instanceof java.lang.reflect.UndeclaredThrowableException) && t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage();
        if (m == null || m.isBlank()) return rootMessage(t);
        // 原因側にしか ORA- コードが無い場合は付け足す
        String root = rootMessage(t);
        return m.contains(root) ? m : m + "\n原因: " + root;
    }

    private void showError(String title, Exception ex) {
        AppLog.error(title, ex);
        String msg = errorMessage(ex);
        status.setText(title + ": " + firstLine(msg));
        JTextArea ta = new JTextArea(title + "\n\n" + msg);
        ta.setEditable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(false);
        ta.setOpaque(false);
        ta.setFont(UIManagerFont());
        JScrollPane sp = new JScrollPane(ta);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setPreferredSize(new Dimension(560, Math.min(260, 60 + 18 * (int) msg.chars().filter(c -> c == '\n').count() + 18 * (msg.length() / 60))));
        JOptionPane.showMessageDialog(this, sp, "エラー", JOptionPane.ERROR_MESSAGE);
    }

    private static Font UIManagerFont() {
        Font f = javax.swing.UIManager.getFont("Label.font");
        return f != null ? f : new Font(Font.DIALOG, Font.PLAIN, 13);
    }
}
