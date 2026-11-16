package jp.mermaidddl.schema

import groovy.transform.CompileStatic

import java.sql.Clob
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Types

/** 接続中の DB からテーブル定義（列・型・コメント・主キー・採番方式）を読み込む。 */
@CompileStatic
abstract class MetadataReader {

    /** テーブル一覧の 1 行 */
    static class TableEntry {
        String schema
        String name
        String type
        String comment

        @Override
        String toString() { comment ? "${name}（${comment}）" : name }
    }

    abstract Dialect getDialect()

    /** 接続ユーザーの既定スキーマ */
    abstract String defaultSchema(Connection con)

    abstract List<TableEntry> listTables(Connection con, String schema)

    abstract TableDef readTable(Connection con, String schema, String table)

    /** 接続先の製品名から読み取り方法を選ぶ（H2 / Oracle 以外は例外） */
    static MetadataReader forConnection(Connection con) {
        Dialect d = Dialect.detect(con.metaData.databaseProductName)
        if (d == Dialect.ORACLE) return new OracleMetadataReader()
        if (d == Dialect.H2) return new H2MetadataReader()
        throw new SQLException("メタデータの読み込みは H2 と Oracle のみ対応しています（接続先: ${con.metaData.databaseProductName}）")
    }

    // ---------- JDBC ヘルパー ----------

    /**
     * SQL を実行し、全行を「列名（大文字）→ 値」のマップで返す。
     * <p>
     * Oracle の LONG 型（ALL_TAB_COLS.DATA_DEFAULT、ALL_TRIGGERS.TRIGGER_BODY など）は JDBC ではストリームとして
     * 送られてくるため、<b>各行の列を SELECT の並び順に 1 回ずつだけ</b>読む必要がある
     * （同じ列を 2 回読む・LONG より後ろの列を先に読むと ORA-17027「ストリームはすでにクローズ済です」になる）。
     * そのためここでは列名・型を先に取得しておき、各行は 1 列目から順に、値を 1 回だけ取り出す。
     * LONG / CLOB は getString で文字列として受け取る。SELECT では LONG 列を最後に置くこと。
     */
    protected static List<Map<String, Object>> query(Connection con, String sql, List<Object> params) {
        PreparedStatement ps = con.prepareStatement(sql)
        try {
            params.eachWithIndex { Object p, int i -> ps.setObject(i + 1, p) }
            ResultSet rs = ps.executeQuery()
            try {
                ResultSetMetaData md = rs.metaData
                int n = md.columnCount
                String[] labels = new String[n]
                boolean[] asString = new boolean[n]
                for (int i = 0; i < n; i++) {
                    labels[i] = md.getColumnLabel(i + 1).toUpperCase(Locale.ROOT)
                    int t = md.getColumnType(i + 1)
                    asString[i] = t == Types.LONGVARCHAR || t == Types.LONGNVARCHAR || t == Types.CLOB || t == Types.NCLOB
                }
                List<Map<String, Object>> rows = []
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>()
                    for (int i = 0; i < n; i++) {
                        Object v = asString[i] ? rs.getString(i + 1) : rs.getObject(i + 1)
                        if (v instanceof Clob) {
                            Clob clob = (Clob) v
                            long len = clob.length()
                            v = clob.getSubString(1L, (int) Math.min(len, (long) Integer.MAX_VALUE))
                        }
                        row.put(labels[i], v)
                    }
                    rows << row
                }
                return rows
            } finally {
                rs.close()
            }
        } finally {
            ps.close()
        }
    }

    /** 列が存在しない（古いバージョンのディクショナリ）エラーか。Oracle: ORA-00904、H2: 42122 */
    protected static boolean isMissingColumn(SQLException e) {
        for (Throwable t = e; t != null; t = t.cause) {
            if (t instanceof SQLException) {
                SQLException se = (SQLException) t
                if (se.errorCode == 904 || se.errorCode == 42122 || se.SQLState == '42S22') return true
                if (se.message?.contains('ORA-00904')) return true
            }
        }
        false
    }

    /** エラー表示用に SQL を 1 行に縮める */
    protected static String compactSql(String sql) {
        String s = sql.replaceAll(/\s+/, ' ').trim()
        s.length() > 160 ? s.substring(0, 160) + ' ...' : s
    }

    /** 制約の行（CONSTRAINT_NAME, CONSTRAINT_TYPE, COLUMN_NAME, R_TABLE_NAME, R_COLUMN_NAME）を制約定義にまとめる */
    protected static void addConstraints(TableDef td, List<Map<String, Object>> rows, String uniqueType, String fkType) {
        Map<String, ConstraintDef> byName = new LinkedHashMap<>()
        rows.each { Map<String, Object> r ->
            String name = str(r.CONSTRAINT_NAME)
            String type = str(r.CONSTRAINT_TYPE) == uniqueType ? 'U' : (str(r.CONSTRAINT_TYPE) == fkType ? 'R' : null)
            if (type == null) return
            ConstraintDef c = byName.get(name)
            if (c == null) {
                c = new ConstraintDef(name: name, type: type)
                byName.put(name, c)
            }
            c.columns << str(r.COLUMN_NAME)
            if (type == 'R') {
                if (r.R_TABLE_NAME) c.refTable = str(r.R_TABLE_NAME)
                if (r.R_COLUMN_NAME) c.refColumns << str(r.R_COLUMN_NAME)
            }
        }
        byName.values().each { ConstraintDef c -> if (c.type == 'U' || c.refTable) td.constraints << c }
    }

    protected static String str(Object o) { o == null ? null : o.toString().trim() }

    protected static Integer intOrNull(Object o) {
        if (o == null) return null
        if (o instanceof Number) return ((Number) o).intValue()
        String s = o.toString().trim()
        s ? new BigDecimal(s).intValue() : null
    }
}

