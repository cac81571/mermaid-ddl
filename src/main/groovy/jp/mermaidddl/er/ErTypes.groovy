package jp.mermaidddl.er

import groovy.transform.CompileStatic
import jp.mermaidddl.schema.ColumnDef
import jp.mermaidddl.schema.Dialect
import jp.mermaidddl.schema.LogicalType
import jp.mermaidddl.schema.TypeMapper

import java.util.regex.Matcher

/**
 * ER 図の型表記と H2 / Oracle の SQL 型の相互変換。
 *
 * <ul>
 *   <li>汎用型（string, int, decimal, date, datetime, text, bool ...）は方言ごとの型に変換する</li>
 *   <li>varchar2(50) や number(10,2) のような DB 固有の型は、同じ方言ならそのまま、別方言なら同等の型に変換する</li>
 *   <li>Mermaid の属性型には空白を書けないので、複数語の型は _ でつなぐ（timestamp_with_time_zone, varchar2(50_char)）</li>
 * </ul>
 */
@CompileStatic
final class ErTypes {

    private ErTypes() {}

    /** 長さ省略時の文字列長 */
    public static final int DEFAULT_STRING_LENGTH = 255

    /** 変換結果 */
    static class SqlType {
        String sql
        String warning
    }

    /** 型表記を分解したもの */
    static class Parsed {
        /** 大文字・空白区切りの型名（例: TIMESTAMP WITH TIME ZONE） */
        String name
        List<String> args = []
        /** BYTE / CHAR 指定（VARCHAR2(10 CHAR)） */
        String semantics
        /** string[] のような配列 */
        boolean array
        String original
    }

    private static final Set<String> MULTI_WORD = ([
        'TIMESTAMP WITH TIME ZONE', 'TIMESTAMP WITH LOCAL TIME ZONE', 'TIMESTAMP WITHOUT TIME ZONE', 'TIME WITH TIME ZONE',
        'TIME WITHOUT TIME ZONE', 'DOUBLE PRECISION', 'CHARACTER VARYING', 'NATIONAL CHARACTER VARYING', 'NATIONAL CHARACTER',
        'CHAR VARYING', 'NCHAR VARYING', 'CHARACTER LARGE OBJECT', 'BINARY LARGE OBJECT', 'NATIONAL CHARACTER LARGE OBJECT',
        'BINARY VARYING', 'LONG RAW', 'INTERVAL YEAR TO MONTH', 'INTERVAL DAY TO SECOND'
    ] as Set<String>)

    /** 型表記を分解する（Mermaid 表記の _ 区切りも受け付ける） */
    static Parsed parse(String raw) {
        Parsed p = new Parsed(original: raw)
        String s = (raw ?: '').trim()
        if (s.endsWith('[]')) {
            p.array = true
            s = s.substring(0, s.length() - 2)
        }
        // 括弧の中身を取り出す（TIMESTAMP(6) WITH TIME ZONE / INTERVAL DAY(2) TO SECOND(6) は最初の括弧を精度とみなす）
        StringBuilder name = new StringBuilder()
        String args = null
        int i = 0
        while (i < s.length()) {
            char c = s.charAt(i)
            if (c == (char) '(') {
                int j = s.indexOf(')', i)
                if (j < 0) j = s.length()
                if (args == null) args = s.substring(i + 1, j)
                i = j + 1
                continue
            }
            name.append(c)
            i++
        }
        String n = name.toString().trim().toUpperCase(Locale.ROOT).replaceAll(/\s+/, ' ')
        if (!MULTI_WORD.contains(n) && !TypeMapper.KNOWN_TYPE_NAMES.contains(n)) {
            String spaced = n.replace('_', ' ').replaceAll(/\s+/, ' ').trim()
            if (MULTI_WORD.contains(spaced) || TypeMapper.KNOWN_TYPE_NAMES.contains(spaced)) n = spaced
        }
        p.name = n
        if (args != null) {
            args.replace('_', ' ').split(',').each { String a ->
                String t = a.trim()
                Matcher mm = (t =~ /(?i)^(\S+)\s+(BYTE|CHAR)$/)
                if (mm.matches()) {
                    p.args << mm.group(1)
                    p.semantics = mm.group(2).toUpperCase(Locale.ROOT)
                } else if (t) {
                    p.args << t
                }
            }
        }
        p
    }

    private static Integer intArg(Parsed p, int idx) {
        if (p.args.size() <= idx) return null
        String a = p.args[idx]
        a ==~ /\d+/ ? Integer.valueOf(a) : null
    }

    private static String argList(Parsed p) {
        p.args ? '(' + p.args.join(',') + ')' : ''
    }

