package jp.mermaidddl;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import jp.mermaidddl.core.SqlScript;
import jp.mermaidddl.er.DdlOptions;
import jp.mermaidddl.er.DdlWriter;
import jp.mermaidddl.er.ErDdlService;
import jp.mermaidddl.er.ErDiagram;
import jp.mermaidddl.er.ErRelationship;
import jp.mermaidddl.er.ErToSchemaOptions;
import jp.mermaidddl.er.ErTypes;
import jp.mermaidddl.er.MermaidParser;
import jp.mermaidddl.er.SchemaToErOptions;
import jp.mermaidddl.schema.ColumnDef;
import jp.mermaidddl.schema.ConstraintDef;
import jp.mermaidddl.schema.DdlParser;
import jp.mermaidddl.schema.Dialect;
import jp.mermaidddl.schema.OracleMetadataReader;
import jp.mermaidddl.schema.SchemaModel;
import jp.mermaidddl.schema.TableDef;
import jp.mermaidddl.ui.MainFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** DDL / DB → Mermaid erDiagram と往復変換 */
class DdlToMermaidTest {

    /** SQL Developer の出力形式（引用識別子・スキーマ名・ストレージ句・ALTER TABLE の FK・/ 区切り） */
    static final String ORACLE_DDL = String.join("\n",
            "--------------------------------------------------------",
            "--  DDL for Table DEPT",
            "--------------------------------------------------------",
            "  CREATE TABLE \"HR\".\"DEPT\"",
            "   (\t\"DEPT_ID\" NUMBER(6,0) NOT NULL ENABLE,",
            "\t\"DEPT_NAME\" VARCHAR2(60 CHAR) NOT NULL ENABLE,",
            "\t\"PARENT_ID\" NUMBER(6,0),",
            "\t CONSTRAINT \"DEPT_PK\" PRIMARY KEY (\"DEPT_ID\")",
            "  USING INDEX PCTFREE 10 INITRANS 2 MAXTRANS 255 TABLESPACE \"USERS\"  ENABLE",
            "   ) SEGMENT CREATION IMMEDIATE PCTFREE 10 PCTUSED 40 INITRANS 1 MAXTRANS 255 NOCOMPRESS LOGGING",
            "  STORAGE(INITIAL 65536 NEXT 1048576) TABLESPACE \"USERS\" ;",
            "",
            "   COMMENT ON TABLE \"HR\".\"DEPT\"  IS '部署';",
            "   COMMENT ON COLUMN \"HR\".\"DEPT\".\"DEPT_NAME\" IS '部署名';",
            "  CREATE TABLE \"HR\".\"EMPLOYEE\"",
            "   (\t\"EMP_ID\" NUMBER(10,0) GENERATED ALWAYS AS IDENTITY MINVALUE 1 INCREMENT BY 1 START WITH 1 CACHE 20 NOT NULL ENABLE,",
            "\t\"EMP_NAME\" VARCHAR2(50 CHAR) NOT NULL ENABLE,",
            "\t\"EMAIL\" VARCHAR2(200 BYTE),",
            "\t\"DEPT_ID\" NUMBER(6,0),",
            "\t\"HIRE_DATE\" DATE DEFAULT SYSDATE NOT NULL ENABLE,",
            "\t\"SALARY\" NUMBER(10,2) DEFAULT 0,",
            "\t\"UPDATED_AT\" TIMESTAMP (6) WITH LOCAL TIME ZONE,",
            "\t\"PROFILE\" CLOB,",
            "\t\"Mixed Case\" CHAR(1 BYTE) DEFAULT 'N',",
            "\t CONSTRAINT \"EMPLOYEE_PK\" PRIMARY KEY (\"EMP_ID\") USING INDEX TABLESPACE \"USERS\" ENABLE,",
            "\t CONSTRAINT \"EMPLOYEE_EMAIL_UK\" UNIQUE (\"EMAIL\") ENABLE,",
            "\t CONSTRAINT \"EMPLOYEE_SALARY_CK\" CHECK (salary >= 0) ENABLE",
            "   ) TABLESPACE \"USERS\"",
            "  LOB (\"PROFILE\") STORE AS SECUREFILE ( TABLESPACE \"USERS\" ENABLE STORAGE IN ROW CHUNK 8192 NOCACHE LOGGING ) ;",
            "   COMMENT ON TABLE \"HR\".\"EMPLOYEE\" IS '社員';",
            "   COMMENT ON COLUMN \"HR\".\"EMPLOYEE\".\"EMP_NAME\" IS '氏名';",
            "   COMMENT ON COLUMN \"HR\".\"EMPLOYEE\".\"HIRE_DATE\" IS '入社日';",
            "  CREATE TABLE \"HR\".\"EMP_DETAIL\"",
            "   (\t\"EMP_ID\" NUMBER(10,0) NOT NULL ENABLE,",
            "\t\"NOTE\" VARCHAR2(4000),",
            "\t CONSTRAINT \"EMP_DETAIL_PK\" PRIMARY KEY (\"EMP_ID\") ENABLE",
            "   ) ;",
            "  CREATE UNIQUE INDEX \"HR\".\"EMPLOYEE_PK\" ON \"HR\".\"EMPLOYEE\" (\"EMP_ID\") TABLESPACE \"USERS\" ;",
            "  ALTER TABLE \"HR\".\"EMPLOYEE\" ADD CONSTRAINT \"EMPLOYEE_DEPT_FK\" FOREIGN KEY (\"DEPT_ID\")",
            "\t  REFERENCES \"HR\".\"DEPT\" (\"DEPT_ID\") ON DELETE SET NULL ENABLE;",
            "  ALTER TABLE \"HR\".\"DEPT\" ADD CONSTRAINT \"DEPT_PARENT_FK\" FOREIGN KEY (\"PARENT_ID\")",
            "\t  REFERENCES \"HR\".\"DEPT\" (\"DEPT_ID\") ENABLE;",
            "  ALTER TABLE \"HR\".\"EMP_DETAIL\" ADD CONSTRAINT \"EMP_DETAIL_FK\" FOREIGN KEY (\"EMP_ID\")",
            "\t  REFERENCES \"HR\".\"EMPLOYEE\" (\"EMP_ID\") ENABLE;",
            "  CREATE OR REPLACE TRIGGER \"HR\".\"DEPT_BI\" BEFORE INSERT ON \"HR\".\"DEPT\" FOR EACH ROW",
            "BEGIN",
            "  NULL;",
            "END;",
            "/",
            "");