/**
 * Oracle のデータディクショナリから読み込む。
 * 使用ビュー: ALL_TAB_COMMENTS, ALL_TAB_COLS, ALL_COL_COMMENTS, ALL_TAB_IDENTITY_COLS（12c 以降）,
 * ALL_CONSTRAINTS, ALL_CONS_COLUMNS, ALL_TRIGGERS, ALL_SEQUENCES
 */
@CompileStatic
class OracleMetadataReader extends MetadataReader {

    static final String SQL_TABLES = '''\
SELECT c.table_name, c.table_type, c.comments
  FROM all_tab_comments c
 WHERE c.owner = ?
   AND c.table_type IN ('TABLE', 'VIEW')
   AND c.table_name NOT LIKE 'BIN$%'
 ORDER BY c.table_name'''

    /**
     * 12.2 以降。既定値は VARCHAR2 の DATA_DEFAULT_VC を使い、LONG 型の DATA_DEFAULT を読まない。
     * （DATA_DEFAULT_VC は既定値が 4000 バイトを超えると NULL）
     */
    static final String SQL_COLUMNS_122 = '''\
SELECT c.column_name, c.data_type, c.data_length, c.char_length, c.char_used, c.data_precision, c.data_scale,
       c.nullable, c.column_id, c.virtual_column, c.identity_column, c.default_on_null, cc.comments,
       c.data_default_vc AS data_default
  FROM all_tab_cols c
  LEFT JOIN all_col_comments cc
         ON cc.owner = c.owner AND cc.table_name = c.table_name AND cc.column_name = c.column_name
 WHERE c.owner = ? AND c.table_name = ? AND c.hidden_column = 'NO'
 ORDER BY c.column_id'''

    /** 12.1（IDENTITY_COLUMN / DEFAULT_ON_NULL あり）。DATA_DEFAULT は LONG 型なので最後に置く */
    static final String SQL_COLUMNS_12C = '''\
SELECT c.column_name, c.data_type, c.data_length, c.char_length, c.char_used, c.data_precision, c.data_scale,
       c.nullable, c.column_id, c.virtual_column, c.identity_column, c.default_on_null, cc.comments, c.data_default
  FROM all_tab_cols c
  LEFT JOIN all_col_comments cc
         ON cc.owner = c.owner AND cc.table_name = c.table_name AND cc.column_name = c.column_name
 WHERE c.owner = ? AND c.table_name = ? AND c.hidden_column = 'NO'
 ORDER BY c.column_id'''

