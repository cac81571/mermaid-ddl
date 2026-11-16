package jp.mermaidddl.er

import groovy.transform.CompileStatic
import jp.mermaidddl.schema.ColumnDef
import jp.mermaidddl.schema.ConstraintDef
import jp.mermaidddl.schema.DdlParser
import jp.mermaidddl.schema.Dialect
import jp.mermaidddl.schema.Generation
import jp.mermaidddl.schema.Identifiers
import jp.mermaidddl.schema.LogicalType
import jp.mermaidddl.schema.SchemaModel
import jp.mermaidddl.schema.TableDef
import jp.mermaidddl.schema.TypeMapper

/** ER 図 → テーブル定義の変換オプション */
@CompileStatic
class ErToSchemaOptions {
    Dialect dialect = Dialect.H2
    /** 英数字の名前を大文字に正規化する（- は _ に置換）。false なら書いたとおり（必要なら引用符付き） */
    boolean upperCaseNames = true
    /** 単一の整数主キー（FK でないもの）を IDENTITY 列にする */
    boolean identityForIntegerPk = false
}

/** テーブル定義 → ER 図の変換オプション */
@CompileStatic
class SchemaToErOptions {
    /** true = string / int などの汎用型、false = DB の型（varchar2(50_char) など） */
    boolean genericTypes = false
    /** コメント末尾に [NOT NULL] [DEFAULT ...] [IDENTITY] タグを出す */
    boolean constraintTags = true
    /** FK が主キーの一部なら --（識別）、それ以外は ..（非識別）。false なら常に -- */
    boolean distinguishIdentifying = false
    /** エンティティの表示名を「論理名 (物理名)」にする（例: CUSTOMER["顧客 (CUSTOMER)"]）。コメントが無い表は表示名なし */
    boolean physicalNameInAlias = true
}

/**
 * ER 図（{@link ErDiagram}）とテーブル定義（{@link SchemaModel}）の相互変換。
 *
 * <p>ER 図 → テーブル定義での外部キーの推定:</p>
 * <ol>
 *   <li>リレーションの多重度で親（1 側）と子（多側）を決める。1 対 1 は FK 属性を持つ側、なければ o| 側を子とする</li>
 *   <li>親の主キー列ごとに、子の属性から「同名の FK 属性」→「親名_主キー名」→「_主キー名 で終わる FK 属性」の順に探す</li>
 *   <li>見つからないとき、子に未使用の FK 属性が 1 つだけならそれを使う（警告付き）</li>
 *   <li>親側が || / }| なら FK 列は NOT NULL、|o なら NULL 可。1 対 1 なら UNIQUE 制約を付ける</li>
 *   <li>どのリレーションにも使われない FK 属性は、名前が一致する主キーを持つテーブルへの外部キーとする（警告付き）</li>
 * </ol>
 */
@CompileStatic
final class ErConverter {

    private ErConverter() {}

    // ================================================================ ER 図 → テーブル定義

