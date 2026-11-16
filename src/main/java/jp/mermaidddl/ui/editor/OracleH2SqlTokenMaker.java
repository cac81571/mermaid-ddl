package jp.mermaidddl.ui.editor;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.modes.SQLTokenMaker;

/**
 * 標準の SQL ハイライト（{@link SQLTokenMaker}）に Oracle / H2 のキーワード・型・関数を追加したもの。
 * SQLTokenMaker が識別子（または予約語）と判定した語を、追加の辞書で予約語・型・関数に置き換える。
 */
public class OracleH2SqlTokenMaker extends SQLTokenMaker {

    static final Map<String, Integer> EXTRA = new HashMap<>();

    private static void put(int type, String words) {
        for (String w : words.trim().split("\\s+")) EXTRA.put(w.toUpperCase(Locale.ROOT), type);
    }

    static {
        // DDL まわりのキーワード（Oracle / H2）
        put(Token.RESERVED_WORD, """
                GENERATED IDENTITY ALWAYS BY DEFAULT ON NULL START INCREMENT MINVALUE MAXVALUE NOMINVALUE NOMAXVALUE
                CYCLE NOCYCLE CACHE NOCACHE ORDER NOORDER SEQUENCE TABLESPACE STORAGE PCTFREE PCTUSED INITRANS
                LOGGING NOLOGGING COMPRESS NOCOMPRESS PARALLEL NOPARALLEL ENABLE DISABLE VALIDATE NOVALIDATE
                DEFERRABLE INITIALLY DEFERRED IMMEDIATE CASCADE CONSTRAINTS PURGE COMMENT COLUMN CONSTRAINT
                REFERENCES FOREIGN PRIMARY KEY UNIQUE CHECK INDEX BITMAP USING SEGMENT CREATION ORGANIZATION HEAP
                LOB SECUREFILE BASICFILE RETENTION INVISIBLE VISIBLE VIRTUAL EDITIONABLE NONEDITIONABLE
                IF EXISTS REPLACE FORCE NOFORCE MERGE MATCHED SYNONYM PUBLIC SCHEMA MODIFY RENAME TRUNCATE
                GLOBAL TEMPORARY PRESERVE ROWS AUTO_INCREMENT AUTOINCREMENT MEMORY CACHED LINKED SET
                CHAR BYTE LOCAL TIME ZONE WITH WITHOUT CURRENT_TIMESTAMP CURRENT_DATE SYSDATE SYSTIMESTAMP
                LOCALTIMESTAMP USER ROWNUM ROWID LEVEL PRIOR CONNECT
                """);
        // データ型
        put(Token.DATA_TYPE, """
                VARCHAR VARCHAR2 NVARCHAR NVARCHAR2 CHAR NCHAR CHARACTER VARYING NUMBER NUMERIC DECIMAL DEC
                INTEGER INT SMALLINT TINYINT BIGINT FLOAT REAL DOUBLE PRECISION BINARY_FLOAT BINARY_DOUBLE
                DATE TIMESTAMP INTERVAL YEAR MONTH DAY SECOND CLOB NCLOB BLOB BFILE LONG RAW
                BOOLEAN BOOL BIT VARBINARY BINARY UUID JSON JAVA_OBJECT GEOMETRY ENUM XMLTYPE UROWID
                CHARACTER_LARGE_OBJECT BINARY_LARGE_OBJECT DECFLOAT
                """);
        // よく使う関数
        put(Token.FUNCTION, """
                NVL NVL2 DECODE COALESCE NULLIF TO_CHAR TO_DATE TO_NUMBER TO_TIMESTAMP SYS_GUID RANDOM_UUID
                SUBSTR INSTR LENGTH LENGTHB UPPER LOWER TRIM LTRIM RTRIM LPAD RPAD REPLACE ROUND TRUNC
                CAST EXTRACT NEXTVAL CURRVAL
                """);
        EXTRA.put("CHAR", Token.DATA_TYPE);
        EXTRA.put("REPLACE", Token.RESERVED_WORD);
    }

    /** 追加辞書での種別（無ければ -1）。テスト用 */
    public static int extraType(String word) {
        Integer t = EXTRA.get(word.toUpperCase(Locale.ROOT));
        return t == null ? -1 : t;
    }

    @Override
    public void addToken(char[] array, int start, int end, int tokenType, int startOffset, boolean hyperlink) {
        // 識別子は辞書にあれば置き換え。標準では予約語扱いの型名（NUMBER など）も型の色にする
        if (tokenType == Token.IDENTIFIER || tokenType == Token.RESERVED_WORD) {
            Integer t = EXTRA.get(new String(array, start, end - start + 1).toUpperCase(Locale.ROOT));
            if (t != null && (tokenType == Token.IDENTIFIER || t != Token.RESERVED_WORD)) tokenType = t;
        }
        super.addToken(array, start, end, tokenType, startOffset, hyperlink);
    }
}
