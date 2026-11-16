package jp.mermaidddl.schema

import jp.mermaidddl.core.SqlScript

import java.util.regex.Matcher

/**
 * CREATE TABLE などの DDL を解析してテーブル定義を作る（H2 / Oracle）。
 *
 * 対応する文:
 * <ul>
 *   <li>CREATE [GLOBAL TEMPORARY | MEMORY | CACHED ...] TABLE [IF NOT EXISTS] [schema.]table (列定義, 制約...) [ストレージ句]</li>
 *   <li>COMMENT ON TABLE [schema.]table IS '...' / COMMENT ON COLUMN [schema.]table.column IS '...'</li>
 *   <li>ALTER TABLE ... ADD [CONSTRAINT n] PRIMARY KEY / UNIQUE / FOREIGN KEY / CHECK、ADD (列定義)、MODIFY (列 NOT NULL / DEFAULT)</li>
 *   <li>CREATE SEQUENCE（主キー採番シーケンスの推定に使用）</li>
 *   <li>CREATE TRIGGER ... BEFORE INSERT ... :NEW.col := seq.NEXTVAL / SELECT seq.NEXTVAL INTO :NEW.col（トリガー採番の検出）</li>
 * </ul>
 * 文の区切りは ; または / だけの行。"..." 引用識別子は大文字小文字を保持し、引用なしは大文字に正規化する。
 * TABLESPACE / STORAGE / PCTFREE / LOB / PARTITION などのストレージ句は無視する。
 * CREATE INDEX / GRANT などのテーブル定義に関係しない文はスキップする。
 */
class DdlParser {

    Dialect dialect = Dialect.ORACLE

    private List<Map> pendingComments = []
    private List<Map> pendingTriggers = []

    DdlParser() {}

    DdlParser(Dialect dialect) { this.dialect = dialect }

    // ================================================================ トークン

    static class Tok {
        /** W=単語, Q="引用識別子", S='文字列', N=数値, P=記号 */
        String type
        String text
        String value

        boolean isWord(String w) { type == 'W' && value == w }
        boolean isPunct(String p) { type == 'P' && text == p }

        String toString() { text }
    }

    static List<Tok> tokenize(String sql) {
        List<Tok> out = []
        int n = sql.length()
        int i = 0
        while (i < n) {
            char c = sql.charAt(i)
            char nx = i + 1 < n ? sql.charAt(i + 1) : (char) 0
            if (Character.isWhitespace(c)) { i++; continue }
            if (c == '-' && nx == '-') { int j = sql.indexOf('\n', i); i = j < 0 ? n : j; continue }
            if (c == '/' && nx == '*') { int j = sql.indexOf('*/', i + 2); i = j < 0 ? n : j + 2; continue }
            // Oracle の q'[...]'（N 接頭辞も可）
            int qs = i
            if ((c == 'n' || c == 'N') && (nx == 'q' || nx == 'Q') && i + 2 < n && sql.charAt(i + 2) == '\'') qs = i + 1
            if ((sql.charAt(qs) == 'q' || sql.charAt(qs) == 'Q') && qs + 2 < n && sql.charAt(qs + 1) == '\'') {
                char open = sql.charAt(qs + 2)
                char close = open == '[' ? ']' : open == '{' ? '}' : open == '(' ? ')' : open == '<' ? '>' : open
                int j = sql.indexOf("${close}'", qs + 3)
                if (j < 0) j = n - 2
                out << new Tok(type: 'S', text: sql.substring(i, Math.min(j + 2, n)), value: sql.substring(qs + 3, Math.max(qs + 3, j)))
                i = Math.min(j + 2, n)
                continue
            }
            if (c == '\'' || ((c == 'N' || c == 'n') && nx == '\'')) {
                int st = c == '\'' ? i : i + 1
                StringBuilder v = new StringBuilder()
                int j = st + 1
                while (j < n) {
                    char d = sql.charAt(j)
                    if (d == '\'') {
                        if (j + 1 < n && sql.charAt(j + 1) == '\'') { v.append('\''); j += 2; continue }
                        break
                    }
                    v.append(d)
                    j++
                }
                out << new Tok(type: 'S', text: sql.substring(i, Math.min(j + 1, n)), value: v.toString())
                i = j + 1
                continue
            }
            if (c == '"') {
                StringBuilder v = new StringBuilder()
                int j = i + 1
                while (j < n) {
                    char d = sql.charAt(j)
                    if (d == '"') {
                        if (j + 1 < n && sql.charAt(j + 1) == '"') { v.append('"'); j += 2; continue }
                        break
                    }
                    v.append(d)
                    j++
                }
                out << new Tok(type: 'Q', text: sql.substring(i, Math.min(j + 1, n)), value: v.toString())
                i = j + 1
                continue
            }
            if (Character.isDigit(c) || (c == '.' && Character.isDigit(nx as char))) {
                int j = i
                while (j < n && (Character.isDigit(sql.charAt(j)) || sql.charAt(j) == '.')) j++
                if (j < n && (sql.charAt(j) == 'e' || sql.charAt(j) == 'E')) {
                    int k = j + 1
                    if (k < n && (sql.charAt(k) == '+' || sql.charAt(k) == '-')) k++
                    if (k < n && Character.isDigit(sql.charAt(k))) { j = k; while (j < n && Character.isDigit(sql.charAt(j))) j++ }
                }
                out << new Tok(type: 'N', text: sql.substring(i, j), value: sql.substring(i, j))
                i = j
                continue
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i
                while (j < n && isIdentPart(sql.charAt(j))) j++
                String w = sql.substring(i, j)
                out << new Tok(type: 'W', text: w, value: w.toUpperCase(Locale.ROOT))
                i = j
                continue
            }
            String two = i + 1 < n ? sql.substring(i, i + 2) : ''
            if (two in [':=', '||', '>=', '<=', '<>', '!=', '=>']) {
                out << new Tok(type: 'P', text: sql.substring(i, i + 2), value: sql.substring(i, i + 2))
                i += 2
                continue
            }
            out << new Tok(type: 'P', text: String.valueOf(c), value: String.valueOf(c))
            i++
        }
        out
    }

