package jp.mermaidddl.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import jp.mermaidddl.AppLog;
import jp.mermaidddl.config.ConnectionFactory;
import jp.mermaidddl.config.ConnectionInfo;
import jp.mermaidddl.config.ConnectionStore;

/** 接続先の一覧・追加・編集・削除・接続テストを行うダイアログ。 */
public class ConnectionDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    /** URL テンプレート: 表示名, URL, ドライバクラス */
    private static final String[][] TEMPLATES = {
        {"（テンプレートから入力）", "", ""},
        {"H2（ファイル）", "jdbc:h2:~/mydb", "org.h2.Driver"},
        {"H2（メモリ）", "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "org.h2.Driver"},
        {"H2（Oracle 互換モード）", "jdbc:h2:~/mydb_ora;MODE=Oracle", "org.h2.Driver"},
        {"H2（サーバー接続）", "jdbc:h2:tcp://localhost:9092/~/mydb", "org.h2.Driver"},
        {"Oracle（サービス名）", "jdbc:oracle:thin:@//localhost:1521/ORCLPDB1", "oracle.jdbc.OracleDriver"},
        {"Oracle（SID）", "jdbc:oracle:thin:@localhost:1521:ORCL", "oracle.jdbc.OracleDriver"},
        {"Oracle（TNS 別名 + TNS_ADMIN）", "jdbc:oracle:thin:@mydb_high?TNS_ADMIN=C:/oracle/wallet", "oracle.jdbc.OracleDriver"},
    };

    private final transient ConnectionStore store;
    private final transient ConnectionFactory factory;
    private final transient List<ConnectionInfo> working = new ArrayList<>();
    private final DefaultListModel<ConnectionInfo> listModel = new DefaultListModel<>();
    private final JList<ConnectionInfo> list = new JList<>(listModel);
    private final JTextField nameField = new JTextField(30);
    private final JTextField urlField = new JTextField(30);
    private final JTextField userField = new JTextField(30);
    private final JPasswordField passwordField = new JPasswordField(30);
    private final JTextField driverField = new JTextField(30);
    private final JComboBox<String> templateCombo = new JComboBox<>();
    private final JLabel info = new JLabel(" ");
    private boolean dirty;
    /** フォームに表示中の接続先の位置（選択切替時に編集内容を自動反映するため） */
    private int shownIndex = -1;

    public ConnectionDialog(JFrame owner, ConnectionStore store, ConnectionFactory factory) {
        super(owner, "接続先の管理", true);
        this.store = store;
        this.factory = factory;
        for (ConnectionInfo c : store.getConnections()) working.add(c.copy());

        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(createListPanel(), BorderLayout.WEST);
        add(createFormPanel(), BorderLayout.CENTER);
        add(createButtons(), BorderLayout.SOUTH);

        reloadList(0);
        setSize(820, 460);
        setLocationRelativeTo(owner);
    }

    private JPanel createListPanel() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                keepFormEdits();
                showSelected();
            }
        });
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(new JLabel("接続先一覧"), BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(220, 300));
        p.add(sp, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btns.add(button("新規", e -> addNew()));
        btns.add(button("複製", e -> duplicate()));
        btns.add(button("削除", e -> deleteSelected()));
        p.add(btns, BorderLayout.SOUTH);
        return p;
    }

    private JPanel createFormPanel() {
        for (String[] t : TEMPLATES) templateCombo.addItem(t[0]);
        templateCombo.addActionListener(e -> {
            int i = templateCombo.getSelectedIndex();
            if (i > 0) {
                urlField.setText(TEMPLATES[i][1]);
                driverField.setText(TEMPLATES[i][2]);
                templateCombo.setSelectedIndex(0);
            }
        });
        JPanel p = new JPanel(new GridBagLayout());
        int row = 0;
        addRow(p, row++, "名前", nameField);
        addRow(p, row++, "テンプレート", templateCombo);
        addRow(p, row++, "JDBC URL", urlField);
        addRow(p, row++, "ユーザー", userField);
        addRow(p, row++, "パスワード", passwordField);
        addRow(p, row++, "ドライバクラス（任意）", driverField);

        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        b.add(button("この内容を反映", e -> applyForm()));
        b.add(button("接続テスト", e -> testForm()));
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 1;
        gc.gridy = row++;
        gc.anchor = GridBagConstraints.WEST;
        p.add(b, gc);

        gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = row;
        gc.gridwidth = 2;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(8, 4, 4, 4);
        p.add(info, gc);

        gc = new GridBagConstraints();
        gc.gridy = row + 1;
        gc.weighty = 1;
        p.add(new JLabel(), gc);
        return p;
    }

    private static void addRow(JPanel p, int row, String label, JComponent field) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = row;
        gc.anchor = GridBagConstraints.EAST;
        gc.insets = new Insets(4, 4, 4, 8);
        p.add(new JLabel(label), gc);
        gc = new GridBagConstraints();
        gc.gridx = 1;
        gc.gridy = row;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(4, 0, 4, 4);
        p.add(field, gc);
    }

    private JPanel createButtons() {
        String path = store.getFile().toAbsolutePath().normalize().toString();
        JTextField pathField = new JTextField(path);
        pathField.setEditable(false);
        pathField.setCaretPosition(0);
        pathField.setToolTipText("接続先の保存ファイル（選択してコピーできます）");

        JPanel pathRow = new JPanel(new BorderLayout(6, 0));
        pathRow.add(new JLabel("保存ファイル"), BorderLayout.WEST);
        pathRow.add(pathField, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(button("保存して閉じる", e -> saveAndClose()));
        actions.add(button("キャンセル", e -> cancel()));

        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(pathRow, BorderLayout.CENTER);
        p.add(actions, BorderLayout.SOUTH);
        return p;
    }

    private static JButton button(String text, java.awt.event.ActionListener a) {
        JButton b = new JButton(text);
        b.addActionListener(a);
        return b;
    }

    /** 別の接続先を選ぶ前に、表示中フォームの編集内容を一時リストへ反映する。 */
    private void keepFormEdits() {
        if (shownIndex < 0 || shownIndex >= working.size()) return;
        ConnectionInfo v = formValue();
        if (!v.getName().isEmpty() && !working.get(shownIndex).equals(v)) {
            working.set(shownIndex, v);
            listModel.set(shownIndex, v);
            dirty = true;
        }
    }

    private void reloadList(int select) {
        shownIndex = -1;
        listModel.clear();
        for (ConnectionInfo c : working) listModel.addElement(c);
        if (!working.isEmpty()) list.setSelectedIndex(Math.max(0, Math.min(select, working.size() - 1)));
        else showSelected();
    }

    private void showSelected() {
        ConnectionInfo c = list.getSelectedValue();
        boolean has = c != null;
        nameField.setText(has ? c.getName() : "");
        urlField.setText(has ? c.getUrl() : "");
        userField.setText(has ? c.getUser() : "");
        passwordField.setText(has ? c.getPassword() : "");
        driverField.setText(has ? c.getDriverClass() : "");
        info.setText(" ");
        shownIndex = list.getSelectedIndex();
    }

    private ConnectionInfo formValue() {
        return new ConnectionInfo(nameField.getText().trim(), urlField.getText().trim(), userField.getText().trim(),
                new String(passwordField.getPassword()), driverField.getText().trim());
    }

    private void addNew() {
        working.add(new ConnectionInfo(uniqueName("新しい接続"), "", "", "", ""));
        dirty = true;
        reloadList(working.size() - 1);
        nameField.requestFocusInWindow();
    }

    private void duplicate() {
        ConnectionInfo c = list.getSelectedValue();
        if (c == null) return;
        ConnectionInfo d = c.copy();
        d.setName(uniqueName(c.getName() + " のコピー"));
        working.add(d);
        dirty = true;
        reloadList(working.size() - 1);
    }

    private void deleteSelected() {
        int i = list.getSelectedIndex();
        if (i < 0) return;
        if (JOptionPane.showConfirmDialog(this, "「" + working.get(i).getName() + "」を削除しますか？", "確認",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        working.remove(i);
        dirty = true;
        reloadList(i);
    }

    /** フォームの内容を選択中の接続先に反映する（ファイルへの保存は「保存して閉じる」で行う）。 */
    private boolean applyForm() {
        int i = list.getSelectedIndex();
        if (i < 0) return true;
        ConnectionInfo v = formValue();
        if (v.getName().isEmpty()) {
            info.setText("名前を入力してください");
            return false;
        }
        for (int j = 0; j < working.size(); j++) {
            if (j != i && working.get(j).getName().equals(v.getName())) {
                info.setText("同じ名前の接続先があります: " + v.getName());
                return false;
            }
        }
        if (!working.get(i).equals(v)) {
            working.set(i, v);
            dirty = true;
            reloadList(i);
        }
        info.setText("反映しました（保存は「保存して閉じる」）");
        return true;
    }

    private void testForm() {
        ConnectionInfo v = formValue();
        info.setText("接続テスト中...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return factory.test(v);
            }

            @Override
            protected void done() {
                try {
                    info.setText("接続成功: " + get());
                    AppLog.info("接続テスト成功: " + v.getName());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    info.setText("<html><font color='#c00'>接続失敗: " + escape(c.getMessage()) + "</font></html>");
                    AppLog.warn("接続テスト失敗: " + v.getName() + " - " + c.getMessage());
                }
            }
        }.execute();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void saveAndClose() {
        if (!applyForm()) return;
        store.setConnections(working);
        try {
            store.save();
            AppLog.info("接続先を保存しました（" + working.size() + " 件）");
            dispose();
        } catch (IOException e) {
            AppLog.error("接続先の保存に失敗しました", e);
            JOptionPane.showMessageDialog(this, "保存に失敗しました:\n" + e.getMessage(), "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void cancel() {
        int i = list.getSelectedIndex();
        boolean formChanged = i >= 0 && !working.get(i).equals(formValue());
        if ((dirty || formChanged) && JOptionPane.showConfirmDialog(this, "変更を破棄して閉じますか？", "確認",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        dispose();
    }

    private String uniqueName(String base) {
        String n = base;
        int k = 2;
        while (true) {
            final String candidate = n;
            if (working.stream().noneMatch(c -> c.getName().equals(candidate))) return n;
            n = base + " " + k++;
        }
    }
}
