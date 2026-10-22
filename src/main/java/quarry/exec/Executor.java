package quarry.exec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import quarry.DataType;
import quarry.plan.Expr;
import quarry.plan.Expr.*;
import quarry.plan.Plan;
import quarry.storage.TableReader;

/**
 * Runs an optimized plan against one table.
 *
 * The bottom of every plan (Scan, then Filter, then either a partial Aggregate or the Project) runs
 * as an independent pipeline per row group, and row groups are spread over a thread pool. Partial
 * aggregates are merged, everything else is concatenated in row-group order, and the remaining
 * operators (final Project after an aggregate, Sort, Limit) run once on the combined result.
 *
 * When the Scan carries a pushed-down predicate it uses late materialization: it reads only the
 * columns the predicate needs, evaluates it, and reads the rest of the columns only if some row
 * matched. With zone maps on, it first checks the row group's min/max and may skip it unread.
 */
public final class Executor {

    /** What a query cost, for EXPLAIN ANALYZE-style output and the benchmark. */
    public record Stats(int rowGroups, int rowGroupsSkipped, int rowGroupsNoMatch, long rowsScanned, long bytesRead) {}

    private final TableReader reader;
    private final int threads;
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger noMatch = new AtomicInteger();
    private final AtomicLong rowsScanned = new AtomicLong();

    // The plan, taken apart. The binder always builds the same shape, so the executor can too.
    private Long limit;
    private List<Plan.SortKey> sortKeys;
    private Plan.Project project;
    private Plan.Aggregate aggregate;
    private Expr residualFilter;
    private Plan.Scan scan;

    private Executor(TableReader reader, int threads) {
        this.reader = reader;
        this.threads = Math.max(1, threads);
    }

    public record Output(List<String> names, List<Vec> columns, Stats stats) {
        public int rows() { return columns.isEmpty() ? 0 : columns.get(0).size(); }
    }

    public static Output run(Plan plan, TableReader reader, int threads) {
        reader.resetBytesRead();
        Executor ex = new Executor(reader, threads);
        ex.decompose(plan);
        List<Vec> cols = ex.execute();
        return new Output(ex.project.names(), cols, new Stats(reader.rowGroupCount(), ex.skipped.get(),
                ex.noMatch.get(), ex.rowsScanned.get(), reader.bytesRead()));
    }

    private void decompose(Plan p) {
        if (p instanceof Plan.Limit l) { limit = l.count(); p = l.input(); }
        if (p instanceof Plan.Sort s) { sortKeys = s.keys(); p = s.input(); }
        project = (Plan.Project) p;
        p = project.input();
        if (p instanceof Plan.Aggregate a) { aggregate = a; p = a.input(); }
        if (p instanceof Plan.Filter f) { residualFilter = f.predicate(); p = f.input(); }
        scan = (Plan.Scan) p;
    }

    private List<Vec> execute() {
        int groups = reader.rowGroupCount();
        ExecutorService pool = threads > 1 ? Executors.newFixedThreadPool(threads) : null;
        try {
            if (aggregate != null) {
                HashAggregate total = new HashAggregate(aggregate.groupBy(), aggregate.aggs());
                // One partial table per task, merged as tasks finish. Tasks cover a contiguous range
                // of row groups so a partial table is reused across many of them.
                for (HashAggregate part : runRanges(pool, groups, this::aggregateRange)) total.merge(part);
                Batch grouped = total.finish();
                return finish(projectBatch(grouped));
            }
            // No aggregate: produce projected rows per row group. LIMIT without ORDER BY can stop
            // early, so run the row groups in waves and stop once enough rows have come back.
            boolean earlyStop = limit != null && sortKeys == null;
            List<List<Vec>> parts = new ArrayList<>();
            long have = 0;
            int wave = earlyStop ? threads : groups;
            for (int start = 0; start < groups; start += wave) {
                int end = Math.min(groups, start + wave);
                List<Future<List<Vec>>> futures = new ArrayList<>();
                List<List<Vec>> done = new ArrayList<>();
                for (int g = start; g < end; g++) {
                    final int group = g;
                    if (pool == null) done.add(projectGroup(group));
                    else futures.add(pool.submit(() -> projectGroup(group)));
                }
                for (Future<List<Vec>> f : futures) done.add(get(f));
                for (List<Vec> d : done) {
                    if (d == null) continue;
                    parts.add(d);
                    have += d.get(0).size();
                }
                if (earlyStop && have >= limit) break;
            }
            return finish(concat(parts));
        } finally {
            if (pool != null) pool.shutdownNow();
        }
    }

    private interface RangeTask<T> { T run(int from, int to); }

    private <T> List<T> runRanges(ExecutorService pool, int groups, RangeTask<T> task) {
        List<T> out = new ArrayList<>();
        if (pool == null) { out.add(task.run(0, groups)); return out; }
        // Interleaved assignment would balance better under skew; contiguous ranges keep each
        // thread's reads sequential in the file. Use more ranges than threads to smooth it out.
        int ranges = Math.min(groups, threads * 4);
        List<Future<T>> futures = new ArrayList<>();
        for (int r = 0; r < ranges; r++) {
            int from = (int) ((long) groups * r / ranges), to = (int) ((long) groups * (r + 1) / ranges);
            futures.add(pool.submit(() -> task.run(from, to)));
        }
        for (Future<T> f : futures) out.add(get(f));
        return out;
    }

