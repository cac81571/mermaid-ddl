package jp.mermaidddl;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;
import jp.mermaidddl.schema.ColumnDef;
import jp.mermaidddl.schema.DdlParser;
import jp.mermaidddl.schema.Dialect;
import jp.mermaidddl.schema.Generation;
import jp.mermaidddl.schema.H2MetadataReader;
import jp.mermaidddl.schema.LogicalType;
import jp.mermaidddl.schema.MetadataReader;
import jp.mermaidddl.schema.OracleMetadataReader;
import jp.mermaidddl.schema.TableDef;
import org.junit.jupiter.api.Test;

/**
 * DB メタデータ取り込みのテスト。
 * H2 は実際の INFORMATION_SCHEMA、Oracle は H2（MODE=Oracle）上に ALL_* ビュー相当の表を作って
 * 本物と同じ SQL で読み込めることを確認する。
 */
class MetadataReaderTest {

    static void exec(Connection c, String... sqls) throws Exception {
        try (Statement st = c.createStatement()) {
            for (String s : sqls) st.execute(s);
        }
    }

    // ================================================================ H2

    @Test
    void H2_INFORMATION_SCHEMAから読み込む() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:meta_h2;DB_CLOSE_DELAY=-1", "sa", "")) {
            exec(c, "CREATE SEQUENCE ORDERS_SEQ",
                    "CREATE TABLE ORDERS (ORDER_ID BIGINT PRIMARY KEY, ORDER_NO INT GENERATED ALWAYS AS IDENTITY,"
                            + " CUSTOMER VARCHAR(40) NOT NULL, AMOUNT NUMERIC(12,2) DEFAULT 0, ORDERED_ON DATE,"
                            + " SHIPPED_AT TIMESTAMP WITH TIME ZONE, PAID BOOLEAN, MEMO CLOB, DOC BLOB,"
                            + " TAX NUMERIC(12,2) GENERATED ALWAYS AS (AMOUNT * 0.1), \"Note 備考\" VARCHAR(10))",
                    "COMMENT ON TABLE ORDERS IS '受注'",
                    "COMMENT ON COLUMN ORDERS.CUSTOMER IS '顧客名'",
                    "CREATE TABLE ITEMS (ITEM_ID INT DEFAULT NEXT VALUE FOR ORDERS_SEQ PRIMARY KEY, NAME VARCHAR(20))");
            MetadataReader r = MetadataReader.forConnection(c);
            assertInstanceOf(H2MetadataReader.class, r);
            assertEquals("PUBLIC", r.defaultSchema(c));

            List<MetadataReader.TableEntry> tables = r.listTables(c, "PUBLIC");
            assertEquals(List.of("ITEMS", "ORDERS"), tables.stream().map(t -> t.getName()).collect(Collectors.toList()));
            assertEquals("受注", tables.get(1).getComment());

            TableDef t = r.readTable(c, "PUBLIC", "ORDERS");
            assertEquals("受注", t.getComment());
            assertEquals(List.of("ORDER_ID"), t.getPrimaryKey());
            assertEquals(List.of("ORDERS_SEQ"), t.getCandidateSequences(), "命名規則 <TABLE>_SEQ");
            assertEquals(Generation.IDENTITY_ALWAYS, t.column("ORDER_NO").getGeneration());
            ColumnDef cust = t.column("CUSTOMER");
            assertEquals(LogicalType.STRING, cust.getLogicalType());
            assertEquals(40, cust.getLength());
            assertFalse(cust.getNullable());
            assertEquals("顧客名", cust.getComment());
            ColumnDef amount = t.column("AMOUNT");
            assertEquals(LogicalType.DECIMAL, amount.getLogicalType());
            assertEquals(12, amount.getPrecision());
            assertEquals(2, amount.getScale());
            assertEquals(LogicalType.DATE, t.column("ORDERED_ON").getLogicalType());
            assertEquals(LogicalType.DATETIME_TZ, t.column("SHIPPED_AT").getLogicalType());
            assertEquals(LogicalType.BOOLEAN, t.column("PAID").getLogicalType());
            assertEquals(LogicalType.CLOB, t.column("MEMO").getLogicalType());
            assertEquals(LogicalType.BINARY, t.column("DOC").getLogicalType());
            assertEquals(Generation.VIRTUAL, t.column("TAX").getGeneration());
            assertTrue(t.column("Note 備考").getQuoted());

