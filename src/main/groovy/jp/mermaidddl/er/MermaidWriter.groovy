package jp.mermaidddl.er

import groovy.transform.CompileStatic

/** {@link ErDiagram} を Mermaid の erDiagram 記法で出力する。 */
@CompileStatic
final class MermaidWriter {

    private MermaidWriter() {}

    /** Mermaid のエンティティ名として引用なしで書ける名前 */
    private static final String ENTITY_WORD = /(?:[^\x00-\x7F]|[\w\-*])+/
    /** Mermaid の属性名・型として書ける単語 */
    private static final String ATTRIBUTE_WORD = /[*A-Za-z_\u00C0-\uFFFF][A-Za-z0-9\-_\[\]().,\u00C0-\uFFFF*]*/

    static String write(ErDiagram d) {
        StringBuilder sb = new StringBuilder()
        if (d.title) sb << '---\n' << 'title: ' << d.title << '\n---\n'
        sb << 'erDiagram\n'
        if (d.direction) sb << '    direction ' << d.direction << '\n'
        d.entities.values().each { ErEntity e ->
            sb << '    ' << entityName(e.name)
            if (e.alias && e.alias != e.name) sb << '["' << clean(e.alias) << '"]'
            if (e.attributes) {
                sb << ' {\n'
                e.attributes.each { ErAttribute a ->
                    sb << '        ' << attrWord(a.type ?: 'unknown') << ' ' << attrName(a.name)
                    if (a.keys) sb << ' ' << a.keys.join(', ')
                    String comment = commentText(a)
                    if (comment) sb << ' "' << comment << '"'
                    sb << '\n'
                }
                sb << '    }'
            }
            sb << '\n'
        }
        if (d.relationships) sb << '\n'
        d.relationships.each { ErRelationship r ->
            sb << '    ' << entityName(r.left) << ' ' << r.leftCard.left << (r.identifying ? '--' : '..') << r.rightCard.right <<
                    ' ' << entityName(r.right) << ' : "' << clean(r.label ?: '') << '"\n'
        }
        sb.toString()
    }

    /** コメント本文 + [NOT NULL] [DEFAULT ...] [IDENTITY] タグ */
    static String commentText(ErAttribute a) {
        List<String> parts = []
        if (a.comment) parts << clean(a.comment)
        if (a.notNull) parts << '[NOT NULL]'
        if (a.defaultExpr) parts << "[DEFAULT ${clean(a.defaultExpr)}]".toString()
        if (a.identity) parts << (a.identityKind ? "[IDENTITY ${a.identityKind}]".toString() : '[IDENTITY]')
        parts.join(' ')
    }

    static String entityName(String n) {
        n ==~ ENTITY_WORD ? n : '"' + n.replace('"', "'") + '"'
    }

    private static String attrName(String n) {
        n ==~ ATTRIBUTE_WORD ? n : '`' + n.replace('`', "'") + '`'
    }

    private static String attrWord(String t) {
        t.replaceAll(/\s+/, '_')
    }

    /** Mermaid の文字列に書けない " と改行を置き換える */
    private static String clean(String s) {
        s.replace('"', "'").replaceAll(/[\r\n]+/, ' ').trim()
    }
}
