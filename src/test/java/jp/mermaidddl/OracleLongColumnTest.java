package jp.mermaidddl;

import static jp.mermaidddl.MetadataReaderTest.exec;
import static jp.mermaidddl.MetadataReaderTest.oracleDictionary;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jp.mermaidddl.er.DdlOptions;
import jp.mermaidddl.er.DdlWriter;
import jp.mermaidddl.er.ErDdlService;
import jp.mermaidddl.er.SchemaToErOptions;
import jp.mermaidddl.schema.ColumnDef;
import jp.mermaidddl.schema.Dialect;
import jp.mermaidddl.schema.Generation;
import jp.mermaidddl.schema.OracleMetadataReader;
import jp.mermaidddl.schema.TableDef;
import org.junit.jupiter.api.Test;

/**
 * Oracle の LONG 列（ALL_TAB_COLS.DATA_DEFAULT、ALL_TRIGGERS.TRIGGER_BODY など）の読み方の回帰テスト。
 * <p>
 * 実際の ojdbc は LONG をストリームで返すため、同じ行で LONG 列を 2 回読む・LONG より後ろの列を先に読むと
 * ORA-17027「ストリームはすでにクローズ済です」になる。H2 ではこの制約が無いので、ResultSet をラップして同じ制約を再現する。
 */
class OracleLongColumnTest {

    /** LONG 型として扱う列（Oracle のディクショナリで LONG のもの） */
    static final Set<String> LONG_COLUMNS = Set.of("DATA_DEFAULT", "TRIGGER_BODY", "SEARCH_CONDITION", "TEXT");

    /** LONG 列の読み取り記録 */
    static final class LongLog {
        final List<String> longReads = new ArrayList<>();
        final List<String> sqls = new ArrayList<>();
    }

    /** 接続をラップし、問い合わせ結果に LONG ストリームの制約を課す */
    static Connection longAware(Connection real, LongLog log) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (p, m, a) -> {
                    if (m.getName().equals("prepareStatement")) log.sqls.add((String) a[0]);
                    Object r = invoke(real, m, a);
                    if (m.getName().equals("prepareStatement") && r instanceof PreparedStatement) {
                        return wrapStatement((PreparedStatement) r, (String) a[0], log);
                    }
                    return r;
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method m, Object[] a) throws Throwable {
        try {
            return m.invoke(target, a);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static PreparedStatement wrapStatement(PreparedStatement real, String sql, LongLog log) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (p, m, a) -> {
                    Object r = invoke(real, m, a);
                    if (m.getName().equals("executeQuery") && r instanceof ResultSet) {
                        return wrapResultSet((ResultSet) r, sql, log);
                    }
                    return r;
                });
    }

    /** 基になる列名（別名ではなく）が LONG 列か。12.2 の「data_default_vc AS data_default」は LONG ではない */
    private static boolean isLong(ResultSetMetaData md, int i, String sql) throws SQLException {
        String label = md.getColumnLabel(i).toUpperCase(Locale.ROOT);
        if (!LONG_COLUMNS.contains(label)) return false;
        return !sql.toLowerCase(Locale.ROOT).contains("data_default_vc as " + label.toLowerCase(Locale.ROOT));
    }

