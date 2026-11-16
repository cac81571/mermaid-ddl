package jp.mermaidddl;

import static org.junit.jupiter.api.Assertions.*;

import jp.mermaidddl.er.ErTypes;
import jp.mermaidddl.schema.Dialect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ER 図の型 ⇔ H2 / Oracle の型 */
class ErTypesTest {

    @ParameterizedTest(name = "{0} → H2 {1} / Oracle {2}")
    @CsvSource(delimiter = ';', value = {
            // 汎用型
            "string;VARCHAR(255);VARCHAR2(255 CHAR)",
            "string(100);VARCHAR(100);VARCHAR2(100 CHAR)",
            "str(10);VARCHAR(10);VARCHAR2(10 CHAR)",
            "text;CLOB;CLOB",
            "int;INTEGER;NUMBER(10)",
            "integer;INTEGER;INTEGER",
            "bigint;BIGINT;NUMBER(19)",
            "long;BIGINT;NUMBER(19)",
            "smallint;SMALLINT;NUMBER(5)",
            "tinyint;TINYINT;NUMBER(3)",
            "decimal;DECIMAL;NUMBER",
            "decimal(12,3);DECIMAL(12,3);NUMBER(12,3)",
            "numeric(8);NUMERIC(8);NUMBER(8)",
            "money;DECIMAL(19,4);NUMBER(19,4)",
            "float;DOUBLE PRECISION;BINARY_DOUBLE",
            "double;DOUBLE PRECISION;BINARY_DOUBLE",
            "real;REAL;BINARY_FLOAT",
            "date;DATE;DATE",
            "datetime;TIMESTAMP;TIMESTAMP",
            "timestamp(3);TIMESTAMP(3);TIMESTAMP(3)",
            "timestamptz;TIMESTAMP WITH TIME ZONE;TIMESTAMP WITH TIME ZONE",
            "time;TIME;VARCHAR2(8)",
            "bool;BOOLEAN;NUMBER(1)",
            "boolean;BOOLEAN;NUMBER(1)",
            "blob;BLOB;BLOB",
            "bytes;BLOB;BLOB",
            "uuid;UUID;RAW(16)",
            "json;JSON;CLOB",
            "string[];VARCHAR(255) ARRAY;CLOB",
            "enum;VARCHAR(255);VARCHAR2(255 CHAR)",
            // DB 固有の型（Oracle）
            "varchar2(50);VARCHAR(50);VARCHAR2(50)",
            "VARCHAR2(50 CHAR);VARCHAR(50);VARCHAR2(50 CHAR)",
            "varchar2(50_char);VARCHAR(50);VARCHAR2(50 CHAR)",
            "varchar2(50 byte);VARCHAR(50);VARCHAR2(50 BYTE)",
            "nvarchar2(20);VARCHAR(20);NVARCHAR2(20)",
            "char(1);CHAR(1);CHAR(1)",
            "char(2_byte);CHAR(2);CHAR(2 BYTE)",
            "number;NUMERIC;NUMBER",
            "number(10);NUMERIC(10);NUMBER(10)",
            "number(5);NUMERIC(5);NUMBER(5)",
            "number(10,2);NUMERIC(10,2);NUMBER(10,2)",
            "number(10, 2);NUMERIC(10,2);NUMBER(10,2)",
            "number(*,0);NUMERIC(38,0);NUMBER(*,0)",
            "clob;CLOB;CLOB",
            "nclob;CLOB;NCLOB",
            "raw(16);VARBINARY(16);RAW(16)",
            "binary_double;DOUBLE PRECISION;BINARY_DOUBLE",
            "binary_float;REAL;BINARY_FLOAT",
            "float(126);FLOAT(126);FLOAT(126)",
            "timestamp(6)_with_time_zone;TIMESTAMP(6) WITH TIME ZONE;TIMESTAMP(6) WITH TIME ZONE",
            "timestamp(6)_with_local_time_zone;TIMESTAMP(6) WITH TIME ZONE;TIMESTAMP(6) WITH LOCAL TIME ZONE",
            "interval_day_to_second;INTERVAL DAY TO SECOND;INTERVAL DAY TO SECOND",
            "interval_year_to_month;INTERVAL YEAR TO MONTH;INTERVAL YEAR TO MONTH",
            "xmltype;CLOB;XMLTYPE",
            "rowid;VARCHAR(18);ROWID",
            // DB 固有の型（H2）
            "varchar(50);VARCHAR(50);VARCHAR2(50)",
            "character_varying(30);VARCHAR(30);VARCHAR2(30 CHAR)",
            "double_precision;DOUBLE PRECISION;BINARY_DOUBLE",
            "varbinary(8);VARBINARY(8);RAW(8)",
            "timestamp;TIMESTAMP;TIMESTAMP",
    })
    void 型の変換(String er, String h2, String oracle) {
        assertEquals(h2, ErTypes.toSql(er, Dialect.H2).getSql(), "H2");
        assertEquals(oracle, ErTypes.toSql(er, Dialect.ORACLE).getSql(), "Oracle");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = ';', value = {
            "VARCHAR2(50 CHAR);varchar2(50_char)",
            "TIMESTAMP(6) WITH TIME ZONE;timestamp(6)_with_time_zone",
            "TIMESTAMP (6) WITH LOCAL TIME ZONE;timestamp(6)_with_local_time_zone",
            "NUMBER(10,2);number(10,2)",
            "NUMBER(10, 2);number(10,2)",
            "NUMBER(*,0);number(*,0)",
            "NUMERIC(10,0);numeric(10)",
            "CHARACTER VARYING(100);varchar(100)",
            "CHARACTER LARGE OBJECT;clob",
            "BINARY LARGE OBJECT;blob",
            "CHARACTER(1);char(1)",
            "DOUBLE PRECISION;double_precision",
            "LONG;clob",
            "LONG RAW;blob",
    })
    void SQLの型をMermaid表記に(String sql, String mermaid) {
        assertEquals(mermaid, ErTypes.toMermaid(sql));
    }

