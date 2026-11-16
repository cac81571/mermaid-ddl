package jp.mermaidddl.schema

import groovy.transform.CompileStatic

/**
 * DB の型 → 論理型の対応表（H2 / Oracle）。DDL 解析・メタデータ取り込み・ER 図の型変換で使う。
 *
 * <pre>
 * Oracle                         H2                                   論理型
 * ------------------------------ ------------------------------------ -------------
 * VARCHAR2(n [BYTE|CHAR])        VARCHAR / CHARACTER VARYING(n)       STRING
 * NVARCHAR2(n) / CHAR / NCHAR    CHAR / CHARACTER / VARCHAR_IGNORECASE STRING
 * CLOB / NCLOB / LONG            CLOB / CHARACTER LARGE OBJECT        CLOB
 * NUMBER(p) / NUMBER(p,0)        INTEGER / BIGINT / SMALLINT / TINYINT INTEGER（p≦18）
 * NUMBER(p,s) / NUMBER / FLOAT   NUMERIC / DECIMAL / DECFLOAT / DOUBLE DECIMAL
 * BINARY_FLOAT / BINARY_DOUBLE   REAL / DOUBLE PRECISION              DECIMAL
 * DATE（時刻を含む）               DATE                                 DATE
 * TIMESTAMP(n)                   TIMESTAMP                            DATETIME
 * TIMESTAMP WITH [LOCAL] TZ      TIMESTAMP WITH TIME ZONE             DATETIME_TZ
 * -                              TIME [WITH TIME ZONE]                TIME
 * BOOLEAN（23ai）                 BOOLEAN                              BOOLEAN
 * BLOB / RAW / LONG RAW / BFILE  BLOB / BINARY [VARYING] / VARBINARY  BINARY
 * ROWID / INTERVAL / XMLTYPE ... UUID / JSON / ARRAY / INTERVAL ...   OTHER
 * </pre>
 */
@CompileStatic
final class TypeMapper {

    private TypeMapper() {}

    private static final Set<String> STRING_TYPES = ([
        'VARCHAR2', 'NVARCHAR2', 'VARCHAR', 'NVARCHAR', 'CHAR', 'NCHAR', 'CHARACTER', 'CHARACTER VARYING',
        'CHAR VARYING', 'NATIONAL CHARACTER', 'NATIONAL CHAR', 'NATIONAL CHARACTER VARYING', 'NATIONAL CHAR VARYING',
        'VARCHAR_IGNORECASE', 'VARCHAR_CASESENSITIVE', 'LONG VARCHAR', 'ENUM'
    ] as Set<String>)
    private static final Set<String> CLOB_TYPES = ([
        'CLOB', 'NCLOB', 'LONG', 'CHARACTER LARGE OBJECT', 'NATIONAL CHARACTER LARGE OBJECT', 'NCHAR LARGE OBJECT',
        'CHAR LARGE OBJECT', 'TEXT', 'MEDIUMTEXT', 'LONGTEXT', 'TINYTEXT'
    ] as Set<String>)
    private static final Set<String> INTEGER_TYPES = ([
        'INTEGER', 'INT', 'SMALLINT', 'TINYINT', 'BIGINT', 'MEDIUMINT', 'INT2', 'INT4', 'INT8', 'PLS_INTEGER',
        'BINARY_INTEGER', 'SIGNED'
    ] as Set<String>)
    private static final Set<String> EXACT_NUMERIC = (['NUMBER', 'NUMERIC', 'DECIMAL', 'DEC'] as Set<String>)
    private static final Set<String> APPROX_NUMERIC = ([
        'FLOAT', 'REAL', 'DOUBLE', 'DOUBLE PRECISION', 'BINARY_FLOAT', 'BINARY_DOUBLE', 'DECFLOAT', 'FLOAT4', 'FLOAT8'
    ] as Set<String>)
    private static final Set<String> DATETIME_TYPES = ([
        'TIMESTAMP', 'DATETIME', 'SMALLDATETIME', 'TIMESTAMP WITHOUT TIME ZONE'
    ] as Set<String>)
    private static final Set<String> DATETIME_TZ_TYPES = ([
        'TIMESTAMP WITH TIME ZONE', 'TIMESTAMP WITH LOCAL TIME ZONE', 'TIMESTAMPTZ'
    ] as Set<String>)
    private static final Set<String> TIME_TYPES = (['TIME', 'TIME WITH TIME ZONE', 'TIME WITHOUT TIME ZONE'] as Set<String>)
    private static final Set<String> BOOLEAN_TYPES = (['BOOLEAN', 'BOOL', 'BIT'] as Set<String>)
    private static final Set<String> BINARY_TYPES = ([
        'BLOB', 'RAW', 'LONG RAW', 'BFILE', 'BINARY', 'VARBINARY', 'BINARY VARYING', 'BINARY LARGE OBJECT',
        'LONGVARBINARY', 'BYTEA', 'IMAGE', 'GEOMETRY'
    ] as Set<String>)

