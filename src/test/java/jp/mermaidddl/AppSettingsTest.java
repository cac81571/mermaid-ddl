package jp.mermaidddl;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 画面オプションの保存（settings.properties） */
class AppSettingsTest {

    @Test
    void 保存して読み直す(@TempDir Path dir) {
        Path f = dir.resolve("sub/settings.properties");
        AppSettings s = new AppSettings(f);
        assertTrue(s.getBoolean("er.physicalNameInAlias", true), "未保存なら既定値");
        s.put("er.physicalNameInAlias", false);
        s.put("ddl.oracleLimit", 1);
        assertTrue(Files.exists(f));
        AppSettings again = new AppSettings(f);
        assertFalse(again.getBoolean("er.physicalNameInAlias", true));
        assertEquals(1, again.getInt("ddl.oracleLimit", 0));
        assertEquals(7, again.getInt("missing", 7));
    }

    @Test
    void 壊れた値や読めないファイルは既定値(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("settings.properties");
        Files.writeString(f, "ddl.oracleLimit=abc\n");
        AppSettings s = new AppSettings(f);
        assertEquals(0, s.getInt("ddl.oracleLimit", 0));
        assertTrue(new AppSettings(dir.resolve("none.properties")).getBoolean("x", true));
    }
}