    private static boolean isIdentPart(char ch) {
        Character.isLetterOrDigit(ch) || ch == ('_' as char) || ch == ('$' as char) || ch == ('#' as char)
    }

    /** トークン列を走査するカーソル */
    static class Cursor {
        final List<Tok> t
        int i = 0

        Cursor(List<Tok> t) { this.t = t }

        boolean atEnd() { i >= t.size() }
        Tok peek(int k = 0) { i + k < t.size() ? t[i + k] : null }
        Tok next() { t[i++] }

        boolean isWord(String... ws) { Tok p = peek(); p != null && p.type == 'W' && ws.contains(p.value) }

        boolean isWords(String... seq) {
            for (int k = 0; k < seq.length; k++) {
                Tok p = peek(k)
                if (p == null || p.type != 'W' || p.value != seq[k]) return false
            }
            true
        }

        boolean accept(String... seq) {
            if (!isWords(seq)) return false
            i += seq.length
            true
        }

        boolean isPunct(String p) { Tok x = peek(); x != null && x.isPunct(p) }

        /** 現在位置の '(' に対応する ')' の位置 */
        int matchParen() {
            int depth = 0
            for (int k = i; k < t.size(); k++) {
                if (t[k].isPunct('(')) depth++
                else if (t[k].isPunct(')')) { depth--; if (depth == 0) return k }
            }
            throw new IllegalStateException('括弧が閉じていません')
        }

        /** 現在位置の (...) の中身を返して、その後ろへ進む */
        List<Tok> group() {
            int end = matchParen()
            List<Tok> inner = new ArrayList<>(t.subList(i + 1, end))
            i = end + 1
            inner
        }
    }

    /** 深さ 0 のカンマで分割する */
    static List<List<Tok>> splitTopLevel(List<Tok> tokens) {
        List<List<Tok>> out = []
        List<Tok> cur = []
        int depth = 0
        tokens.each { Tok tk ->
            if (tk.isPunct('(')) depth++
            if (tk.isPunct(')')) depth--
            if (depth == 0 && tk.isPunct(',')) {
                if (cur) out << cur
                cur = []
            } else {
                cur << tk
            }
        }
        if (cur) out << cur
        out
    }

    /** トークン列を SQL 文字列に戻す（表示用） */
    static String text(List<Tok> tokens) {
        StringBuilder sb = new StringBuilder()
        Tok prev = null
        tokens.each { Tok tk ->
            boolean glue = prev == null || tk.isPunct(')') || tk.isPunct(',') || tk.isPunct('.') || prev.isPunct('.') ||
                    prev.isPunct('(') || (tk.isPunct('(') && prev.type in ['W', 'Q'])
            if (!glue) sb.append(' ')
            sb.append(tk.text)
            prev = tk
        }
        sb.toString()
    }

