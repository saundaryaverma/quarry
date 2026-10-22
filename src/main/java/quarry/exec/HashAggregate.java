package quarry.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import quarry.DataType;
import quarry.plan.Expr.*;

/**
 * Hash aggregation in two phases. Every worker thread builds a partial table for the row groups it
 * scans; the partial tables are then merged into one. Running state is kept per aggregate in flat
 * primitive arrays indexed by group id, so updating a SUM is "sums[group[i]] += x[i]".
 *
 * Group keys are boxed (one value, or a List for multi-column keys); the per-row hash lookup is
 * the main cost of a GROUP BY and the first thing to replace with a primitive open-addressing table.
 */
public final class HashAggregate {
    private final List<Col> groupBy;
    private final List<Agg> aggs;
    private final Map<Object, Integer> ids = new HashMap<>();
    private final List<Object> keys = new ArrayList<>();
    private final Acc[] accs;

    public HashAggregate(List<Col> groupBy, List<Agg> aggs) {
        this.groupBy = groupBy;
        this.aggs = aggs;
        this.accs = new Acc[aggs.size()];
        for (int i = 0; i < accs.length; i++) accs[i] = Acc.create(aggs.get(i));
    }

    public int groups() { return keys.size(); }

    public void add(Batch b) {
        int n = b.rows();
        int[] gid = new int[n];
        if (groupBy.isEmpty()) {
            if (n > 0) groupId(List.of());
        } else {
            Vec[] k = new Vec[groupBy.size()];
            for (int j = 0; j < k.length; j++) k[j] = b.column(groupBy.get(j).name());
            for (int i = 0; i < n; i++) gid[i] = groupId(key(k, i));
        }
        for (Acc a : accs) a.ensure(keys.size());
        for (int j = 0; j < accs.length; j++) {
            Agg g = aggs.get(j);
            Vec arg = g.arg() == null || g.fn() == AggFn.COUNT ? null : Evaluator.eval(g.arg(), b);
            accs[j].update(gid, n, arg);
        }
    }

    /** Folds another partial table into this one. */
    public void merge(HashAggregate other) {
        int[] map = new int[other.keys.size()];
        for (int g = 0; g < map.length; g++) map[g] = groupId(other.keys.get(g));
        for (Acc a : accs) a.ensure(keys.size());
        for (int j = 0; j < accs.length; j++) accs[j].merge(other.accs[j], map);
    }

    /**
     * The result: group columns under their own names, then $agg0, $agg1, ...
     * Quarry has no NULLs, so an aggregate over zero rows needs a rule. With no GROUP BY, SQL returns
     * one row; Quarry does too when every aggregate is COUNT or SUM (0 is the right answer for both).
     * If there's an AVG, MIN or MAX, which have no value for zero rows, it returns no rows instead.
     */
    public Batch finish() {
        if (groupBy.isEmpty() && keys.isEmpty() && aggs.stream().allMatch(a -> a.fn() == AggFn.COUNT || a.fn() == AggFn.SUM)) {
            groupId(List.of());
            for (Acc a : accs) a.ensure(1);
        }
        int n = keys.size();
        Map<String, Vec> cols = new HashMap<>();
        for (int j = 0; j < groupBy.size(); j++) {
            List<Object> vals = new ArrayList<>(n);
            for (Object k : keys) vals.add(groupBy.size() == 1 ? k : ((List<?>) k).get(j));
            cols.put(groupBy.get(j).name(), Vec.fromObjects(groupBy.get(j).type(), vals));
        }
        for (int j = 0; j < accs.length; j++) cols.put("$agg" + j, accs[j].result(n));
        return new Batch(n, cols);
    }

    private Object key(Vec[] k, int i) {
        if (k.length == 1) return k[0].get(i);
        Object[] parts = new Object[k.length];
        for (int j = 0; j < k.length; j++) parts[j] = k[j].get(i);
        return List.of(parts);
    }

    private int groupId(Object key) {
        Integer id = ids.get(key);
        if (id != null) return id;
        ids.put(key, keys.size());
        keys.add(key);
        return keys.size() - 1;
    }

    /** Running state for one aggregate across all groups. */
    private abstract static class Acc {
        abstract void ensure(int groups);
        abstract void update(int[] gid, int n, Vec arg);
        abstract void merge(Acc other, int[] map);
        abstract Vec result(int groups);

        static int grow(int have, int need) { return Math.max(need, Math.max(16, have * 2)); }

        static Acc create(Agg a) {
            DataType t = a.arg() == null ? DataType.LONG : a.arg().type();
            return switch (a.fn()) {
                case COUNT -> new Count();
                case SUM -> t == DataType.LONG ? new SumLong() : new SumDouble(false);
                case AVG -> new SumDouble(true);
                case MIN, MAX -> switch (t) {
                    case LONG -> new ExtremeLong(a.fn() == AggFn.MAX);
                    case DOUBLE -> new ExtremeDouble(a.fn() == AggFn.MAX);
                    default -> new ExtremeString(a.fn() == AggFn.MAX);
                };
            };
        }
    }