    /** 11g 以前。DATA_DEFAULT は LONG 型なので最後に置く */
    static final String SQL_COLUMNS_11G = '''\
SELECT c.column_name, c.data_type, c.data_length, c.char_length, c.char_used, c.data_precision, c.data_scale,
       c.nullable, c.column_id, c.virtual_column, cc.comments, c.data_default
  FROM all_tab_cols c
  LEFT JOIN all_col_comments cc
         ON cc.owner = c.owner AND cc.table_name = c.table_name AND cc.column_name = c.column_name
 WHERE c.owner = ? AND c.table_name = ? AND c.hidden_column = 'NO'
 ORDER BY c.column_id'''

    static final String SQL_IDENTITY = '''\
SELECT column_name, generation_type
  FROM all_tab_identity_cols
 WHERE owner = ? AND table_name = ?'''

    static final String SQL_PRIMARY_KEY = '''\
SELECT c.constraint_name, cc.column_name
  FROM all_constraints c
  JOIN all_cons_columns cc
    ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name AND cc.table_name = c.table_name
 WHERE c.owner = ? AND c.table_name = ? AND c.constraint_type = 'P'
 ORDER BY cc.position'''

    /** 一意制約と外部キー（参照先の列は位置で対応付ける） */
    static final String SQL_CONSTRAINTS = '''\
SELECT c.constraint_name, c.constraint_type, cc.column_name, cc.position, r.table_name AS r_table_name, rc.column_name AS r_column_name
  FROM all_constraints c
  JOIN all_cons_columns cc
    ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name AND cc.table_name = c.table_name
  LEFT JOIN all_constraints r
    ON r.owner = c.r_owner AND r.constraint_name = c.r_constraint_name
  LEFT JOIN all_cons_columns rc
    ON rc.owner = r.owner AND rc.constraint_name = r.constraint_name AND rc.position = cc.position
 WHERE c.owner = ? AND c.table_name = ? AND c.constraint_type IN ('U', 'R')
 ORDER BY c.constraint_name, cc.position'''

    /** TRIGGER_BODY は LONG 型なので最後に置く */
    static final String SQL_TRIGGERS = '''\
SELECT trigger_name, trigger_type, triggering_event, trigger_body
  FROM all_triggers
 WHERE table_owner = ? AND table_name = ? AND status = 'ENABLED'
   AND trigger_type LIKE 'BEFORE%' AND triggering_event LIKE '%INSERT%\''''

    static final String SQL_SEQUENCES = '''\
SELECT sequence_name FROM all_sequences WHERE sequence_owner = ?'''

    @Override
    Dialect getDialect() { Dialect.ORACLE }

    @Override
    String defaultSchema(Connection con) {
        try {
            String s = con.getSchema()
            if (s) return s
        } catch (Throwable ignore) {
            // 古いドライバ
        }
        try {
            List<Map<String, Object>> r = query(con, "SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS s FROM dual", [])
            if (r && r[0].S) return str(r[0].S)
        } catch (SQLException ignore) {
            // 続行
        }
        con.metaData.userName?.toUpperCase(Locale.ROOT)
    }

    @Override
    List<TableEntry> listTables(Connection con, String schema) {
        query(con, SQL_TABLES, [schema as Object]).collect { Map<String, Object> r ->
            new TableEntry(schema: schema, name: str(r.TABLE_NAME), type: str(r.TABLE_TYPE), comment: str(r.COMMENTS))
        }
    }

    /** 実行中の読み込み手順（エラー表示用） */
    private String step
    private String stepSql

    private List<Map<String, Object>> q(Connection con, String what, String sql, List<Object> params) {
        step = what
        stepSql = sql
        query(con, sql, params)
    }

    @Override
    TableDef readTable(Connection con, String schema, String table) {
        try {
            return doReadTable(con, schema, table)
        } catch (SQLException e) {
            throw new SQLException("テーブル ${schema}.${table} の読み込みに失敗しました（${step}）: ${e.message}\nSQL: ${compactSql(stepSql)}".toString(),
                    e.SQLState, e.errorCode, e)
        }
    }