    // ================================================================ 解析

    SchemaModel parse(String ddl) {
        SchemaModel m = new SchemaModel()
        pendingComments = []
        pendingTriggers = []
        for (SqlScript.Statement st : SqlScript.splitWithLines(ddl ?: '')) {
            int before = m.warnings.size()
            try {
                parseStatement(st.sql, m)
            } catch (Exception e) {
                m.warnings << "解析できない文をスキップしました: ${abbrev(st.sql)}（${e.message}）".toString()
            }
            // 警告に文の開始行を付ける
            for (int i = before; i < m.warnings.size(); i++) m.warnings[i] = "${st.line} 行目: ${m.warnings[i]}".toString()
        }
        finish(m)
        m
    }

    private static String abbrev(String s) {
        String one = s.replaceAll(/\s+/, ' ').trim()
        one.length() > 60 ? one.substring(0, 60) + '…' : one
    }

    private void parseStatement(String sql, SchemaModel m) {
        List<Tok> toks = tokenize(sql)
        if (!toks) return
        Cursor c = new Cursor(toks)
        String head = c.peek().value
        if (head == 'CREATE') {
            c.next()
            while (c.isWord('OR', 'REPLACE', 'GLOBAL', 'PRIVATE', 'TEMPORARY', 'LOCAL', 'MEMORY', 'CACHED', 'EDITIONABLE',
                    'NONEDITIONABLE', 'SHARDED', 'DUPLICATED', 'IMMUTABLE', 'BLOCKCHAIN', 'FORCE', 'NOFORCE', 'UNIQUE',
                    'BITMAP', 'LINKED', 'EXTERNAL', 'TRANSACTIONAL', 'NOT', 'PERSISTENT', 'MATERIALIZED')) c.next()
            String kind = c.atEnd() ? '' : c.next().value
            switch (kind) {
                case 'TABLE': parseCreateTable(c, m); break
                case 'SEQUENCE': parseSequence(c, m); break
                case 'TRIGGER': parseTrigger(sql); break
                default: m.skipped << "CREATE ${kind}".toString()
            }
        } else if (head == 'COMMENT') {
            parseComment(c, m)
        } else if (head == 'ALTER' && c.peek(1)?.value == 'TABLE') {
            c.next(); c.next()
            parseAlterTable(c, m)
        } else {
            m.skipped << (toks.size() > 1 ? "${head} ${toks[1].value ?: toks[1].text}" : head).toString()
        }
    }

    /** 識別子 1 つ（引用なしは大文字化） */
    private static Map ident(Cursor c) {
        Tok tk = c.next()
        if (tk.type == 'Q') return [name: tk.value, quoted: true]
        if (tk.type == 'W') return [name: tk.value, quoted: false]
        throw new IllegalStateException("識別子が必要な位置に「${tk.text}」があります")
    }

    /** a.b.c のような修飾名 */
    private static List<Map> nameParts(Cursor c) {
        List<Map> parts = [ident(c)]
        while (c.isPunct('.')) {
            c.next()
            parts << ident(c)
        }
        parts
    }

    private static List<String> identList(Cursor c) {
        if (!c.isPunct('(')) return []
        splitTopLevel(c.group()).collect { List<Tok> e -> ident(new Cursor(e)).name as String }
    }

    // ---------------------------------------------------------------- CREATE TABLE

    private static final Set<String> STORAGE_WORDS = ([
        'TABLESPACE', 'STORAGE', 'PCTFREE', 'PCTUSED', 'INITRANS', 'MAXTRANS', 'LOGGING', 'NOLOGGING', 'COMPRESS',
        'NOCOMPRESS', 'SEGMENT', 'LOB', 'PARTITION', 'SUBPARTITION', 'CACHE', 'NOCACHE', 'PARALLEL', 'NOPARALLEL',
        'MONITORING', 'NOMONITORING', 'ORGANIZATION', 'ROW', 'FLASHBACK', 'INMEMORY', 'NO', 'ON', 'ENABLE', 'DISABLE',
        'RESULT_CACHE', 'CLUSTER', 'ROWDEPENDENCIES', 'NOROWDEPENDENCIES', 'ENGINE', 'TRANSACTIONAL', 'NOT', 'PERSISTENT',
        'XMLTYPE', 'NESTED', 'VARRAY', 'COLUMN', 'DEFAULT', 'COLLATION', 'SHARING', 'MEMOPTIMIZE', 'ILM', 'INDEXING'
    ] as Set<String>)

