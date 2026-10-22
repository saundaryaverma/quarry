package quarry;

import static quarry.TestRunner.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

import quarry.Engine.Options;
import quarry.plan.Binder;
import quarry.plan.Optimizer.Rules;
import quarry.plan.Plan;
import quarry.plan.Scalar;
import quarry.sql.Ast;
import quarry.sql.Parser;
import quarry.storage.Column;
import quarry.storage.Field;
import quarry.storage.TableReader;
import quarry.storage.TableWriter;

/**
 * Generates random SQL, runs it through the engine under several optimizer and thread settings,
 * and checks every answer against the reference interpreter. This is what catches the bugs
 * hand-written tests miss: an optimizer rule that changes results for some predicate shape, a
 * zone map that skips a row group it shouldn't, a merge that double-counts a group.
 */
class DifferentialTest {
    static final int QUERIES = Integer.getInteger("quarry.queries", 600);

    static final List<Options> CONFIGS = List.of(
            new Options(Rules.ALL, 1),
            new Options(Rules.ALL, 3),
            new Options(Rules.NONE, 1),
            new Options(Rules.NONE, 2),
            new Options(new Rules(true, true, false, true), 2),   // no projection pruning
            new Options(new Rules(false, true, true, true), 1),   // no constant folding
            new Options(new Rules(true, true, true, false), 2));  // no zone maps

    static final String[] STRS = {"", "red", "Red", "blue", "é", "z", "O'Neil"};

    static void testRandomQueriesMatchTheReferenceInterpreter() throws Exception {
        Path dir = Files.createTempDirectory("quarry-diff");
        writeTable(dir.resolve("t.qry"), 1500, 64, 11);
        Engine engine = new Engine(dir);
        SplittableRandom rnd = new SplittableRandom(2024);
        int withSkips = 0, nonEmpty = 0;
        try (TableReader table = new TableReader(dir.resolve("t.qry"))) {
            for (int q = 0; q < QUERIES; q++) {
                String sql = new Gen(rnd).query();
                Ast.Select ast;
                try {
                    ast = Parser.parse(sql);
                } catch (QueryException e) {
                    throw new AssertionError("generator produced invalid SQL: " + sql + "\n" + e.getMessage());
                }
                Plan plan = Binder.bind(ast, table.schema());
                Plan unlimited = plan instanceof Plan.Limit l ? l.input() : plan;
                List<Object[]> full = Reference.run(unlimited, table);
                List<Object[]> expected = plan instanceof Plan.Limit l
                        ? full.subList(0, (int) Math.min(full.size(), l.count())) : full;
                if (!expected.isEmpty()) nonEmpty++;
                for (Options opt : CONFIGS) {
                    Engine.Result r = engine.execute(sql, opt);
                    if (r.stats().rowGroupsSkipped() > 0) withSkips++;
                    compare(sql, opt, plan, expected, full, r);
                }
            }
        }
        check(withSkips > QUERIES / 10, "too few queries exercised zone maps: " + withSkips);
        check(nonEmpty > QUERIES / 2, "too many queries returned nothing: " + nonEmpty);
    }

    // ---- comparison -------------------------------------------------------------------------

