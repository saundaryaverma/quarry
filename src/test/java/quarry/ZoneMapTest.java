package quarry;

import static quarry.TestRunner.*;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import quarry.exec.ZoneMaps;
import quarry.plan.Binder;
import quarry.plan.Expr;
import quarry.plan.Plan;
import quarry.plan.Scalar;
import quarry.sql.Parser;
import quarry.storage.Field;
import quarry.storage.Stats;

class ZoneMapTest {
    static final List<Field> SCHEMA = List.of(new Field("a", DataType.LONG), new Field("x", DataType.DOUBLE), new Field("s", DataType.STRING));

    static Expr pred(String where) {
        Plan.Filter f = (Plan.Filter) ((Plan.Project) Binder.bind(Parser.parse("SELECT a FROM t WHERE " + where), SCHEMA)).input();
        // constant folding only, so "a > -3" sees a literal -3, as it would in a real query
        Plan folded = quarry.plan.Optimizer.optimize(f, new quarry.plan.Optimizer.Rules(true, false, false, false));
        return folded instanceof Plan.Filter ff ? ff.predicate() : f.predicate();
    }

    static boolean may(String where, Map<String, Stats> stats) {
        return ZoneMaps.mayMatch(pred(where), stats::get);
    }

    static void testRanges() {
        Map<String, Stats> st = Map.of("a", new Stats(10L, 20L), "x", new Stats(-1.5, 2.5), "s", new Stats("b", "d"));
        check(!may("a < 10", st), "a < 10 can't match [10,20]");
        check(may("a <= 10", st), "a <= 10 can");
        check(!may("a > 20", st), "a > 20 can't");
        check(may("a >= 20", st), "a >= 20 can");
        check(!may("a = 21", st), "a = 21 can't");
        check(may("a = 15", st), "a = 15 can");
        check(!may("25 < a", st), "flipped comparison");
        check(may("a != 15", st), "!= almost always may");
        check(!may("x > 2.5", st), "double range");
        check(!may("a > 20.5", st), "long column vs double literal");
        check(may("a > 19.5", st), "long column vs double literal, inside");
        check(!may("s = 'a'", st), "string below range");
        check(!may("s > 'd'", st), "string above range");
        check(may("s = 'c'", st), "string inside");
        check(!may("a < 5 OR a > 30", st), "OR of two impossible");
        check(may("a < 5 OR a > 15", st), "OR with one possible");
        check(!may("a > 15 AND x > 3", st), "AND with one impossible");
        check(may("NOT a < 5", st), "NOT is treated as maybe");
        check(may("a + 1 > 100", st), "expressions are treated as maybe");
        check(!may("a > 15 AND 1 = 2", st), "folded FALSE");

        Map<String, Stats> single = Map.of("a", new Stats(7L, 7L), "x", new Stats(0.0, 0.0), "s", new Stats("q", "q"));
        check(!may("a != 7", single), "every value equals 7");
    }

    /**
     * The safety property, checked by brute force: for random row groups and random predicates,
     * if any row in the group satisfies the predicate, mayMatch must say yes.
     */
    static void testNeverSkipsAMatchingRowGroup() {
        SplittableRandom rnd = new SplittableRandom(7);
        String[] ops = {"=", "!=", "<", "<=", ">", ">="};
        String[] strs = {"", "a", "ab", "b", "zz", "é"};
        int skippedSomething = 0;
        for (int trial = 0; trial < 3000; trial++) {
            int n = 1 + rnd.nextInt(6);
            long[] a = new long[n];
            double[] x = new double[n];
            String[] s = new String[n];
            for (int i = 0; i < n; i++) {
                a[i] = rnd.nextInt(-5, 6);
                x[i] = rnd.nextInt(10) == 0 ? Double.NaN : rnd.nextInt(-8, 9) / 2.0;
                s[i] = strs[rnd.nextInt(strs.length)];
            }
            Map<String, Stats> stats = Map.of(
                    "a", new quarry.storage.Column.Longs(a).stats(),
                    "x", new quarry.storage.Column.Doubles(x).stats(),
                    "s", new quarry.storage.Column.Strings(s).stats());
            String where = atom(rnd, ops, strs);
            int shape = rnd.nextInt(3);
            if (shape == 1) where = where + " AND " + atom(rnd, ops, strs);
            if (shape == 2) where = where + " OR " + atom(rnd, ops, strs);
            Expr p = pred(where);
            boolean any = false;
            for (int i = 0; i < n && !any; i++) any = evalRow(p, a[i], x[i], s[i]);
            boolean may = ZoneMaps.mayMatch(p, stats::get);
            if (any && !may) throw new AssertionError("zone map skipped a match for WHERE " + where + " with a=" +
                    java.util.Arrays.toString(a) + " x=" + java.util.Arrays.toString(x) + " s=" + java.util.Arrays.toString(s));
            if (!may) skippedSomething++;
        }
        check(skippedSomething > 300, "zone maps should be able to skip some groups, skipped " + skippedSomething);
    }

    private static String atom(SplittableRandom rnd, String[] ops, String[] strs) {
        String op = ops[rnd.nextInt(ops.length)];
        return switch (rnd.nextInt(4)) {
            case 0 -> "a " + op + " " + rnd.nextInt(-6, 7);
            case 1 -> "x " + op + " " + (rnd.nextInt(-9, 10) / 2.0);
            case 2 -> rnd.nextInt(-6, 7) + " " + op + " a";
            default -> "s " + op + " '" + strs[rnd.nextInt(strs.length)] + "'";
        };
    }

    private static boolean evalRow(Expr e, long a, double x, String s) {
        return (Boolean) eval(e, a, x, s);
    }

    private static Object eval(Expr e, long a, double x, String s) {
        return switch (e) {
            case Expr.Col c -> switch (c.name()) { case "a" -> (Object) a; case "x" -> (Object) x; default -> s; };
            case Expr.Lit l -> l.value();
            case Expr.Bin b -> Scalar.binary(b.op(), b.type(), eval(b.left(), a, x, s), eval(b.right(), a, x, s));
            case Expr.Not n -> !(Boolean) eval(n.operand(), a, x, s);
            case Expr.Neg n -> eval(n.operand(), a, x, s) instanceof Long l ? (Object) (-l) : (Object) (-(Double) eval(n.operand(), a, x, s));
            case Expr.Agg g -> throw new IllegalStateException();
        };
    }
}