    private void parseCreateTable(Cursor c, SchemaModel m) {
        c.accept('IF', 'NOT', 'EXISTS')
        List<Map> parts = nameParts(c)
        TableDef td = new TableDef(schema: parts.size() > 1 ? parts[-2].name : null, name: parts[-1].name, quoted: parts[-1].quoted)
        if (!c.isPunct('(')) {
            m.warnings << "CREATE TABLE ${td.qualifiedName} は列定義がない（AS SELECT など）ため解析できません".toString()
            return
        }
        List<Tok> body = c.group()
        splitTopLevel(body).each { List<Tok> e -> parseTableElement(e, td, m) }
        if (!c.atEnd()) {
            // 閉じ括弧の後ろはストレージ句など。深さ 0 の単語を記録して無視する
            LinkedHashSet<String> ignored = new LinkedHashSet<>()
            int depth = 0
            c.t.subList(c.i, c.t.size()).each { Tok tk ->
                if (tk.isPunct('(')) depth++
                else if (tk.isPunct(')')) depth--
                else if (depth == 0 && tk.type == 'W' && STORAGE_WORDS.contains(tk.value)) ignored << tk.value
            }
            if (c.isWord('AS')) m.warnings << "CREATE TABLE ${td.qualifiedName} ... AS SELECT の SELECT 部分は無視しました".toString()
            if (ignored) td.notes << "ストレージ句などを無視しました: ${ignored.join(', ')}".toString()
        }
        TableDef existing = m.tables.find { it.name == td.name && it.schema == td.schema }
        if (existing) {
            m.warnings << "テーブル ${td.qualifiedName} が重複して定義されています（後の定義を使用）".toString()
            m.tables.remove(existing)
        }
        m.tables << td
    }

    private void parseTableElement(List<Tok> e, TableDef td, SchemaModel m) {
        Cursor c = new Cursor(e)
        String cname = null
        if (c.isWord('CONSTRAINT')) {
            c.next()
            cname = ident(c).name
        }
        if (cname != null || c.isWord('PRIMARY', 'UNIQUE', 'FOREIGN', 'CHECK')) {
            parseTableConstraint(c, cname, td)
            return
        }
        if (c.isWord('SUPPLEMENTAL', 'PERIOD', 'LIKE', 'INDEX', 'KEY', 'SCOPE', 'REF')) {
            td.notes << "表要素「${text(e)}」は無視しました".toString()
            return
        }
        parseColumn(c, td)
    }

    private void parseTableConstraint(Cursor c, String name, TableDef td) {
        ConstraintDef cd = new ConstraintDef(name: name)
        if (c.accept('PRIMARY', 'KEY')) {
            cd.type = 'P'
            cd.columns = identList(c)
        } else if (c.accept('UNIQUE')) {
            c.accept('KEY')
            cd.type = 'U'
            cd.columns = identList(c)
        } else if (c.accept('FOREIGN', 'KEY')) {
            cd.type = 'R'
            cd.columns = identList(c)
            if (c.accept('REFERENCES')) {
                List<Map> rp = nameParts(c)
                cd.refTable = rp[-1].name
                cd.refColumns = identList(c)
            }
        } else if (c.accept('CHECK')) {
            cd.type = 'C'
            cd.checkExpr = c.isPunct('(') ? text(c.group()) : null
        } else {
            return // NOT NULL 制約名だけ等
        }
        // USING INDEX ... / ENABLE / DEFERRABLE などは無視
        td.constraints << cd
    }

    // ---------------------------------------------------------------- 列定義

    private static final List<List<String>> MULTI_WORD_TYPES = [
        ['NATIONAL', 'CHARACTER', 'LARGE', 'OBJECT'], ['NATIONAL', 'CHARACTER', 'VARYING'], ['NATIONAL', 'CHAR', 'VARYING'],
        ['CHARACTER', 'LARGE', 'OBJECT'], ['BINARY', 'LARGE', 'OBJECT'], ['CHAR', 'LARGE', 'OBJECT'], ['NCHAR', 'LARGE', 'OBJECT'],
        ['CHARACTER', 'VARYING'], ['CHAR', 'VARYING'], ['BINARY', 'VARYING'], ['NATIONAL', 'CHARACTER'], ['NATIONAL', 'CHAR'],
        ['DOUBLE', 'PRECISION'], ['LONG', 'RAW'], ['LONG', 'VARCHAR']
    ]

