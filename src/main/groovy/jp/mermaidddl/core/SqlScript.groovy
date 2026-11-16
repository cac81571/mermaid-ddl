package jp.mermaidddl.core

import groovy.transform.CompileStatic

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * SQL テキストの解析ユーティリティ（文の分割・種別判定）。DDL の解析と実行に使う。
 * H2 と Oracle（SQL*Plus / SQL Developer 形式のスクリプト）を想定する。
 *
 * <ul>
 *   <li>文の区切り: 行末の ; または 行頭の / だけの行（Oracle）</li>
 *   <li>PL/SQL ブロック（BEGIN/DECLARE、CREATE [OR REPLACE] PROCEDURE/FUNCTION/PACKAGE/TRIGGER/TYPE）は
 *       内部の ; では区切らず、/ だけの行（またはテキスト末尾）で区切る</li>
 *   <li>文字列 '...'（'' エスケープ）、Oracle の q'[...]' 引用、"..." 引用識別子、-- と /* *&#47; コメント内は無視</li>
 * </ul>
 */
@CompileStatic
final class SqlScript {

    private SqlScript() {}

    private static final Pattern PLSQL_HEAD = Pattern.compile(
            '^(?:BEGIN|DECLARE|CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?'
                    + '(?:PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE|LIBRARY|JAVA))\\b',
            Pattern.CASE_INSENSITIVE)

    private static final Set<String> DDL_HEADS = ['CREATE', 'ALTER', 'DROP', 'COMMENT', 'GRANT', 'REVOKE',
                                                   'TRUNCATE', 'RENAME', 'ANALYZE', 'AUDIT', 'PURGE'] as Set<String>

    /** 分割した文と、その文が始まる行番号（1 始まり） */
    static class Statement {
        String sql
        int line

        @Override
        String toString() { "${line}: ${sql}" }
    }

    /** 文を分割する。区切りの ; は除去するが、PL/SQL ブロック末尾の END; の ; は保持する。 */
    static List<String> split(String text) {
        splitWithLines(text).collect { Statement st -> st.sql }
    }

    /** {@link #split(String)} と同じ分割を行い、各文の開始行も返す（エラー表示用） */
    static List<Statement> splitWithLines(String text) {
        List<Statement> out = []
        if (text == null) return out
        int segStart = 0
        StringBuilder cur = new StringBuilder()
        int n = text.length()
        int i = 0
        while (i < n) {
            char c = text.charAt(i)
            char next = i + 1 < n ? text.charAt(i + 1) : (char) 0
            int lit = literalEnd(text, i)
            if (lit > i) {
                cur.append(text, i, lit)
                i = lit
            } else if (c == (char) '-' && next == (char) '-') {
                int j = text.indexOf('\n', i)
                if (j < 0) j = n
                cur.append(text, i, j)
                i = j
            } else if (c == (char) '/' && next == (char) '*') {
                int j = text.indexOf('*/', i + 2)
                j = j < 0 ? n : j + 2
                cur.append(text, i, j)
                i = j
            } else if (c == (char) '/' && isSlashLine(text, i)) {
                // SQL*Plus の「/」だけの行 = 文（PL/SQL ブロック）の終わり
                addIfNotBlank(out, cur.toString(), text, segStart)
                cur.setLength(0)
                int j = text.indexOf('\n', i)
                i = j < 0 ? n : j + 1
                segStart = i
            } else if (c == (char) ';' && !isPlsql(cur.toString())) {
                addIfNotBlank(out, cur.toString(), text, segStart)
                cur.setLength(0)
                i++
                segStart = i
            } else {
                cur.append(c)
                i++
            }
        }
        addIfNotBlank(out, cur.toString(), text, segStart)
        out
    }

    /** i の位置から文字列リテラル／引用識別子が始まっていれば、その終端の次の位置を返す。始まっていなければ i。 */
    private static int literalEnd(String s, int i) {
        int n = s.length()
        char c = s.charAt(i)
        // Oracle q'[...]' / Q'{...}' / nq'...'
        if ((c == (char) 'q' || c == (char) 'Q') && i + 2 < n && s.charAt(i + 1) == (char) '\''
                && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)) || s.charAt(i - 1) == (char) 'n' || s.charAt(i - 1) == (char) 'N')) {
            char open = s.charAt(i + 2)
            char close = open == (char) '[' ? (char) ']' : open == (char) '{' ? (char) '}'
                    : open == (char) '(' ? (char) ')' : open == (char) '<' ? (char) '>' : open
            int j = s.indexOf(new String([close, (char) '\''] as char[]), i + 3)
            return j < 0 ? n : j + 2
        }
        if (c == (char) '\'' || c == (char) '"') {
            int j = i + 1
            while (j < n) {
                if (s.charAt(j) == c) {
                    if (j + 1 < n && s.charAt(j + 1) == c) { j += 2; continue } // '' / "" エスケープ
                    return j + 1
                }
                j++
            }
            return n
        }
        i
    }

    private static boolean isSlashLine(String s, int i) {
        int ls = s.lastIndexOf('\n', i - 1) + 1
        if (s.substring(ls, i).trim()) return false
        int le = s.indexOf('\n', i)
        if (le < 0) le = s.length()
        return !s.substring(i + 1, le).trim()
    }

    private static void addIfNotBlank(List<Statement> out, String s, String text, int segStart) {
        if (!stripComments(s).trim()) return
        // 先頭の空白・コメントを飛ばした位置の行番号
        int k = 0
        int n = s.length()
        while (k < n) {
            char c = s.charAt(k)
            if (Character.isWhitespace(c)) { k++; continue }
            if (c == (char) '-' && k + 1 < n && s.charAt(k + 1) == (char) '-') {
                int j = s.indexOf('\n', k)
                k = j < 0 ? n : j
                continue
            }
            if (c == (char) '/' && k + 1 < n && s.charAt(k + 1) == (char) '*') {
                int j = s.indexOf('*/', k + 2)
                k = j < 0 ? n : j + 2
                continue
            }
            break
        }
        int line = 1
        int end = Math.min(segStart + k, text.length())
        for (int i = 0; i < end; i++) if (text.charAt(i) == (char) '\n') line++
        out << new Statement(sql: s.trim(), line: line)
    }

    /** PL/SQL ブロック（内部に ; を含み、; で区切ってはいけない文）か。 */
    static boolean isPlsql(String sql) {
        PLSQL_HEAD.matcher(stripComments(sql).trim()).find()
    }

    /** DDL（CREATE/ALTER/DROP/COMMENT など）か。 */
    static boolean isDdl(String sql) {
        firstWord(sql) in DDL_HEADS
    }

    static String firstWord(String sql) {
        String s = stripComments(sql).trim().replaceAll(/^\(+/, '').trim()
        (s.split(/[\s(]+/, 2)[0] ?: '').toUpperCase(Locale.ROOT)
    }

    /** コメントを除去する（文字列リテラルは保持）。 */
    static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder()
        int n = sql.length()
        int i = 0
        while (i < n) {
            char c = sql.charAt(i)
            char next = i + 1 < n ? sql.charAt(i + 1) : (char) 0
            int lit = literalEnd(sql, i)
            if (lit > i) {
                sb.append(sql, i, lit)
                i = lit
            } else if (c == (char) '-' && next == (char) '-') {
                int j = sql.indexOf('\n', i)
                i = j < 0 ? n : j
            } else if (c == (char) '/' && next == (char) '*') {
                int j = sql.indexOf('*/', i + 2)
                i = j < 0 ? n : j + 2
                sb.append(' ')
            } else {
                sb.append(c)
                i++
            }
        }
        sb.toString()
    }

}
