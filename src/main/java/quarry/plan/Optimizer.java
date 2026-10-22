package quarry.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import quarry.DataType;
import quarry.plan.Expr.*;
import quarry.storage.Field;

/**
 * Rewrites a logical plan into a cheaper one that returns the same rows. Each rule can be
 * switched off, which is how the benchmark measures what each one is worth and how the
 * tests check that none of them change results.
 */
public final class Optimizer {

    /** Which rules to apply. */
    public record Rules(boolean constantFolding, boolean predicatePushdown, boolean projectionPruning, boolean zoneMaps) {
        public static final Rules ALL = new Rules(true, true, true, true);
        public static final Rules NONE = new Rules(false, false, false, false);
    }

    private Optimizer() {}

    public static Plan optimize(Plan plan, Rules rules) {
        if (rules.constantFolding()) plan = foldPlan(plan);
        if (rules.predicatePushdown()) plan = pushDown(plan, rules.zoneMaps());
        if (rules.projectionPruning()) plan = prune(plan, new LinkedHashSet<>(), true);
        return plan;
    }

    // ---- Rule 1: constant folding -------------------------------------------------------------

    private static Plan foldPlan(Plan p) {
        return switch (p) {
            case Plan.Scan s -> s.filter() == null ? s : new Plan.Scan(s.table(), s.schema(), s.columns(), fold(s.filter()), s.zoneMaps());
            case Plan.Filter f -> {
                Expr pred = fold(f.predicate());
                Plan in = foldPlan(f.input());
                yield pred instanceof Lit l && Boolean.TRUE.equals(l.value()) ? in : new Plan.Filter(in, pred);
            }
            case Plan.Aggregate a -> {
                List<Agg> aggs = new ArrayList<>();
                for (Agg g : a.aggs()) aggs.add(g.arg() == null ? g : new Agg(g.fn(), fold(g.arg()), g.type()));
                yield new Plan.Aggregate(foldPlan(a.input()), a.groupBy(), aggs);
            }
            case Plan.Project pr -> new Plan.Project(foldPlan(pr.input()), pr.exprs().stream().map(Optimizer::fold).toList(), pr.names());
            case Plan.Sort s -> new Plan.Sort(foldPlan(s.input()), s.keys());
            case Plan.Limit l -> new Plan.Limit(foldPlan(l.input()), l.count());
        };
    }

    /** Evaluates constant sub-expressions once, at plan time: amount > 10 * 100 becomes amount > 1000. */
    static Expr fold(Expr e) {
        return switch (e) {
            case Bin b -> {
                Expr l = fold(b.left()), r = fold(b.right());
                if (l instanceof Lit a && r instanceof Lit c) yield new Lit(Scalar.binary(b.op(), b.type(), a.value(), c.value()), b.type());
                if (b.op() == Op.AND) {
                    if (isBool(l, true)) yield r;
                    if (isBool(r, true)) yield l;
                    if (isBool(l, false) || isBool(r, false)) yield new Lit(false, DataType.BOOLEAN);
                }
                if (b.op() == Op.OR) {
                    if (isBool(l, false)) yield r;
                    if (isBool(r, false)) yield l;
                    if (isBool(l, true) || isBool(r, true)) yield new Lit(true, DataType.BOOLEAN);
                }
                yield new Bin(b.op(), l, r, b.type());
            }
            case Not n -> {
                Expr x = fold(n.operand());
                yield x instanceof Lit l ? new Lit(!(Boolean) l.value(), DataType.BOOLEAN) : new Not(x);
            }
            case Neg n -> {
                Expr x = fold(n.operand());
                if (x instanceof Lit l) yield new Lit(l.value() instanceof Long v ? (Object) (-v) : (Object) (-(Double) l.value()), x.type());
                yield new Neg(x);
            }
            default -> e;
        };
    }

    private static boolean isBool(Expr e, boolean v) {
        return e instanceof Lit l && Boolean.valueOf(v).equals(l.value());
    }

    // ---- Rule 2: predicate pushdown -----------------------------------------------------------

    /**
     * Moves a Filter into the Scan below it. The scanner can then evaluate the predicate first,
     * read the other columns only for row groups where something matched, and (with zone maps)
     * skip row groups whose min/max rule out every row.
     */
    private static Plan pushDown(Plan p, boolean zoneMaps) {
        return switch (p) {
            case Plan.Filter f when f.input() instanceof Plan.Scan s && s.filter() == null ->
                    new Plan.Scan(s.table(), s.schema(), s.columns(), f.predicate(), zoneMaps);
            case Plan.Filter f -> new Plan.Filter(pushDown(f.input(), zoneMaps), f.predicate());
            case Plan.Aggregate a -> new Plan.Aggregate(pushDown(a.input(), zoneMaps), a.groupBy(), a.aggs());
            case Plan.Project pr -> new Plan.Project(pushDown(pr.input(), zoneMaps), pr.exprs(), pr.names());
            case Plan.Sort s -> new Plan.Sort(pushDown(s.input(), zoneMaps), s.keys());
            case Plan.Limit l -> new Plan.Limit(pushDown(l.input(), zoneMaps), l.count());
            case Plan.Scan s -> s;
        };
    }

    // ---- Rule 3: projection pruning -----------------------------------------------------------

    /** Collects the columns each operator needs on the way down, then tells the Scan to read only those. */
    private static Plan prune(Plan p, Set<String> needed, boolean projectAbove) {
        return switch (p) {
            case Plan.Limit l -> new Plan.Limit(prune(l.input(), needed, projectAbove), l.count());
            case Plan.Sort s -> new Plan.Sort(prune(s.input(), needed, projectAbove), s.keys());
            case Plan.Project pr -> {
                Set<String> cols = new LinkedHashSet<>(needed);
                for (Expr e : pr.exprs()) collect(e, cols);
                yield new Plan.Project(prune(pr.input(), cols, true), pr.exprs(), pr.names());
            }
            case Plan.Aggregate a -> {
                Set<String> cols = new LinkedHashSet<>();
                for (Col c : a.groupBy()) cols.add(c.name());
                for (Agg g : a.aggs()) if (g.arg() != null) collect(g.arg(), cols);
                yield new Plan.Aggregate(prune(a.input(), cols, false), a.groupBy(), a.aggs());
            }
            case Plan.Filter f -> {
                Set<String> cols = new LinkedHashSet<>(needed);
                collect(f.predicate(), cols);
                yield new Plan.Filter(prune(f.input(), cols, projectAbove), f.predicate());
            }
            case Plan.Scan s -> {
                Set<String> cols = new LinkedHashSet<>(needed);
                if (s.filter() != null) collect(s.filter(), cols);
                List<String> keep = new ArrayList<>();
                for (Field f : s.schema()) if (cols.contains(f.name())) keep.add(f.name());
                yield new Plan.Scan(s.table(), s.schema(), keep, s.filter(), s.zoneMaps());
            }
        };
    }

    private static void collect(Expr e, Set<String> out) {
        switch (e) {
            case Col c -> { if (!c.name().startsWith("$agg")) out.add(c.name()); }
            case Bin b -> { collect(b.left(), out); collect(b.right(), out); }
            case Not n -> collect(n.operand(), out);
            case Neg n -> collect(n.operand(), out);
            case Agg a -> { if (a.arg() != null) collect(a.arg(), out); }
            case Lit l -> { }
        }
    }
}