    private static ResultSet wrapResultSet(ResultSet real, String sql, LongLog log) throws SQLException {
        ResultSetMetaData rmd = real.getMetaData();
        int n = rmd.getColumnCount();
        boolean[] longCol = new boolean[n + 1];
        for (int i = 1; i <= n; i++) longCol[i] = isLong(rmd, i, sql);
        ResultSetMetaData md = (ResultSetMetaData) Proxy.newProxyInstance(ResultSetMetaData.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class}, (p, m, a) -> {
                    // Oracle と同じく LONG 列の型は LONGVARCHAR
                    if (m.getName().equals("getColumnType") && longCol[(Integer) a[0]]) return Types.LONGVARCHAR;
                    if (m.getName().equals("getColumnTypeName") && longCol[(Integer) a[0]]) return "LONG";
                    return invoke(rmd, m, a);
                });
        Set<Integer> readInRow = new HashSet<>();
        int[] maxRead = {0};
        InvocationHandler h = (p, m, a) -> {
            String name = m.getName();
            if (name.equals("getMetaData")) return md;
            if (name.equals("next")) {
                readInRow.clear();
                maxRead[0] = 0;
                return invoke(real, m, a);
            }
            if (name.startsWith("get") && a != null && a.length >= 1 && (a[0] instanceof Integer || a[0] instanceof String)
                    && !name.equals("getMetaData") && !name.equals("getStatement")) {
                int idx = a[0] instanceof Integer ? (Integer) a[0] : real.findColumn((String) a[0]);
                if (idx >= 1 && idx <= n) {
                    if (longCol[idx]) {
                        if (readInRow.contains(idx)) {
                            throw new SQLException("ORA-17027: ストリームはすでにクローズ済です（LONG 列 " + idx + " を 2 回読み込み）", "99999", 17027);
                        }
                        if (maxRead[0] > idx) {
                            throw new SQLException("ORA-17027: ストリームはすでにクローズ済です（LONG 列 " + idx
                                    + " より後ろの列 " + maxRead[0] + " を先に読み込み）", "99999", 17027);
                        }
                        log.longReads.add(rmd.getColumnLabel(idx).toUpperCase(Locale.ROOT));
                    } else {
                        // LONG 列を読まずに後ろの列へ進むと、LONG のストリームは読み捨てられる
                        for (int j = 1; j < idx; j++) if (longCol[j]) readInRow.add(j);
                    }
                    readInRow.add(idx);
                    maxRead[0] = Math.max(maxRead[0], idx);
                }
            }
            return invoke(real, m, a);
        };
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, h);
    }

    /** ユーザー報告のテーブル（USER02.ORDERS）を ALL_* に登録する */
    private static void addOrders(Connection c, boolean is12c) throws Exception {
        String id = is12c ? ",'YES','NO'" : "";
        String no = is12c ? ",'NO','NO'" : "";
        exec(c,
                "INSERT INTO ALL_TAB_COMMENTS VALUES ('USER02','ORDERS','TABLE',NULL)",
                "INSERT INTO ALL_TAB_COLS VALUES ('USER02','ORDERS','ORDER_ID','NUMBER',22,0,NULL,10,0,'N',1,'NO','NO'" + (is12c ? id : "")
                        + ",'\"USER02\".\"ISEQ$$_74512\".nextval')",
                "INSERT INTO ALL_TAB_COLS VALUES ('USER02','ORDERS','CUSTOMER_ID','NUMBER',22,0,NULL,10,0,'Y',2,'NO','NO'" + no + ",NULL)",
                // 実際の DATA_DEFAULT は末尾に空白・改行が付くことが多い
                "INSERT INTO ALL_TAB_COLS VALUES ('USER02','ORDERS','ORDERED_AT','TIMESTAMP(6)',11,0,NULL,NULL,6,'Y',3,'NO','NO'" + no
                        + ",'CURRENT_TIMESTAMP\n')",
                "INSERT INTO ALL_TAB_COLS VALUES ('USER02','ORDERS','AMOUNT','NUMBER',22,0,NULL,10,2,'Y',4,'NO','NO'" + no + ",NULL)",
                "INSERT INTO ALL_CONSTRAINTS (OWNER, CONSTRAINT_NAME, TABLE_NAME, CONSTRAINT_TYPE) VALUES ('USER02','PK_ORDERS','ORDERS','P')",
                "INSERT INTO ALL_CONS_COLUMNS VALUES ('USER02','PK_ORDERS','ORDERS','ORDER_ID',1)",
                "INSERT INTO ALL_TRIGGERS VALUES ('USER02','ORDERS_BI','BEFORE EACH ROW','INSERT','USER02','ORDERS','ENABLED',"
                        + "'BEGIN\n  :NEW.AMOUNT := NVL(:NEW.AMOUNT, 0);\nEND;')");
        if (is12c) exec(c, "INSERT INTO ALL_TAB_IDENTITY_COLS VALUES ('USER02','ORDERS','ORDER_ID','BY DEFAULT')");
    }

    private static void assertOrders(TableDef t) {
        assertEquals(4, t.getColumns().size());
        assertEquals(List.of("ORDER_ID"), t.getPrimaryKey());
        assertEquals("PK_ORDERS", t.getPrimaryKeyConstraint().getName());
        ColumnDef at = t.column("ORDERED_AT");
        assertEquals("CURRENT_TIMESTAMP", at.getDefaultExpr());
        assertEquals("TIMESTAMP", at.getTypeName());
        assertEquals(10, t.column("AMOUNT").getPrecision());
        assertEquals(2, t.column("AMOUNT").getScale());
    }

    @Test
    void ラッパーはLONG列の二重読みと順序違反を検出する() throws Exception {
        try (Connection raw = oracleDictionary("long_guard", true)) {
            addOrders(raw, true);
            Connection c = longAware(raw, new LongLog());
            String sql = "SELECT column_name, data_default FROM all_tab_cols WHERE owner = 'USER02' ORDER BY column_id";
            // 修正前の読み方: rs.getObject(i) を 2 回（instanceof の判定と値の取得）
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                rs.getObject(1);
                rs.getObject(2);
                SQLException e = assertThrows(SQLException.class, () -> rs.getObject(2));
                assertTrue(e.getMessage().contains("ORA-17027"));
            }
            // LONG より後ろの列を先に読む
            String sql2 = "SELECT data_default, column_name FROM all_tab_cols WHERE owner = 'USER02' ORDER BY column_id";
            try (PreparedStatement ps = c.prepareStatement(sql2); ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                rs.getString(2);
                assertThrows(SQLException.class, () -> rs.getString(1));
                assertTrue(rs.next());
                assertDoesNotThrow(() -> rs.getString("DATA_DEFAULT"), "次の行ではまた読める");
            }
        }
    }

    @Test
    void Oracle12_1_LONGのDATA_DEFAULTとTRIGGER_BODYを順序どおり1回だけ読む() throws Exception {
        try (Connection raw = oracleDictionary("long_121", true)) {
            addOrders(raw, true);
            LongLog log = new LongLog();
            TableDef t = new OracleMetadataReader().readTable(longAware(raw, log), "USER02", "ORDERS");
            assertOrders(t);
            assertEquals(Generation.IDENTITY_BY_DEFAULT, t.column("ORDER_ID").getGeneration());
            assertNull(t.column("ORDER_ID").getSequenceName(), "ISEQ$$ は IDENTITY の内部シーケンス");
            assertEquals(4, log.longReads.stream().filter("DATA_DEFAULT"::equals).count(), "各行 1 回ずつ");
            assertTrue(log.longReads.contains("TRIGGER_BODY"));
            assertTrue(log.sqls.stream().anyMatch(s -> s.contains("data_default_vc")), "まず 12.2 用を試す");
            assertTrue(t.getNotes().stream().noneMatch(n -> n.contains("読み込めません")), t.getNotes().toString());
        }
    }

    @Test
    void Oracle12_2以降はDATA_DEFAULT_VCを使いLONGを読まない() throws Exception {
        try (Connection raw = oracleDictionary("long_122", true)) {
            addOrders(raw, true);
            exec(raw, "ALTER TABLE ALL_TAB_COLS ADD DATA_DEFAULT_VC VARCHAR2(4000)",
                    "UPDATE ALL_TAB_COLS SET DATA_DEFAULT_VC = CAST(DATA_DEFAULT AS VARCHAR2(4000))");
            LongLog log = new LongLog();
            TableDef t = new OracleMetadataReader().readTable(longAware(raw, log), "USER02", "ORDERS");
            assertOrders(t);
            assertEquals(Generation.IDENTITY_BY_DEFAULT, t.column("ORDER_ID").getGeneration());
            assertFalse(log.longReads.contains("DATA_DEFAULT"), "列定義では LONG を読まない: " + log.longReads);
            assertFalse(t.getNotes().stream().anyMatch(n -> n.contains("12c 未満")));

            // DB から ER 図 → DDL（ユーザー報告の操作「DBから読込」）
            ErDdlService.Result r = ErDdlService.dbToMermaid(new OracleMetadataReader(), longAware(raw, new LongLog()),
                    "USER02", List.of("ORDERS"), new SchemaToErOptions());
            assertTrue(r.isOk(), r.getErrors().toString());
            assertTrue(r.getText().contains("ORDERED_AT \"[DEFAULT CURRENT_TIMESTAMP]\""), r.getText());
            assertTrue(r.getText().contains("ORDER_ID PK \"[IDENTITY]\""), r.getText());
            DdlOptions o = new DdlOptions();
            o.setDialect(Dialect.ORACLE);
            String ddl = DdlWriter.write(r.getModel(), o).getSql();
            assertTrue(ddl.contains("ORDER_ID    NUMBER(10) GENERATED BY DEFAULT AS IDENTITY NOT NULL"), ddl);
            assertTrue(ddl.contains("ORDERED_AT  TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP"), ddl);
            assertTrue(ddl.contains("CONSTRAINT PK_ORDERS PRIMARY KEY (ORDER_ID)"), ddl);
        }
    }

    @Test
    void Oracle11g_LONGのDATA_DEFAULTを読む() throws Exception {
        try (Connection raw = oracleDictionary("long_11", false)) {
            addOrders(raw, false);
            LongLog log = new LongLog();
            TableDef t = new OracleMetadataReader().readTable(longAware(raw, log), "USER02", "ORDERS");
            assertOrders(t);
            assertTrue(t.getNotes().stream().anyMatch(n -> n.contains("12c 未満")));
            assertEquals(4, log.longReads.stream().filter("DATA_DEFAULT"::equals).count());
        }
    }

    @Test
    void 列が無い以外のエラーは握りつぶさずテーブル名と手順を表示する() throws Exception {
        try (Connection raw = oracleDictionary("long_err", true)) {
            addOrders(raw, true);
            exec(raw, "DROP TABLE ALL_CONS_COLUMNS");
            SQLException e = assertThrows(SQLException.class, () -> new OracleMetadataReader().readTable(raw, "USER02", "ORDERS"));
            assertTrue(e.getMessage().contains("USER02.ORDERS"), e.getMessage());
            assertTrue(e.getMessage().contains("主キー"), e.getMessage());
            assertTrue(e.getMessage().contains("SQL: SELECT"), e.getMessage());
            assertNotNull(e.getCause());
        }
        // 列定義の問い合わせで ORA-17027 相当が起きたら、11g 用にフォールバックせずにそのまま報告する
        try (Connection raw = oracleDictionary("long_err2", true)) {
            addOrders(raw, true);
            Connection broken = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (p, m, a) -> {
                        if (m.getName().equals("prepareStatement") && ((String) a[0]).contains("all_tab_cols")) {
                            throw new SQLException("ORA-17027: ストリームはすでにクローズ済です", "99999", 17027);
                        }
                        return invoke(raw, m, a);
                    });
            SQLException e = assertThrows(SQLException.class, () -> new OracleMetadataReader().readTable(broken, "USER02", "ORDERS"));
            assertTrue(e.getMessage().contains("列定義 ALL_TAB_COLS（12.2 以降）"), e.getMessage());
            assertTrue(e.getMessage().contains("ORA-17027"), e.getMessage());
        }
    }

    @Test
    void 報告されたDDLをそのまま解析できる() {
        TableDef t = new jp.mermaidddl.schema.DdlParser(Dialect.ORACLE).parse(DdlParserOracleTest.USER02_ORDERS).getTables().get(0);
        assertEquals("USER02", t.getSchema());
        assertOrders(t);
        assertEquals(Generation.IDENTITY_BY_DEFAULT, t.column("ORDER_ID").getGeneration());
    }
}
