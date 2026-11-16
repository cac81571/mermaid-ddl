package jp.mermaidddl.er

import groovy.transform.CompileStatic

/** リレーションの多重度（Mermaid erDiagram の記号に対応） */
@CompileStatic
enum Cardinality {
    /** |o / o| : 0 または 1 */
    ZERO_OR_ONE('|o', 'o|'),
    /** || : ちょうど 1 */
    EXACTLY_ONE('||', '||'),
    /** }o / o{ : 0 以上 */
    ZERO_OR_MORE('}o', 'o{'),
    /** }| / |{ : 1 以上 */
    ONE_OR_MORE('}|', '|{')

    final String left
    final String right

    Cardinality(String left, String right) {
        this.left = left
        this.right = right
    }

    boolean isMany() { this == ZERO_OR_MORE || this == ONE_OR_MORE }

    boolean isOptional() { this == ZERO_OR_ONE || this == ZERO_OR_MORE }
}

/** 解析エラー・警告（行番号付き。0 = 行なし） */
@CompileStatic
class ErIssue {
    int line
    String message
    boolean error

    @Override
    String toString() { line > 0 ? "${line} 行目: ${message}" : message }
}

/** 属性（列） */
@CompileStatic
class ErAttribute {
    String type
    String name
    /** PK / FK / UK */
    Set<String> keys = new LinkedHashSet<>()
    /** コメント（末尾の [NOT NULL] などのタグを除いた本文） */
    String comment
    /** コメント末尾のタグ: NOT NULL / DEFAULT ... / IDENTITY / UNIQUE */
    boolean notNull
    String defaultExpr
    boolean identity
    /** IDENTITY の種類: null（BY DEFAULT）/ ALWAYS / ON NULL */
    String identityKind
    int line

    boolean isPk() { keys.contains('PK') }
    boolean isFk() { keys.contains('FK') }
    boolean isUk() { keys.contains('UK') }
}

/** エンティティ（テーブル） */
@CompileStatic
class ErEntity {
    String name
    /** 表示名 CUSTOMER["顧客"] の "顧客"。DDL ではテーブルコメントになる */
    String alias
    List<ErAttribute> attributes = []
    int line

    ErAttribute attribute(String n) {
        attributes.find { it.name == n } ?: attributes.find { it.name.equalsIgnoreCase(n) }
    }
}

/** リレーション: left [leftCard]--[rightCard] right : label */
@CompileStatic
class ErRelationship {
    String left
    String right
    /** left 側の記号（right から見た left の数） */
    Cardinality leftCard
    /** right 側の記号（left から見た right の数） */
    Cardinality rightCard
    /** -- = 識別（実線）, .. = 非識別（点線） */
    boolean identifying = true
    String label
    int line
}

/** erDiagram 全体 */
@CompileStatic
class ErDiagram {
    String title
    String direction
    Map<String, ErEntity> entities = new LinkedHashMap<>()
    List<ErRelationship> relationships = []
    List<ErIssue> issues = []

    List<ErIssue> getErrors() { issues.findAll { it.error } }

    List<ErIssue> getWarnings() { issues.findAll { !it.error } }

    boolean hasErrors() { issues.any { it.error } }

    ErEntity entity(String n) { entities.get(n) }

    void error(int line, String msg) { issues << new ErIssue(line: line, message: msg, error: true) }

    void warn(int line, String msg) { issues << new ErIssue(line: line, message: msg, error: false) }
}
