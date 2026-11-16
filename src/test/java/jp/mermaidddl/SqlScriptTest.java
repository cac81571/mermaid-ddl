package jp.mermaidddl;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import jp.mermaidddl.core.SqlScript;
import org.junit.jupiter.api.Test;

/** SQL スクリプトの分割のテスト */
class SqlScriptTest {

    @Test
    void 複数文に分割する() {
        String sql = "INSERT INTO t VALUES ('a;b'); -- x;y\nUPDATE t SET v = 1;\n/* ; */\n  ;SELECT 1";
        List<String> s = SqlScript.split(sql);
        assertEquals(3, s.size());
        assertTrue(s.get(0).contains("'a;b'"));
        assertTrue(s.get(2).startsWith("SELECT"));
    }

    @Test
    void 先頭の語() {
        assertEquals("DROP", SqlScript.firstWord("-- comment\n drop table t"));
        assertEquals("CREATE", SqlScript.firstWord("/* x */ CREATE TABLE t (id INT)"));
    }
}