    static void compare(String sql, Options opt, Plan plan, List<Object[]> expected, List<Object[]> full, Engine.Result r) {
        List<List<Object>> got = r.rowList();
        String where = "\nquery:  " + sql + "\nconfig: " + opt + "\n";
        if (got.size() != expected.size()) {
            throw new AssertionError("row count " + got.size() + " != expected " + expected.size() + where + r.format(20));
        }
        Plan.Sort sort = plan instanceof Plan.Limit l && l.input() instanceof Plan.Sort s ? s : plan instanceof Plan.Sort s ? s : null;
        boolean limited = plan instanceof Plan.Limit;
        if (sort != null) {
            // The engine's own output must be in order...
            for (int i = 1; i < got.size(); i++) {
                for (Plan.SortKey k : sort.keys()) {
                    int c = Scalar.compare(got.get(i - 1).get(k.column()), got.get(i).get(k.column()));
                    if (k.descending()) c = -c;
                    if (c < 0) break;
                    if (c > 0) throw new AssertionError("rows " + (i - 1) + " and " + i + " out of order" + where + r.format(50));
                }
            }
            // ...and the sort keys must match the reference row by row (other columns may differ on ties).
            for (int i = 0; i < got.size(); i++) {
                for (Plan.SortKey k : sort.keys()) {
                    if (!same(expected.get(i)[k.column()], got.get(i).get(k.column()))) {
                        throw new AssertionError("row " + i + " sort key differs: expected " + expected.get(i)[k.column()]
                                + " got " + got.get(i).get(k.column()) + where + r.format(50));
                    }
                }
            }
        }
        if (!limited) {
            // Same multiset of rows.
            List<String> a = new ArrayList<>(), b = new ArrayList<>();
            for (Object[] row : expected) a.add(key(List.of(row)));
            for (List<Object> row : got) b.add(key(row));
            a.sort(null);
            b.sort(null);
            if (!a.equals(b)) {
                for (int i = 0; i < a.size(); i++) {
                    if (!a.get(i).equals(b.get(i))) {
                        throw new AssertionError("rows differ, first mismatch: expected " + a.get(i) + " got " + b.get(i) + where + r.format(20));
                    }
                }
            }
        } else {
            // A LIMIT can pick any qualifying rows on ties, so check each returned row exists in the full result.
            Map<String, Integer> pool = new HashMap<>();
            for (Object[] row : full) pool.merge(key(List.of(row)), 1, Integer::sum);
            for (List<Object> row : got) {
                String k = key(row);
                Integer n = pool.get(k);
                if (n == null || n == 0) throw new AssertionError("row " + k + " is not in the unlimited result" + where + r.format(20));
                pool.put(k, n - 1);
            }
        }
    }

    /** Canonical text for a row. Doubles are rounded to 9 significant digits, since SUM/AVG of
     *  doubles legitimately vary in the last bits with the order in which partial sums are merged. */
    static String key(List<Object> row) {
        StringBuilder sb = new StringBuilder();
        for (Object v : row) {
            if (v instanceof Double d) sb.append(d.isNaN() || d.isInfinite() ? d.toString() : String.format(Locale.ROOT, "%.9g", d == 0.0 ? 0.0 : d));
            else sb.append(v.getClass().getSimpleName().charAt(0)).append(v);
            sb.append('|');
        }
        return sb.toString();
    }

    static boolean same(Object a, Object b) {
        if (a instanceof Double x && b instanceof Double y) {
            if (x.isNaN() || y.isNaN() || x.isInfinite() || y.isInfinite()) return x.equals(y);
            return Math.abs(x - y) <= 1e-9 * Math.max(Math.abs(x), Math.abs(y)) + 1e-9;
        }
        return a.equals(b);
    }

    // ---- data -------------------------------------------------------------------------------

    /**
     * k: sorted, so zone maps can skip row groups. a: small random ints. x: doubles with some
     * zeros (for division by zero), negative zero and NaN. s: a few strings including '' and a
     * non-ASCII one. t: sorted strings.
     */
    static void writeTable(Path file, int n, int rowGroup, long seed) throws Exception {
        SplittableRandom rnd = new SplittableRandom(seed);
        long[] k = new long[n], a = new long[n];
        double[] x = new double[n];
        String[] s = new String[n], t = new String[n];
        for (int i = 0; i < n; i++) {
            k[i] = i / 40;
            a[i] = rnd.nextInt(-5, 21);
            int r = rnd.nextInt(100);
            x[i] = r == 0 ? Double.NaN : r == 1 ? -0.0 : r < 5 ? 0.0 : rnd.nextInt(-10000, 10001) / 100.0;
            s[i] = STRS[rnd.nextInt(STRS.length)];
            t[i] = String.format("c%02d", i / 100);
        }
        TableWriter.write(file, List.of(new Field("k", DataType.LONG), new Field("a", DataType.LONG), new Field("x", DataType.DOUBLE),
                        new Field("s", DataType.STRING), new Field("t", DataType.STRING)),
                List.of(new Column.Longs(k), new Column.Longs(a), new Column.Doubles(x), new Column.Strings(s), new Column.Strings(t)), rowGroup);
    }

    // ---- query generator --------------------------------------------------------------------

    static final class Gen {
        final SplittableRandom r;

        Gen(SplittableRandom r) { this.r = r; }

        String query() {
            return switch (r.nextInt(3)) {
                case 0 -> plain();
                case 1 -> grouped();
                default -> global();
            };
        }