    /** 型名の基本部分として認識する単語（DDL パーサーが型の終わりを判断するのに使う） */
    static final Set<String> KNOWN_TYPE_NAMES = ((STRING_TYPES + CLOB_TYPES + INTEGER_TYPES + EXACT_NUMERIC + APPROX_NUMERIC
            + DATETIME_TYPES + DATETIME_TZ_TYPES + TIME_TYPES + BOOLEAN_TYPES + BINARY_TYPES
            + (['DATE', 'ROWID', 'UROWID', 'XMLTYPE', 'SYS.XMLTYPE', 'JSON', 'UUID', 'INTERVAL', 'SDO_GEOMETRY',
                'ARRAY', 'OTHER', 'JAVA_OBJECT', 'ROW', 'VECTOR'] as Set<String>)) as Set<String>).asImmutable()

    /** 型名を正規化する（大文字化、"(6)" などの桁指定を除去、空白をまとめる）。 */
    static String normalizeTypeName(String raw) {
        if (raw == null) return null
        raw.toUpperCase(Locale.ROOT).replaceAll(/\([^)]*\)/, ' ').replaceAll(/\s+/, ' ').trim()
    }

    /** 列の型情報から論理型を決める。 */
    static LogicalType logicalType(ColumnDef c, Dialect dialect) {
        String t = normalizeTypeName(c.typeName)
        if (t == null) return LogicalType.OTHER
        if (STRING_TYPES.contains(t)) return LogicalType.STRING
        if (CLOB_TYPES.contains(t)) return LogicalType.CLOB
        if (INTEGER_TYPES.contains(t)) return LogicalType.INTEGER
        if (EXACT_NUMERIC.contains(t)) {
            Integer p = c.precision
            Integer s = c.scale
            // Oracle: NUMBER(p) は NUMBER(p,0)。INTEGER は ディクショナリ上 NUMBER(precision=null, scale=0)
            boolean integral = (s == null && p != null) || (s != null && s == 0)
            if (t == 'NUMBER' && p == null && s == null) return LogicalType.DECIMAL // 精度指定なし NUMBER
            if (t != 'NUMBER' && p == null && s == null) return LogicalType.DECIMAL
            if (integral && (p == null || p <= 18)) return LogicalType.INTEGER
            return LogicalType.DECIMAL
        }
        if (APPROX_NUMERIC.contains(t)) return LogicalType.DECIMAL
        if (t == 'DATE') return LogicalType.DATE
        if (DATETIME_TYPES.contains(t)) return LogicalType.DATETIME
        if (DATETIME_TZ_TYPES.contains(t)) return LogicalType.DATETIME_TZ
        if (TIME_TYPES.contains(t)) return LogicalType.TIME
        if (BOOLEAN_TYPES.contains(t)) return LogicalType.BOOLEAN
        if (BINARY_TYPES.contains(t)) return LogicalType.BINARY
        LogicalType.OTHER
    }


}