    private HashAggregate aggregateRange(int from, int to) {
        HashAggregate part = new HashAggregate(aggregate.groupBy(), aggregate.aggs());
        for (int g = from; g < to; g++) {
            Batch b = scanGroup(g);
            if (b != null) part.add(b);
        }
        return part;
    }

    private List<Vec> projectGroup(int g) {
        Batch b = scanGroup(g);
        if (b == null || b.rows() == 0) return null;
        return projectBatch(b);
    }

    /** Scan plus filtering for one row group. Returns null if no row survives. */
    Batch scanGroup(int g) {
        int rows = reader.rowCount(g);
        Batch b;
        if (scan.filter() != null) {
            if (scan.zoneMaps() && !ZoneMaps.mayMatch(scan.filter(), name -> reader.stats(g, reader.columnIndex(name)))) {
                skipped.incrementAndGet();
                return null;
            }
            Set<String> predCols = new LinkedHashSet<>();
            columnsOf(scan.filter(), predCols);
            Map<String, Vec> cols = read(g, predCols);
            rowsScanned.addAndGet(rows);
            Vec.Bools mask = (Vec.Bools) Evaluator.eval(scan.filter(), new Batch(rows, cols));
            int[] sel = new int[rows];
            int n = Evaluator.select(mask, sel);
            if (n == 0) { noMatch.incrementAndGet(); return null; }
            Map<String, Vec> out = new HashMap<>();
            for (Map.Entry<String, Vec> e : cols.entrySet()) {
                if (scan.columns().contains(e.getKey())) out.put(e.getKey(), n == rows ? e.getValue() : e.getValue().gather(sel, n));
            }
            // Late materialization: the other columns are read only now, and only for this row group.
            Set<String> rest = new LinkedHashSet<>(scan.columns());
            rest.removeAll(predCols);
            for (Map.Entry<String, Vec> e : read(g, rest).entrySet()) {
                out.put(e.getKey(), n == rows ? e.getValue() : e.getValue().gather(sel, n));
            }
            b = new Batch(n, out);
        } else {
            b = new Batch(rows, read(g, scan.columns()));
            rowsScanned.addAndGet(rows);
        }
        if (residualFilter != null) {
            Vec.Bools mask = (Vec.Bools) Evaluator.eval(residualFilter, b);
            int[] sel = new int[b.rows()];
            int n = Evaluator.select(mask, sel);
            if (n == 0) return null;
            if (n < b.rows()) b = b.gather(sel, n);
        }
        return b;
    }

    private Map<String, Vec> read(int g, Iterable<String> names) {
        Map<String, Vec> out = new HashMap<>();
        try {
            for (String name : names) out.put(name, Vec.of(reader.readColumn(g, reader.columnIndex(name))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private List<Vec> projectBatch(Batch b) {
        List<Vec> out = new ArrayList<>();
        for (Expr e : project.exprs()) out.add(Evaluator.eval(e, b));
        return out;
    }

    private List<Vec> concat(List<List<Vec>> parts) {
        List<Vec> out = new ArrayList<>();
        for (int c = 0; c < project.exprs().size(); c++) {
            DataType t = project.exprs().get(c).type();
            List<Vec> pieces = new ArrayList<>();
            for (List<Vec> p : parts) pieces.add(p.get(c));
            out.add(pieces.isEmpty() ? Vec.fromObjects(t, List.of()) : Vec.concat(t, pieces));
        }
        return out;
    }

    /** Sort and limit, on the combined result. */
    private List<Vec> finish(List<Vec> cols) {
        int n = cols.isEmpty() ? 0 : cols.get(0).size();
        int keep = limit == null ? n : (int) Math.min(n, limit);
        if (sortKeys == null) {
            if (keep == n) return cols;
            int[] idx = new int[keep];
            for (int i = 0; i < keep; i++) idx[i] = i;
            return cols.stream().map(v -> v.gather(idx, keep)).toList();
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Comparator<Integer> cmp = null;
        for (Plan.SortKey k : sortKeys) {
            Comparator<Integer> c = comparator(cols.get(k.column()));
            if (k.descending()) c = c.reversed();
            cmp = cmp == null ? c : cmp.thenComparing(c);
        }
        java.util.Arrays.sort(order, cmp);  // stable, so ties keep their scan order
        int[] idx = new int[keep];
        for (int i = 0; i < keep; i++) idx[i] = order[i];
        return cols.stream().map(v -> v.gather(idx, keep)).toList();
    }

    private static Comparator<Integer> comparator(Vec v) {
        return switch (v) {
            case Vec.Longs l -> (a, b) -> Long.compare(l.v()[a], l.v()[b]);
            case Vec.Doubles d -> (a, b) -> Double.compare(d.v()[a], d.v()[b]);
            case Vec.Strs s -> (a, b) -> s.v()[a].compareTo(s.v()[b]);
            case Vec.Bools x -> (a, b) -> Boolean.compare(x.v()[a], x.v()[b]);
        };
    }

    private static void columnsOf(Expr e, Set<String> out) {
        switch (e) {
            case Col c -> out.add(c.name());
            case Bin b -> { columnsOf(b.left(), out); columnsOf(b.right(), out); }
            case Not n -> columnsOf(n.operand(), out);
            case Neg n -> columnsOf(n.operand(), out);
            case Agg a -> { if (a.arg() != null) columnsOf(a.arg(), out); }
            case Lit l -> { }
        }
    }

    private static <T> T get(Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new IllegalStateException(e.getCause());
        }
    }
}
