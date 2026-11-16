package jp.mermaidddl;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.stream.Collectors;
import jp.mermaidddl.er.Cardinality;
import jp.mermaidddl.er.ErAttribute;
import jp.mermaidddl.er.ErDiagram;
import jp.mermaidddl.er.ErEntity;
import jp.mermaidddl.er.ErIssue;
import jp.mermaidddl.er.ErRelationship;
import jp.mermaidddl.er.MermaidParser;
import jp.mermaidddl.ui.MainFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Mermaid erDiagram パーサーのテスト */
class MermaidParserTest {

    static ErDiagram parse(String s) {
        return new MermaidParser().parse(s);
    }

    static ErDiagram parseOk(String s) {
        ErDiagram d = parse(s);
        assertFalse(d.hasErrors(), () -> "errors=" + d.getErrors());
        return d;
    }

    private static List<Integer> errorLines(ErDiagram d) {
        return d.getErrors().stream().map(ErIssue::getLine).collect(Collectors.toList());
    }

    @Test
    void サンプルを解析できる() {
        ErDiagram d = parseOk(MainFrame.SAMPLE);
        assertEquals(List.of("CUSTOMER", "ORDERS", "ORDER_ITEM", "PRODUCT"), List.copyOf(d.getEntities().keySet()));
        ErEntity c = d.entity("CUSTOMER");
        assertEquals("顧客", c.getAlias());
        assertEquals(2, c.getLine());
        ErAttribute id = c.attribute("customer_id");
        assertEquals("int", id.getType());
        assertTrue(id.isPk());
        assertEquals("顧客ID", id.getComment());
        ErAttribute name = c.attribute("name");
        assertEquals("string(100)", name.getType());
        assertTrue(name.getNotNull(), "[NOT NULL] タグ");
        assertEquals("氏名", name.getComment(), "タグはコメントから除く");
        assertTrue(c.attribute("email").isUk());
        assertEquals("varchar2(200)", c.attribute("email").getType());
        assertEquals("number(10,2)", d.entity("ORDERS").attribute("amount").getType());
        ErAttribute oi = d.entity("ORDER_ITEM").attribute("order_id");
        assertEquals(List.of("PK", "FK"), List.copyOf(oi.getKeys()), "PK, FK の複数キー");
        assertTrue(d.entity("ORDERS").attribute("order_id").getIdentity());
        assertEquals("CURRENT_TIMESTAMP", d.entity("ORDERS").attribute("ordered_at").getDefaultExpr());

        assertEquals(3, d.getRelationships().size());
        ErRelationship r = d.getRelationships().get(1);
        assertEquals("ORDERS", r.getLeft());
        assertEquals("ORDER_ITEM", r.getRight());
        assertEquals(Cardinality.EXACTLY_ONE, r.getLeftCard());
        assertEquals(Cardinality.ONE_OR_MORE, r.getRightCard());
        assertTrue(r.getIdentifying());
        assertEquals("含む", r.getLabel());
        assertEquals(26, r.getLine());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', value = {
            "A |o--o{ B : x;ZERO_OR_ONE;ZERO_OR_MORE;true",
            "A ||--|{ B : x;EXACTLY_ONE;ONE_OR_MORE;true",
            "A }o..o| B : x;ZERO_OR_MORE;ZERO_OR_ONE;false",
            "A }|..|| B : x;ONE_OR_MORE;EXACTLY_ONE;false",
            "A||--o{B : x;EXACTLY_ONE;ZERO_OR_MORE;true",
            "A |o.-o{ B : x;ZERO_OR_ONE;ZERO_OR_MORE;false",
            "A one or zero to zero or more B : x;ZERO_OR_ONE;ZERO_OR_MORE;true",
            "A only one optionally to one or more B : x;EXACTLY_ONE;ONE_OR_MORE;false",
            "A 1 to 0+ B : x;EXACTLY_ONE;ZERO_OR_MORE;true",
            "A zero or one -- many(1) B : x;ZERO_OR_ONE;ONE_OR_MORE;true",
            "A one -- one or many B : x;EXACTLY_ONE;ONE_OR_MORE;true",
            "A many(0) .. 1 B : x;ZERO_OR_MORE;EXACTLY_ONE;false",
            "A 1+ to zero or many B : x;ONE_OR_MORE;ZERO_OR_MORE;true",
    })
    void 多重度記号と語形(String rel, Cardinality left, Cardinality right, boolean identifying) {
        ErDiagram d = parseOk("erDiagram\n    " + rel + "\n");
        assertEquals(1, d.getRelationships().size());
        ErRelationship r = d.getRelationships().get(0);
        assertEquals("A", r.getLeft());
        assertEquals("B", r.getRight());
        assertEquals(left, r.getLeftCard());
        assertEquals(right, r.getRightCard());
        assertEquals(identifying, r.getIdentifying());
        assertEquals("x", r.getLabel());
        assertEquals(2, r.getLine());
    }

    @Test
    void ラベルの書き方() {
        ErDiagram d = parseOk(String.join("\n", "erDiagram",
                "A ||--o{ B : \"places order\"",
                "A ||--o{ C : places",
                "A ||--o{ D : \"\"",
                "A ||--o{ E"));
        List<ErRelationship> r = d.getRelationships();
        assertEquals("places order", r.get(0).getLabel());
        assertEquals("places", r.get(1).getLabel());
        assertNull(r.get(2).getLabel());
        assertNull(r.get(3).getLabel());
        assertTrue(d.getWarnings().stream().anyMatch(w -> w.getLine() == 5 && w.getMessage().contains("ラベル")), d.getWarnings().toString());
    }

    @Test
    void エンティティ名と表示名の書き方() {
        ErDiagram d = parseOk(String.join("\n",
                "erDiagram",
                "  \"LINE ITEM\" { int id PK }",
                "  顧客 { int 顧客ID PK \"日本語名\" }",
                "  LINE-ITEM[\"明細行\"] { int id PK }",
                "  p[Person] {",
                "  }",
                "  STYLED:::someclass { int id PK }",
                "  ALONE",
                "  ALIAS_ONLY[\"別名だけ\"]",
                "  ORDER_X['単引用符']"));
        assertEquals(List.of("LINE ITEM", "顧客", "LINE-ITEM", "p", "STYLED", "ALONE", "ALIAS_ONLY", "ORDER_X"),
                List.copyOf(d.getEntities().keySet()));
        assertEquals("明細行", d.entity("LINE-ITEM").getAlias());
        assertEquals("Person", d.entity("p").getAlias());
        assertEquals("別名だけ", d.entity("ALIAS_ONLY").getAlias());
        assertEquals("単引用符", d.entity("ORDER_X").getAlias());
        assertEquals("日本語名", d.entity("顧客").attribute("顧客ID").getComment());
        assertEquals(1, d.entity("STYLED").getAttributes().size());
        assertTrue(d.entity("ALONE").getAttributes().isEmpty());
    }

    @Test
    void 一行の属性ブロックと複数属性() {
        ErDiagram d = parseOk("erDiagram\nT { int id PK string name \"名前\" date d }\n");
        ErEntity t = d.entity("T");
        assertEquals(List.of("id", "name", "d"), t.getAttributes().stream().map(ErAttribute::getName).collect(Collectors.toList()));
        assertEquals("名前", t.attribute("name").getComment());
    }

    @Test
    void 型の表記を保持する() {
        ErDiagram d = parseOk(String.join("\n", "erDiagram", "T {",
                "  varchar2(50) a",
                "  number(10,2) b",
                "  number(10, 2) c",
                "  varchar2(50 char) d",
                "  timestamp(6)_with_time_zone e",
                "  string[] f",
                "  NUMBER(*,0) g",
                "}"));
        ErEntity t = d.entity("T");
        assertEquals("varchar2(50)", t.attribute("a").getType());
        assertEquals("number(10,2)", t.attribute("b").getType());
        assertEquals("number(10, 2)", t.attribute("c").getType(), "括弧内の空白も型の一部");
        assertEquals("varchar2(50 char)", t.attribute("d").getType());
        assertEquals("timestamp(6)_with_time_zone", t.attribute("e").getType());
        assertEquals("string[]", t.attribute("f").getType());
        assertEquals("NUMBER(*,0)", t.attribute("g").getType());
    }

    @Test
    void 前処理_front_matter_コメント_無視する文() {
        String src = String.join("\n",
                "---",
                "title: 受注管理",
                "config:",
                "  theme: forest",
                "---",
                "%%{init: {'theme': 'dark'}}%%",
                "erDiagram",
                "    direction LR",
                "    %% コメント行",
                "    accTitle: アクセシビリティ",
                "    accDescr {",
                "      複数行の説明",
                "    }",
                "    classDef important fill:#f96,stroke:#333",
                "    class A important",
                "    style B fill:#f9f",
                "    A { int id PK %% 行末コメント",
                "    }",
                "    A ||--o{ B : has %% ここもコメント",
                "    B { int id PK",
                "        int a_id FK }");
        ErDiagram d = parseOk(src);
        assertEquals("受注管理", d.getTitle());
        assertEquals("LR", d.getDirection());
        assertEquals(List.of("A", "B"), List.copyOf(d.getEntities().keySet()));
        assertEquals("has", d.getRelationships().get(0).getLabel());
        assertEquals(19, d.getRelationships().get(0).getLine(), "front matter を除いても行番号は元のまま");
        assertEquals(2, d.entity("B").getAttributes().size());
    }

    @Test
    void 同じエンティティの複数宣言はまとめる() {
        ErDiagram d = parse(String.join("\n", "erDiagram",
                "A { int id PK }",
                "A [\"エー\"] { string name }",
                "A { string name }"));
        assertEquals(1, d.getEntities().size());
        assertEquals(2, d.entity("A").getAttributes().size());
        assertEquals("エー", d.entity("A").getAlias());
        assertTrue(d.getWarnings().stream().anyMatch(w -> w.getLine() == 4 && w.getMessage().contains("重複")));
    }

    @Test
    void コメントのタグ() {
        ErDiagram d = parseOk(String.join("\n", "erDiagram", "T {",
                "  int a PK \"ID [IDENTITY ALWAYS]\"",
                "  char(1) b \"区分 [DEFAULT 'N'] [NOT NULL]\"",
                "  string c \"[UNIQUE] [必須]\"",
                "  string d \"[参考] 角括弧は残る\"",
                "  int e \"[IDENTITY ON NULL]\"",
                "}"));
        ErEntity t = d.entity("T");
        assertTrue(t.attribute("a").getIdentity());
        assertEquals("ALWAYS", t.attribute("a").getIdentityKind());
        assertEquals("ID", t.attribute("a").getComment());
        assertEquals("'N'", t.attribute("b").getDefaultExpr());
        assertTrue(t.attribute("b").getNotNull());
        assertEquals("区分", t.attribute("b").getComment());
        assertTrue(t.attribute("c").isUk());
        assertTrue(t.attribute("c").getNotNull());
        assertNull(t.attribute("c").getComment());
        assertEquals("[参考] 角括弧は残る", t.attribute("d").getComment());
        assertEquals("ON NULL", t.attribute("e").getIdentityKind());
    }

    @Test
    void エラーは行番号付きで_解析を続ける() {
        String src = String.join("\n",
                "erDiagram",                          // 1
                "  A {",                              // 2
                "    int",                            // 3 名前がない
                "    int id PK, XX",                  // 4 不正なキー
                "    string name \"閉じない",          // 5
                "  }",                                // 6
                "  A --o{ B : x",                     // 7 左の多重度がない
                "  C ||--o{ : x",                     // 8 右のエンティティがない
                "  D { int id PK }",                  // 9 正常
                "  }",                                // 10 余分な }
                "  E {",                              // 11 閉じない
                "    int id PK");                     // 12
        ErDiagram d = parse(src);
        List<Integer> lines = errorLines(d);
        assertTrue(lines.contains(3), d.getErrors().toString());
        assertTrue(lines.contains(4), d.getErrors().toString());
        assertTrue(lines.contains(5), d.getErrors().toString());
        assertTrue(lines.contains(7), d.getErrors().toString());
        assertTrue(lines.contains(8), d.getErrors().toString());
        assertTrue(lines.contains(10), d.getErrors().toString());
        assertTrue(lines.contains(11), d.getErrors().toString());
        assertTrue(d.getErrors().stream().anyMatch(e -> e.getMessage().contains("属性名がありません")));
        assertNotNull(d.entity("D"), "エラーの後も解析を続ける");
        assertEquals(1, d.entity("D").getAttributes().size());
        assertTrue(d.getErrors().get(0).toString().matches("\\d+ 行目: .*"));
    }

    @Test
    void erDiagramで始まらない() {
        ErDiagram d = parse("\n\nflowchart LR\n  A --> B\n");
        assertTrue(d.hasErrors());
        assertTrue(d.getErrors().stream().anyMatch(e -> e.getLine() == 3 && e.getMessage().contains("erDiagram")), d.getErrors().toString());
        assertTrue(parse("").hasErrors());
    }
}
