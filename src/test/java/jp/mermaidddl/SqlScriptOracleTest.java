package jp.mermaidddl;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import jp.mermaidddl.core.SqlScript;
import org.junit.jupiter.api.Test;

/** Oracle 形式スクリプトの分割（/ 区切り・PL/SQL ブロック・q 引用）のテスト */
class SqlScriptOracleTest {

    @Test
    void スラッシュ行とPLSQLブロック() {
        String script = """
                CREATE SEQUENCE emp_seq START WITH 1;
                CREATE OR REPLACE TRIGGER emp_bi
                BEFORE INSERT ON emp FOR EACH ROW
                BEGIN
                  IF :NEW.id IS NULL THEN
                    SELECT emp_seq.NEXTVAL INTO :NEW.id FROM dual;
                  END IF;
                END;
                /
                BEGIN
                  UPDATE emp SET name = :name WHERE id = :id;
                  v := 1;
                END;
                /
                INSERT INTO emp(id) VALUES (1)
                /
                SELECT 'a/b;c' FROM dual;
                """;
        List<String> s = SqlScript.split(script);
        assertEquals(5, s.size(), s.toString());
        assertTrue(s.get(1).startsWith("CREATE OR REPLACE TRIGGER"));
        assertTrue(s.get(1).endsWith("END;"), "PL/SQL の END; は保持する");
        assertTrue(s.get(1).contains("FROM dual;"), "ブロック内の ; で分割しない");
        assertTrue(s.get(2).startsWith("BEGIN"));
        assertEquals("INSERT INTO emp(id) VALUES (1)", s.get(3));
        assertEquals("SELECT 'a/b;c' FROM dual", s.get(4));
    }

    @Test
    void PLSQL判定とDDL判定() {
        assertTrue(SqlScript.isPlsql("CREATE OR REPLACE EDITIONABLE TRIGGER x BEFORE INSERT ON t BEGIN NULL; END;"));
        assertTrue(SqlScript.isPlsql("create or replace package body p as end;"));
        assertTrue(SqlScript.isPlsql("DECLARE v NUMBER; BEGIN NULL; END;"));
        assertFalse(SqlScript.isPlsql("CREATE TABLE t (id NUMBER)"));
        assertTrue(SqlScript.isDdl("COMMENT ON TABLE t IS 'x'"));
        assertTrue(SqlScript.isDdl("  create table t(id int)"));
        assertFalse(SqlScript.isDdl("INSERT INTO t VALUES (1)"));
    }

    @Test
    void q引用と引用識別子内の区切り文字は無視() {
        List<String> s = SqlScript.split("INSERT INTO t VALUES (q'[it's; ok]', \"A;B\");\nSELECT 1 FROM dual");
        assertEquals(2, s.size());
        assertTrue(s.get(0).contains("q'[it's; ok]'"));
    }

}