    static final String H2_DDL = String.join("\n",
            "CREATE TABLE AUTHOR (",
            "  AUTHOR_ID BIGINT AUTO_INCREMENT PRIMARY KEY,",
            "  NAME VARCHAR(100) NOT NULL COMMENT '著者名',",
            "  ACTIVE BOOLEAN DEFAULT TRUE",
            ");",
            "COMMENT ON TABLE AUTHOR IS '著者';",
            "CREATE TABLE BOOK (",
            "  BOOK_ID INT GENERATED BY DEFAULT AS IDENTITY,",
            "  AUTHOR_ID BIGINT NOT NULL REFERENCES AUTHOR(AUTHOR_ID),",
            "  TITLE VARCHAR(200) NOT NULL,",
            "  PRICE DECIMAL(8,2),",
            "  PUBLISHED DATE,",
            "  ISBN CHAR(13),",
            "  CONSTRAINT PK_BOOK PRIMARY KEY (BOOK_ID),",
            "  CONSTRAINT UK_BOOK_ISBN UNIQUE (ISBN)",
            ");",
            "CREATE TABLE REVIEW (",
            "  REVIEW_ID INT PRIMARY KEY,",
            "  BOOK_ID INT,",
            "  BODY CLOB,",
            "  CREATED_AT TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,",
            "  FOREIGN KEY (BOOK_ID) REFERENCES BOOK (BOOK_ID)",
            ");");

    static ErDdlService.Result toMermaid(String ddl, Dialect d, SchemaToErOptions o) {
        ErDdlService.Result r = ErDdlService.ddlToMermaid(ddl, d, o);
        assertTrue(r.isOk(), () -> r.getErrors().toString());
        ErDiagram check = new MermaidParser().parse(r.getText());
        assertFalse(check.hasErrors(), () -> "生成した Mermaid が解析できない: " + check.getErrors() + "\n" + r.getText());
        return r;
    }