            TableDef items = r.readTable(c, "PUBLIC", "ITEMS");
            assertEquals(Generation.SEQUENCE_DEFAULT, items.column("ITEM_ID").getGeneration());
            assertEquals("ORDERS_SEQ", items.column("ITEM_ID").getSequenceName());
        }
    }

    @Test
    void H2_DDL解析とメタデータ取り込みの結果が一致する() throws Exception {
        String ddl = "CREATE TABLE STAFF (STAFF_ID INT PRIMARY KEY, NAME VARCHAR(30) NOT NULL, BIRTH DATE,"
                + " SALARY NUMERIC(9,0), ACTIVE BOOLEAN, MEMO CLOB);\nCOMMENT ON COLUMN STAFF.NAME IS '氏名';";
        TableDef parsed = new DdlParser(Dialect.H2).parse(ddl).getTables().get(0);
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:meta_h2b", "sa", "")) {
            for (String s : jp.mermaidddl.core.SqlScript.split(ddl)) exec(c, s);
            TableDef read = new H2MetadataReader().readTable(c, "PUBLIC", "STAFF");
            assertEquals(parsed.getPrimaryKey(), read.getPrimaryKey());
            for (ColumnDef p : parsed.getColumns()) {
                ColumnDef m = read.column(p.getName());
                assertEquals(p.getLogicalType(), m.getLogicalType(), p.getName());
                assertEquals(p.getNullable(), m.getNullable(), p.getName());
                assertEquals(p.getComment(), m.getComment(), p.getName());
            }
        }
    }

    // ================================================================ Oracle（ディクショナリを H2 上に再現）

    static Connection oracleDictionary(String db, boolean is12c) throws Exception {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:" + db + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
        exec(c,
                "CREATE TABLE ALL_TAB_COMMENTS (OWNER VARCHAR2(128), TABLE_NAME VARCHAR2(128), TABLE_TYPE VARCHAR2(11), COMMENTS VARCHAR2(4000))",
                "CREATE TABLE ALL_TAB_COLS (OWNER VARCHAR2(128), TABLE_NAME VARCHAR2(128), COLUMN_NAME VARCHAR2(128),"
                        + " DATA_TYPE VARCHAR2(128), DATA_LENGTH NUMBER, CHAR_LENGTH NUMBER, CHAR_USED VARCHAR2(1),"
                        + " DATA_PRECISION NUMBER, DATA_SCALE NUMBER, NULLABLE VARCHAR2(1), COLUMN_ID NUMBER,"
                        + " VIRTUAL_COLUMN VARCHAR2(3), HIDDEN_COLUMN VARCHAR2(3),"
                        + (is12c ? " IDENTITY_COLUMN VARCHAR2(3), DEFAULT_ON_NULL VARCHAR2(3)," : "")
                        + " DATA_DEFAULT CLOB)",
                "CREATE TABLE ALL_COL_COMMENTS (OWNER VARCHAR2(128), TABLE_NAME VARCHAR2(128), COLUMN_NAME VARCHAR2(128), COMMENTS VARCHAR2(4000))",
                "CREATE TABLE ALL_CONSTRAINTS (OWNER VARCHAR2(128), CONSTRAINT_NAME VARCHAR2(128), TABLE_NAME VARCHAR2(128), CONSTRAINT_TYPE VARCHAR2(1),"
                        + " R_OWNER VARCHAR2(128), R_CONSTRAINT_NAME VARCHAR2(128))",
                "CREATE TABLE ALL_CONS_COLUMNS (OWNER VARCHAR2(128), CONSTRAINT_NAME VARCHAR2(128), TABLE_NAME VARCHAR2(128), COLUMN_NAME VARCHAR2(4000), POSITION NUMBER)",
                "CREATE TABLE ALL_TRIGGERS (OWNER VARCHAR2(128), TRIGGER_NAME VARCHAR2(128), TRIGGER_TYPE VARCHAR2(16), TRIGGERING_EVENT VARCHAR2(246),"
                        + " TABLE_OWNER VARCHAR2(128), TABLE_NAME VARCHAR2(128), STATUS VARCHAR2(8), TRIGGER_BODY CLOB)",
                "CREATE TABLE ALL_SEQUENCES (SEQUENCE_OWNER VARCHAR2(128), SEQUENCE_NAME VARCHAR2(128))");
        if (is12c) {
            exec(c, "CREATE TABLE ALL_TAB_IDENTITY_COLS (OWNER VARCHAR2(128), TABLE_NAME VARCHAR2(128), COLUMN_NAME VARCHAR2(128), GENERATION_TYPE VARCHAR2(10))");
        }
        return c;
    }

    /** ALL_TAB_COLS に 1 行追加（12c 形式） */
    private static String col(String table, String name, String type, Integer len, Integer charLen, String charUsed,
                              Integer prec, Integer scale, String nullable, int id, String virtual, String hidden,
                              String identity, String onNull, String dflt) {
        return String.format("INSERT INTO ALL_TAB_COLS VALUES ('HR','%s','%s','%s',%s,%s,%s,%s,%s,'%s',%d,'%s','%s','%s','%s',%s)",
                table, name, type, len, charLen, charUsed == null ? "NULL" : "'" + charUsed + "'", prec, scale, nullable, id,
                virtual, hidden, identity, onNull, dflt == null ? "NULL" : "'" + dflt.replace("'", "''") + "'");
    }

    @Test
    void Oracle12c_ディクショナリから読み込む() throws Exception {
        try (Connection c = oracleDictionary("ora12", true)) {
            exec(c,
                    "INSERT INTO ALL_TAB_COMMENTS VALUES ('HR','EMPLOYEE','TABLE','社員マスタ')",
                    "INSERT INTO ALL_TAB_COMMENTS VALUES ('HR','DEPT','TABLE',NULL)",
                    "INSERT INTO ALL_TAB_COMMENTS VALUES ('HR','EMP_V','VIEW','社員ビュー')",
                    "INSERT INTO ALL_TAB_COMMENTS VALUES ('HR','BIN$abc==$0','TABLE',NULL)",
                    "INSERT INTO ALL_TAB_COMMENTS VALUES ('SCOTT','EMP','TABLE',NULL)",
                    col("EMPLOYEE", "EMP_ID", "NUMBER", 22, 0, null, 10, 0, "N", 1, "NO", "NO", "YES", "NO", "\"HR\".\"ISEQ$$_73137\".nextval"),
                    col("EMPLOYEE", "EMP_NAME", "VARCHAR2", 200, 50, "C", null, null, "N", 2, "NO", "NO", "NO", "NO", null),
                    col("EMPLOYEE", "KANA", "VARCHAR2", 100, 100, "B", null, null, "Y", 3, "NO", "NO", "NO", "NO", null),
                    col("EMPLOYEE", "SALARY", "NUMBER", 22, 0, null, 10, 2, "Y", 4, "NO", "NO", "NO", "NO", "0 "),
                    col("EMPLOYEE", "DEPT_ID", "NUMBER", 22, 0, null, null, 0, "Y", 5, "NO", "NO", "NO", "NO", null),
                    col("EMPLOYEE", "BONUS", "NUMBER", 22, 0, null, null, null, "Y", 6, "NO", "NO", "NO", "NO", null),
                    col("EMPLOYEE", "HIRE_DATE", "DATE", 7, 0, null, null, null, "N", 7, "NO", "NO", "NO", "NO", "SYSDATE"),
                    col("EMPLOYEE", "UPDATED_AT", "TIMESTAMP(6) WITH TIME ZONE", 13, 0, null, null, 6, "Y", 8, "NO", "NO", "NO", "NO", null),
                    col("EMPLOYEE", "PROFILE", "CLOB", 4000, 0, null, null, null, "Y", 9, "NO", "NO", "NO", "NO", null),
                    col("EMPLOYEE", "PHOTO", "BLOB", 4000, 0, null, null, null, "Y", 10, "NO", "NO", "NO", "NO", null),
                    col("EMPLOYEE", "ANNUAL", "NUMBER", 22, 0, null, null, null, "Y", 11, "YES", "NO", "NO", "NO", "\"SALARY\"*12"),
                    col("EMPLOYEE", "SYS_NC00012$", "RAW", 16, 0, null, null, null, "Y", 12, "YES", "YES", "NO", "NO", null),
                    col("EMPLOYEE", "CODE", "NUMBER", 22, 0, null, 8, 0, "Y", 13, "NO", "NO", "NO", "NO", "\"HR\".\"CODE_SEQ\".\"NEXTVAL\""),
                    col("EMPLOYEE", "FLAG", "CHAR", 1, 1, "B", null, null, "Y", 14, "NO", "NO", "NO", "NO", "'N'"),
                    col("EMPLOYEE", "INV", "NUMBER", 22, 0, null, null, null, "Y", 15, "NO", "NO", "YES", "YES", "\"HR\".\"ISEQ$$_73140\".nextval"),
                    "INSERT INTO ALL_TAB_IDENTITY_COLS VALUES ('HR','EMPLOYEE','EMP_ID','ALWAYS')",
                    "INSERT INTO ALL_TAB_IDENTITY_COLS VALUES ('HR','EMPLOYEE','INV','BY DEFAULT')",
                    "INSERT INTO ALL_COL_COMMENTS VALUES ('HR','EMPLOYEE','EMP_ID','社員ID')",
                    "INSERT INTO ALL_COL_COMMENTS VALUES ('HR','EMPLOYEE','EMP_NAME','氏名')",
                    "INSERT INTO ALL_COL_COMMENTS VALUES ('HR','EMPLOYEE','HIRE_DATE','入社日')",
                    "INSERT INTO ALL_CONSTRAINTS (OWNER, CONSTRAINT_NAME, TABLE_NAME, CONSTRAINT_TYPE) VALUES ('HR','EMPLOYEE_PK','EMPLOYEE','P')",
                    "INSERT INTO ALL_CONSTRAINTS (OWNER, CONSTRAINT_NAME, TABLE_NAME, CONSTRAINT_TYPE) VALUES ('HR','EMPLOYEE_UK','EMPLOYEE','U')",
                    "INSERT INTO ALL_CONS_COLUMNS VALUES ('HR','EMPLOYEE_PK','EMPLOYEE','EMP_ID',1)",
                    "INSERT INTO ALL_CONS_COLUMNS VALUES ('HR','EMPLOYEE_UK','EMPLOYEE','KANA',1)",
                    // DEPT: 11g 方式（トリガー + シーケンス）
                    col("DEPT", "DEPT_ID", "NUMBER", 22, 0, null, 6, 0, "N", 1, "NO", "NO", "NO", "NO", null),
                    col("DEPT", "DEPT_NAME", "VARCHAR2", 60, 60, "B", null, null, "N", 2, "NO", "NO", "NO", "NO", null),
                    "INSERT INTO ALL_CONSTRAINTS (OWNER, CONSTRAINT_NAME, TABLE_NAME, CONSTRAINT_TYPE) VALUES ('HR','DEPT_PK','DEPT','P')",
                    "INSERT INTO ALL_CONS_COLUMNS VALUES ('HR','DEPT_PK','DEPT','DEPT_ID',1)",
                    "INSERT INTO ALL_TRIGGERS VALUES ('HR','DEPT_BI','BEFORE EACH ROW','INSERT','HR','DEPT','ENABLED',"
                            + "'BEGIN\n  IF :NEW.DEPT_ID IS NULL THEN\n    SELECT DEPT_SEQ.NEXTVAL INTO :NEW.DEPT_ID FROM DUAL;\n  END IF;\nEND;')",
                    "INSERT INTO ALL_SEQUENCES VALUES ('HR','DEPT_SEQ')",
                    "INSERT INTO ALL_SEQUENCES VALUES ('HR','CODE_SEQ')");

            OracleMetadataReader r = new OracleMetadataReader();
            List<MetadataReader.TableEntry> list = r.listTables(c, "HR");
            assertEquals(List.of("DEPT", "EMPLOYEE", "EMP_V"), list.stream().map(MetadataReader.TableEntry::getName).collect(Collectors.toList()),
                    "ごみ箱（BIN$）と他スキーマは除外");
            assertEquals("社員マスタ", list.get(1).getComment());
            assertEquals("VIEW", list.get(2).getType());

            TableDef t = r.readTable(c, "HR", "EMPLOYEE");
            assertEquals("社員マスタ", t.getComment());
            assertEquals(14, t.getColumns().size(), "非表示列は除外");
            assertNull(t.column("SYS_NC00012$"));
            assertEquals(List.of("EMP_ID"), t.getPrimaryKey());
            assertEquals("EMPLOYEE_PK", t.getPrimaryKeyConstraint().getName());

            ColumnDef id = t.column("EMP_ID");
            assertEquals(Generation.IDENTITY_ALWAYS, id.getGeneration());
            assertEquals(LogicalType.INTEGER, id.getLogicalType());
            assertEquals("社員ID", id.getComment());
            ColumnDef name = t.column("EMP_NAME");
            assertEquals(50, name.getLength(), "CHAR_USED=C → CHAR_LENGTH");
            assertTrue(name.getCharSemantics());
            assertEquals("VARCHAR2(50 CHAR)", name.getRawType());
            assertFalse(name.getNullable());
            ColumnDef kana = t.column("KANA");
            assertEquals(100, kana.getLength(), "CHAR_USED=B → DATA_LENGTH");
            assertFalse(kana.getCharSemantics());
            assertEquals(LogicalType.DECIMAL, t.column("SALARY").getLogicalType());
            assertEquals("0", t.column("SALARY").getDefaultExpr());
            assertEquals(LogicalType.INTEGER, t.column("DEPT_ID").getLogicalType(), "INTEGER = NUMBER(null,0)");
            assertEquals("INTEGER", t.column("DEPT_ID").getRawType());
            assertEquals(LogicalType.DECIMAL, t.column("BONUS").getLogicalType());
            assertEquals(LogicalType.DATE, t.column("HIRE_DATE").getLogicalType());
            assertEquals("TIMESTAMP WITH TIME ZONE", t.column("UPDATED_AT").getTypeName());
            assertEquals(LogicalType.DATETIME_TZ, t.column("UPDATED_AT").getLogicalType());
            assertEquals(LogicalType.CLOB, t.column("PROFILE").getLogicalType());
            assertEquals(LogicalType.BINARY, t.column("PHOTO").getLogicalType());
            assertEquals(Generation.VIRTUAL, t.column("ANNUAL").getGeneration());
            assertEquals(Generation.SEQUENCE_DEFAULT, t.column("CODE").getGeneration());
            assertEquals("CODE_SEQ", t.column("CODE").getSequenceName());
            assertEquals(Generation.IDENTITY_ON_NULL, t.column("INV").getGeneration());

            TableDef dept = r.readTable(c, "HR", "DEPT");
            assertEquals(Generation.TRIGGER_SEQUENCE, dept.column("DEPT_ID").getGeneration(), "ALL_TRIGGERS の本文から検出");
            assertEquals("DEPT_SEQ", dept.column("DEPT_ID").getSequenceName());
            assertTrue(dept.getCandidateSequences().isEmpty(), "トリガー採番済みなら候補は不要");

            assertThrows(java.sql.SQLException.class, () -> r.readTable(c, "HR", "NO_SUCH"));
        }
    }

    @Test
    void Oracle11g_IDENTITY列なしのディクショナリにフォールバック() throws Exception {
        try (Connection c = oracleDictionary("ora11", false)) {
            exec(c,
                    "INSERT INTO ALL_TAB_COMMENTS VALUES ('HR','T1','TABLE','旧テーブル')",
                    "INSERT INTO ALL_TAB_COLS VALUES ('HR','T1','ID','NUMBER',22,0,NULL,9,0,'N',1,'NO','NO',NULL)",
                    "INSERT INTO ALL_TAB_COLS VALUES ('HR','T1','NAME','VARCHAR2',30,30,'B',NULL,NULL,'Y',2,'NO','NO',NULL)",
                    "INSERT INTO ALL_COL_COMMENTS VALUES ('HR','T1','NAME','名称')");
            TableDef t = new OracleMetadataReader().readTable(c, "HR", "T1");
            assertEquals("旧テーブル", t.getComment());
            assertEquals(2, t.getColumns().size());
            assertEquals("名称", t.column("NAME").getComment());
            assertEquals(Generation.NONE, t.column("ID").getGeneration());
            assertTrue(t.getNotes().stream().anyMatch(n -> n.contains("12c 未満")));
            assertTrue(t.getPrimaryKey().isEmpty());
        }
    }
}
