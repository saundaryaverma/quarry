package quarry.plan;

import java.util.List;

import quarry.storage.Field;

/**
 * Logical query plan. The binder always produces the same shape, from the bottom up:
 * Scan, then optional Filter, optional Aggregate, Project, optional Sort, optional Limit.
 * Optimizer rules rewrite it, for example by moving the Filter into the Scan.
 */
public sealed interface Plan permits Plan.Scan, Plan.Filter, Plan.Aggregate, Plan.Project, Plan.Sort, Plan.Limit {

    /**
     * Reads a table. columns: the columns to read. filter: a predicate pushed into the scan (or null).
     * zoneMaps: whether to skip row groups using their min/max statistics.
     */
    record Scan(String table, List<Field> schema, List<String> columns, Expr filter, boolean zoneMaps) implements Plan {}

    record Filter(Plan input, Expr predicate) implements Plan {}

    /** Outputs one row per group: the group columns (same names), then aggregates named $agg0, $agg1, ... */
    record Aggregate(Plan input, List<Expr.Col> groupBy, List<Expr.Agg> aggs) implements Plan {}

    record Project(Plan input, List<Expr> exprs, List<String> names) implements Plan {}

    record SortKey(int column, boolean descending) {}

    /** Sorts by output columns of the Project below it. */
    record Sort(Plan input, List<SortKey> keys) implements Plan {}

    record Limit(Plan input, long count) implements Plan {}

    static String explain(Plan p) {
        StringBuilder sb = new StringBuilder();
        explain(p, 0, sb);
        return sb.toString();
    }

    private static void explain(Plan p, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth));
        switch (p) {
            case Scan s -> {
                sb.append("Scan ").append(s.table()).append(" columns=").append(s.columns());
                if (s.filter() != null) sb.append(" filter=").append(s.filter().sql());
                if (s.filter() != null && s.zoneMaps()) sb.append(" zone_maps=on");
                sb.append('\n');
            }
            case Filter f -> { sb.append("Filter ").append(f.predicate().sql()).append('\n'); explain(f.input(), depth + 1, sb); }
            case Aggregate a -> {
                sb.append("Aggregate group_by=").append(a.groupBy().stream().map(Expr::sql).toList())
                        .append(" aggs=").append(a.aggs().stream().map(Expr::sql).toList()).append('\n');
                explain(a.input(), depth + 1, sb);
            }
            case Project pr -> {
                sb.append("Project ");
                for (int i = 0; i < pr.exprs().size(); i++) {
                    if (i > 0) sb.append(", ");
                    String e = pr.exprs().get(i).sql();
                    sb.append(e.equals(pr.names().get(i)) ? e : e + " AS " + pr.names().get(i));
                }
                sb.append('\n');
                explain(pr.input(), depth + 1, sb);
            }
            case Sort s -> {
                sb.append("Sort ").append(s.keys().stream().map(k -> "#" + (k.column() + 1) + (k.descending() ? " DESC" : "")).toList()).append('\n');
                explain(s.input(), depth + 1, sb);
            }
            case Limit l -> { sb.append("Limit ").append(l.count()).append('\n'); explain(l.input(), depth + 1, sb); }
        }
    }
}