    private static final class Count extends Acc {
        long[] c = new long[0];
        void ensure(int g) { if (c.length < g) c = Arrays.copyOf(c, grow(c.length, g)); }
        void update(int[] gid, int n, Vec arg) { for (int i = 0; i < n; i++) c[gid[i]]++; }
        void merge(Acc o, int[] map) { long[] x = ((Count) o).c; for (int g = 0; g < map.length; g++) c[map[g]] += x[g]; }
        Vec result(int g) { return new Vec.Longs(Arrays.copyOf(c, g)); }
    }

    private static final class SumLong extends Acc {
        long[] s = new long[0];
        void ensure(int g) { if (s.length < g) s = Arrays.copyOf(s, grow(s.length, g)); }
        void update(int[] gid, int n, Vec arg) { long[] x = ((Vec.Longs) arg).v(); for (int i = 0; i < n; i++) s[gid[i]] += x[i]; }
        void merge(Acc o, int[] map) { long[] x = ((SumLong) o).s; for (int g = 0; g < map.length; g++) s[map[g]] += x[g]; }
        Vec result(int g) { return new Vec.Longs(Arrays.copyOf(s, g)); }
    }

    /** SUM of doubles, or AVG (sum and count, divided at the end). */
    private static final class SumDouble extends Acc {
        final boolean avg;
        double[] s = new double[0];
        long[] c = new long[0];
        SumDouble(boolean avg) { this.avg = avg; }
        void ensure(int g) { if (s.length < g) { int n = grow(s.length, g); s = Arrays.copyOf(s, n); c = Arrays.copyOf(c, n); } }
        void update(int[] gid, int n, Vec arg) {
            double[] x = Evaluator.asDoubles(arg);
            for (int i = 0; i < n; i++) { s[gid[i]] += x[i]; c[gid[i]]++; }
        }
        void merge(Acc o, int[] map) {
            SumDouble x = (SumDouble) o;
            for (int g = 0; g < map.length; g++) { s[map[g]] += x.s[g]; c[map[g]] += x.c[g]; }
        }
        Vec result(int g) {
            double[] out = Arrays.copyOf(s, g);
            if (avg) for (int i = 0; i < g; i++) out[i] /= c[i];
            return new Vec.Doubles(out);
        }
    }

    private static final class ExtremeLong extends Acc {
        final boolean max;
        long[] v = new long[0];
        boolean[] seen = new boolean[0];
        ExtremeLong(boolean max) { this.max = max; }
        void ensure(int g) { if (v.length < g) { int n = grow(v.length, g); v = Arrays.copyOf(v, n); seen = Arrays.copyOf(seen, n); } }
        void offer(int g, long x) {
            if (!seen[g] || (max ? x > v[g] : x < v[g])) { v[g] = x; seen[g] = true; }
        }
        void update(int[] gid, int n, Vec arg) { long[] x = ((Vec.Longs) arg).v(); for (int i = 0; i < n; i++) offer(gid[i], x[i]); }
        void merge(Acc o, int[] map) { ExtremeLong x = (ExtremeLong) o; for (int g = 0; g < map.length; g++) if (x.seen[g]) offer(map[g], x.v[g]); }
        Vec result(int g) { return new Vec.Longs(Arrays.copyOf(v, g)); }
    }

    private static final class ExtremeDouble extends Acc {
        final boolean max;
        double[] v = new double[0];
        boolean[] seen = new boolean[0];
        ExtremeDouble(boolean max) { this.max = max; }
        void ensure(int g) { if (v.length < g) { int n = grow(v.length, g); v = Arrays.copyOf(v, n); seen = Arrays.copyOf(seen, n); } }
        void offer(int g, double x) {
            int c = seen[g] ? Double.compare(x, v[g]) : 0;
            if (!seen[g] || (max ? c > 0 : c < 0)) { v[g] = x; seen[g] = true; }
        }
        void update(int[] gid, int n, Vec arg) { double[] x = ((Vec.Doubles) arg).v(); for (int i = 0; i < n; i++) offer(gid[i], x[i]); }
        void merge(Acc o, int[] map) { ExtremeDouble x = (ExtremeDouble) o; for (int g = 0; g < map.length; g++) if (x.seen[g]) offer(map[g], x.v[g]); }
        Vec result(int g) { return new Vec.Doubles(Arrays.copyOf(v, g)); }
    }

    private static final class ExtremeString extends Acc {
        final boolean max;
        String[] v = new String[0];
        ExtremeString(boolean max) { this.max = max; }
        void ensure(int g) { if (v.length < g) v = Arrays.copyOf(v, grow(v.length, g)); }
        void offer(int g, String x) {
            if (v[g] == null || (max ? x.compareTo(v[g]) > 0 : x.compareTo(v[g]) < 0)) v[g] = x;
        }
        void update(int[] gid, int n, Vec arg) { String[] x = ((Vec.Strs) arg).v(); for (int i = 0; i < n; i++) offer(gid[i], x[i]); }
        void merge(Acc o, int[] map) { String[] x = ((ExtremeString) o).v; for (int g = 0; g < map.length; g++) if (x[g] != null) offer(map[g], x[g]); }
        Vec result(int g) { return new Vec.Strs(Arrays.copyOf(v, g)); }
    }
}