    /** ER 図の型表記を方言の SQL 型にする */
    static SqlType toSql(String raw, Dialect dialect) {
        Parsed p = parse(raw)
        boolean ora = dialect == Dialect.ORACLE
        SqlType r = new SqlType()
        if (!p.name) {
            r.sql = ora ? "VARCHAR2(${DEFAULT_STRING_LENGTH} CHAR)" : "VARCHAR(${DEFAULT_STRING_LENGTH})"
            r.warning = '型が空のため文字列として扱いました'
            return r
        }
        if (p.array) {
            if (ora) {
                r.sql = 'CLOB'
                r.warning = "配列型 ${raw} は Oracle にないため CLOB にしました"
            } else {
                r.sql = toSql(raw.substring(0, raw.length() - 2), dialect).sql + ' ARRAY'
            }
            return r
        }
        Integer a0 = intArg(p, 0)
        Integer a1 = intArg(p, 1)
        String n = p.name
        switch (n) {
            // ---------- 文字列
            case 'STRING': case 'STR': case 'VARCHAR': case 'CHARACTER VARYING': case 'CHAR VARYING': case 'NVARCHAR':
            case 'NATIONAL CHARACTER VARYING': case 'NCHAR VARYING': case 'VARCHAR_IGNORECASE': case 'VARCHAR_CASESENSITIVE':
                int len = a0 ?: DEFAULT_STRING_LENGTH
                if (ora && len > 4000 && !p.semantics) {
                    r.sql = 'CLOB'
                    r.warning = "${raw} は VARCHAR2 の上限（4000 バイト）を超えるため CLOB にしました"
                } else if (ora) {
                    if (n == 'NVARCHAR' || n.startsWith('NATIONAL') || n == 'NCHAR VARYING') {
                        r.sql = "NVARCHAR2(${len})"
                    } else {
                        // 汎用の string は文字数指定（CHAR）、varchar(n) は Oracle の既定（BYTE）
                        String sem = p.semantics ?: (n == 'VARCHAR' ? null : 'CHAR')
                        r.sql = sem ? "VARCHAR2(${len} ${sem})" : "VARCHAR2(${len})"
                    }
                } else {
                    r.sql = n == 'VARCHAR_IGNORECASE' ? "VARCHAR_IGNORECASE(${len})" : "VARCHAR(${len})"
                }
                return r
            case 'VARCHAR2': case 'NVARCHAR2':
                int len2 = a0 ?: DEFAULT_STRING_LENGTH
                if (ora) {
                    r.sql = n == 'NVARCHAR2' ? "NVARCHAR2(${len2})" : (p.semantics ? "VARCHAR2(${len2} ${p.semantics})" : "VARCHAR2(${len2})")
                } else {
                    r.sql = "VARCHAR(${len2})"
                }
                if (!a0) r.warning = "${raw} に長さがないため ${len2} にしました"
                return r
            case 'CHAR': case 'CHARACTER': case 'NCHAR': case 'NATIONAL CHARACTER':
                int clen = a0 ?: 1
                if (ora) {
                    r.sql = n in ['NCHAR', 'NATIONAL CHARACTER'] ? "NCHAR(${clen})" : (p.semantics ? "CHAR(${clen} ${p.semantics})" : "CHAR(${clen})")
                } else {
                    r.sql = "CHAR(${clen})"
                }
                return r
            case 'TEXT': case 'CLOB': case 'NCLOB': case 'LONGTEXT': case 'MEDIUMTEXT': case 'TINYTEXT': case 'NTEXT':
            case 'CHARACTER LARGE OBJECT': case 'NATIONAL CHARACTER LARGE OBJECT':
                r.sql = ora && n == 'NCLOB' ? 'NCLOB' : 'CLOB'
                return r
            case 'JSON': case 'JSONB':
                r.sql = ora ? 'CLOB' : 'JSON'
                if (ora) r.warning = "${raw} は CLOB にしました（Oracle 21c 以降なら JSON 型も使えます）"
                return r
            case 'XML': case 'XMLTYPE':
                r.sql = ora ? 'XMLTYPE' : 'CLOB'
                return r
            case 'UUID': case 'GUID': case 'UNIQUEIDENTIFIER':
                r.sql = ora ? 'RAW(16)' : 'UUID'
                return r
            case 'ENUM':
                r.sql = ora ? "VARCHAR2(${DEFAULT_STRING_LENGTH} CHAR)" : "VARCHAR(${DEFAULT_STRING_LENGTH})"
                r.warning = 'ENUM は文字列にしました（必要なら CHECK 制約を追加してください）'
                return r
            // ---------- 整数
            case 'INT': case 'INTEGER': case 'INT4': case 'MEDIUMINT': case 'SIGNED':
                // Oracle: integer は Oracle の INTEGER（NUMBER(*,0)）のまま、int などは NUMBER(10)
                if (ora) r.sql = n == 'INTEGER' ? 'INTEGER' : 'NUMBER(10)'
                else r.sql = 'INTEGER'
                return r
            case 'BIGINT': case 'INT8': case 'SERIAL8': case 'BIGSERIAL': case 'LONG':
                // long は汎用型の 64 ビット整数として扱う（Oracle の LONG 型は ER 図では clob と書く）
                r.sql = ora ? 'NUMBER(19)' : 'BIGINT'
                return r
            case 'SMALLINT': case 'INT2': case 'SHORT':
                r.sql = ora ? 'NUMBER(5)' : 'SMALLINT'
                return r
            case 'TINYINT': case 'BYTE':
                r.sql = ora ? 'NUMBER(3)' : 'TINYINT'
                return r
            case 'SERIAL':
                r.sql = ora ? 'NUMBER(10)' : 'INTEGER'
                r.warning = 'SERIAL は整数にしました（自動採番は [IDENTITY] タグで指定してください）'
                return r
            // ---------- 10 進数
            case 'NUMBER':
                if (ora) {
                    r.sql = 'NUMBER' + argList(p)
                } else if (a0 != null && (a1 == null || a1 == 0) && a0 <= 9 && p.args.size() <= 2) {
                    r.sql = "NUMERIC(${a0})"
                } else {
                    r.sql = p.args && p.args[0] != '*' ? 'NUMERIC' + argList(p) : (a1 != null ? "NUMERIC(38,${a1})" : 'NUMERIC')
                }
                return r
            case 'DECIMAL': case 'NUMERIC': case 'DEC':
                if (ora) r.sql = 'NUMBER' + argList(p)
                else r.sql = (n == 'DEC' ? 'DECIMAL' : n) + argList(p)
                return r
            case 'MONEY': case 'CURRENCY':
                r.sql = ora ? 'NUMBER(19,4)' : 'DECIMAL(19,4)'
                return r
            case 'FLOAT':
                if (a0 != null) r.sql = "FLOAT(${a0})"
                else r.sql = ora ? 'BINARY_DOUBLE' : 'DOUBLE PRECISION'
                return r
            case 'REAL': case 'FLOAT4': case 'BINARY_FLOAT':
                r.sql = ora ? 'BINARY_FLOAT' : 'REAL'
                return r
            case 'DOUBLE': case 'DOUBLE PRECISION': case 'FLOAT8': case 'BINARY_DOUBLE':
                r.sql = ora ? 'BINARY_DOUBLE' : 'DOUBLE PRECISION'
                return r
            case 'DECFLOAT':
                r.sql = ora ? 'NUMBER' : 'DECFLOAT' + argList(p)
                return r
            // ---------- 日付・時刻
            case 'DATE':
                r.sql = 'DATE'
                return r
            case 'DATETIME': case 'DATETIME2': case 'TIMESTAMP': case 'TIMESTAMP WITHOUT TIME ZONE': case 'SMALLDATETIME':
                r.sql = 'TIMESTAMP' + (a0 != null ? "(${a0})" : '')
                return r
            case 'TIMESTAMPTZ': case 'DATETIMEOFFSET': case 'TIMESTAMP WITH TIME ZONE':
                r.sql = 'TIMESTAMP' + (a0 != null ? "(${a0})" : '') + ' WITH TIME ZONE'
                return r
            case 'TIMESTAMP WITH LOCAL TIME ZONE':
                if (ora) {
                    r.sql = 'TIMESTAMP' + (a0 != null ? "(${a0})" : '') + ' WITH LOCAL TIME ZONE'
                } else {
                    r.sql = 'TIMESTAMP' + (a0 != null ? "(${a0})" : '') + ' WITH TIME ZONE'
                    r.warning = 'H2 に WITH LOCAL TIME ZONE はないため WITH TIME ZONE にしました'
                }
                return r
            case 'TIME': case 'TIME WITHOUT TIME ZONE':
                if (ora) {
                    r.sql = 'VARCHAR2(8)'
                    r.warning = "Oracle に TIME 型はないため ${raw} を VARCHAR2(8)（HH:MI:SS 文字列）にしました"
                } else {
                    r.sql = 'TIME' + (a0 != null ? "(${a0})" : '')
                }
                return r
            case 'TIMETZ': case 'TIME WITH TIME ZONE':
                if (ora) {
                    r.sql = 'VARCHAR2(14)'
                    r.warning = "Oracle に TIME 型はないため ${raw} を文字列にしました"
                } else {
                    r.sql = 'TIME WITH TIME ZONE'
                }
                return r
            case 'INTERVAL YEAR TO MONTH': case 'INTERVAL DAY TO SECOND':
                r.sql = n
                if (a0 != null && n.startsWith('INTERVAL DAY')) r.sql = "INTERVAL DAY(${a0}) TO SECOND"
                if (a0 != null && n.startsWith('INTERVAL YEAR')) r.sql = "INTERVAL YEAR(${a0}) TO MONTH"
                return r
            // ---------- 真偽値
            case 'BOOL': case 'BOOLEAN': case 'BIT':
                if (ora) {
                    r.sql = 'NUMBER(1)'
                    r.warning = "${raw} は NUMBER(1)（0/1）にしました（Oracle 23ai なら BOOLEAN も使えます）"
                } else {
                    r.sql = 'BOOLEAN'
                }
                return r
            // ---------- バイナリ
            case 'BLOB': case 'BYTES': case 'BYTEA': case 'IMAGE': case 'LONGBLOB': case 'MEDIUMBLOB': case 'BINARY LARGE OBJECT':
            case 'LONG RAW':
                r.sql = 'BLOB'
                if (n == 'LONG RAW') r.warning = 'LONG RAW は非推奨のため BLOB にしました'
                return r
            case 'RAW': case 'BINARY': case 'VARBINARY': case 'BINARY VARYING':
                int blen = a0 ?: (n == 'BINARY' ? 1 : 2000)
                if (ora) r.sql = blen <= 2000 ? "RAW(${blen})" : 'BLOB'
                else r.sql = n == 'BINARY' ? "BINARY(${blen})" : "VARBINARY(${blen})"
                return r
            case 'ROWID': case 'UROWID':
                r.sql = ora ? n : 'VARCHAR(18)'
                if (!ora) r.warning = "${n} は H2 にないため VARCHAR(18) にしました"
                return r
            default:
                r.sql = n + argList(p)
                r.warning = "型「${raw}」は変換表にないため、そのまま出力しました"
                return r
        }
    }

