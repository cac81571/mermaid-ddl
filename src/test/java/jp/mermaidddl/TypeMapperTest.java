package jp.mermaidddl;

import static org.junit.jupiter.api.Assertions.*;

import jp.mermaidddl.schema.ColumnDef;
import jp.mermaidddl.schema.Dialect;
import jp.mermaidddl.schema.LogicalType;
import jp.mermaidddl.schema.TypeMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** H2 / Oracle の型 → 論理型の対応表のテスト */
class TypeMapperTest {

    private static LogicalType map(String type, String p, String s, Dialect d) {
        ColumnDef c = new ColumnDef();
        c.setTypeName(type);
        c.setPrecision(p == null || p.isEmpty() ? null : Integer.valueOf(p));
        c.setScale(s == null || s.isEmpty() ? null : Integer.valueOf(s));
        return TypeMapper.logicalType(c, d);
    }

    @ParameterizedTest(name = "Oracle {0}({1},{2}) → {3}")
    @CsvSource({
        "VARCHAR2,,,STRING", "NVARCHAR2,,,STRING", "CHAR,,,STRING", "NCHAR,,,STRING",
        "CLOB,,,CLOB", "NCLOB,,,CLOB", "LONG,,,CLOB",
        "NUMBER,10,0,INTEGER", "NUMBER,10,,INTEGER", "NUMBER,18,0,INTEGER", "NUMBER,19,0,DECIMAL",
        "NUMBER,,0,INTEGER", "NUMBER,10,2,DECIMAL", "NUMBER,,,DECIMAL", "NUMBER,5,-2,DECIMAL",
        "FLOAT,126,,DECIMAL", "BINARY_FLOAT,,,DECIMAL", "BINARY_DOUBLE,,,DECIMAL", "INTEGER,,,INTEGER",
        "DATE,,,DATE", "TIMESTAMP,6,,DATETIME", "TIMESTAMP WITH TIME ZONE,,,DATETIME_TZ",
        "TIMESTAMP WITH LOCAL TIME ZONE,,,DATETIME_TZ", "TIMESTAMP(6) WITH TIME ZONE,,,DATETIME_TZ",
        "BOOLEAN,,,BOOLEAN", "BLOB,,,BINARY", "RAW,,,BINARY", "LONG RAW,,,BINARY", "BFILE,,,BINARY",
        "ROWID,,,OTHER", "UROWID,,,OTHER", "XMLTYPE,,,OTHER", "INTERVAL DAY TO SECOND,,,OTHER", "INTERVAL YEAR TO MONTH,,,OTHER"
    })
    void oracle(String type, String p, String s, LogicalType expected) {
        assertEquals(expected, map(type, p, s, Dialect.ORACLE));
    }

    @ParameterizedTest(name = "H2 {0}({1},{2}) → {3}")
    @CsvSource({
        "CHARACTER VARYING,,,STRING", "VARCHAR,,,STRING", "VARCHAR_IGNORECASE,,,STRING", "CHARACTER,,,STRING",
        "CHARACTER LARGE OBJECT,,,CLOB", "CLOB,,,CLOB",
        "INTEGER,,,INTEGER", "BIGINT,,,INTEGER", "SMALLINT,,,INTEGER", "TINYINT,,,INTEGER",
        "NUMERIC,10,2,DECIMAL", "NUMERIC,10,0,INTEGER", "NUMERIC,,,DECIMAL", "DECIMAL,20,0,DECIMAL", "DECFLOAT,,,DECIMAL",
        "REAL,,,DECIMAL", "DOUBLE PRECISION,,,DECIMAL",
        "DATE,,,DATE", "TIMESTAMP,,,DATETIME", "TIMESTAMP WITH TIME ZONE,,,DATETIME_TZ", "TIME,,,TIME",
        "TIME WITH TIME ZONE,,,TIME", "BOOLEAN,,,BOOLEAN",
        "BINARY VARYING,,,BINARY", "BINARY LARGE OBJECT,,,BINARY", "BLOB,,,BINARY", "VARBINARY,,,BINARY",
        "UUID,,,OTHER", "JSON,,,OTHER", "ARRAY,,,OTHER"
    })
    void h2(String type, String p, String s, LogicalType expected) {
        assertEquals(expected, map(type, p, s, Dialect.H2));
    }

}