    private static final Set<String> LENGTH_TYPES = ([
        'VARCHAR2', 'NVARCHAR2', 'VARCHAR', 'NVARCHAR', 'CHAR', 'NCHAR', 'CHARACTER', 'CHARACTER VARYING', 'CHAR VARYING',
        'NATIONAL CHARACTER', 'NATIONAL CHAR', 'NATIONAL CHARACTER VARYING', 'NATIONAL CHAR VARYING', 'VARCHAR_IGNORECASE',
        'RAW', 'BINARY', 'VARBINARY', 'BINARY VARYING', 'CLOB', 'BLOB', 'NCLOB', 'CHARACTER LARGE OBJECT', 'BINARY LARGE OBJECT'
    ] as Set<String>)

    private static final Set<String> EXPR_STOP = ([
        'NOT', 'CONSTRAINT', 'PRIMARY', 'UNIQUE', 'CHECK', 'REFERENCES', 'GENERATED', 'ENABLE', 'DISABLE', 'COMMENT',
        'AUTO_INCREMENT', 'VISIBLE', 'INVISIBLE', 'COLLATE', 'SELECTIVITY', 'ENCRYPT', 'SORT', 'NOVALIDATE', 'VALIDATE',
        'RELY', 'IDENTITY', 'SEQUENCE', 'ANNOTATIONS'
    ] as Set<String>)

    private void parseColumn(Cursor c, TableDef td) {
        Map id = ident(c)
        ColumnDef col = new ColumnDef(name: id.name, quoted: id.quoted, position: td.columns.size() + 1)
        if (!c.atEnd() && !c.isWord('AS', 'GENERATED')) parseType(c, col)
        String pendingName = null
        while (!c.atEnd()) {
            if (c.isWord('DEFAULT')) {
                c.next()
                boolean onNull = c.accept('ON', 'NULL')
                c.accept('FOR', 'INSERT', 'ONLY') // 23ai
                c.accept('FOR', 'INSERT', 'AND', 'UPDATE')
                col.defaultExpr = collectExpr(c)
                applyDefault(col, onNull)
            } else if (c.isWord('GENERATED')) {
                c.next()
                String kind = c.accept('ALWAYS') ? 'ALWAYS' : (c.accept('BY', 'DEFAULT') ? 'BY DEFAULT' : 'ALWAYS')
                boolean onNull = c.accept('ON', 'NULL')
                c.accept('AS')
                if (c.accept('IDENTITY')) {
                    col.generation = kind == 'ALWAYS' ? Generation.IDENTITY_ALWAYS
                            : (onNull ? Generation.IDENTITY_ON_NULL : Generation.IDENTITY_BY_DEFAULT)
                    if (c.isPunct('(')) c.group()
                } else {
                    if (c.isPunct('(')) col.virtualExpr = text(c.group())
                    col.generation = Generation.VIRTUAL
                }
            } else if (c.isWord('AS') && c.peek(1)?.isPunct('(')) {
                c.next()
                col.virtualExpr = text(c.group())
                col.generation = Generation.VIRTUAL
            } else if (c.isWord('AUTO_INCREMENT', 'AUTOINCREMENT', 'IDENTITY')) {
                c.next()
                col.generation = Generation.AUTO_INCREMENT
                if (c.isPunct('(')) c.group()
            } else if (c.accept('NOT', 'NULL')) {
                col.nullable = false
            } else if (c.accept('NULL')) {
                col.nullable = true
            } else if (c.accept('CONSTRAINT')) {
                pendingName = ident(c).name
            } else if (c.accept('PRIMARY', 'KEY')) {
                td.constraints << new ConstraintDef(name: pendingName, type: 'P', columns: [col.name])
                col.nullable = false
                pendingName = null
            } else if (c.accept('UNIQUE')) {
                td.constraints << new ConstraintDef(name: pendingName, type: 'U', columns: [col.name])
                pendingName = null
            } else if (c.accept('REFERENCES')) {
                List<Map> rp = nameParts(c)
                td.constraints << new ConstraintDef(name: pendingName, type: 'R', columns: [col.name],
                        refTable: rp[-1].name, refColumns: identList(c))
                pendingName = null
            } else if (c.accept('CHECK')) {
                td.constraints << new ConstraintDef(name: pendingName, type: 'C', columns: [col.name],
                        checkExpr: c.isPunct('(') ? text(c.group()) : null)
                pendingName = null
            } else if (c.isWord('COMMENT') && c.peek(1)?.type == 'S') {
                c.next()
                col.comment = c.next().value
            } else if (c.isPunct('(')) {
                c.group()
            } else {
                c.next() // ENABLE / VISIBLE / COLLATE x / ON DELETE CASCADE / identity オプションなど
            }
        }
        td.columns << col
    }