    /** SQL の型を Mermaid の属性型表記にする（空白は _、大文字は小文字） */
    static String toMermaid(String sqlType) {
        String s = (sqlType ?: 'unknown').trim().toLowerCase(Locale.ROOT)
        s = s.replaceAll(/\s*\(\s*/, '(').replaceAll(/\s*\)\s*/, ')').replaceAll(/\s*,\s*/, ',')
        s = s.replaceAll(/\)(?=\w)/, ') ')
        // H2 の標準型名を短い別名にする
        s = s.replaceAll(/^character large object(\(-?\d+\))?/, 'clob').replaceAll(/^binary large object(\(-?\d+\))?/, 'blob')
             .replaceAll(/^character varying/, 'varchar').replaceAll(/^binary varying/, 'varbinary')
             .replaceAll(/^character(?=\(|$)/, 'char')
        // Oracle の非推奨型 LONG / LONG RAW は clob / blob（ER 図の long は整数の意味になるため）
        if (s == 'long') s = 'clob'
        // SQL の INT は H2 / Oracle とも INTEGER と同じ（ER 図の int は Oracle では NUMBER(10) になるため integer にする）
        if (s == 'int') s = 'integer'
        if (s == 'long raw') s = 'blob'
        // numeric(10,0) → numeric(10)
        s = s.replaceAll(/^(number|numeric|decimal)\((\d+),0\)/, '$1($2)')
        s.replaceAll(/\s+/, '_')
    }