    private TableDef doReadTable(Connection con, String schema, String table) {
        TableDef td = new TableDef(schema: schema, name: table, quoted: !(table ==~ /[A-Z][A-Z0-9_$#]*/))
        List<Object> key = [schema as Object, table]
        List<Map<String, Object>> tabs = q(con, 'テーブルコメント ALL_TAB_COMMENTS',
                'SELECT table_type, comments FROM all_tab_comments WHERE owner = ? AND table_name = ?', key)
        if (tabs) {
            td.comment = str(tabs[0].COMMENTS)
            td.tableType = str(tabs[0].TABLE_TYPE) ?: 'TABLE'
        }

        // 列定義: 12.2+（DATA_DEFAULT_VC）→ 12.1（IDENTITY_COLUMN あり）→ 11g の順に試す。
        // 列が無いエラー（ORA-00904）のときだけ次へ進み、それ以外のエラーはそのまま報告する。
        List<Map<String, Object>> cols
        boolean has12c = true
        try {
            cols = q(con, '列定義 ALL_TAB_COLS（12.2 以降）', SQL_COLUMNS_122, key)
        } catch (SQLException e122) {
            if (!isMissingColumn(e122)) throw e122
            try {
                cols = q(con, '列定義 ALL_TAB_COLS（12.1）', SQL_COLUMNS_12C, key)
            } catch (SQLException e12) {
                if (!isMissingColumn(e12)) throw e12
                has12c = false
                td.notes << '12c 未満のディクショナリとして読み込みました（IDENTITY 列なし）'
                cols = q(con, '列定義 ALL_TAB_COLS（11g）', SQL_COLUMNS_11G, key)
            }
        }
        if (!cols) throw new SQLException("テーブル ${schema}.${table} が見つからないか、参照権限がありません")

        Map<String, String> identity = [:]
        if (has12c) {
            try {
                q(con, 'IDENTITY 列 ALL_TAB_IDENTITY_COLS', SQL_IDENTITY, key).each { Map<String, Object> r ->
                    identity.put(str(r.COLUMN_NAME), str(r.GENERATION_TYPE))
                }
            } catch (SQLException ignore) {
                // ビューが無い・権限なし
            }
        }

        cols.each { Map<String, Object> r -> td.columns << toColumn(r, identity) }

        List<Map<String, Object>> pk = q(con, '主キー ALL_CONSTRAINTS / ALL_CONS_COLUMNS', SQL_PRIMARY_KEY, key)
        if (pk) {
            td.constraints << new ConstraintDef(name: str(pk[0].CONSTRAINT_NAME), type: 'P',
                    columns: pk.collect { Map<String, Object> r -> str(r.COLUMN_NAME) })
        }
        try {
            addConstraints(td, q(con, '一意制約・外部キー ALL_CONSTRAINTS', SQL_CONSTRAINTS, key), 'U', 'R')
        } catch (SQLException e) {
            td.notes << "一意制約・外部キーを読み込めませんでした: ${e.message}".toString()
        }

        // 11g 方式のトリガー採番
        try {
            q(con, 'トリガー ALL_TRIGGERS', SQL_TRIGGERS, key).each { Map<String, Object> r ->
                String sql = "CREATE TRIGGER ${r.TRIGGER_NAME} ${r.TRIGGER_TYPE} ${r.TRIGGERING_EVENT} ON \"${table}\"\n${r.TRIGGER_BODY}"
                Map found = DdlParser.detectTriggerSequence(sql)
                ColumnDef c = found ? td.column(found.column as String) : null
                if (c && c.generation == Generation.NONE) {
                    c.generation = Generation.TRIGGER_SEQUENCE
                    c.sequenceName = found.sequence
                }
            }
        } catch (SQLException e) {
            td.notes << "トリガーを読み込めませんでした（トリガー採番の検出をスキップ）: ${e.message}".toString()
        }

        List<String> sequences = []
        try {
            sequences = q(con, 'シーケンス ALL_SEQUENCES', SQL_SEQUENCES, [schema as Object]).collect { Map<String, Object> r -> str(r.SEQUENCE_NAME) }
        } catch (SQLException ignore) {
            // 権限なし
        }
        DdlParser.finishTable(td, sequences, Dialect.ORACLE)
        td
    }

    /** ALL_TAB_COLS の 1 行を列定義に変換する */
    static ColumnDef toColumn(Map<String, Object> r, Map<String, String> identity) {
        String dataType = str(r.DATA_TYPE)
        ColumnDef c = new ColumnDef(name: str(r.COLUMN_NAME), position: intOrNull(r.COLUMN_ID) ?: 0)
        c.quoted = !(c.name ==~ /[A-Z][A-Z0-9_$#]*/)
        c.typeName = TypeMapper.normalizeTypeName(dataType)
        String charUsed = str(r.CHAR_USED)
        if (charUsed) {
            c.charSemantics = charUsed == 'C'
            c.length = c.charSemantics ? intOrNull(r.CHAR_LENGTH) : intOrNull(r.DATA_LENGTH)
        } else if (c.typeName in ['RAW']) {
            c.length = intOrNull(r.DATA_LENGTH)
        }
        c.precision = intOrNull(r.DATA_PRECISION)
        c.scale = intOrNull(r.DATA_SCALE)
        c.rawType = rawType(c, dataType)
        c.nullable = str(r.NULLABLE) != 'N'
        c.comment = str(r.COMMENTS)
        c.defaultExpr = str(r.DATA_DEFAULT)
        boolean onNull = str(r.DEFAULT_ON_NULL) == 'YES'
        if (str(r.VIRTUAL_COLUMN) == 'YES') {
            c.generation = Generation.VIRTUAL
            c.virtualExpr = c.defaultExpr
            c.defaultExpr = null
        } else if (str(r.IDENTITY_COLUMN) == 'YES' || identity.containsKey(c.name)) {
            String g = identity.get(c.name)
            c.generation = g == 'ALWAYS' ? Generation.IDENTITY_ALWAYS : (onNull ? Generation.IDENTITY_ON_NULL : Generation.IDENTITY_BY_DEFAULT)
        } else if (c.defaultExpr) {
            DdlParser.applyDefault(c, onNull)
        }
        c
    }

    private static String rawType(ColumnDef c, String dataType) {
        if (c.length != null && c.typeName in ['VARCHAR2', 'CHAR']) return "${c.typeName}(${c.length} ${c.charSemantics ? 'CHAR' : 'BYTE'})"
        if (c.length != null) return "${c.typeName}(${c.length})"
        if (c.typeName == 'NUMBER' && c.precision != null) return c.scale ? "NUMBER(${c.precision},${c.scale})" : "NUMBER(${c.precision})"
        if (c.typeName == 'NUMBER' && c.scale == 0) return 'INTEGER'
        dataType
    }
}

/** H2 の INFORMATION_SCHEMA から読み込む（H2 2.x）。 */
@CompileStatic
class H2MetadataReader extends MetadataReader {

    static final String SQL_TABLES = '''\
SELECT TABLE_NAME, TABLE_TYPE, REMARKS
  FROM INFORMATION_SCHEMA.TABLES
 WHERE TABLE_SCHEMA = ? AND TABLE_TYPE IN ('BASE TABLE', 'VIEW', 'TABLE')
 ORDER BY TABLE_NAME'''

    static final String SQL_COLUMNS = '''\
SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_PRECISION_RADIX, NUMERIC_SCALE,
       IS_NULLABLE, COLUMN_DEFAULT, IS_IDENTITY, IDENTITY_GENERATION, IS_GENERATED, REMARKS, ORDINAL_POSITION
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
 ORDER BY ORDINAL_POSITION'''

    static final String SQL_PRIMARY_KEY = '''\
SELECT tc.CONSTRAINT_NAME, k.COLUMN_NAME
  FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
  JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
    ON k.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
 WHERE tc.TABLE_SCHEMA = ? AND tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'PRIMARY KEY'
 ORDER BY k.ORDINAL_POSITION'''

    static final String SQL_CONSTRAINTS = '''\
SELECT tc.CONSTRAINT_NAME, tc.CONSTRAINT_TYPE, k.COLUMN_NAME, k.ORDINAL_POSITION, uk.TABLE_NAME AS R_TABLE_NAME, uk.COLUMN_NAME AS R_COLUMN_NAME
  FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
  JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
    ON k.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
  LEFT JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc
    ON rc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND rc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
  LEFT JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE uk
    ON uk.CONSTRAINT_SCHEMA = rc.UNIQUE_CONSTRAINT_SCHEMA AND uk.CONSTRAINT_NAME = rc.UNIQUE_CONSTRAINT_NAME
   AND uk.ORDINAL_POSITION = k.POSITION_IN_UNIQUE_CONSTRAINT
 WHERE tc.TABLE_SCHEMA = ? AND tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE IN ('UNIQUE', 'FOREIGN KEY')
 ORDER BY tc.CONSTRAINT_NAME, k.ORDINAL_POSITION'''

    static final String SQL_SEQUENCES = '''\
SELECT SEQUENCE_NAME FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = ?'''

    @Override
    Dialect getDialect() { Dialect.H2 }

    @Override
    String defaultSchema(Connection con) {
        con.getSchema() ?: 'PUBLIC'
    }

    @Override
    List<TableEntry> listTables(Connection con, String schema) {
        query(con, SQL_TABLES, [schema as Object]).collect { Map<String, Object> r ->
            new TableEntry(schema: schema, name: str(r.TABLE_NAME), type: str(r.TABLE_TYPE) == 'VIEW' ? 'VIEW' : 'TABLE',
                    comment: str(r.REMARKS))
        }
    }

    @Override
    TableDef readTable(Connection con, String schema, String table) {
        TableDef td = new TableDef(schema: schema, name: table, quoted: !(table ==~ /[A-Z][A-Z0-9_$#]*/))
        List<Map<String, Object>> tabs = query(con,
                'SELECT TABLE_TYPE, REMARKS FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?',
                [schema as Object, table])
        if (!tabs) throw new SQLException("テーブル ${schema}.${table} が見つかりません")
        td.comment = str(tabs[0].REMARKS)
        td.tableType = str(tabs[0].TABLE_TYPE) == 'VIEW' ? 'VIEW' : 'TABLE'

        query(con, SQL_COLUMNS, [schema as Object, table]).each { Map<String, Object> r -> td.columns << toColumn(r) }

        List<Map<String, Object>> pk = query(con, SQL_PRIMARY_KEY, [schema as Object, table])
        if (pk) {
            td.constraints << new ConstraintDef(name: str(pk[0].CONSTRAINT_NAME), type: 'P',
                    columns: pk.collect { Map<String, Object> r -> str(r.COLUMN_NAME) })
        }
        addConstraints(td, query(con, SQL_CONSTRAINTS, [schema as Object, table]), 'UNIQUE', 'FOREIGN KEY')
        List<String> sequences = query(con, SQL_SEQUENCES, [schema as Object]).collect { Map<String, Object> r -> str(r.SEQUENCE_NAME) }
        DdlParser.finishTable(td, sequences, Dialect.H2)
        td
    }

    static ColumnDef toColumn(Map<String, Object> r) {
        ColumnDef c = new ColumnDef(name: str(r.COLUMN_NAME), position: intOrNull(r.ORDINAL_POSITION) ?: 0)
        c.quoted = !(c.name ==~ /[A-Z][A-Z0-9_$#]*/)
        c.typeName = TypeMapper.normalizeTypeName(str(r.DATA_TYPE))
        Integer len = intOrNull(r.CHARACTER_MAXIMUM_LENGTH)
        c.length = len != null && len > 0 && len < 1_000_000 ? len : null
        c.charSemantics = true
        if (intOrNull(r.NUMERIC_PRECISION_RADIX) == 10 && c.typeName in ['NUMERIC', 'DECIMAL', 'NUMBER', 'DECFLOAT']) {
            c.precision = intOrNull(r.NUMERIC_PRECISION)
            c.scale = c.typeName == 'DECFLOAT' ? null : intOrNull(r.NUMERIC_SCALE)
        }
        c.rawType = c.length != null ? "${c.typeName}(${c.length})" :
                (c.precision != null ? "${c.typeName}(${c.precision}${c.scale != null ? ',' + c.scale : ''})" : c.typeName)
        c.nullable = str(r.IS_NULLABLE) != 'NO'
        c.comment = str(r.REMARKS)
        c.defaultExpr = str(r.COLUMN_DEFAULT)
        if (str(r.IS_GENERATED) == 'ALWAYS') {
            c.generation = Generation.VIRTUAL
        } else if (str(r.IS_IDENTITY) == 'YES') {
            c.generation = str(r.IDENTITY_GENERATION) == 'ALWAYS' ? Generation.IDENTITY_ALWAYS : Generation.IDENTITY_BY_DEFAULT
        } else if (c.defaultExpr) {
            DdlParser.applyDefault(c, false)
        }
        c
    }
}
