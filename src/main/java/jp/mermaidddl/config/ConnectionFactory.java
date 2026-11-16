package jp.mermaidddl.config;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.ServiceLoader;

/**
 * JDBC 接続を作成する。ドライバは JAR に同梱した H2 と Oracle（ojdbc11）を使う。
 */
public class ConnectionFactory {

    public Connection open(ConnectionInfo info) throws SQLException {
        if (info == null) throw new SQLException("接続先が選択されていません");
        if (info.getUrl().isBlank()) throw new SQLException("JDBC URL が空です");
        Properties props = new Properties();
        if (!info.getUser().isEmpty()) props.setProperty("user", info.getUser());
        if (!info.getPassword().isEmpty()) props.setProperty("password", info.getPassword());

        Driver driver = findDriver(info);
        Connection con = driver != null ? driver.connect(info.getUrl(), props) : DriverManager.getConnection(info.getUrl(), props);
        if (con == null) throw new SQLException("この URL を扱える JDBC ドライバがありません: " + info.getUrl());
        return con;
    }

    private Driver findDriver(ConnectionInfo info) throws SQLException {
        ClassLoader cl = ConnectionFactory.class.getClassLoader();
        if (!info.getDriverClass().isBlank()) {
            try {
                Class<?> c = Class.forName(info.getDriverClass().trim(), true, cl);
                return (Driver) c.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | ClassCastException e) {
                throw new SQLException("JDBC ドライバクラスが見つかりません: " + info.getDriverClass()
                        + "（同梱しているのは H2 と Oracle のドライバです）", e);
            }
        }
        for (Driver d : ServiceLoader.load(Driver.class, cl)) {
            try {
                if (d.acceptsURL(info.getUrl())) return d;
            } catch (SQLException ignore) {
                // 次のドライバへ
            }
        }
        return null; // DriverManager に任せる
    }

    /** 接続テスト。成功時は DB 製品名とバージョンを返す。 */
    public String test(ConnectionInfo info) throws SQLException {
        try (Connection con = open(info)) {
            var md = con.getMetaData();
            return md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
        }
    }
}
