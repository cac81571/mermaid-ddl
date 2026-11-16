package jp.mermaidddl.er

import groovy.transform.CompileStatic
import jp.mermaidddl.core.SqlScript
import jp.mermaidddl.schema.DdlParser
import jp.mermaidddl.schema.Dialect
import jp.mermaidddl.schema.MetadataReader
import jp.mermaidddl.schema.SchemaModel
import jp.mermaidddl.schema.TableDef

import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement

/** ER 図 ⇔ DDL 変換の窓口（画面とテストから使う） */
@CompileStatic
final class ErDdlService {

    private ErDdlService() {}

    /** 変換結果 */
    static class Result {
        /** 出力テキスト（エラー時は null） */
        String text
        /** 行番号付きのエラー（あると出力しない） */
        List<String> errors = []
        List<String> warnings = []
        SchemaModel model
        ErDiagram diagram

        boolean isOk() { !errors }
    }

    /** Mermaid erDiagram → DDL */
    static Result mermaidToDdl(String mermaid, ErToSchemaOptions so, DdlOptions dop) {
        Result r = new Result()
        ErDiagram d = new MermaidParser().parse(mermaid)
        r.diagram = d
        r.errors.addAll(d.errors.collect { it.toString() })
        if (r.errors) {
            r.warnings.addAll(d.warnings.collect { it.toString() })
            return r
        }
        if (!d.entities) {
            r.errors << 'エンティティが 1 つもありません'
            return r
        }
        so.dialect = dop.dialect
        SchemaModel m = ErConverter.toSchema(d, so)
        r.model = m
        if (!m.tables) {
            r.errors << '属性を持つエンティティがないため、DDL を出力できません'
            r.warnings.addAll(m.warnings)
            return r
        }
        DdlResult dr = DdlWriter.write(m, dop)
        r.text = dr.sql
        r.warnings.addAll(m.warnings)
        r.warnings.addAll(dr.warnings)
        r
    }

    /** DDL → Mermaid erDiagram */
    static Result ddlToMermaid(String ddl, Dialect dialect, SchemaToErOptions eo) {
        Result r = new Result()
        SchemaModel m = new DdlParser(dialect).parse(ddl)
        r.model = m
        if (!m.tables) {
            r.errors << 'CREATE TABLE 文が見つかりません'
            r.errors.addAll(m.warnings)
            return r
        }
        schemaToMermaid(m, eo, r)
    }

    /** 接続中の DB のテーブル → Mermaid erDiagram */
    static Result dbToMermaid(Connection con, String schema, List<String> tables, SchemaToErOptions eo) {
        dbToMermaid(MetadataReader.forConnection(con), con, schema, tables, eo)
    }

    /** 読み取り方法を指定して DB のテーブル → Mermaid erDiagram */
    static Result dbToMermaid(MetadataReader reader, Connection con, String schema, List<String> tables, SchemaToErOptions eo) {
        Result r = new Result()
        SchemaModel m = new SchemaModel()
        tables.each { String t ->
            TableDef td = reader.readTable(con, schema, t)
            m.tables << td
            td.notes.each { String n -> m.warnings << "${t}: ${n}".toString() }
        }
        r.model = m
        schemaToMermaid(m, eo, r)
    }

    private static Result schemaToMermaid(SchemaModel m, SchemaToErOptions eo, Result r) {
        ErDiagram d = ErConverter.toDiagram(m, eo)
        r.diagram = d
        r.text = MermaidWriter.write(d)
        r.warnings.addAll(d.warnings.collect { it.toString() })
        r
    }

    // ================================================================ DDL の実行

    /** 実行結果 */
    static class ExecReport {
        int executed
        List<String> skipped = []
        /** 失敗した文（null = すべて成功） */
        String failedStatement
        int failedLine
        String failure

        boolean isOk() { failure == null }

        String summary() {
            ok ? "${executed} 文を実行しました${skipped ? '（存在しないオブジェクトの DROP ' + skipped.size() + ' 件はスキップ）' : ''}"
               : "${failedLine} 行目の文でエラー: ${failure}（それまでの ${executed} 文は実行済み）"
        }
    }

    /**
     * DDL を 1 文ずつ実行する。存在しないテーブル・シーケンスの DROP のエラーは無視し、それ以外のエラーで停止する。
     * DDL は Oracle では自動コミットされる点に注意。
     */
    static ExecReport executeDdl(Connection con, String ddl) {
        ExecReport rep = new ExecReport()
        for (SqlScript.Statement st : SqlScript.splitWithLines(ddl)) {
            Statement s = con.createStatement()
            try {
                s.execute(st.sql)
                rep.executed++
            } catch (SQLException e) {
                if (SqlScript.firstWord(st.sql) == 'DROP' && isNotFound(e)) {
                    rep.skipped << st.sql
                    continue
                }
                rep.failedStatement = st.sql
                rep.failedLine = st.line
                rep.failure = e.message?.readLines()?.find { it.trim() } ?: e.toString()
                return rep
            } finally {
                s.close()
            }
        }
        rep
    }

    /** ORA-00942（表なし）/ ORA-02289（順序なし）/ ORA-04043 / H2 42102, 42104, 90036 */
    static boolean isNotFound(SQLException e) {
        int c = e.errorCode
        c in [942, 2289, 4043, 42102, 42104, 90036] || e.SQLState in ['42S02', '42102', '42104', '90036']
    }
}