    private void parseType(Cursor c, ColumnDef col) {
        int start = c.i
        Tok first = c.peek()
        if (first.type != 'W' && first.type != 'Q') return
        String base = null
        for (List<String> mw : MULTI_WORD_TYPES) {
            if (c.isWords(mw as String[])) {
                base = mw.join(' ')
                c.i += mw.size()
                break
            }
        }
        if (base == null) {
            base = c.next().value ?: first.text
            while (c.isPunct('.') && c.peek(1) != null && c.peek(1).type in ['W', 'Q']) { // SYS.XMLTYPE
                c.next()
                base += '.' + c.next().value
            }
        }
        if (base in ['IDENTITY', 'SERIAL', 'BIGSERIAL']) { // H2 の旧構文 "ID IDENTITY"
            col.generation = Generation.AUTO_INCREMENT
            base = base == 'SERIAL' ? 'INTEGER' : 'BIGINT'
        }
        String typeName = base
        if (c.isPunct('(')) {
            List<Tok> args = c.group()
            List<List<Tok>> parts = splitTopLevel(args)
            Integer first1 = num(parts ? parts[0] : null)
            String unit = parts ? parts[0].find { it.type == 'W' }?.value : null
            if (LENGTH_TYPES.contains(base)) {
                col.length = first1
                col.charSemantics = unit == 'CHAR' || unit == 'CHARACTERS' || base.startsWith('N') || base.startsWith('NATIONAL')
            } else {
                col.precision = first1 // NUMBER(*,0) の * は null
                if (parts.size() > 1) col.scale = num(parts[1])
            }
        }
        if (base in ['TIMESTAMP', 'TIME'] && c.isWord('WITH', 'WITHOUT')) {
            List<String> ws = []
            while (!c.atEnd() && c.peek().type == 'W') {
                String w = c.next().value
                ws << w
                if (w == 'ZONE') break
            }
            typeName = base + ' ' + ws.join(' ')
        } else if (base == 'INTERVAL') {
            List<String> ws = []
            while (!c.atEnd()) {
                if (c.isPunct('(')) { c.group(); continue }
                if (c.peek().type != 'W') break
                String w = c.next().value
                ws << w
                if (w == 'MONTH' || w == 'SECOND') { if (c.isPunct('(')) c.group(); break }
            }
            typeName = 'INTERVAL ' + ws.join(' ')
        }
        if (c.isWord('ARRAY')) { c.next(); if (c.isPunct('[')) { while (!c.atEnd() && !c.isPunct(']')) c.next(); if (!c.atEnd()) c.next() }; typeName = 'ARRAY' }
        col.typeName = typeName
        col.rawType = text(c.t.subList(start, c.i))
    }

    private static Integer num(List<Tok> toks) {
        if (!toks) return null
        boolean neg = toks[0].isPunct('-')
        Tok n = toks.find { it.type == 'N' }
        if (n == null) return null // '*'
        Integer v = new BigDecimal(n.value).intValue()
        neg ? -v : v
    }

    /** DEFAULT 句の式を、次の列属性キーワードまで集める */
    private static String collectExpr(Cursor c) {
        List<Tok> out = []
        int depth = 0
        while (!c.atEnd()) {
            Tok tk = c.peek()
            if (depth == 0 && tk.type == 'W') {
                if (EXPR_STOP.contains(tk.value)) break
                if (tk.value == 'NULL' && !out.isEmpty()) break
                if (tk.value == 'ON' && c.peek(1)?.value == 'UPDATE') break
            }
            if (tk.isPunct('(')) depth++
            if (tk.isPunct(')')) depth--
            out << c.next()
        }
        text(out)
    }

    private static final String NAME_RE = /(?:"[^"]+"|[\p{L}_][\p{L}\w$#]*)/

