package quarry.exec;

import java.util.function.Function;

import quarry.plan.Expr;
import quarry.plan.Expr.*;
import quarry.plan.Scalar;
import quarry.storage.Stats;

/**
 * Decides from a row group's min/max statistics whether a predicate could be true for any row in it.
 * It must never answer "no" when a row matches (that would drop results); answering "maybe" when
 * nothing matches only costs a wasted read. So anything it doesn't understand is a "maybe".
 */
public final class ZoneMaps {
    private ZoneMaps() {}

    /** stats: column name to that column's stats in the row group being considered. */
    public static boolean mayMatch(Expr pred, Function<String, Stats> stats) {
        return switch (pred) {
            case Lit l -> !Boolean.FALSE.equals(l.value());
            case Bin b when b.op() == Op.AND -> mayMatch(b.left(), stats) && mayMatch(b.right(), stats);
            case Bin b when b.op() == Op.OR -> mayMatch(b.left(), stats) || mayMatch(b.right(), stats);
            case Bin b when b.op().isComparison() && b.left() instanceof Col c && b.right() instanceof Lit l ->
                    range(b.op(), stats.apply(c.name()), l.value());
            case Bin b when b.op().isComparison() && b.left() instanceof Lit l && b.right() instanceof Col c ->
                    range(b.op().flip(), stats.apply(c.name()), l.value());
            default -> true;
        };
    }

    /** Could some value v with min <= v <= max satisfy "v OP k"? */
    private static boolean range(Op op, Stats s, Object k) {
        int lo = Scalar.compare(s.min(), k), hi = Scalar.compare(s.max(), k);
        return switch (op) {
            case EQ -> lo <= 0 && hi >= 0;
            case NE -> !(lo == 0 && hi == 0);  // only "every value equals k" rules it out
            case LT -> lo < 0;
            case LE -> lo <= 0;
            case GT -> hi > 0;
            case GE -> hi >= 0;
            default -> true;
        };
    }
}
