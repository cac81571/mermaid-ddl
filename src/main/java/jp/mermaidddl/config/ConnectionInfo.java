package jp.mermaidddl.config;

import java.util.Objects;

/** DB 接続先の定義。password はメモリ上では平文、保存時に暗号化される。 */
public class ConnectionInfo {

    private String name = "";
    private String url = "";
    private String user = "";
    private String password = "";
    /** JDBC ドライバクラス名（任意。空なら URL から自動判定） */
    private String driverClass = "";

    public ConnectionInfo() {}

    public ConnectionInfo(String name, String url, String user, String password, String driverClass) {
        this.name = nz(name);
        this.url = nz(url);
        this.user = nz(user);
        this.password = nz(password);
        this.driverClass = nz(driverClass);
    }

    public ConnectionInfo copy() {
        return new ConnectionInfo(name, url, user, password, driverClass);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    public String getName() { return name; }
    public void setName(String name) { this.name = nz(name); }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = nz(url); }
    public String getUser() { return user; }
    public void setUser(String user) { this.user = nz(user); }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = nz(password); }
    public String getDriverClass() { return driverClass; }
    public void setDriverClass(String driverClass) { this.driverClass = nz(driverClass); }

    @Override
    public String toString() { return name; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ConnectionInfo)) return false;
        ConnectionInfo c = (ConnectionInfo) o;
        return name.equals(c.name) && url.equals(c.url) && user.equals(c.user)
                && password.equals(c.password) && driverClass.equals(c.driverClass);
    }

    @Override
    public int hashCode() { return Objects.hash(name, url, user, password, driverClass); }
}
