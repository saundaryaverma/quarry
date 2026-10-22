package quarry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import quarry.plan.Expr;
import quarry.plan.Expr.*;
import quarry.plan.Plan;
import quarry.plan.Scalar;
import quarry.storage.Column;
import quarry.storage.Field;
import quarry.storage.TableReader;

/**
 * The simplest possible way to run a plan: load every row, then evaluate one row and one boxed
 * value at a time with plan.Scalar. No optimizer, no vectors, no threads, no zone maps. Slow and
 * obviously correct, which is the point: the differential test checks the real engine against it.
 */
final class Reference {
    private Reference() {}

    static List<Object[]> run(Plan p, TableReader table) throws IOException {
        return switch (p) {
            case Plan.Scan s -> {
                List<Object[]> rows = new ArrayList<>();
                List<Field> schema = table.schema();
                for (int g = 0; g < table.rowGroupCount(); g++) {
                    List<Column> cols = new ArrayList<>();
                    for (int c = 0; c < schema.size(); c++) cols.add(table.readColumn(g, c));
                    for (int i = 0; i < table.rowCount(g); i++) {
                        Object[] row = new Object[schema.size()];
                        for (int c = 0; c < schema.size(); c++) row[c] = switch (cols.get(c)) {
                            case Column.Longs l -> l.values()[i];
                            case Column.Doubles d -> d.values()[i];
                            case Column.Strings str -> str.values()[i];
                        };
                        rows.add(row);
                    }
                }
                yield rows;
            }
            case Plan.Filter f -> {
                Map<String, Integer> idx = index(table.schema());
                List<Object[]> out = new ArrayList<>();
                for (Object[] row : run(f.input(), table)) if ((Boolean) eval(f.predicate(), row, idx)) out.add(row);
                yield out;
            }
            case Plan.Aggregate a -> aggregate(a, run(a.input(), table), index(table.schema()));
            case Plan.Project pr -> {
                Map<String, Integer> idx = pr.input() instanceof Plan.Aggregate a ? aggIndex(a) : index(table.schema());
                List<Object[]> out = new ArrayList<>();
                for (Object[] row : run(pr.input(), table)) {
                    Object[] o = new Object[pr.exprs().size()];
                    for (int i = 0; i < o.length; i++) o[i] = eval(pr.exprs().get(i), row, idx);
                    out.add(o);
                }
                yield out;
            }
            case Plan.Sort s -> {
                List<Object[]> rows = new ArrayList<>(run(s.input(), table));
                Comparator<Object[]> cmp = (x, y) -> 0;
                for (Plan.SortKey k : s.keys()) {
                    Comparator<Object[]> c = (x, y) -> Scalar.compare(x[k.column()], y[k.column()]);
                    cmp = cmp.thenComparing(k.descending() ? c.reversed() : c);
                }
                rows.sort(cmp);
                yield rows;
            }
            case Plan.Limit l -> {
                List<Object[]> rows = run(l.input(), table);
                yield new ArrayList<>(rows.subList(0, (int) Math.min(rows.size(), l.count())));
            }
        };
    }

    private static List<Object[]> aggregate(Plan.Aggregate a, List<Object[]> rows, Map<String, Integer> idx) {
        Map<List<Object>, List<Object[]>> groups = new LinkedHashMap<>();
        for (Object[] row : rows) {
            List<Object> key = new ArrayList<>();
            for (Col c : a.groupBy()) key.add(row[idx.get(c.name())]);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        if (a.groupBy().isEmpty() && groups.isEmpty()
                && a.aggs().stream().allMatch(g -> g.fn() == AggFn.COUNT || g.fn() == AggFn.SUM)) {
            groups.put(List.of(), List.of());
        }
        List<Object[]> out = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Object[]>> e : groups.entrySet()) {
            Object[] o = new Object[a.groupBy().size() + a.aggs().size()];
            for (int i = 0; i < a.groupBy().size(); i++) o[i] = e.getKey().get(i);
            for (int j = 0; j < a.aggs().size(); j++) {
                Agg g = a.aggs().get(j);
                List<Object> vals = new ArrayList<>();
                for (Object[] row : e.getValue()) vals.add(g.arg() == null ? null : eval(g.arg(), row, idx));
                o[a.groupBy().size() + j] = switch (g.fn()) {
                    case COUNT -> (long) vals.size();
                    case SUM -> {
                        if (g.type() == quarry.DataType.LONG) { long s = 0; for (Object v : vals) s += (Long) v; yield s; }
                        double s = 0; for (Object v : vals) s += ((Number) v).doubleValue(); yield s;
                    }
                    case AVG -> { double s = 0; for (Object v : vals) s += ((Number) v).doubleValue(); yield s / vals.size(); }
                    case MIN -> vals.stream().min(Scalar::compare).orElseThrow();
                    case MAX -> vals.stream().max(Scalar::compare).orElseThrow();
                };
            }
            out.add(o);
        }
        return out;
    }

    private static Map<String, Integer> index(List<Field> schema) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < schema.size(); i++) m.put(schema.get(i).name(), i);
        return m;
    }

    private static Map<String, Integer> aggIndex(Plan.Aggregate a) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < a.groupBy().size(); i++) m.put(a.groupBy().get(i).name(), i);
        for (int j = 0; j < a.aggs().size(); j++) m.put("$agg" + j, a.groupBy().size() + j);
        return m;
    }

    static Object eval(Expr e, Object[] row, Map<String, Integer> idx) {
        return switch (e) {
            case Col c -> row[idx.get(c.name())];
            case Lit l -> l.value();
            case Bin b -> Scalar.binary(b.op(), b.type(), eval(b.left(), row, idx), eval(b.right(), row, idx));
            case Not n -> !(Boolean) eval(n.operand(), row, idx);
            case Neg n -> {
                Object v = eval(n.operand(), row, idx);
                yield v instanceof Long l ? (Object) (-l) : (Object) (-(Double) v);
            }
            case Agg a -> throw new IllegalStateException("unplanned aggregate");
        };
    }
}