    static SchemaModel toSchema(ErDiagram d, ErToSchemaOptions o) {
        SchemaModel m = new SchemaModel()
        Map<String, TableDef> byEntity = new LinkedHashMap<>()
        Map<TableDef, ErEntity> entityOf = new IdentityHashMap<>()

        d.entities.values().each { ErEntity e ->
            if (!e.attributes) {
                m.warnings << at(e.line, "エンティティ ${e.name} に属性がないため、テーブルを作成しません（{ 型 名前 PK } を追加してください）")
                return
            }
            Map nm = tableName(e.name, o, m, e.line)
            TableDef td = new TableDef(name: nm.name as String, quoted: (boolean) nm.quoted,
                    comment: logicalName(e.alias, e.name, nm.name as String))
            if (m.tables.any { it.name == td.name }) {
                m.warnings << at(e.line, "エンティティ ${e.name} のテーブル名 ${td.name} が他と重複しています")
            }
            int pos = 0
            e.attributes.each { ErAttribute a ->
                Map cn = tableName(a.name, o, m, a.line)
                ColumnDef c = new ColumnDef(name: cn.name as String, quoted: (boolean) cn.quoted, position: ++pos, comment: a.comment)
                if (td.column(c.name) != null) {
                    m.warnings << at(a.line, "${td.name}.${c.name} が重複しています（大文字化後）")
                    return
                }
                ErTypes.SqlType st = ErTypes.toSql(a.type, o.dialect)
                if (st.warning) m.warnings << at(a.line, "${e.name}.${a.name}: ${st.warning}")
                applySqlType(c, st.sql, o.dialect)
                c.nullable = !(a.pk || a.notNull)
                if (a.defaultExpr) {
                    c.defaultExpr = a.defaultExpr
                    DdlParser.applyDefault(c, false)
                    if (c.sequenceName) m.sequences << c.sequenceName
                }
                if (a.identity) {
                    if (c.logicalType != LogicalType.INTEGER && c.logicalType != LogicalType.DECIMAL) {
                        m.warnings << at(a.line, "${e.name}.${a.name}: 数値型でない列に [IDENTITY] は付けられません（無視）")
                    } else {
                        c.generation = a.identityKind == 'ALWAYS' ? Generation.IDENTITY_ALWAYS
                                : (a.identityKind == 'ON NULL' ? Generation.IDENTITY_ON_NULL : Generation.IDENTITY_BY_DEFAULT)
                        c.nullable = false
                    }
                }
                td.columns << c
            }
            List<String> pk = e.attributes.findAll { it.pk }.collect { colName(td, it, o) }
            if (pk) td.constraints << new ConstraintDef(type: 'P', columns: pk)
            e.attributes.findAll { it.uk }.each { ErAttribute a ->
                String cn = colName(td, a, o)
                if (!(pk == [cn])) td.constraints << new ConstraintDef(type: 'U', columns: [cn])
            }
            m.tables << td
            byEntity.put(e.name, td)
            entityOf.put(td, e)
        }

        // ---- リレーション → 外部キー
        Map<TableDef, Set<String>> usedFk = new IdentityHashMap<>()
        List<Map> pending = []
        d.relationships.each { ErRelationship r ->
            TableDef lt = byEntity.get(r.left)
            TableDef rt = byEntity.get(r.right)
            String desc = "${r.left} ${r.leftCard.left}${r.identifying ? '--' : '..'}${r.rightCard.right} ${r.right}"
            if (lt == null || rt == null) {
                m.warnings << at(r.line, "リレーション ${desc}: 属性のないエンティティを含むため外部キーを作れません")
                return
            }
            if (r.leftCard.many && r.rightCard.many) {
                m.warnings << at(r.line, "リレーション ${desc} は多対多のため外部キーを作れません（中間テーブルを定義してください）")
                return
            }
            TableDef parent
            TableDef child
            Cardinality parentCard
            boolean oneToOne = false
            if (!r.leftCard.many && r.rightCard.many) {
                parent = lt; child = rt; parentCard = r.leftCard
            } else if (r.leftCard.many && !r.rightCard.many) {
                parent = rt; child = lt; parentCard = r.rightCard
            } else {
                oneToOne = true
                boolean rightRefs = hasMatchingFk(entityOf.get(rt), lt, o)
                boolean leftRefs = hasMatchingFk(entityOf.get(lt), rt, o)
                if (leftRefs && !rightRefs) { parent = rt; child = lt; parentCard = r.rightCard }
                else if (rightRefs && !leftRefs) { parent = lt; child = rt; parentCard = r.leftCard }
                else if (r.leftCard.optional && !r.rightCard.optional) { parent = rt; child = lt; parentCard = r.rightCard }
                else { parent = lt; child = rt; parentCard = r.leftCard }
            }
            if (!parent.primaryKey) {
                m.warnings << at(r.line, "リレーション ${desc}: 参照先 ${parent.name} に主キー（PK）がないため外部キーを作れません")
                return
            }
            Map job = [rel: r, parent: parent, child: child, parentCard: parentCard, oneToOne: oneToOne, desc: desc] as Map
            List<String> cols = matchFkColumns(entityOf.get(child), child, parent, o, usedFk, false)
            if (cols != null) {
                addFk(m, job, cols, usedFk)
            } else {
                pending << job
            }
        }
        // 名前で決まらなかったリレーション: 未使用の FK 属性が 1 つだけなら採用
        pending.each { Map job ->
            TableDef parent = (TableDef) job.parent
            TableDef child = (TableDef) job.child
            ErRelationship r = (ErRelationship) job.rel
            List<String> cols = matchFkColumns(entityOf.get(child), child, parent, o, usedFk, true)
            if (cols != null) {
                m.warnings << at(r.line, "リレーション ${job.desc}: 列名が一致しないため ${child.name}.${cols.join(', ')} を ${parent.name} への外部キーとしました")
                addFk(m, job, cols, usedFk)
            } else {
                m.warnings << at(r.line, "リレーション ${job.desc}: ${child.name} に ${parent.name}（${parent.primaryKey.join(', ')}）を参照する列が見つかりません。" +
                        "${child.name} に「型 ${parent.primaryKey[0]} FK」属性を追加してください")
            }
        }
        // リレーションに使われていない FK 属性
        m.tables.each { TableDef td ->
            ErEntity e = entityOf.get(td)
            Set<String> used = usedFk.get(td) ?: ([] as Set<String>)
            e.attributes.findAll { it.fk }.each { ErAttribute a ->
                String cn = colName(td, a, o)
                if (used.contains(cn)) return
                List<TableDef> targets = m.tables.findAll { TableDef t ->
                    t.primaryKey.size() == 1 && (t != td || !td.primaryKey.contains(cn)) &&
                            (t.primaryKey[0].equalsIgnoreCase(cn) || cn.equalsIgnoreCase("${t.name}_${t.primaryKey[0]}".toString())) &&
                            !(t == td && t.primaryKey[0].equalsIgnoreCase(cn))
                }
                if (targets.size() == 1) {
                    TableDef t = targets[0]
                    td.constraints << new ConstraintDef(type: 'R', columns: [cn], refTable: t.name, refColumns: [t.primaryKey[0]])
                    m.warnings << at(a.line, "${td.name}.${cn} は FK ですがリレーションがないため、主キー名が一致する ${t.name} を参照先にしました")
                    checkTypes(m, td, [cn], t, a.line)
                } else {
                    m.warnings << at(a.line, "${td.name}.${cn} は FK ですが参照先が分かりません（リレーションを追加してください）")
                }
            }
        }
        // 単一整数主キーを IDENTITY に
        if (o.identityForIntegerPk) {
            m.tables.each { TableDef td ->
                if (td.primaryKey.size() != 1) return
                ColumnDef c = td.column(td.primaryKey[0])
                boolean isFk = td.constraints.any { it.type == 'R' && it.columns.contains(c.name) }
                if (c.logicalType == LogicalType.INTEGER && c.generation == Generation.NONE && !isFk && !c.defaultExpr) {
                    c.generation = Generation.IDENTITY_BY_DEFAULT
                }
            }
        }
        m.tables.each { TableDef td -> DdlParser.finishTable(td, m.sequences, o.dialect) }
        m.warnings.addAll(0, d.issues.findAll { !it.error }.collect { ErIssue i -> i.toString() })
        m
    }