    @ParameterizedTest(name = "Oracle {0}")
    @CsvSource(delimiter = ';', value = {
            "VARCHAR2(50 CHAR)", "VARCHAR2(50 BYTE)", "VARCHAR2(50)", "NVARCHAR2(30)", "CHAR(1 BYTE)", "CHAR(3)",
            "NUMBER", "NUMBER(10)", "NUMBER(10,2)", "NUMBER(*,0)", "INTEGER", "BINARY_DOUBLE", "BINARY_FLOAT", "FLOAT(126)",
            "DATE", "TIMESTAMP", "TIMESTAMP(6)", "TIMESTAMP(6) WITH TIME ZONE", "TIMESTAMP(6) WITH LOCAL TIME ZONE",
            "CLOB", "NCLOB", "BLOB", "RAW(16)", "XMLTYPE", "INTERVAL YEAR TO MONTH", "INTERVAL DAY TO SECOND", "ROWID",
    })
    void Oracleの型はMermaid経由で元に戻る(String sql) {
        assertEquals(sql, ErTypes.toSql(ErTypes.toMermaid(sql), Dialect.ORACLE).getSql());
    }

    @ParameterizedTest(name = "H2 {0}")
    @CsvSource(delimiter = ';', value = {
            "VARCHAR(50)", "CHAR(2)", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "NUMERIC(10,2)", "DECIMAL(10,2)",
            "BOOLEAN", "DATE", "TIME", "TIMESTAMP", "TIMESTAMP(3)", "TIMESTAMP WITH TIME ZONE", "CLOB", "BLOB", "UUID", "JSON",
            "DOUBLE PRECISION", "REAL", "VARBINARY(16)",
    })
    void H2の型はMermaid経由で元に戻る(String sql) {
        assertEquals(sql, ErTypes.toSql(ErTypes.toMermaid(sql), Dialect.H2).getSql());
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {"bool;ORACLE", "time;ORACLE", "json;ORACLE", "string[];ORACLE", "unknown_type;H2",
            "string(5000);ORACLE"})
    void 変換で注意が出る型(String er, Dialect d) {
        assertNotNull(ErTypes.toSql(er, d).getWarning());
    }
}
