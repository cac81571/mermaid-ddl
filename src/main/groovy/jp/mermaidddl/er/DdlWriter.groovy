package jp.mermaidddl.er

import groovy.transform.CompileStatic
import jp.mermaidddl.schema.ColumnDef
import jp.mermaidddl.schema.ConstraintDef
import jp.mermaidddl.schema.Dialect
import jp.mermaidddl.schema.Generation
import jp.mermaidddl.schema.Identifiers
import jp.mermaidddl.schema.SchemaModel
import jp.mermaidddl.schema.TableDef

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/** DDL 出力オプション */
@CompileStatic
class DdlOptions {
    Dialect dialect = Dialect.H2
    /** 外部キーを CREATE TABLE の後に ALTER TABLE ... ADD CONSTRAINT で出す */
    boolean foreignKeysAsAlter = false
    /** 先頭に DROP 文を出す */
    boolean includeDrop = false
    /** COMMENT ON TABLE / COLUMN を出す */
    boolean includeComments = true
    /** Oracle の識別子の上限バイト数（12.1 以前は 30、12.2 以降は 128） */
    int oracleMaxIdentifierBytes = Identifiers.ORACLE_MAX_BYTES
    /** テーブル名にスキーマ名を付ける（DB から読み込んだときのみ意味がある） */
    boolean qualifyWithSchema = false
}

/** DDL 出力結果 */
@CompileStatic
class DdlResult {
    String sql
    List<String> warnings = []
    /** 出力したテーブルの順序（参照先が先） */
    List<String> tableOrder = []
}

/**
 * テーブル定義から H2 / Oracle の DDL を出力する。
 * <ul>
 *   <li>参照されるテーブルが先になるように並べ替える（循環参照は ALTER TABLE に回す）</li>
 *   <li>制約名は PK_表, UK_表_列, FK_表_参照表（上限を超える場合は短縮してハッシュを付ける）</li>
 *   <li>COMMENT ON TABLE / COLUMN（H2 も対応）</li>
 * </ul>
 */
@CompileStatic
final class DdlWriter {

    private DdlWriter() {}

