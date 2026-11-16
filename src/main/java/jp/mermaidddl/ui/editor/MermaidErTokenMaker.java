package jp.mermaidddl.ui.editor;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMap;

/**
 * Mermaid erDiagram 用の簡易ハイライト。1 行単位で解析する（複数行にまたがる構文は無い）。
 * <ul>
 * <li>erDiagram などのキーワード → 予約語</li>
 * <li>PK / FK / UK → 予約語 2</li>
 * <li>多重度つきの線（||--o{ など） → 演算子</li>
 * <li>"..." → 文字列。文字列内の [NOT NULL] などのタグ → 注釈</li>
 * <li>%% → 行末までコメント</li>
 * <li>string / varchar2(50) などの既知の型 → データ型</li>
 * </ul>
 */
public class MermaidErTokenMaker extends AbstractTokenMaker {

    static final Set<String> KEYWORDS = Set.of("erdiagram", "title", "direction", "accTitle", "accDescr",
            "classDef", "class", "style").stream().map(s -> s.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    static final Set<String> KEYS = Set.of("PK", "FK", "UK");
    static final Set<String> TYPES = Set.of("string", "int", "integer", "bigint", "smallint", "tinyint", "long",
            "decimal", "numeric", "number", "float", "double", "real", "money", "date", "time", "datetime",
            "timestamp", "timestamptz", "bool", "boolean", "bit", "blob", "clob", "nclob", "text", "char", "nchar",
            "varchar", "varchar2", "nvarchar", "nvarchar2", "uuid", "json", "binary", "varbinary", "raw", "bytes",
            "interval", "binary_float", "binary_double");

    /** 左右の多重度と線（例: ||--o{ 、 }|..|{ ） */
    private static final Pattern CARDINALITY =
            Pattern.compile("(\\|o|\\|\\||\\}o|\\}\\||o\\||o\\{|\\|\\{)(--|\\.\\.)(o\\||\\|\\||o\\{|\\|\\{|\\|o|\\}o|\\}\\|)");
    private static final Pattern TAG = Pattern.compile("\\[[^\\[\\]\"]*\\]");

    @Override
    public TokenMap getWordsToHighlight() {
        TokenMap m = new TokenMap(true);
        KEYWORDS.forEach(w -> m.put(w, Token.RESERVED_WORD));
        KEYS.forEach(w -> m.put(w, Token.RESERVED_WORD_2));
        TYPES.forEach(w -> m.put(w, Token.DATA_TYPE));
        return m;
    }

    @Override
    public String[] getLineCommentStartAndEnd(int languageIndex) {
        return new String[]{"%%", null};
    }

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        resetTokenList();
        char[] a = text.array;
        int off = text.offset;
        int end = off + text.count;
        int delta = startOffset - off; // 文書上の位置 = 配列上の位置 + delta
        String line = new String(a, off, text.count);
        int i = off;
        while (i < end) {
            char c = a[i];
            int rel = i - off;
            if (Character.isWhitespace(c)) {
                int j = i;
                while (j < end && Character.isWhitespace(a[j])) j++;
                addToken(a, i, j - 1, Token.WHITESPACE, i + delta);
                i = j;
            } else if (c == '%' && i + 1 < end && a[i + 1] == '%') {
                addToken(a, i, end - 1, Token.COMMENT_EOL, i + delta);
                i = end;
            } else if (c == '"') {
                int j = i + 1;
                while (j < end && a[j] != '"') j++;
                int close = Math.min(j, end - 1); // 閉じ引用符（無ければ行末まで）
                addString(a, i, close, delta);
                i = close + 1;
            } else if (c == '`') {
                int j = i + 1;
                while (j < end && a[j] != '`') j++;
                int close = Math.min(j, end - 1);
                addToken(a, i, close, Token.VARIABLE, i + delta);
                i = close + 1;
            } else if ("|}o.-".indexOf(c) >= 0 && matches(CARDINALITY, line, rel)) {
                int len = matchLength(CARDINALITY, line, rel);
                addToken(a, i, i + len - 1, Token.OPERATOR, i + delta);
                i += len;
            } else if (isWordStart(c)) {
                int j = i + 1;
                while (j < end && isWordPart(a, j, end)) j++;
                // varchar2(50) / decimal(10,2) / varchar2(50_char) のような型は括弧まで含める
                String word = new String(a, i, j - i);
                int type = wordType(word);
                if (type == Token.DATA_TYPE && j < end && a[j] == '(') {
                    int k = j;
                    while (k < end && a[k] != ')' && !Character.isWhitespace(a[k])) k++;
                    if (k < end && a[k] == ')') j = k + 1;
                }
                addToken(a, i, j - 1, type, i + delta);
                i = j;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < end && Character.isDigit(a[j])) j++;
                addToken(a, i, j - 1, Token.LITERAL_NUMBER_DECIMAL_INT, i + delta);
                i = j;
            } else if ("{}[]()".indexOf(c) >= 0) {
                addToken(a, i, i, Token.SEPARATOR, i + delta);
                i++;
            } else if (c == ':' || c == ',') {
                addToken(a, i, i, Token.OPERATOR, i + delta);
                i++;
            } else {
                addToken(a, i, i, Token.IDENTIFIER, i + delta);
                i++;
            }
        }
        addNullToken();
        return firstToken;
    }

    /** 文字列を、タグ（[...]）とそれ以外に分けて追加する */
    private void addString(char[] a, int from, int to, int delta) {
        String s = new String(a, from, to - from + 1);
        Matcher m = TAG.matcher(s);
        int pos = 0;
        while (m.find()) {
            if (m.start() > pos) addToken(a, from + pos, from + m.start() - 1, Token.LITERAL_STRING_DOUBLE_QUOTE, from + pos + delta);
            addToken(a, from + m.start(), from + m.end() - 1, Token.ANNOTATION, from + m.start() + delta);
            pos = m.end();
        }
        if (pos < s.length()) addToken(a, from + pos, to, Token.LITERAL_STRING_DOUBLE_QUOTE, from + pos + delta);
    }

    private int wordType(String word) {
        if (KEYS.contains(word)) return Token.RESERVED_WORD_2;
        String lw = word.toLowerCase(Locale.ROOT);
        if (KEYWORDS.contains(lw)) return Token.RESERVED_WORD;
        if (TYPES.contains(lw)) return Token.DATA_TYPE;
        return Token.IDENTIFIER;
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    /** 名前の続き（英数字・_・単語中の -） */
    private static boolean isWordPart(char[] a, int i, int end) {
        char c = a[i];
        if (Character.isLetterOrDigit(c) || c == '_') return true;
        return c == '-' && i + 1 < end && Character.isLetterOrDigit(a[i + 1]);
    }

    private static boolean matches(Pattern p, String s, int at) {
        Matcher m = p.matcher(s);
        m.region(at, s.length());
        return m.lookingAt();
    }

    private static int matchLength(Pattern p, String s, int at) {
        Matcher m = p.matcher(s);
        m.region(at, s.length());
        return m.lookingAt() ? m.end() - at : 1;
    }
}