    private static String unquote(String s) {
        s.startsWith('"') ? s.substring(1, s.length() - 1) : s.toUpperCase(Locale.ROOT)
    }

    /** DEFAULT 式からシーケンス採番を検出する（seq.NEXTVAL / NEXT VALUE FOR seq） */
    static void applyDefault(ColumnDef col, boolean onNull) {
        String e = col.defaultExpr ?: ''
        Matcher m1 = e =~ ("(?i)(?:(${NAME_RE})\\s*\\.\\s*)?(${NAME_RE})\\s*\\.\\s*\"?NEXTVAL\\b\"?")
        Matcher m2 = e =~ ("(?i)NEXT\\s+VALUE\\s+FOR\\s+(?:(${NAME_RE})\\s*\\.\\s*)?(${NAME_RE})")
        Matcher m = m1.find() ? m1 : (m2.find() ? m2 : null)
        if (m != null) {
            String seq = unquote(m.group(2))
            if (seq.startsWith('ISEQ$$')) {
                // Oracle の IDENTITY 列が内部で使うシーケンス
                if (col.generation == Generation.NONE) col.generation = onNull ? Generation.IDENTITY_ON_NULL : Generation.IDENTITY_BY_DEFAULT
            } else {
                col.generation = Generation.SEQUENCE_DEFAULT
                col.sequenceName = seq
            }
        }
    }

    // ---------------------------------------------------------------- その他の文

    private void parseSequence(Cursor c, SchemaModel m) {
        c.accept('IF', 'NOT', 'EXISTS')
        m.sequences << (nameParts(c)[-1].name as String)
    }

    private void parseComment(Cursor c, SchemaModel m) {
        c.next() // COMMENT
        if (!c.accept('ON')) return
        String kind = c.next().value
        if (kind == 'MATERIALIZED') { c.accept('VIEW'); kind = 'TABLE' }
        if (!(kind in ['TABLE', 'COLUMN', 'VIEW'])) { m.skipped << "COMMENT ON ${kind}".toString(); return }
        List<Map> parts = nameParts(c)
        if (!c.accept('IS')) return
        Tok s = c.next()
        String value = s.type == 'S' ? s.value : (s.isWord('NULL') ? null : s.text)
        pendingComments << [kind: kind, parts: parts, value: value]
    }

    private void parseTrigger(String sql) {
        Map found = detectTriggerSequence(sql)
        if (found) pendingTriggers << found
    }

    /**
     * BEFORE INSERT トリガーの本文から、シーケンス採番している列を検出する。
     * 対応パターン: SELECT seq.NEXTVAL INTO :NEW.col FROM DUAL / :NEW.col := seq.NEXTVAL
     * @return [schema, table, column, sequence] または null
     */
    static Map detectTriggerSequence(String sql) {
        String s = SqlScript.stripComments(sql)
        String qn = "(${NAME_RE}(?:\\s*\\.\\s*${NAME_RE})?)"
        Matcher on = s =~ ("(?is)\\bBEFORE\\b(.*?)\\bON\\s+${qn}")
        if (!on.find() || !(on.group(1).toUpperCase(Locale.ROOT).contains('INSERT'))) return null
        String table = on.group(2)
        Matcher p1 = s =~ ("(?is)SELECT\\s+${qn}\\s*\\.\\s*NEXTVAL\\s+INTO\\s+:NEW\\s*\\.\\s*(${NAME_RE})")
        Matcher p2 = s =~ ("(?is):NEW\\s*\\.\\s*(${NAME_RE})\\s*:=\\s*${qn}\\s*\\.\\s*NEXTVAL")
        String column = null
        String seq = null
        if (p1.find()) { seq = p1.group(1); column = p1.group(2) }
        else if (p2.find()) { column = p2.group(1); seq = p2.group(2) }
        if (column == null) return null
        List<String> tp = splitName(table)
        [schema: tp.size() > 1 ? tp[-2] : null, table: tp[-1], column: unquote(column), sequence: splitName(seq)[-1]]
    }

    static List<String> splitName(String qualified) {
        List<String> out = []
        Matcher m = qualified =~ NAME_RE
        while (m.find()) out << unquote(m.group())
        out
    }