    static DdlResult write(SchemaModel m, DdlOptions o) {
        DdlResult r = new DdlResult()
        boolean ora = o.dialect == Dialect.ORACLE
        int maxBytes = ora ? o.oracleMaxIdentifierBytes : Identifiers.ORACLE_MAX_BYTES
        Set<String> tableNames = m.tables.collect { it.name } as Set<String>

        // ---- 識別子の長さ
        if (ora) {
            m.tables.each { TableDef td ->
                String w = Identifiers.checkOracleLength('テーブル名', td.name, maxBytes)
                if (w) r.warnings << w
                td.columns.each { ColumnDef c ->
                    String cw = Identifiers.checkOracleLength("列名（${td.name}）", c.name, maxBytes)
                    if (cw) r.warnings << cw
                }
            }
        }

        // ---- 制約名
        Set<String> used = new HashSet<>()
        Map<ConstraintDef, String> names = new IdentityHashMap<>()
        m.tables.each { TableDef td -> td.constraints.each { ConstraintDef c -> if (c.name) used << c.name.toUpperCase(Locale.ROOT) } }
        m.tables.each { TableDef td ->
            td.constraints.each { ConstraintDef c ->
                if (c.name) {
                    names.put(c, c.name)
                    if (ora) {
                        String w = Identifiers.checkOracleLength('制約名', c.name, maxBytes)
                        if (w) r.warnings << w
                    }
                    return
                }
                String base
                switch (c.type) {
                    case 'P': base = "PK_${td.name}"; break
                    case 'U': base = "UK_${td.name}_${c.columns.join('_')}"; break
                    case 'R': base = "FK_${td.name}_${c.refTable}"; break
                    default: base = "CK_${td.name}"
                }
                String n = unique(shorten(base.toString(), maxBytes), used, maxBytes)
                if (n != base) r.warnings << "制約名 ${base} は ${maxBytes} バイトを超えるか重複するため ${n} にしました".toString()
                used << n.toUpperCase(Locale.ROOT)
                names.put(c, n)
            }
        }

        // ---- 並べ替え（参照先が先）
        List<TableDef> remaining = new ArrayList<>(m.tables)
        List<TableDef> ordered = []
        Set<ConstraintDef> deferred = Collections.newSetFromMap(new IdentityHashMap<ConstraintDef, Boolean>())
        while (remaining) {
            TableDef next = remaining.find { TableDef t ->
                t.constraints.findAll { it.type == 'R' && !deferred.contains(it) }.every { ConstraintDef fk ->
                    fk.refTable == t.name || !tableNames.contains(fk.refTable) || ordered.any { it.name == fk.refTable }
                }
            }
            if (next == null) {
                next = remaining[0]
                next.constraints.findAll { ConstraintDef fk ->
                    fk.type == 'R' && fk.refTable != next.name && tableNames.contains(fk.refTable) && !ordered.any { it.name == fk.refTable }
                }.each { ConstraintDef fk ->
                    deferred << fk
                    r.warnings << "${next.name} → ${fk.refTable} は循環参照のため、外部キーを ALTER TABLE で後から追加します".toString()
                }
            }
            remaining.remove(next)
            ordered << next
        }
        r.tableOrder = ordered.collect { it.name }
        m.tables.each { TableDef td ->
            td.constraints.findAll { it.type == 'R' && !tableNames.contains(it.refTable) }.each { ConstraintDef fk ->
                r.warnings << "${td.name} の外部キーの参照先 ${fk.refTable} は出力対象に含まれていません（先に作成されている必要があります）".toString()
            }
        }

        StringBuilder sb = new StringBuilder()
        sb << "-- ${o.dialect == Dialect.ORACLE ? 'Oracle' : 'H2'} 用 DDL（mermaid-ddl で生成）\n"

        // ---- DROP
        Set<String> sequences = new LinkedHashSet<>(m.sequences)
        m.tables.each { TableDef td -> td.columns.each { ColumnDef c -> if (c.sequenceName) sequences << c.sequenceName } }
        if (o.includeDrop) {
            if (ora) sb << '-- テーブルが存在しない場合の ORA-00942 / ORA-02289 は無視してかまいません（アプリの「DDLを実行」では自動で無視します）\n'
            ordered.reverse().each { TableDef td ->
                sb << (ora ? "DROP TABLE ${tname(td, o)} CASCADE CONSTRAINTS;\n" : "DROP TABLE IF EXISTS ${tname(td, o)} CASCADE;\n")
            }
            sequences.each { String s -> sb << (ora ? "DROP SEQUENCE ${Identifiers.sql(s)};\n" : "DROP SEQUENCE IF EXISTS ${Identifiers.sql(s)};\n") }
            sb << '\n'
        }

        // ---- シーケンス
        sequences.each { String s -> sb << "CREATE SEQUENCE ${Identifiers.sql(s)} START WITH 1 INCREMENT BY 1;\n" }
        if (sequences) sb << '\n'

        // ---- CREATE TABLE
        List<String> alters = []
        ordered.each { TableDef td ->
            if (td.comment) sb << '-- ' << td.comment.readLines().join(' ') << '\n'
            sb << 'CREATE TABLE ' << tname(td, o) << ' (\n'
            List<String> lines = []
            int w = td.columns.collect { Identifiers.sql(it.name).length() }.max() ?: 0
            td.columns.each { ColumnDef c -> lines << '    ' + columnSql(c, o, w) }
            td.constraints.each { ConstraintDef c ->
                String cs = constraintSql(c, names.get(c), o)
                if (cs == null) return
                if (c.type == 'R' && (o.foreignKeysAsAlter || deferred.contains(c))) {
                    alters << "ALTER TABLE ${tname(td, o)} ADD ${cs};".toString()
                } else {
                    lines << '    ' + cs
                }
            }
            sb << lines.join(',\n') << '\n);\n'
            if (o.includeComments) {
                if (td.comment) sb << "COMMENT ON TABLE ${tname(td, o)} IS ${lit(td.comment)};\n"
                td.columns.findAll { it.comment }.each { ColumnDef c ->
                    sb << "COMMENT ON COLUMN ${tname(td, o)}.${Identifiers.sql(c.name)} IS ${lit(c.comment)};\n"
                }
            }
            sb << '\n'
        }
        if (alters) {
            sb << '-- 外部キー\n'
            alters.each { sb << it << '\n' }
        }
        r.sql = sb.toString().replaceAll(/\n{3,}/, '\n\n').trim() + '\n'
        r
    }

