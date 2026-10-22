package quarry.plan;

import quarry.DataType;

/** A resolved, type-checked expression. */
public sealed interface Expr permits Expr.Col, Expr.Lit, Expr.Bin, Expr.Not, Expr.Neg, Expr.Agg {
    DataType type();

    String sql();

    enum Op {
        ADD("+"), SUB("-"), MUL("*"), DIV("/"),
        EQ("="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">="),
        AND("AND"), OR("OR");

        public final String symbol;

        Op(String symbol) { this.symbol = symbol; }

        public boolean isArithmetic() { return ordinal() <= DIV.ordinal(); }

        public boolean isComparison() { return ordinal() >= EQ.ordinal() && ordinal() <= GE.ordinal(); }

        public static Op of(String s) {
            for (Op o : values()) if (o.symbol.equals(s)) return o;
            throw new IllegalArgumentException(s);
        }

        /** The comparison with its sides swapped: 5 < x is the same as x > 5. */
        public Op flip() {
            return switch (this) {
                case LT -> GT; case LE -> GE; case GT -> LT; case GE -> LE; default -> this;
            };
        }
    }

    enum AggFn { COUNT, SUM, AVG, MIN, MAX }

    record Col(String name, DataType type) implements Expr {
        public String sql() { return name; }
    }

    /** A constant. value is a Long, Double, String, or Boolean. */
    record Lit(Object value, DataType type) implements Expr {
        public String sql() {
            if (value instanceof String s) return "'" + s.replace("'", "''") + "'";
            if (value instanceof Boolean b) return b ? "TRUE" : "FALSE";
            return String.valueOf(value);
        }
    }

    record Bin(Op op, Expr left, Expr right, DataType type) implements Expr {
        public String sql() { return "(" + left.sql() + " " + op.symbol + " " + right.sql() + ")"; }
    }

    record Not(Expr operand) implements Expr {
        public DataType type() { return DataType.BOOLEAN; }
        public String sql() { return "NOT " + operand.sql(); }
    }

    record Neg(Expr operand) implements Expr {
        public DataType type() { return operand.type(); }
        public String sql() { return "-" + operand.sql(); }
    }

    /** An aggregate call. arg is null for COUNT(*). */
    record Agg(AggFn fn, Expr arg, DataType type) implements Expr {
        public String sql() { return fn + "(" + (arg == null ? "*" : arg.sql()) + ")"; }
    }
}
