package jp.mermaidddl.schema

import groovy.transform.CompileStatic

import java.nio.charset.StandardCharsets

/**
 * 識別子のルール（H2 / Oracle 共通）。
 * <ul>
 *   <li>引用符なしの識別子は大文字に正規化される。"..." で囲むと大文字小文字・記号・日本語を保持する</li>
 *   <li>Oracle の識別子長: 12.1 以前は 30 バイト、12.2 以降は 128 バイト（DB 文字セットでのバイト数。ここでは UTF-8 で概算）</li>
 * </ul>
 */
@CompileStatic
final class Identifiers {

    private Identifiers() {}

    public static final int ORACLE_LEGACY_MAX_BYTES = 30
    public static final int ORACLE_MAX_BYTES = 128

    /** 引用なしで書ける識別子（英字始まり、英数字と _ $ #） */
    private static final String SIMPLE = /[A-Z][A-Z0-9_$#]*/

    /** 引用が必要になる代表的な予約語（H2 / Oracle） */
    public static final Set<String> RESERVED = ([
        'ACCESS', 'ADD', 'ALL', 'ALTER', 'AND', 'ANY', 'AS', 'ASC', 'AUDIT', 'BETWEEN', 'BY', 'CHAR', 'CHECK',
        'CLUSTER', 'COLUMN', 'COMMENT', 'COMPRESS', 'CONNECT', 'CONSTRAINT', 'CREATE', 'CROSS', 'CURRENT', 'CURRENT_DATE',
        'CURRENT_TIME', 'CURRENT_TIMESTAMP', 'CURRENT_USER', 'DATE', 'DAY', 'DECIMAL', 'DEFAULT', 'DELETE', 'DESC', 'DISTINCT', 'DROP',
        'ELSE', 'EXCEPT', 'EXCLUSIVE', 'EXISTS', 'FALSE', 'FETCH', 'FILE', 'FLOAT', 'FOR', 'FOREIGN', 'FROM', 'FULL', 'GRANT',
        'GROUP', 'HAVING', 'HOUR', 'IDENTIFIED', 'IF', 'IMMEDIATE', 'IN', 'INCREMENT', 'INDEX', 'INITIAL', 'INNER', 'INSERT',
        'INTEGER', 'INTERSECT', 'INTERVAL', 'INTO', 'IS', 'JOIN', 'KEY', 'LEFT', 'LEVEL', 'LIKE', 'LIMIT', 'LOCK', 'LONG',
        'MAXEXTENTS', 'MINUS', 'MINUTE', 'MLSLABEL', 'MODE', 'MODIFY', 'MONTH', 'NATURAL', 'NOAUDIT', 'NOCOMPRESS', 'NOT',
        'NOWAIT', 'NULL', 'NUMBER', 'OF', 'OFFLINE', 'OFFSET', 'ON', 'ONLINE', 'OPTION', 'OR', 'ORDER', 'PCTFREE',
        'PRIMARY', 'PRIOR', 'PUBLIC', 'RAW', 'RENAME', 'RESOURCE', 'REVOKE', 'RIGHT', 'ROW', 'ROWID', 'ROWNUM', 'ROWS',
        'SECOND', 'SELECT', 'SESSION', 'SET', 'SHARE', 'SIZE', 'SMALLINT', 'START', 'SUCCESSFUL', 'SYNONYM', 'SYSDATE',
        'TABLE', 'THEN', 'TO', 'TRIGGER', 'TRUE', 'UID', 'UNION', 'UNIQUE', 'UPDATE', 'USER', 'USING', 'VALIDATE', 'VALUE', 'VALUES',
        'VARCHAR', 'VARCHAR2', 'VIEW', 'WHENEVER', 'WHERE', 'WITH', 'YEAR'
    ] as Set<String>).asImmutable()

    /** 引用符なしの識別子を正規化（大文字化）する。 */
    static String normalize(String raw, boolean quoted) {
        quoted ? raw : raw.toUpperCase(Locale.ROOT)
    }

    /** SQL に書くときの表記（必要なら "..." で囲む）。 */
    static String sql(String name) {
        if (name ==~ SIMPLE && !RESERVED.contains(name)) return name
        '"' + name.replace('"', '""') + '"'
    }

    static String sql(String schema, String name) {
        schema ? sql(schema) + '.' + sql(name) : sql(name)
    }

    static int byteLength(String name) {
        name == null ? 0 : name.getBytes(StandardCharsets.UTF_8).length
    }

    /**
     * Oracle の識別子長チェック。問題なければ null、あればメッセージ。
     * @param maxBytes 30（12.1 以前）または 128（12.2 以降）
     */
    static String checkOracleLength(String kind, String name, int maxBytes) {
        int len = byteLength(name)
        if (len > ORACLE_MAX_BYTES) {
            return "${kind}「${name}」は ${len} バイトで、Oracle の上限 128 バイトを超えています".toString()
        }
        if (len > ORACLE_LEGACY_MAX_BYTES) {
            if (maxBytes <= ORACLE_LEGACY_MAX_BYTES) {
                return "${kind}「${name}」は ${len} バイトで、Oracle 12.1 以前の上限 30 バイトを超えています".toString()
            }
            return "${kind}「${name}」は ${len} バイトです（Oracle 12.2 以降のみ可。12.1 以前は 30 バイトまで）".toString()
        }
        null
    }

}
