package jp.mermaidddl;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jp.mermaidddl.config.ConnectionInfo;
import jp.mermaidddl.config.ConnectionStore;
import jp.mermaidddl.config.CryptoUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 接続先の保存（パスワード暗号化）のテスト */
class ConnectionStoreTest {

    @TempDir
    Path dir;

    @Test
    void 暗号化と復号() throws Exception {
        CryptoUtil c = new CryptoUtil(dir.resolve("secret.key"));
        String enc = c.encrypt("p@ssワード");
        assertTrue(enc.startsWith("enc:"));
        assertNotEquals(enc, c.encrypt("p@ssワード"), "IV がランダムなので毎回異なる");
        assertEquals("p@ssワード", c.decrypt(enc));
        assertEquals("plain", c.decrypt("plain"));
        // 同じ鍵ファイルを読み直しても復号できる
        assertEquals("p@ssワード", new CryptoUtil(dir.resolve("secret.key")).decrypt(enc));
        // 別の鍵では復号できない
        assertThrows(IllegalStateException.class, () -> new CryptoUtil(dir.resolve("other.key")).decrypt(enc));
    }

    @Test
    void 初回はサンプルH2を作成し_保存と読込ができる() throws Exception {
        Path file = dir.resolve("connections.json");
        CryptoUtil crypto = new CryptoUtil(dir.resolve("secret.key"));
        ConnectionStore store = new ConnectionStore(file, crypto);
        store.load();
        assertEquals(1, store.getConnections().size());
        assertEquals("サンプル H2", store.getConnections().get(0).getName());

        store.setConnections(List.of(
                ConnectionStore.sampleH2(),
                new ConnectionInfo("本番PG", "jdbc:postgresql://db/app", "app", "secret!", "org.postgresql.Driver")));
        store.setLastSelected("本番PG");
        store.save();

        String json = Files.readString(file);
        assertFalse(json.contains("secret!"), "パスワードは平文で保存されない");

        ConnectionStore again = new ConnectionStore(file, crypto);
        again.load();
        assertEquals(2, again.getConnections().size());
        assertEquals("secret!", again.find("本番PG").getPassword());
        assertEquals("本番PG", again.getLastSelected());
    }
}