    private static String at(int line, String msg) { line > 0 ? "${line} 行目: ${msg}".toString() : msg }

    /** ER 図の名前を SQL の識別子にする */
    static Map tableName(String raw, ErToSchemaOptions o, SchemaModel m, int line) {
        String n = raw.trim()
        if (o.upperCaseNames && n ==~ /[A-Za-z][A-Za-z0-9_$#\-]*/) {
            String up = n.replace('-', '_').toUpperCase(Locale.ROOT)
            if (Identifiers.RESERVED.contains(up)) {
                m.warnings << at(line, "${raw} は SQL の予約語のため \"${up}\" と引用符付きになります（別名を推奨）")
            }
            return [name: up, quoted: false]
        }
        [name: n, quoted: !(n ==~ /[A-Z][A-Z0-9_$#]*/)]
    }

    private static String colName(TableDef td, ErAttribute a, ErToSchemaOptions o) {
        tableName(a.name, o, new SchemaModel(), 0).name as String
    }

    static void applySqlType(ColumnDef c, String sql, Dialect dialect) {
        c.rawType = sql
        ErTypes.Parsed p = ErTypes.parse(sql)
        c.typeName = TypeMapper.normalizeTypeName(sql)
        if (c.typeName?.endsWith(' ARRAY')) c.typeName = 'ARRAY'
        String base = p.name
        if (base in ['VARCHAR', 'VARCHAR2', 'NVARCHAR2', 'CHAR', 'NCHAR', 'VARCHAR_IGNORECASE', 'RAW', 'VARBINARY', 'BINARY']) {
            c.length = p.args ? (p.args[0] ==~ /\d+/ ? Integer.valueOf(p.args[0]) : null) : null
            c.charSemantics = p.semantics == 'CHAR' || base.startsWith('N') || dialect == Dialect.H2
        } else if (p.args) {
            c.precision = p.args[0] ==~ /\d+/ ? Integer.valueOf(p.args[0]) : null
            if (p.args.size() > 1 && p.args[1] ==~ /-?\d+/) c.scale = Integer.valueOf(p.args[1])
        }
        c.logicalType = TypeMapper.logicalType(c, dialect)
    }

    private static boolean hasMatchingFk(ErEntity child, TableDef parent, ErToSchemaOptions o) {
        if (child == null || !parent.primaryKey) return false
        parent.primaryKey.every { String pk ->
            child.attributes.any { ErAttribute a ->
                String n = tableName(a.name, o, new SchemaModel(), 0).name as String
                a.fk && (n.equalsIgnoreCase(pk) || n.equalsIgnoreCase("${parent.name}_${pk}".toString()) || n.toUpperCase(Locale.ROOT).endsWith('_' + pk.toUpperCase(Locale.ROOT)))
            }
        }
    }

    /** 子テーブルから親の主キーに対応する列を探す。見つからなければ null */
    private static List<String> matchFkColumns(ErEntity ce, TableDef child, TableDef parent, ErToSchemaOptions o,
                                               Map<TableDef, Set<String>> usedFk, boolean loose) {
        Set<String> used = usedFk.get(child) ?: ([] as Set<String>)
        List<String> pkCols = parent.primaryKey
        Map<String, ErAttribute> attrs = new LinkedHashMap<>()
        ce.attributes.each { ErAttribute a -> attrs.put(tableName(a.name, o, new SchemaModel(), 0).name as String, a) }
        boolean self = child.is(parent)
        if (loose) {
            if (pkCols.size() != 1) return null
            List<String> free = attrs.findAll { String n, ErAttribute a -> a.fk && !used.contains(n) && !(self && pkCols.contains(n)) }.keySet().toList()
            return free.size() == 1 ? free : null
        }
        List<String> out = []
        for (String pk : pkCols) {
            String hit = null
            String up = pk.toUpperCase(Locale.ROOT)
            // 1) 同名の FK 属性（自己参照では自分の主キーは除く）
            hit = attrs.find { String n, ErAttribute a -> a.fk && n.equalsIgnoreCase(pk) && !used.contains(n) && !self && !out.contains(n) }?.key
            // 2) 親名_主キー名（FK 指定なしでも可）
            if (hit == null) {
                String want = "${parent.name}_${pk}".toString()
                hit = attrs.find { String n, ErAttribute a -> n.equalsIgnoreCase(want) && !used.contains(n) && !out.contains(n) }?.key
            }
            // 3) _主キー名 で終わる FK 属性（BILLING_CUSTOMER_ID → CUSTOMER_ID）
            if (hit == null) {
                hit = attrs.find { String n, ErAttribute a ->
                    a.fk && n.toUpperCase(Locale.ROOT).endsWith('_' + up) && !used.contains(n) && !out.contains(n) && !(self && pkCols.contains(n))
                }?.key
            }
            if (hit == null) return null
            out << hit
        }
        out
    }

    private static void addFk(SchemaModel m, Map job, List<String> cols, Map<TableDef, Set<String>> usedFk) {
        TableDef parent = (TableDef) job.parent
        TableDef child = (TableDef) job.child
        ErRelationship r = (ErRelationship) job.rel
        Cardinality parentCard = (Cardinality) job.parentCard
        if (child.constraints.any { it.type == 'R' && it.columns == cols && it.refTable == parent.name }) {
            m.warnings << at(r.line, "リレーション ${job.desc} は重複しています")
            return
        }
        child.constraints << new ConstraintDef(type: 'R', columns: new ArrayList<String>(cols), refTable: parent.name,
                refColumns: new ArrayList<String>(parent.primaryKey))
        Set<String> used = usedFk.get(child)
        if (used == null) { used = new LinkedHashSet<String>(); usedFk.put(child, used) }
        used.addAll(cols)
        boolean required = !parentCard.optional
        cols.each { String cn ->
            ColumnDef c = child.column(cn)
            if (required) {
                c.nullable = false
            } else if (!c.nullable && !child.primaryKey.contains(cn)) {
                m.warnings << at(r.line, "${child.name}.${cn} は NOT NULL ですが、リレーションは 0 件を許す（|o）記号です")
            }
        }
        if (job.oneToOne) {
            boolean unique = child.primaryKey == cols || child.constraints.any { it.type == 'U' && it.columns == cols }
            if (!unique) child.constraints << new ConstraintDef(type: 'U', columns: new ArrayList<String>(cols))
        }
        checkTypes(m, child, cols, parent, r.line)
    }

    private static void checkTypes(SchemaModel m, TableDef child, List<String> cols, TableDef parent, int line) {
        cols.eachWithIndex { String cn, int i ->
            ColumnDef c = child.column(cn)
            ColumnDef pc = parent.column(parent.primaryKey[i])
            if (c && pc && c.rawType != pc.rawType) {
                m.warnings << at(line, "${child.name}.${cn}（${c.rawType}）と参照先 ${parent.name}.${pc.name}（${pc.rawType}）の型が異なります")
            }
        }
    }

    // ================================================================ テーブル定義 → ER 図

    static ErDiagram toDiagram(SchemaModel m, SchemaToErOptions o) {
        ErDiagram d = new ErDiagram()
        Set<String> names = m.tables.collect { it.name } as Set<String>
        // 先にリレーションを決める（FK 列の [NOT NULL] タグを省くため）
        Map<TableDef, Set<String>> relCols = new IdentityHashMap<>()
        List<ErRelationship> rels = []
        m.tables.each { TableDef td ->
            td.constraints.findAll { it.type == 'R' }.each { ConstraintDef fk ->
                if (!names.contains(fk.refTable)) {
                    d.warn(0, "${td.name} の外部キー（${fk.columns.join(', ')}）の参照先 ${fk.refTable} は対象外のためリレーションを省略しました")
                    return
                }
                TableDef parent = m.tables.find { it.name == fk.refTable }
                boolean notNull = fk.columns.every { String c -> td.column(c) != null && !td.column(c).nullable }
                boolean unique = (td.primaryKey as Set) == (fk.columns as Set) ||
                        td.constraints.any { it.type == 'U' && (it.columns as Set) == (fk.columns as Set) }
                boolean identifying = !o.distinguishIdentifying || td.primaryKey.containsAll(fk.columns)
                rels << new ErRelationship(left: parent.name, right: td.name,
                        leftCard: notNull ? Cardinality.EXACTLY_ONE : Cardinality.ZERO_OR_ONE,
                        rightCard: unique ? Cardinality.ZERO_OR_ONE : Cardinality.ZERO_OR_MORE,
                        identifying: identifying, label: fk.columns.join(', '))
                Set<String> s = relCols.get(td)
                if (s == null) { s = new LinkedHashSet<String>(); relCols.put(td, s) }
                s.addAll(fk.columns)
            }
        }
        m.tables.each { TableDef td ->
            ErEntity e = new ErEntity(name: td.name, alias: o.physicalNameInAlias
                    ? displayAlias(firstLine(td.comment), td.name) : firstLine(td.comment))
            Set<String> fkCols = td.constraints.findAll { it.type == 'R' }.collectMany { it.columns } as Set<String>
            td.constraints.findAll { it.type == 'U' && it.columns.size() > 1 }.each { ConstraintDef u ->
                d.warn(0, "${td.name} の複合一意制約（${u.columns.join(', ')}）は ER 図では列ごとの UK になります")
            }
            td.constraints.findAll { it.type == 'C' }.each { ConstraintDef ck ->
                d.warn(0, "${td.name} の CHECK 制約${ck.name ? ' ' + ck.name : ''}は ER 図に出力されません")
            }
            // 1 対 1 の FK に付いた一意制約はリレーション記号（o|）で表すので UK にしない
            List<ConstraintDef> fks = td.constraints.findAll { it.type == 'R' && names.contains(it.refTable) }
            Set<String> ukCols = td.constraints.findAll { ConstraintDef u ->
                u.type == 'U' && !fks.any { (it.columns as Set) == (u.columns as Set) }
            }.collectMany { it.columns } as Set<String>
            td.columns.each { ColumnDef c ->
                ErAttribute a = new ErAttribute(name: c.name, comment: firstLine(c.comment))
                a.type = o.genericTypes ? ErTypes.toGeneric(c) : ErTypes.toMermaid(c.rawType ?: c.typeName)
                if (td.primaryKey.contains(c.name)) a.keys << 'PK'
                if (fkCols.contains(c.name)) a.keys << 'FK'
                if (ukCols.contains(c.name) && !(td.primaryKey == [c.name])) a.keys << 'UK'
                if (o.constraintTags) {
                    boolean coveredByRel = relCols.get(td)?.contains(c.name)
                    if (!c.nullable && !a.pk && !coveredByRel && !c.generation.identity) a.notNull = true
                    if (c.generation.identity) {
                        a.identity = true
                        if (c.generation == Generation.IDENTITY_ALWAYS) a.identityKind = 'ALWAYS'
                        if (c.generation == Generation.IDENTITY_ON_NULL) a.identityKind = 'ON NULL'
                    }
                    if (c.defaultExpr && !c.generation.identity && c.generation != Generation.VIRTUAL) {
                        String expr = c.defaultExpr.replaceAll(/"([A-Z][A-Z0-9_$#]*)"/, '$1').trim()
                        if (expr.contains('"')) d.warn(0, "${td.name}.${c.name} の DEFAULT 式は \" を含むため ER 図に出力できません")
                        else a.defaultExpr = expr
                    }
                }
                if (c.generation == Generation.VIRTUAL) d.warn(0, "${td.name}.${c.name} は仮想列ですが、ER 図では通常の列になります")
                e.attributes << a
            }
            d.entities.put(e.name, e)
        }
        d.relationships.addAll(rels)
        m.warnings.each { String w -> d.warn(0, w) }
        d
    }

    /** 「論理名 (物理名)」の形（全角括弧も可） */
    private static final java.util.regex.Pattern LOGICAL_PHYSICAL = ~/^(.*?)\s*[(（]\s*([^()（）]+?)\s*[)）]\s*$/

    /**
     * 表示名から論理名（テーブルコメント）を取り出す。表示名が「論理名 (物理名)」で、括弧内が
     * エンティティ名・テーブル名のどれかと一致する場合は論理名の部分だけを返す（一致しなければ表示名そのまま）。
     * 例: logicalName("顧客 (CUSTOMER)", "CUSTOMER") → "顧客"、logicalName("顧客 (法人)", "CUSTOMER") → "顧客 (法人)"
     */
    static String logicalName(String alias, String... physicalNames) {
        if (alias == null) return null
        def m = LOGICAL_PHYSICAL.matcher(alias.trim())
        if (!m.matches()) return alias
        String inner = m.group(2)
        boolean same = physicalNames.any { String p -> p != null && samePhysical(inner, p) }
        if (!same) return alias
        String logical = m.group(1).trim()
        logical ?: null
    }

    private static boolean samePhysical(String a, String b) {
        String x = a.trim().replaceAll(/^"|"$/, '')
        String y = b.trim().replaceAll(/^"|"$/, '')
        x.equalsIgnoreCase(y) || x.replace('-', '_').equalsIgnoreCase(y.replace('-', '_'))
    }

    /** 論理名と物理名から表示名「論理名 (物理名)」を作る。論理名が無ければ null、既に併記済みなら二重に付けない */
    static String displayAlias(String logical, String physical) {
        String l = logicalName(logical, physical)
        if (!l) return null
        samePhysical(l, physical) ? l : "${l} (${physical})".toString()
    }

    private static String firstLine(String s) {
        if (!s) return null
        String t = s.readLines().find { it.trim() }?.trim()
        t ?: null
    }
}