    /** 列定義を汎用型の Mermaid 表記にする */
    static String toGeneric(ColumnDef c) {
        switch (c.logicalType) {
            case LogicalType.STRING:
                return c.length ? "string(${c.length})" : 'string'
            case LogicalType.CLOB: return 'text'
            case LogicalType.INTEGER:
                if (c.typeName in ['BIGINT', 'INT8']) return 'bigint'
                if (c.typeName in ['SMALLINT', 'TINYINT']) return c.typeName.toLowerCase(Locale.ROOT)
                return c.precision != null && c.precision > 9 ? 'bigint' : 'int'
            case LogicalType.DECIMAL:
                if (c.typeName in ['BINARY_DOUBLE', 'DOUBLE', 'DOUBLE PRECISION', 'FLOAT', 'FLOAT8']) return 'double'
                if (c.typeName in ['BINARY_FLOAT', 'REAL']) return 'float'
                return c.precision != null ? "decimal(${c.precision},${c.scale ?: 0})" : 'decimal'
            case LogicalType.DATE: return 'date'
            case LogicalType.DATETIME: return 'datetime'
            case LogicalType.DATETIME_TZ: return 'timestamptz'
            case LogicalType.TIME: return 'time'
            case LogicalType.BOOLEAN: return 'bool'
            case LogicalType.BINARY: return 'blob'
            default: return toMermaid(c.rawType ?: c.typeName)
        }
    }
}
