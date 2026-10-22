package quarry.exec;

import quarry.DataType;
import quarry.plan.Expr;
import quarry.plan.Expr.*;

/**
 * Vectorized expression evaluation: each call computes an expression for every row of a batch,
 * one operator at a time, with a primitive loop per operator.
 *
 * Semantics match plan.Scalar exactly (the differential tests depend on it): LONG + - * wrap on
 * overflow, / always yields a DOUBLE, and doubles compare with Double.compare, so NaN sorts above
 * every number and equals itself.
 *
 * The common filter shape "column OP constant" gets a fast path that compares against the
 * constant directly instead of first filling an array with copies of it.
 */
public final class Evaluator {
    private Evaluator() {}

    public static Vec eval(Expr e, Batch b) {
        return switch (e) {
            case Col c -> b.column(c.name());
            case Lit l -> Vec.constant(l.value(), l.type(), b.rows());
            case Not n -> {
                boolean[] x = ((Vec.Bools) eval(n.operand(), b)).v();
                boolean[] out = new boolean[x.length];
                for (int i = 0; i < x.length; i++) out[i] = !x[i];
                yield new Vec.Bools(out);
            }
            case Neg n -> switch (eval(n.operand(), b)) {
                case Vec.Longs l -> { long[] o = new long[l.v().length]; for (int i = 0; i < o.length; i++) o[i] = -l.v()[i]; yield new Vec.Longs(o); }
                case Vec.Doubles d -> { double[] o = new double[d.v().length]; for (int i = 0; i < o.length; i++) o[i] = -d.v()[i]; yield new Vec.Doubles(o); }
                default -> throw new IllegalStateException();
            };
            case Bin bin -> binary(bin, b);
            case Agg a -> throw new IllegalStateException("aggregate " + a.sql() + " should have been planned away");
        };
    }

    /** Row indexes where the predicate is true, packed into the front of the returned array. */
    public static int select(Vec.Bools mask, int[] out) {
        boolean[] m = mask.v();
        int n = 0;
        for (int i = 0; i < m.length; i++) if (m[i]) out[n++] = i;
        return n;
    }

    private static Vec binary(Bin bin, Batch b) {
        Op op = bin.op();
        if (op.isComparison() && bin.right() instanceof Lit lit) return compareToConstant(op, eval(bin.left(), b), lit.value());
        if (op.isComparison() && bin.left() instanceof Lit lit) return compareToConstant(op.flip(), eval(bin.right(), b), lit.value());

        Vec l = eval(bin.left(), b), r = eval(bin.right(), b);
        int n = b.rows();
        if (op == Op.AND || op == Op.OR) {
            boolean[] x = ((Vec.Bools) l).v(), y = ((Vec.Bools) r).v(), o = new boolean[n];
            if (op == Op.AND) for (int i = 0; i < n; i++) o[i] = x[i] & y[i];
            else for (int i = 0; i < n; i++) o[i] = x[i] | y[i];
            return new Vec.Bools(o);
        }
        if (op.isArithmetic()) {
            if (bin.type() == DataType.LONG) {
                long[] x = ((Vec.Longs) l).v(), y = ((Vec.Longs) r).v(), o = new long[n];
                switch (op) {
                    case ADD -> { for (int i = 0; i < n; i++) o[i] = x[i] + y[i]; }
                    case SUB -> { for (int i = 0; i < n; i++) o[i] = x[i] - y[i]; }
                    case MUL -> { for (int i = 0; i < n; i++) o[i] = x[i] * y[i]; }
                    default -> throw new IllegalStateException();
                }
                return new Vec.Longs(o);
            }
            double[] x = asDoubles(l), y = asDoubles(r), o = new double[n];
            switch (op) {
                case ADD -> { for (int i = 0; i < n; i++) o[i] = x[i] + y[i]; }
                case SUB -> { for (int i = 0; i < n; i++) o[i] = x[i] - y[i]; }
                case MUL -> { for (int i = 0; i < n; i++) o[i] = x[i] * y[i]; }
                case DIV -> { for (int i = 0; i < n; i++) o[i] = x[i] / y[i]; }
                default -> throw new IllegalStateException();
            }
            return new Vec.Doubles(o);
        }
        boolean[] o = new boolean[n];
        if (l instanceof Vec.Longs x && r instanceof Vec.Longs y) {
            for (int i = 0; i < n; i++) o[i] = test(op, Long.compare(x.v()[i], y.v()[i]));
        } else if (l.type().isNumeric()) {
            double[] x = asDoubles(l), y = asDoubles(r);
            for (int i = 0; i < n; i++) o[i] = test(op, Double.compare(x[i], y[i]));
        } else if (l instanceof Vec.Strs x && r instanceof Vec.Strs y) {
            for (int i = 0; i < n; i++) o[i] = test(op, x.v()[i].compareTo(y.v()[i]));
        } else {
            boolean[] x = ((Vec.Bools) l).v(), y = ((Vec.Bools) r).v();
            for (int i = 0; i < n; i++) o[i] = test(op, Boolean.compare(x[i], y[i]));
        }
        return new Vec.Bools(o);
    }

    private static Vec compareToConstant(Op op, Vec v, Object c) {
        int n = v.size();
        boolean[] o = new boolean[n];
        switch (v) {
            case Vec.Longs l when c instanceof Long k -> {
                long[] x = l.v();
                long kk = k;
                switch (op) {  // specialised loops for the hottest cases; the JIT turns these into SIMD
                    case EQ -> { for (int i = 0; i < n; i++) o[i] = x[i] == kk; }
                    case LT -> { for (int i = 0; i < n; i++) o[i] = x[i] < kk; }
                    case LE -> { for (int i = 0; i < n; i++) o[i] = x[i] <= kk; }
                    case GT -> { for (int i = 0; i < n; i++) o[i] = x[i] > kk; }
                    case GE -> { for (int i = 0; i < n; i++) o[i] = x[i] >= kk; }
                    default -> { for (int i = 0; i < n; i++) o[i] = test(op, Long.compare(x[i], kk)); }
                }
            }
            case Vec.Longs l -> {
                double k = ((Number) c).doubleValue();
                for (int i = 0; i < n; i++) o[i] = test(op, Double.compare(l.v()[i], k));
            }
            case Vec.Doubles d -> {
                double k = ((Number) c).doubleValue();
                double[] x = d.v();
                for (int i = 0; i < n; i++) o[i] = test(op, Double.compare(x[i], k));
            }
            case Vec.Strs s -> {
                String k = (String) c;
                String[] x = s.v();
                // Strings come out of a dictionary-encoded chunk, so equal values are usually the same
                // object; checking identity first skips most of the character comparisons.
                for (int i = 0; i < n; i++) o[i] = test(op, x[i] == k ? 0 : x[i].compareTo(k));
            }
            case Vec.Bools bb -> {
                boolean k = (Boolean) c;
                for (int i = 0; i < n; i++) o[i] = test(op, Boolean.compare(bb.v()[i], k));
            }
        }
        return new Vec.Bools(o);
    }

    static boolean test(Op op, int c) {
        return switch (op) {
            case EQ -> c == 0; case NE -> c != 0; case LT -> c < 0; case LE -> c <= 0; case GT -> c > 0; case GE -> c >= 0;
            default -> throw new IllegalStateException(op.toString());
        };
    }

    static double[] asDoubles(Vec v) {
        if (v instanceof Vec.Doubles d) return d.v();
        long[] l = ((Vec.Longs) v).v();
        double[] o = new double[l.length];
        for (int i = 0; i < l.length; i++) o[i] = l[i];
        return o;
    }
}
