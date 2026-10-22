package quarry.plan;

import quarry.DataType;
import quarry.plan.Expr.Op;

/**
 * Row-at-a-time arithmetic and comparison on boxed values. Used for constant folding, zone maps,
 * and the reference interpreter in the tests. The vectorized executor implements the same rules
 * on whole columns at once, and the differential tests check that the two agree.
 *
 * Rules: + - * on two LONGs give a LONG (Java overflow semantics); anything with a DOUBLE gives a
 * DOUBLE; / always gives a DOUBLE (so 7 / 2 = 3.5, and x / 0 follows IEEE: Infinity or NaN).
 */
public final class Scalar {
    private Scalar() {}

    public static Object binary(Op op, DataType resultType, Object a, Object b) {
        switch (op) {
            case AND: return (Boolean) a && (Boolean) b;
            case OR: return (Boolean) a || (Boolean) b;
            default:
        }
        if (op.isArithmetic()) {
            if (resultType == DataType.LONG) {
                long x = (Long) a, y = (Long) b;
                return switch (op) { case ADD -> x + y; case SUB -> x - y; case MUL -> x * y; default -> throw new IllegalStateException(); };
            }
            double x = ((Number) a).doubleValue(), y = ((Number) b).doubleValue();
            return switch (op) { case ADD -> x + y; case SUB -> x - y; case MUL -> x * y; case DIV -> x / y; default -> throw new IllegalStateException(); };
        }
        int c = compare(a, b);
        return switch (op) {
            case EQ -> c == 0; case NE -> c != 0; case LT -> c < 0; case LE -> c <= 0; case GT -> c > 0; case GE -> c >= 0;
            default -> throw new IllegalStateException();
        };
    }

    /** Compares two values of compatible types. Mixed LONG/DOUBLE compare numerically. */
    public static int compare(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) return Long.compare(x, y);
        if (a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue());
        if (a instanceof String x && b instanceof String y) return x.compareTo(y);
        if (a instanceof Boolean x && b instanceof Boolean y) return Boolean.compare(x, y);
        throw new IllegalArgumentException("Can't compare " + a + " and " + b);
    }
}
