package jp.mermaidddl.er

import groovy.transform.CompileStatic

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Mermaid の erDiagram 記法を解析して {@link ErDiagram} にする。
 *
 * <p>対応する記法（Mermaid 11/12 の erDiagram）:</p>
 * <ul>
 *   <li>先頭の front matter（--- title: ... ---）、%% コメント、%%{init}%% ディレクティブ</li>
 *   <li>エンティティ: NAME / "名前 空白あり" / NAME["表示名"] / NAME[表示名] / NAME:::class、属性ブロック { ... }</li>
 *   <li>属性: 型 名前 [PK|FK|UK[, ...]] ["コメント"]。型は varchar2(50) や number(10,2) のような括弧付きも可</li>
 *   <li>リレーション: A |o--o{ B : "ラベル"。記号 |o || }o }| / o| || o{ |{、-- と ..、
 *       one / only one / 1 / zero or one / one or more / 1+ / zero or more / 0+ / many(0) / many(1) などの語形、to / optionally to</li>
 *   <li>direction / classDef / class / style / accTitle / accDescr / subgraph ... end は読み飛ばす</li>
 * </ul>
 * <p>コメント末尾の [NOT NULL] [DEFAULT 式] [IDENTITY] [UNIQUE] タグは DDL 生成用の拡張として解釈する。</p>
 */
@CompileStatic
class MermaidParser {

    /** トークン */
    static class T {
        /** WORD STR BQ NL LBRACE RBRACE ALIAS LABEL CARD REL COMMA CLASS */
        String k
        String v
        int line
        Cardinality card
        boolean identifying

        @Override
        String toString() { "${k}(${v})" }
    }

    private static final class CardPat {
        final Pattern p
        final Cardinality c
        final boolean warnParent

        CardPat(String regex, Cardinality c, boolean warnParent = false) {
            this.p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
            this.c = c
            this.warnParent = warnParent
        }
    }

    private static final List<CardPat> CARDS = [
        new CardPat(/one or zero\b/, Cardinality.ZERO_OR_ONE),
        new CardPat(/one or more\b/, Cardinality.ONE_OR_MORE),
        new CardPat(/one or many\b/, Cardinality.ONE_OR_MORE),
        new CardPat(/1\+/, Cardinality.ONE_OR_MORE),
        new CardPat(/\|o\b/, Cardinality.ZERO_OR_ONE),
        new CardPat(/zero or one\b/, Cardinality.ZERO_OR_ONE),
        new CardPat(/zero or more\b/, Cardinality.ZERO_OR_MORE),
        new CardPat(/zero or many\b/, Cardinality.ZERO_OR_MORE),
        new CardPat(/0\+/, Cardinality.ZERO_OR_MORE),
        new CardPat(/\}o\b/, Cardinality.ZERO_OR_MORE),
        new CardPat(/many\(0\)/, Cardinality.ZERO_OR_MORE),
        new CardPat(/many\(1\)/, Cardinality.ONE_OR_MORE),
        new CardPat(/many\b/, Cardinality.ZERO_OR_MORE),
        new CardPat(/\}\|/, Cardinality.ONE_OR_MORE),
        new CardPat(/only one\b/, Cardinality.EXACTLY_ONE),
        new CardPat(/one\b/, Cardinality.EXACTLY_ONE),
        new CardPat(/1\b/, Cardinality.EXACTLY_ONE),
        new CardPat(/\|\|/, Cardinality.EXACTLY_ONE),
        new CardPat(/o\|/, Cardinality.ZERO_OR_ONE),
        new CardPat(/o\{/, Cardinality.ZERO_OR_MORE),
        new CardPat(/\|\{/, Cardinality.ONE_OR_MORE),
        new CardPat(/u(?=[.\-|])/, Cardinality.EXACTLY_ONE, true),
    ]

    private static final List<List<Object>> RELS = [
        [Pattern.compile(/optionally to\b/, Pattern.CASE_INSENSITIVE), false] as List<Object>,
        [Pattern.compile(/to\b/, Pattern.CASE_INSENSITIVE), true] as List<Object>,
        [Pattern.compile(/--/), true] as List<Object>,
        [Pattern.compile(/\.\./), false] as List<Object>,
        [Pattern.compile(/\.-/), false] as List<Object>,
        [Pattern.compile(/-\./), false] as List<Object>,
    ]

    private static final Pattern WORD = Pattern.compile(/(?:[^\x00-\x7F]|[\w\-*$#])+/)
    private static final Pattern TAG = Pattern.compile(/\s*\[([^\[\]]+)\]\s*$/)
    private static final Set<String> SKIP_STATEMENTS = (['direction', 'classdef', 'class', 'style', 'subgraph', 'end',
                                                         'acctitle', 'accdescr', 'title', 'linkstyle'] as Set<String>)

    private ErDiagram d
    private List<T> toks
    private int p

    /** 解析する。エラーがあっても可能な範囲で続行し、{@link ErDiagram#getIssues()} に行番号付きで記録する。 */
    ErDiagram parse(String text) {
        d = new ErDiagram()
        String src = (text ?: '').replace('\r\n', '\n').replace('\r', '\n')
        src = stripFrontMatter(src)
        toks = lex(src)
        p = 0
        parseDocument()
        d
    }

    // ================================================================ 前処理

    /** --- ... --- を同じ行数の空行に置き換え（行番号を保つ）、title を取り出す */
    private String stripFrontMatter(String src) {
        String[] lines = src.split('\n', -1)
        int first = 0
        while (first < lines.length && !lines[first].trim()) first++
        if (first >= lines.length || lines[first].trim() != '---') return src
        int last = -1
        for (int i = first + 1; i < lines.length; i++) {
            if (lines[i].trim() == '---') { last = i; break }
        }
        if (last < 0) {
            d.error(first + 1, 'front matter（---）が閉じられていません')
            return src
        }
        for (int i = first; i <= last; i++) {
            Matcher m = lines[i] =~ /^\s*title\s*:\s*(.*)$/
            if (m.find()) d.title = unquote(m.group(1).trim())
            lines[i] = ''
        }
        lines.join('\n')
    }

    // ================================================================ 字句解析

    private List<T> lex(String s) {
        List<T> out = []
        int n = s.length()
        int i = 0
        int line = 1
        boolean block = false
        int blockLine = 0
        while (i < n) {
            char c = s.charAt(i)
            // %% コメント（文字列外）
            if (c == (char) '%' && i + 1 < n && s.charAt(i + 1) == (char) '%') {
                int j = s.indexOf('\n', i)
                i = j < 0 ? n : j
                continue
            }
            if (c == (char) '\n') {
                if (!block) out << tok('NL', null, line)
                line++
                i++
                continue
            }
            if (Character.isWhitespace(c)) { i++; continue }

            if (block) {
                if (c == (char) '}') { out << tok('RBRACE', '}', line); block = false; i++; continue }
                if (c == (char) '{') { d.error(line, '属性ブロックの中に { があります'); i++; continue }
                if (c == (char) ',') { out << tok('COMMA', ',', line); i++; continue }
                if (c == (char) '"' || c == (char) '`') {
                    int j = s.indexOf(String.valueOf(c), i + 1)
                    int eol = s.indexOf('\n', i)
                    if (j < 0 || (eol >= 0 && j > eol)) {
                        d.error(line, c == (char) '"' ? 'コメントの " が閉じられていません' : '` が閉じられていません')
                        i = eol < 0 ? n : eol
                        continue
                    }
                    out << tok(c == (char) '"' ? 'STR' : 'BQ', s.substring(i + 1, j), line)
                    i = j + 1
                    continue
                }
                // 単語（型・名前・キー）。括弧の中は空白・カンマも含める: number(10, 2) / varchar2(50 char)
                int j = i
                int depth = 0
                while (j < n) {
                    char x = s.charAt(j)
                    if (x == (char) '\n') break
                    if (x == (char) '(') depth++
                    else if (x == (char) ')') { if (depth > 0) depth-- }
                    else if (depth == 0 && (Character.isWhitespace(x) || x == (char) ',' || x == (char) '"' || x == (char) '}' || x == (char) '{' || x == (char) '`')) break
                    j++
                }
                if (depth > 0) d.error(line, "括弧が閉じられていません: ${s.substring(i, j).trim()}")
                out << tok('WORD', s.substring(i, j).trim(), line)
                i = j
                continue
            }

            // ---- ブロック外
            if (c == (char) ';') { out << tok('NL', null, line); i++; continue }
            if (c == (char) '{') {
                // accDescr { 複数行 } は読み飛ばす
                if (out && out[-1].k == 'WORD' && out[-1].v.equalsIgnoreCase('accDescr')) {
                    int j = s.indexOf('}', i)
                    j = j < 0 ? n : j + 1
                    line += s.substring(i, j).count('\n')
                    i = j
                    continue
                }
                out << tok('LBRACE', '{', line)
                block = true
                blockLine = line
                i++
                continue
            }
            if (c == (char) '"') {
                int j = s.indexOf('"', i + 1)
                int eol = s.indexOf('\n', i)
                if (j < 0 || (eol >= 0 && j > eol)) {
                    d.error(line, '" が閉じられていません')
                    i = eol < 0 ? n : eol
                    continue
                }
                out << tok('STR', s.substring(i + 1, j), line)
                i = j + 1
                continue
            }
            if (c == (char) '[') {
                int j = s.indexOf(']', i + 1)
                int eol = s.indexOf('\n', i)
                if (j < 0 || (eol >= 0 && j > eol)) {
                    d.error(line, '表示名の [ が閉じられていません')
                    i = eol < 0 ? n : eol
                    continue
                }
                out << tok('ALIAS', unquote(s.substring(i + 1, j).trim()), line)
                i = j + 1
                continue
            }
            if (s.startsWith(':::', i)) {
                Matcher m = WORD.matcher(s)
                m.region(i + 3, n)
                int j = m.lookingAt() ? m.end() : i + 3
                out << tok('CLASS', s.substring(i + 3, j), line)
                i = j
                continue
            }
            if (c == (char) ':') {
                int eol = s.indexOf('\n', i)
                if (eol < 0) eol = n
                String rest = s.substring(i + 1, eol)
                int pct = rest.indexOf('%%')
                if (pct >= 0 && !rest.substring(0, pct).contains('"')) rest = rest.substring(0, pct)
                out << tok('LABEL', labelText(rest.trim()), line)
                i = eol
                continue
            }
            T card = matchCard(s, i, n, line)
            if (card) {
                out << card
                i += card.v.length()
                continue
            }
            if (c == (char) '}') { d.error(line, '対応する { がない } があります'); i++; continue }
            boolean rel = false
            for (List<Object> r : RELS) {
                Matcher m = ((Pattern) r[0]).matcher(s)
                m.region(i, n)
                if (m.lookingAt()) {
                    T t = tok('REL', m.group(), line)
                    t.identifying = (boolean) r[1]
                    out << t
                    i = m.end()
                    rel = true
                    break
                }
            }
            if (rel) continue
            Matcher w = WORD.matcher(s)
            w.region(i, n)
            if (w.lookingAt()) {
                out << tok('WORD', w.group(), line)
                i = w.end()
                continue
            }
            d.error(line, "解釈できない文字「${c}」があります")
            i++
        }
        if (block) d.error(blockLine, "${blockLine} 行目の { に対応する } がありません")
        out
    }

    private T matchCard(String s, int i, int n, int line) {
        for (CardPat cp : CARDS) {
            Matcher m = cp.p.matcher(s)
            m.region(i, n)
            if (m.lookingAt()) {
                // 「1」「one」「many」などの語形は、直後がリレーション記号・別の多重度・空白のときだけ
                T t = tok('CARD', m.group(), line)
                t.card = cp.c
                if (cp.warnParent) d.warn(line, 'u（MD_PARENT）は「ちょうど 1」として扱います')
                return t
            }
        }
        null
    }

    private static String labelText(String raw) {
        if (raw.startsWith('"')) {
            int j = raw.indexOf('"', 1)
            return j > 0 ? raw.substring(1, j) : raw.substring(1)
        }
        raw
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1)
        }
        s
    }

    private static T tok(String k, String v, int line) { new T(k: k, v: v, line: line) }

    // ================================================================ 構文解析

    private T peek(int k = 0) { p + k < toks.size() ? toks[p + k] : null }

    private T next() { toks[p++] }

    private boolean at(String k) { peek() != null && peek().k == k }

    private void skipNl() { while (at('NL')) p++ }

    private void skipStatement() {
        while (peek() != null && !at('NL')) {
            if (at('LBRACE')) {
                while (peek() != null && !at('RBRACE')) p++
            }
            p++
        }
    }

    private void parseDocument() {
        skipNl()
        T first = peek()
        if (first != null && first.k == 'WORD' && first.v.equalsIgnoreCase('erDiagram')) {
            p++
        } else {
            d.error(first?.line ?: 1, 'erDiagram で始まっていません（1 行目に erDiagram と書いてください）')
            if (first != null && first.k == 'WORD' && first.v ==~ /(?i)(graph|flowchart|classDiagram|sequenceDiagram).*/) return
        }
        while (true) {
            skipNl()
            T t = peek()
            if (t == null) break
            int before = p
            try {
                parseStatement()
            } catch (ParseFailure e) {
                d.error(e.line, e.message)
                skipStatement()
            }
            if (p == before) p++
        }
    }

    private static class ParseFailure extends RuntimeException {
        final int line

        ParseFailure(int line, String msg) {
            super(msg)
            this.line = line
        }
    }

    private void parseStatement() {
        T t = peek()
        if (t.k == 'WORD' && SKIP_STATEMENTS.contains(t.v.toLowerCase(Locale.ROOT))) {
            if (t.v.equalsIgnoreCase('direction')) d.direction = peek(1)?.k == 'WORD' ? peek(1).v.toUpperCase(Locale.ROOT) : null
            skipStatement()
            return
        }
        if (t.k == 'WORD' && t.v.equalsIgnoreCase('erDiagram')) {
            d.warn(t.line, 'erDiagram が重複しています')
            p++
            return
        }
        if (t.k != 'WORD' && t.k != 'STR') throw new ParseFailure(t.line, "エンティティ名が必要な位置に「${t.v ?: t.k}」があります")
        p++
        String name = t.v.trim()
        if (!name) throw new ParseFailure(t.line, 'エンティティ名が空です')
        String alias = acceptAliasAndClass()

        if (at('CARD')) {
            parseRelationship(name, alias, t.line)
            return
        }
        ErEntity e = ensureEntity(name, t.line, true)
        if (alias) setAlias(e, alias, t.line)
        if (at('LBRACE')) {
            p++
            parseAttributes(e)
        }
        T end = peek()
        if (end != null && end.k != 'NL') {
            if (end.k == 'REL') throw new ParseFailure(end.line, "リレーションの多重度記号がありません（例: ${name} ||--o{ OTHER）")
            throw new ParseFailure(end.line, "「${end.v ?: end.k}」は解釈できません")
        }
    }

    private String acceptAliasAndClass() {
        String alias = null
        if (at('ALIAS')) alias = next().v
        while (at('CLASS')) p++
        alias
    }

    private ErEntity ensureEntity(String name, int line, boolean declared) {
        ErEntity e = d.entities.get(name)
        if (e == null) {
            e = new ErEntity(name: name, line: line)
            d.entities.put(name, e)
        } else if (declared && !e.line) {
            e.line = line
        }
        e
    }

    private void setAlias(ErEntity e, String alias, int line) {
        if (e.alias && e.alias != alias) d.warn(line, "${e.name} の表示名が複数あります（「${e.alias}」を使用）")
        else e.alias = alias
    }

    private void parseRelationship(String left, String leftAlias, int line) {
        T lc = next()
        if (!at('REL')) throw new ParseFailure(lc.line, "多重度「${lc.v}」の後に -- または .. が必要です")
        T rel = next()
        if (!at('CARD')) throw new ParseFailure(rel.line, "「${rel.v}」の後に右側の多重度（o{ や || など）が必要です")
        T rc = next()
        T rt = peek()
        if (rt == null || (rt.k != 'WORD' && rt.k != 'STR')) throw new ParseFailure(rc.line, '右側のエンティティ名がありません')
        p++
        String right = rt.v.trim()
        String rightAlias = acceptAliasAndClass()
        String label = null
        if (at('LABEL')) label = next().v
        else d.warn(rc.line, "${left} と ${right} のリレーションにラベル（: \"ラベル\"）がありません（Mermaid では必須）")
        if (peek() != null && !at('NL')) throw new ParseFailure(peek().line, "リレーションの後ろの「${peek().v ?: peek().k}」は解釈できません")

        ErEntity le = ensureEntity(left, line, false)
        ErEntity re = ensureEntity(right, line, false)
        if (leftAlias) setAlias(le, leftAlias, line)
        if (rightAlias) setAlias(re, rightAlias, line)
        d.relationships << new ErRelationship(left: left, right: right, leftCard: lc.card, rightCard: rc.card,
                identifying: rel.identifying, label: label ?: null, line: line)
    }

    private void parseAttributes(ErEntity e) {
        while (true) {
            T t = peek()
            if (t == null) return
            if (t.k == 'RBRACE') { p++; return }
            if (t.k != 'WORD') {
                d.error(t.line, t.k == 'STR' ? "コメント \"${t.v}\" の前に型と属性名が必要です" : "属性の型が必要な位置に「${t.v}」があります")
                p++
                continue
            }
            p++
            ErAttribute a = new ErAttribute(type: t.v, line: t.line)
            T nm = peek()
            if (nm == null || (nm.k != 'WORD' && nm.k != 'BQ') || nm.line != t.line) {
                d.error(t.line, "属性名がありません（「${t.v}」の後ろに名前を書いてください。書式: 型 名前 PK \"コメント\"）")
                continue
            }
            p++
            a.name = nm.v
            // キー
            while (at('WORD') && peek().line == a.line && peek().v.toUpperCase(Locale.ROOT) in ['PK', 'FK', 'UK']) {
                a.keys << next().v.toUpperCase(Locale.ROOT)
                if (at('COMMA')) {
                    p++
                    if (!(at('WORD') && peek().v.toUpperCase(Locale.ROOT) in ['PK', 'FK', 'UK'])) {
                        d.error(a.line, "「,」の後に PK / FK / UK が必要です（${a.name}）")
                    }
                }
            }
            if (at('COMMA')) {
                d.error(peek().line, "属性 ${a.name} の後ろに不要な「,」があります（キーは PK, FK のように書きます）")
                p++
            }
            if (at('STR') && peek().line == a.line) applyComment(a, next().v)
            if (at('WORD') && peek().line == a.line && !(peek(1)?.line == a.line && (peek(1).k == 'WORD' || peek(1).k == 'BQ'))) {
                d.error(a.line, "属性 ${a.name} の後ろの「${peek().v}」は解釈できません（キーは PK / FK / UK のみ）")
                p++
            }
            if (e.attribute(a.name) != null) {
                d.warn(a.line, "${e.name}.${a.name} が重複しています（後の定義は無視）")
            } else {
                e.attributes << a
            }
        }
    }

    /** コメント末尾の [NOT NULL] [DEFAULT 式] [IDENTITY] [UNIQUE] を取り出す */
    static void applyComment(ErAttribute a, String raw) {
        String c = raw ?: ''
        while (true) {
            Matcher m = TAG.matcher(c)
            if (!m.find()) break
            String tag = m.group(1).trim()
            String up = tag.toUpperCase(Locale.ROOT)
            if (up == 'NOT NULL' || up == 'NN' || tag == '必須') a.notNull = true
            else if (up == 'IDENTITY' || up == 'AUTO' || tag == '自動採番') a.identity = true
            else if (up == 'IDENTITY ALWAYS') { a.identity = true; a.identityKind = 'ALWAYS' }
            else if (up == 'IDENTITY ON NULL') { a.identity = true; a.identityKind = 'ON NULL' }
            else if (up == 'UNIQUE') a.keys << 'UK'
            else if (up.startsWith('DEFAULT ')) a.defaultExpr = tag.substring(8).trim()
            else break
            c = c.substring(0, m.start())
        }
        a.comment = c.trim() ?: null
    }
}