        String plain() {
            int n = 1 + r.nextInt(3);
            List<String> items = new ArrayList<>();
            for (int i = 0; i < n; i++) items.add((r.nextInt(4) == 0 ? strCol() : num(2)) + " AS c" + i);
            return "SELECT " + String.join(", ", items) + " FROM t" + where() + orderAndLimit(n);
        }

        String grouped() {
            List<String> cols = new ArrayList<>(List.of("k", "a", "s", "t", "x"));
            List<String> keys = new ArrayList<>();
            int nk = 1 + r.nextInt(2);
            for (int i = 0; i < nk; i++) keys.add(cols.remove(r.nextInt(cols.size())));
            List<String> items = new ArrayList<>(keys);
            int na = 1 + r.nextInt(3);
            for (int i = 0; i < na; i++) items.add(agg());
            return "SELECT " + String.join(", ", items) + " FROM t" + where() + " GROUP BY " + String.join(", ", keys) + orderAndLimit(items.size());
        }

        String global() {
            List<String> items = new ArrayList<>();
            int na = 1 + r.nextInt(3);
            for (int i = 0; i < na; i++) items.add(agg());
            return "SELECT " + String.join(", ", items) + " FROM t" + where();
        }

        String agg() {
            return switch (r.nextInt(8)) {
                case 0 -> "COUNT(*)";
                case 1 -> "SUM(" + num(1) + ")";
                case 2 -> "AVG(" + num(1) + ")";
                case 3 -> "MIN(" + num(1) + ")";
                case 4 -> "MAX(" + num(1) + ")";
                case 5 -> (r.nextBoolean() ? "MIN(" : "MAX(") + strCol() + ")";
                case 6 -> "SUM(a) * 2 - COUNT(*)";
                default -> "COUNT(" + numCol() + ")";
            };
        }

        String where() {
            return r.nextInt(5) == 0 ? "" : " WHERE " + pred(2);
        }

        String orderAndLimit(int items) {
            StringBuilder sb = new StringBuilder();
            if (r.nextBoolean()) {
                sb.append(" ORDER BY ");
                int nk = 1 + r.nextInt(Math.min(2, items));
                for (int i = 0; i < nk; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(1 + r.nextInt(items)).append(r.nextBoolean() ? " DESC" : "");
                }
            }
            if (r.nextInt(3) == 0) sb.append(" LIMIT ").append(r.nextInt(30));
            return sb.toString();
        }

        String pred(int depth) {
            int c = r.nextInt(depth > 0 ? 9 : 5);
            return switch (c) {
                case 0, 1 -> numCol() + " " + cmp() + " " + numLit();               // zone-map friendly
                case 2 -> numLit() + " " + cmp() + " " + numCol();
                case 3 -> strCol() + " " + cmp() + " " + strLit();
                case 4 -> num(1) + " " + cmp() + " " + num(1);
                case 5, 6 -> "(" + pred(depth - 1) + (r.nextBoolean() ? " AND " : " OR ") + pred(depth - 1) + ")";
                case 7 -> "NOT (" + pred(depth - 1) + ")";
                default -> r.nextBoolean() ? "TRUE" : "1 = " + (r.nextBoolean() ? "1" : "2");
            };
        }

        String num(int depth) {
            if (depth == 0 || r.nextInt(3) == 0) return r.nextInt(4) == 0 ? numLit() : numCol();
            if (r.nextInt(6) == 0) return "-(" + num(depth - 1) + ")";
            String op = new String[]{"+", "-", "*", "/"}[r.nextInt(4)];
            return "(" + num(depth - 1) + " " + op + " " + num(depth - 1) + ")";
        }

        String numCol() { return new String[]{"k", "a", "x"}[r.nextInt(3)]; }

        String strCol() { return r.nextBoolean() ? "s" : "t"; }

        String numLit() {
            String v = r.nextBoolean() ? String.valueOf(r.nextInt(0, 40)) : String.valueOf(r.nextInt(0, 400) / 4.0);
            return r.nextInt(4) == 0 ? "(-" + v + ")" : v;
        }

        String strLit() {
            String v = r.nextBoolean() ? STRS[r.nextInt(STRS.length)] : String.format("c%02d", r.nextInt(18));
            return "'" + v.replace("'", "''") + "'";
        }

        String cmp() { return new String[]{"=", "!=", "<", "<=", ">", ">="}[r.nextInt(6)]; }
    }
}