    private void parseAlterTable(Cursor c, SchemaModel m) {
        c.accept('IF', 'EXISTS')
        List<Map> parts = nameParts(c)
        TableDef td = m.table(parts.size() > 1 ? parts[-2].name : null, parts[-1].name)
        if (td == null) {
            m.warnings << "ALTER TABLE ${parts*.name.join('.')}: 先に CREATE TABLE がないため無視しました".toString()
            return
        }
        while (!c.atEnd()) {
            if (c.accept('ADD')) {
                if (c.isPunct('(')) {
                    splitTopLevel(c.group()).each { List<Tok> e -> parseTableElement(e, td, m) }
                } else {
                    c.accept('COLUMN')
                    parseTableElement(new ArrayList<Tok>(c.t.subList(c.i, c.t.size())), td, m)
                    return
                }
            } else if (c.accept('MODIFY') || c.accept('ALTER', 'COLUMN')) {
                if (c.isPunct('(')) {
                    splitTopLevel(c.group()).each { List<Tok> e -> modifyColumn(new Cursor(e), td) }
                } else {
                    c.accept('COLUMN')
                    modifyColumn(new Cursor(new ArrayList<Tok>(c.t.subList(c.i, c.t.size()))), td)
                    return
                }
            } else {
                m.skipped << "ALTER TABLE ... ${c.next().text}".toString()
                return
            }
        }
    }

    private void modifyColumn(Cursor c, TableDef td) {
        ColumnDef col = td.column(ident(c).name as String)
        if (col == null) return
        while (!c.atEnd()) {
            if (c.accept('NOT', 'NULL')) col.nullable = false
            else if (c.accept('NULL')) col.nullable = true
            else if (c.accept('DEFAULT')) {
                boolean onNull = c.accept('ON', 'NULL')
                col.defaultExpr = collectExpr(c)
                applyDefault(col, onNull)
            } else if (c.isPunct('(')) c.group()
            else c.next()
        }
    }

    // ---------------------------------------------------------------- 仕上げ

    private void finish(SchemaModel m) {
        pendingComments.each { Map pc ->
            List<Map> p = pc.parts as List<Map>
            if (pc.kind == 'COLUMN') {
                if (p.size() < 2) return
                TableDef td = m.table(p.size() > 2 ? p[-3].name : null, p[-2].name)
                ColumnDef col = td?.column(p[-1].name as String)
                if (col) col.comment = pc.value
                else m.warnings << "COMMENT ON COLUMN ${p*.name.join('.')}: 対象の列が見つかりません".toString()
            } else {
                TableDef td = m.table(p.size() > 1 ? p[-2].name : null, p[-1].name)
                if (td) td.comment = pc.value
                else m.warnings << "COMMENT ON TABLE ${p*.name.join('.')}: 対象のテーブルが見つかりません".toString()
            }
        }
        pendingTriggers.each { Map tr ->
            TableDef td = m.table(tr.schema as String, tr.table as String)
            ColumnDef col = td?.column(tr.column as String)
            if (col && col.generation == Generation.NONE) {
                col.generation = Generation.TRIGGER_SEQUENCE
                col.sequenceName = tr.sequence
            }
        }
        m.tables.each { TableDef td -> finishTable(td, m.sequences, dialect) }
    }

    /** 型の対応付け・主キー列の NOT NULL 化・採番シーケンスの推定（メタデータ取得でも共通に使う） */
    static void finishTable(TableDef td, Collection<String> sequences, Dialect dialect) {
        td.primaryKey.each { String pk -> ColumnDef c = td.column(pk); if (c) c.nullable = false }
        td.columns.each { ColumnDef c ->
            c.logicalType = c.generation == Generation.VIRTUAL && c.typeName == null ? LogicalType.OTHER : TypeMapper.logicalType(c, dialect)
            if (dialect == Dialect.H2 && c.logicalType == LogicalType.STRING) c.charSemantics = true // H2 の長さは文字数
            if (c.generation == Generation.VIRTUAL && c.typeName != null) { /* 型つき仮想列は表示のみ */ }
        }
        List<String> pk = td.primaryKey
        if (pk.size() == 1 && sequences) {
            ColumnDef pc = td.column(pk[0])
            if (pc && pc.generation == Generation.NONE && pc.logicalType.numeric) {
                String t = td.name
                List<String> names = ["${t}_SEQ", "SEQ_${t}", "${t}_${pc.name}_SEQ", "S_${t}", "${t}_S", "SQ_${t}", "${t}_SQ"]*.toString()
                td.candidateSequences = names.findAll { String n -> sequences.any { it.equalsIgnoreCase(n) } }
            }
        }
    }
}