    @Test
    void Oracle_DDLからER図() {
        ErDdlService.Result r = toMermaid(ORACLE_DDL, Dialect.ORACLE, new SchemaToErOptions());
        String mm = r.getText();
        assertTrue(mm.startsWith("erDiagram\n"), mm);
        assertTrue(mm.contains("    DEPT[\"部署 (DEPT)\"] {\n        number(6) DEPT_ID PK\n        varchar2(60_char) DEPT_NAME \"部署名 [NOT NULL]\"\n"
                + "        number(6) PARENT_ID FK\n    }"), mm);
        assertTrue(mm.contains("number(10) EMP_ID PK \"[IDENTITY ALWAYS]\""), mm);
        assertTrue(mm.contains("varchar2(50_char) EMP_NAME \"氏名 [NOT NULL]\""), mm);
        assertTrue(mm.contains("varchar2(200_byte) EMAIL UK"), mm);
        assertTrue(mm.contains("number(6) DEPT_ID FK"), mm);
        assertTrue(mm.contains("date HIRE_DATE \"入社日 [NOT NULL] [DEFAULT SYSDATE]\""), mm);
        assertTrue(mm.contains("timestamp(6)_with_local_time_zone UPDATED_AT"), mm);
        assertTrue(mm.contains("char(1_byte) `Mixed Case` \"[DEFAULT 'N']\""), mm);
        // NULL 可の FK → |o--o{、自己参照、主キー = FK（1 対 1）→ ||--o|
        assertTrue(mm.contains("DEPT |o--o{ EMPLOYEE : \"DEPT_ID\""), mm);
        assertTrue(mm.contains("DEPT |o--o{ DEPT : \"PARENT_ID\""), mm);
        assertTrue(mm.contains("EMPLOYEE ||--o| EMP_DETAIL : \"EMP_ID\""), mm);
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("CHECK")), r.getWarnings().toString());
    }

    @Test
    void H2_DDLからER図() {
        ErDdlService.Result r = toMermaid(H2_DDL, Dialect.H2, new SchemaToErOptions());
        String mm = r.getText();
        assertTrue(mm.contains("AUTHOR[\"著者 (AUTHOR)\"] {"), mm);
        assertTrue(mm.contains("bigint AUTHOR_ID PK \"[IDENTITY]\""), mm);
        assertTrue(mm.contains("varchar(100) NAME \"著者名 [NOT NULL]\""), mm);
        assertTrue(mm.contains("boolean ACTIVE \"[DEFAULT TRUE]\""), mm);
        assertTrue(mm.contains("char(13) ISBN UK"), mm);
        assertTrue(mm.contains("bigint AUTHOR_ID FK\n"), "関係で NOT NULL が分かる FK にはタグを付けない: " + mm);
        assertTrue(mm.contains("AUTHOR ||--o{ BOOK : \"AUTHOR_ID\""), mm);
        assertTrue(mm.contains("BOOK |o--o{ REVIEW : \"BOOK_ID\""), mm);
        assertTrue(mm.contains("timestamp_with_time_zone CREATED_AT \"[DEFAULT CURRENT_TIMESTAMP]\""), mm);
    }

    @Test
    void 汎用型と非識別リレーションのオプション() {
        SchemaToErOptions o = new SchemaToErOptions();
        o.setGenericTypes(true);
        o.setDistinguishIdentifying(true);
        o.setConstraintTags(false);
        String mm = toMermaid(ORACLE_DDL, Dialect.ORACLE, o).getText();
        assertTrue(mm.contains("int DEPT_ID PK"), mm);
        assertTrue(mm.contains("string(60) DEPT_NAME \"部署名\""), mm);
        assertTrue(mm.contains("bigint EMP_ID PK\n"), mm);
        assertTrue(mm.contains("decimal(10,2) SALARY"), mm);
        assertTrue(mm.contains("date HIRE_DATE \"入社日\""), mm);
        assertTrue(mm.contains("text PROFILE"), mm);
        assertTrue(mm.contains("DEPT |o..o{ EMPLOYEE"), "主キーでない FK は ..: " + mm);
        assertTrue(mm.contains("EMPLOYEE ||--o| EMP_DETAIL"), "主キーの FK は --: " + mm);
    }

    @Test
    void CREATE_TABLEがないとエラー() {
        ErDdlService.Result r = ErDdlService.ddlToMermaid("SELECT 1 FROM DUAL;", Dialect.ORACLE, new SchemaToErOptions());
        assertFalse(r.isOk());
        assertNull(r.getText());
    }

    @Test
    void DDL解析の警告は行番号付き() {
        SchemaModel m = new DdlParser(Dialect.ORACLE).parse("CREATE TABLE A (ID NUMBER);\n\n\nCREATE TABLE B AS SELECT * FROM A;\n");
        assertTrue(m.getWarnings().stream().anyMatch(w -> w.startsWith("4 行目: ")), m.getWarnings().toString());
        List<SqlScript.Statement> st = SqlScript.splitWithLines("-- head\nSELECT 1 FROM DUAL;\n/* c */\n\nBEGIN\n  NULL;\nEND;\n/\nSELECT 2 FROM DUAL");
        assertEquals(List.of(2, 5, 9), st.stream().map(SqlScript.Statement::getLine).collect(Collectors.toList()));
    }

    // ================================================================ 往復

    /** 比較用: テーブル定義の要点を文字列にする（制約名・仮想列・CHECK は除く） */
    static List<String> summary(SchemaModel m, Dialect d) {
        List<String> out = new ArrayList<>();
        for (TableDef t : m.getTables()) {
            out.add("T " + t.getName() + " comment=" + t.getComment() + " pk=" + t.getPrimaryKey());
            for (ColumnDef c : t.getColumns()) {
                out.add("  C " + c.getName() + " " + ErTypes.toSql(ErTypes.toMermaid(c.getRawType()), d).getSql()
                        + " null=" + c.getNullable() + " def=" + (c.getDefaultExpr() == null ? null : c.getDefaultExpr().replace("\"", ""))
                        + " gen=" + (c.getGeneration() == jp.mermaidddl.schema.Generation.AUTO_INCREMENT
                        ? jp.mermaidddl.schema.Generation.IDENTITY_BY_DEFAULT : c.getGeneration()) + " comment=" + c.getComment());
            }
            t.getConstraints().stream().filter(k -> "U".equals(k.getType()) || "R".equals(k.getType()))
                    .map(k -> "  K " + k.getType() + " " + k.getColumns() + " " + k.getRefTable() + " " + k.getRefColumns())
                    .sorted().forEach(out::add);
        }
        return out;
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void DDLからER図からDDLへ往復(Dialect d) {
        String ddl = d == Dialect.ORACLE ? ORACLE_DDL : H2_DDL;
        SchemaModel m1 = new DdlParser(d).parse(ddl);
        m1.getTables().forEach(t -> t.getConstraints().removeIf(c -> "C".equals(c.getType())));
        String mermaid = toMermaid(ddl, d, new SchemaToErOptions()).getText();
        DdlOptions o = new DdlOptions();
        o.setDialect(d);
        ErDdlService.Result back = ErDdlService.mermaidToDdl(mermaid, new ErToSchemaOptions(), o);
        assertTrue(back.isOk(), back.getErrors()::toString);
        SchemaModel m2 = new DdlParser(d).parse(back.getText());
        assertEquals(String.join("\n", summary(m1, d)), String.join("\n", summary(m2, d)), back.getText());
        // 2 周目は ER 図の文字列も一致する（不動点）
        String mermaid2 = toMermaid(back.getText(), d, new SchemaToErOptions()).getText();
        assertEquals(mermaid, mermaid2);
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void ER図からDDLからER図へ往復(Dialect d) {
        String src = MermaidToDdlTest.FULL;
        ErDiagram d1 = new MermaidParser().parse(src);
        DdlOptions o = new DdlOptions();
        o.setDialect(d);
        ErDdlService.Result ddl = ErDdlService.mermaidToDdl(src, new ErToSchemaOptions(), o);
        assertTrue(ddl.isOk());
        ErDiagram d2 = new MermaidParser().parse(toMermaid(ddl.getText(), d, new SchemaToErOptions()).getText());

        assertEquals(d1.getEntities().size(), d2.getEntities().size());
        d1.getEntities().values().forEach(e1 -> {
            var e2 = d2.entity(e1.getName().toUpperCase());
            assertNotNull(e2, e1.getName());
            assertEquals(e1.getAlias(), jp.mermaidddl.er.ErConverter.logicalName(e2.getAlias(), e2.getName()), "論理名は往復で同じ");
            assertEquals(e1.getAttributes().size(), e2.getAttributes().size(), e1.getName());
            for (int i = 0; i < e1.getAttributes().size(); i++) {
                var a1 = e1.getAttributes().get(i);
                var a2 = e2.getAttributes().get(i);
                String where = e1.getName() + "." + a1.getName();
                assertEquals(a1.getName().toUpperCase(), a2.getName(), where);
                assertEquals(a1.getComment(), a2.getComment(), where);
                assertEquals(new java.util.TreeSet<>(a1.getKeys()), new java.util.TreeSet<>(a2.getKeys()), where);
                assertEquals(ErTypes.toSql(a1.getType(), d).getSql(), ErTypes.toSql(a2.getType(), d).getSql(), where);
                assertEquals(a1.getIdentity(), a2.getIdentity(), where);
            }
        });
        // リレーション（}| / |{ は DB では 0 件と区別できないので o{ になる）
        List<String> r1 = d1.getRelationships().stream().map(r -> rel(r, true)).sorted().collect(Collectors.toList());
        List<String> r2 = d2.getRelationships().stream().map(r -> rel(r, false)).sorted().collect(Collectors.toList());
        assertEquals(r1, r2);
    }

    private static String rel(ErRelationship r, boolean normalize) {
        String rc = r.getRightCard().name();
        if (normalize && "ONE_OR_MORE".equals(rc)) rc = "ZERO_OR_MORE";
        return r.getLeft().toUpperCase() + " " + r.getLeftCard() + " " + rc + " " + r.getRight().toUpperCase();
    }

    // ================================================================ DB から

    @Test
    void H2のDBからER図() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:er_db_h2", "sa", "")) {
            assertTrue(ErDdlService.executeDdl(c, H2_DDL).isOk());
            ErDdlService.Result r = ErDdlService.dbToMermaid(c, "PUBLIC", List.of("AUTHOR", "BOOK", "REVIEW"), new SchemaToErOptions());
            String mm = r.getText();
            assertTrue(mm.contains("AUTHOR[\"著者 (AUTHOR)\"] {"), mm);
            assertTrue(mm.contains("varchar(100) NAME \"著者名 [NOT NULL]\""), mm);
            assertTrue(mm.contains("char(13) ISBN UK"), mm);
            assertTrue(mm.contains("AUTHOR ||--o{ BOOK"), mm);
            assertTrue(mm.contains("BOOK |o--o{ REVIEW"), mm);
            assertFalse(new MermaidParser().parse(mm).hasErrors());

            // 一部のテーブルだけ: 対象外への FK は省略して注意を出す
            ErDdlService.Result part = ErDdlService.dbToMermaid(c, "PUBLIC", List.of("BOOK"), new SchemaToErOptions());
            assertFalse(part.getText().contains("||--"), part.getText());
            assertTrue(part.getWarnings().stream().anyMatch(w -> w.contains("AUTHOR") && w.contains("対象外")), part.getWarnings().toString());

            // DB → ER 図 → DDL（Oracle 用）→ H2 Oracle モードで実行
            DdlOptions o = new DdlOptions();
            o.setDialect(Dialect.ORACLE);
            ErDdlService.Result ora = ErDdlService.mermaidToDdl(mm, new ErToSchemaOptions(), o);
            assertTrue(ora.isOk());
            try (Connection oc = DriverManager.getConnection("jdbc:h2:mem:er_db_h2_ora;MODE=Oracle", "sa", "")) {
                ErDdlService.ExecReport rep = ErDdlService.executeDdl(oc, ora.getText());
                assertTrue(rep.isOk(), () -> rep.summary() + "\n" + ora.getText());
            }
        }
    }

    @Test
    void Oracleのディクショナリから外部キーと一意制約を読む() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:er_ora_dict;MODE=Oracle", "sa", "")) {
            try (Statement st = c.createStatement()) {
                for (String s : new String[]{
                        "CREATE TABLE ALL_TAB_COMMENTS (OWNER VARCHAR2(128), TABLE_NAME VARCHAR2(128), TABLE_TYPE VARCHAR2(11), COMMENTS VARCHAR2(4000))",
                        "CREATE TABLE ALL_TAB_COLS (OWNER VARCHAR2(128), TABLE_NAME VARCHAR2(128), COLUMN_NAME VARCHAR2(128), DATA_TYPE VARCHAR2(128),"
                                + " DATA_LENGTH NUMBER, CHAR_LENGTH NUMBER, CHAR_USED VARCHAR2(1), DATA_PRECISION NUMBER, DATA_SCALE NUMBER,"
                                + " NULLABLE VARCHAR2(1), COLUMN_ID NUMBER, VIRTUAL_COLUMN VARCHAR2(3), HIDDEN_COLUMN VARCHAR2(3),"
                                + " IDENTITY_COLUMN VARCHAR2(3), DEFAULT_ON_NULL VARCHAR2(3), DATA_DEFAULT CLOB)",
                        "CREATE TABLE ALL_COL_COMMENTS (OWNER VARCHAR2(128), TABLE_NAME VARCHAR2(128), COLUMN_NAME VARCHAR2(128), COMMENTS VARCHAR2(4000))",
                        "CREATE TABLE ALL_TAB_IDENTITY_COLS (OWNER VARCHAR2(128), TABLE_NAME VARCHAR2(128), COLUMN_NAME VARCHAR2(128), GENERATION_TYPE VARCHAR2(10))",
                        "CREATE TABLE ALL_CONSTRAINTS (OWNER VARCHAR2(128), CONSTRAINT_NAME VARCHAR2(128), TABLE_NAME VARCHAR2(128), CONSTRAINT_TYPE VARCHAR2(1),"
                                + " R_OWNER VARCHAR2(128), R_CONSTRAINT_NAME VARCHAR2(128))",
                        "CREATE TABLE ALL_CONS_COLUMNS (OWNER VARCHAR2(128), CONSTRAINT_NAME VARCHAR2(128), TABLE_NAME VARCHAR2(128), COLUMN_NAME VARCHAR2(4000), POSITION NUMBER)",
                        "CREATE TABLE ALL_TRIGGERS (OWNER VARCHAR2(128), TRIGGER_NAME VARCHAR2(128), TRIGGER_TYPE VARCHAR2(16), TRIGGERING_EVENT VARCHAR2(246),"
                                + " TABLE_OWNER VARCHAR2(128), TABLE_NAME VARCHAR2(128), STATUS VARCHAR2(8), TRIGGER_BODY CLOB)",
                        "CREATE TABLE ALL_SEQUENCES (SEQUENCE_OWNER VARCHAR2(128), SEQUENCE_NAME VARCHAR2(128))",
                        "INSERT INTO ALL_TAB_COMMENTS VALUES ('HR','DEPT','TABLE','部署')",
                        "INSERT INTO ALL_TAB_COMMENTS VALUES ('HR','EMP','TABLE','社員')",
                        "INSERT INTO ALL_TAB_COLS VALUES ('HR','DEPT','DEPT_ID','NUMBER',22,0,NULL,6,0,'N',1,'NO','NO','NO','NO',NULL)",
                        "INSERT INTO ALL_TAB_COLS VALUES ('HR','DEPT','DEPT_NAME','VARCHAR2',120,30,'C',NULL,NULL,'N',2,'NO','NO','NO','NO',NULL)",
                        "INSERT INTO ALL_TAB_COLS VALUES ('HR','EMP','EMP_ID','NUMBER',22,0,NULL,10,0,'N',1,'NO','NO','YES','NO','\"HR\".\"ISEQ$$_1\".nextval')",
                        "INSERT INTO ALL_TAB_COLS VALUES ('HR','EMP','DEPT_ID','NUMBER',22,0,NULL,6,0,'N',2,'NO','NO','NO','NO',NULL)",
                        "INSERT INTO ALL_TAB_COLS VALUES ('HR','EMP','MAIL','VARCHAR2',100,100,'B',NULL,NULL,'Y',3,'NO','NO','NO','NO',NULL)",
                        "INSERT INTO ALL_TAB_IDENTITY_COLS VALUES ('HR','EMP','EMP_ID','BY DEFAULT')",
                        "INSERT INTO ALL_COL_COMMENTS VALUES ('HR','EMP','MAIL','メール')",
                        "INSERT INTO ALL_CONSTRAINTS VALUES ('HR','DEPT_PK','DEPT','P',NULL,NULL)",
                        "INSERT INTO ALL_CONSTRAINTS VALUES ('HR','EMP_PK','EMP','P',NULL,NULL)",
                        "INSERT INTO ALL_CONSTRAINTS VALUES ('HR','EMP_MAIL_UK','EMP','U',NULL,NULL)",
                        "INSERT INTO ALL_CONSTRAINTS VALUES ('HR','EMP_DEPT_FK','EMP','R','HR','DEPT_PK')",
                        "INSERT INTO ALL_CONSTRAINTS VALUES ('HR','SYS_C001','EMP','C',NULL,NULL)",
                        "INSERT INTO ALL_CONS_COLUMNS VALUES ('HR','DEPT_PK','DEPT','DEPT_ID',1)",
                        "INSERT INTO ALL_CONS_COLUMNS VALUES ('HR','EMP_PK','EMP','EMP_ID',1)",
                        "INSERT INTO ALL_CONS_COLUMNS VALUES ('HR','EMP_MAIL_UK','EMP','MAIL',1)",
                        "INSERT INTO ALL_CONS_COLUMNS VALUES ('HR','EMP_DEPT_FK','EMP','DEPT_ID',1)",
                        "INSERT INTO ALL_CONS_COLUMNS VALUES ('HR','SYS_C001','EMP','EMP_ID',NULL)",
                }) {
                    st.execute(s);
                }
            }
            TableDef emp = new OracleMetadataReader().readTable(c, "HR", "EMP");
            ConstraintDef fk = emp.getConstraints().stream().filter(k -> "R".equals(k.getType())).findFirst().orElseThrow();
            assertEquals("EMP_DEPT_FK", fk.getName());
            assertEquals("DEPT", fk.getRefTable());
            assertEquals(List.of("DEPT_ID"), fk.getColumns());
            assertEquals(List.of("DEPT_ID"), fk.getRefColumns());
            assertTrue(emp.getConstraints().stream().anyMatch(k -> "U".equals(k.getType()) && k.getColumns().equals(List.of("MAIL"))));
            assertEquals(3, emp.getConstraints().size(), "PK / UK / FK（CHECK は対象外）");

            ErDdlService.Result r = ErDdlService.dbToMermaid(new OracleMetadataReader(), c, "HR", List.of("DEPT", "EMP"), new SchemaToErOptions());
            String mm = r.getText();
            assertTrue(mm.contains("DEPT[\"部署 (DEPT)\"] {"), mm);
            assertTrue(mm.contains("varchar2(30_char) DEPT_NAME \"[NOT NULL]\""), mm);
            assertTrue(mm.contains("number(10) EMP_ID PK \"[IDENTITY]\""), mm);
            assertTrue(mm.contains("varchar2(100_byte) MAIL UK \"メール\""), mm);
            assertTrue(mm.contains("DEPT ||--o{ EMP : \"DEPT_ID\""), mm);

            // ER 図 → Oracle DDL
            DdlOptions o = new DdlOptions();
            o.setDialect(Dialect.ORACLE);
            String ddl = DdlWriter.write(r.getModel(), o).getSql();
            assertTrue(ddl.contains("CONSTRAINT EMP_DEPT_FK FOREIGN KEY (DEPT_ID) REFERENCES DEPT (DEPT_ID)"), "DB の制約名を使う: " + ddl);
            assertTrue(ddl.contains("EMP_ID  NUMBER(10) GENERATED BY DEFAULT AS IDENTITY NOT NULL"), ddl);
        }
    }

    @Test
    void サンプルのER図はMermaidとして往復しても同じ() {
        ErDdlService.Result r = MermaidToDdlTest.convert(MainFrame.SAMPLE, MermaidToDdlTest.opts(Dialect.H2));
        assertNotNull(r.getText());
    }
}
