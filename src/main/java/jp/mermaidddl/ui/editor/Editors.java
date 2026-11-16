package jp.mermaidddl.ui.editor;

import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.swing.Icon;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import jp.mermaidddl.AppLog;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxUtilities;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;

/** RSyntaxTextArea を使ったエディタの生成・テーマ・エラー行表示 */
public final class Editors {

    public static final String SYNTAX_SQL = "text/x-mermaid-ddl-sql";
    public static final String SYNTAX_MERMAID = "text/x-mermaid-er";

    /** 日本語も表示できる等幅フォントの候補（先頭から順に、存在するものを使う） */
    static final String[] FONT_CANDIDATES = {
            "BIZ UDGothic", "BIZ UDゴシック", "MS Gothic", "ＭＳ ゴシック", "Osaka-Mono", "Noto Sans Mono CJK JP",
            "Source Han Code JP", "VL Gothic", "IPAGothic", "TakaoGothic"};

    private static Font cachedFont;

    static {
        AbstractTokenMakerFactory f = (AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance();
        f.putMapping(SYNTAX_SQL, OracleH2SqlTokenMaker.class.getName(), Editors.class.getClassLoader());
        f.putMapping(SYNTAX_MERMAID, MermaidErTokenMaker.class.getName(), Editors.class.getClassLoader());
    }

    private Editors() {}

    public static RSyntaxTextArea create(String syntaxStyle) {
        RSyntaxTextArea a = new RSyntaxTextArea();
        a.setSyntaxEditingStyle(syntaxStyle);
        a.setTabSize(4);
        a.setCodeFoldingEnabled(false);
        a.setAntiAliasingEnabled(true);
        a.setBracketMatchingEnabled(true);
        a.setMarkOccurrences(false);
        applyTheme(a);
        return a;
    }

    public static RTextScrollPane scroll(RSyntaxTextArea a) {
        RTextScrollPane sp = new RTextScrollPane(a, true);
        sp.setIconRowHeaderEnabled(true);
        Gutter g = sp.getGutter();
        g.setLineNumberFont(a.getFont());
        return sp;
    }

    /** FlatLaf の明暗に合わせたテーマを適用する（フォントは日本語対応のものに戻す） */
    public static void applyTheme(RSyntaxTextArea a) {
        boolean dark = FlatLaf.isLafDark();
        String name = dark ? "dark.xml" : "idea.xml";
        try (InputStream in = RSyntaxTextArea.class.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/" + name)) {
            if (in != null) Theme.load(in, editorFont()).apply(a);
        } catch (IOException e) {
            AppLog.warn("エディタのテーマを読み込めません: " + name + " " + e.getMessage());
        }
        a.setFont(editorFont());
        tuneColors(a, dark);
        if (!dark) a.setCurrentLineHighlightColor(new Color(0xF2F6FC));
        Color sel = UIManager.getColor("TextArea.selectionBackground");
        if (sel != null && !dark) a.setSelectionColor(sel);
    }

    /** 型・キー・多重度・タグを見分けやすくする（テーマの色を一部上書き） */
    private static void tuneColors(RSyntaxTextArea a, boolean dark) {
        SyntaxScheme sc = a.getSyntaxScheme();
        Font plain = editorFont();
        Font bold = plain.deriveFont(Font.BOLD);
        Font italic = plain.deriveFont(Font.ITALIC);
        style(sc, Token.DATA_TYPE, dark ? 0x4EC9B0 : 0x00796B, plain);          // 型: 青緑
        style(sc, Token.RESERVED_WORD_2, dark ? 0xC586C0 : 0x8E24AA, bold);      // PK / FK / UK: 紫
        style(sc, Token.OPERATOR, dark ? 0xF07178 : 0xC62828, bold);             // 多重度つきの線・コロン: 赤
        style(sc, Token.ANNOTATION, dark ? 0xD7BA7D : 0x9E6A00, italic);         // [NOT NULL] などのタグ: 黄土
        style(sc, Token.FUNCTION, dark ? 0xDCDCAA : 0x795E26, plain);            // 関数
        style(sc, Token.VARIABLE, dark ? 0x9CDCFE : 0x1565C0, plain);            // `名前`
        style(sc, Token.LITERAL_STRING_DOUBLE_QUOTE, dark ? 0xCE9178 : 0x2E7D32, plain);
        style(sc, Token.LITERAL_CHAR, dark ? 0xCE9178 : 0x2E7D32, plain);
        style(sc, Token.SEPARATOR, dark ? 0xD4D4D4 : 0x555555, plain);
        a.setSyntaxScheme(sc);
    }

    private static void style(SyntaxScheme sc, int type, int rgb, Font font) {
        Style st = sc.getStyle(type);
        st.foreground = new Color(rgb);
        st.font = font;
    }

    /** 日本語（全角）も表示できる等幅フォント。見つからなければ論理フォント Monospaced（自動でフォールバックされる） */
    public static synchronized Font editorFont() {
        if (cachedFont == null) cachedFont = pickFont(13);
        return cachedFont;
    }

    static Font pickFont(int size) {
        Set<String> installed = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String n : FONT_CANDIDATES) {
            if (!installed.contains(n)) continue;
            Font f = new Font(n, Font.PLAIN, size);
            if (f.canDisplayUpTo("ABCabc012あ漢字ア") == -1) return f;
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    /** エラー行の色（背景） */
    public static Color errorLineColor() {
        return FlatLaf.isLafDark() ? new Color(0x5C2B2B) : new Color(0xFFE0E0);
    }

    /** エラー行を赤く表示する（行は 1 始まり）。行番号の横に赤い印も付ける */
    public static void markErrorLines(RSyntaxTextArea a, Set<Integer> lines) {
        clearErrors(a);
        Gutter g = RSyntaxUtilities.getGutter(a);
        for (int line : lines) {
            if (line < 1 || line > a.getLineCount()) continue;
            try {
                a.addLineHighlight(line - 1, errorLineColor());
                if (g != null) g.addLineTrackingIcon(line - 1, ERROR_ICON, line + " 行目にエラー・注意があります");
            } catch (BadLocationException ignore) {
                // 無視
            }
        }
    }

    public static void clearErrors(RSyntaxTextArea a) {
        a.removeAllLineHighlights();
        Gutter g = RSyntaxUtilities.getGutter(a);
        if (g != null) g.removeAllTrackingIcons();
    }

    /** 行番号の横に出す赤い丸 */
    static final Icon ERROR_ICON = new Icon() {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0xE0, 0x30, 0x30));
            g2.fillOval(x + 2, y + 2, 9, 9);
            g2.dispose();
        }
        @Override public int getIconWidth() { return 13; }
        @Override public int getIconHeight() { return 13; }
    };
}