    private static String tname(TableDef td, DdlOptions o) {
        o.qualifyWithSchema && td.schema ? Identifiers.sql(td.schema, td.name) : Identifiers.sql(td.name)
    }

    static String columnSql(ColumnDef c, DdlOptions o, int width) {
        boolean ora = o.dialect == Dialect.ORACLE
        String name = Identifiers.sql(c.name)
        StringBuilder sb = new StringBuilder(name.padRight(width))
        sb << ' ' << (c.rawType ?: c.typeName ?: (ora ? 'VARCHAR2(255)' : 'VARCHAR(255)'))
        switch (c.generation) {
            case Generation.IDENTITY_ALWAYS:
                sb << ' GENERATED ALWAYS AS IDENTITY'
                break
            case Generation.IDENTITY_ON_NULL:
                sb << ' GENERATED BY DEFAULT ON NULL AS IDENTITY'
                break
            case Generation.IDENTITY_BY_DEFAULT:
            case Generation.AUTO_INCREMENT:
                sb << ' GENERATED BY DEFAULT AS IDENTITY'
                break
            case Generation.VIRTUAL:
                if (c.virtualExpr) sb << ' GENERATED ALWAYS AS (' << c.virtualExpr << ')' << (ora ? ' VIRTUAL' : '')
                break
            default:
                if (c.defaultExpr) sb << ' DEFAULT ' << defaultFor(c, o)
        }
        if (!c.nullable && c.generation != Generation.VIRTUAL) sb << ' NOT NULL'
        sb.toString()
    }

    /** シーケンス既定値は方言に合わせて書き換える（seq.NEXTVAL ⇔ NEXT VALUE FOR seq） */
    private static String defaultFor(ColumnDef c, DdlOptions o) {
        if (c.generation == Generation.SEQUENCE_DEFAULT && c.sequenceName) {
            String s = Identifiers.sql(c.sequenceName)
            return o.dialect == Dialect.ORACLE ? "${s}.NEXTVAL".toString() : "NEXT VALUE FOR ${s}".toString()
        }
        c.defaultExpr
    }

    static String constraintSql(ConstraintDef c, String name, DdlOptions o) {
        String cols = c.columns.collect { Identifiers.sql(it) }.join(', ')
        String head = name ? "CONSTRAINT ${Identifiers.sql(name)} " : ''
        switch (c.type) {
            case 'P': return "${head}PRIMARY KEY (${cols})".toString()
            case 'U': return "${head}UNIQUE (${cols})".toString()
            case 'R':
                String ref = c.refColumns ? ' (' + c.refColumns.collect { Identifiers.sql(it) }.join(', ') + ')' : ''
                return "${head}FOREIGN KEY (${cols}) REFERENCES ${Identifiers.sql(c.refTable)}${ref}".toString()
            case 'C':
                return c.checkExpr ? "${head}CHECK ${c.checkExpr.startsWith('(') ? c.checkExpr : '(' + c.checkExpr + ')'}".toString() : null
            default: return null
        }
    }

    private static String lit(String s) { "'" + s.replace("'", "''") + "'" }

    /** UTF-8 で maxBytes を超える名前を「先頭部分_ハッシュ4桁」に短縮する */
    static String shorten(String name, int maxBytes) {
        if (Identifiers.byteLength(name) <= maxBytes) return name
        CRC32 crc = new CRC32()
        crc.update(name.getBytes(StandardCharsets.UTF_8))
        String hash = String.format('%04X', crc.value & 0xFFFF)
        String head = cutBytes(name, maxBytes - 5).replaceAll(/_+$/, '')
        "${head}_${hash}".toString()
    }

    private static String unique(String name, Set<String> used, int maxBytes) {
        if (!used.contains(name.toUpperCase(Locale.ROOT))) return name
        for (int i = 2; ; i++) {
            String suffix = "_${i}"
            String cand = cutBytes(name, maxBytes - suffix.length()) + suffix
            if (!used.contains(cand.toUpperCase(Locale.ROOT))) return cand
        }
    }

    private static String cutBytes(String s, int maxBytes) {
        StringBuilder sb = new StringBuilder()
        int bytes = 0
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i)
            String ch = new String(Character.toChars(cp))
            int b = ch.getBytes(StandardCharsets.UTF_8).length
            if (bytes + b > maxBytes) break
            sb.append(ch)
            bytes += b
            i += Character.charCount(cp)
        }
        sb.toString()
    }
}
