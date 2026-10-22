package quarry.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import quarry.DataType;
import quarry.QueryException;
import quarry.plan.Expr.*;
import quarry.sql.Ast;
import quarry.storage.Field;

/** Resolves names and types in a parsed query and builds the initial logical plan. */
public final class Binder {
    private final List<Field> schema;

    private Binder(List<Field> schema) { this.schema = schema; }

    public static Plan bind(Ast.Select q, List<Field> schema) {
        return new Binder(schema).plan(q);
    }

    private Plan plan(Ast.Select q) {
        List<String> all = schema.stream().map(Field::name).toList();
        Plan plan = new Plan.Scan(q.table(), schema, all, null, false);

        if (q.where() != null) {
            Expr pred = bind(q.where(), false);
            if (pred.type() != DataType.BOOLEAN) throw new QueryException("WHERE must be a true/false condition, got " + pred.type());
            plan = new Plan.Filter(plan, pred);
        }

        List<Expr> exprs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Ast.Node> astItems = new ArrayList<>();
        for (Ast.SelectItem item : q.items()) {
            if (item.expr() instanceof Ast.Star) {
                if (item.alias() != null) throw new QueryException("* can't have an alias");
                for (Field f : schema) {
                    exprs.add(new Col(f.name(), f.type()));
                    names.add(f.name());
                    astItems.add(new Ast.Ident(f.name()));
                }
            } else {
                Expr e = bind(item.expr(), true);
                exprs.add(e);
                names.add(item.alias() != null ? item.alias() : e instanceof Col c ? c.name() : e.sql());
                astItems.add(item.expr());
            }
        }

        List<Col> groupBy = new ArrayList<>();
        for (Ast.Node g : q.groupBy()) {
            if (!(bind(g, false) instanceof Col c)) throw new QueryException("GROUP BY supports column names only");
            if (!groupBy.contains(c)) groupBy.add(c);
        }
        boolean aggregate = !groupBy.isEmpty() || exprs.stream().anyMatch(Binder::hasAgg);
        if (aggregate) {
            if (q.items().stream().anyMatch(i -> i.expr() instanceof Ast.Star)) {
                throw new QueryException("SELECT * can't be combined with GROUP BY or aggregates");
            }
            List<Agg> aggs = new ArrayList<>();
            List<Expr> rewritten = new ArrayList<>();
            for (Expr e : exprs) rewritten.add(replaceAggs(e, aggs, groupBy));
            plan = new Plan.Aggregate(plan, groupBy, aggs);
            exprs = rewritten;
        }
        plan = new Plan.Project(plan, exprs, names);

        if (!q.orderBy().isEmpty()) {
            List<Plan.SortKey> keys = new ArrayList<>();
            for (Ast.OrderItem o : q.orderBy()) keys.add(new Plan.SortKey(orderColumn(o.expr(), names, astItems), o.descending()));
            plan = new Plan.Sort(plan, keys);
        }
        if (q.limit() != null) plan = new Plan.Limit(plan, q.limit());
        return plan;
    }

    private int orderColumn(Ast.Node e, List<String> names, List<Ast.Node> astItems) {
        if (e instanceof Ast.Num n && !n.text().contains(".")) {
            int k = Integer.parseInt(n.text());
            if (k < 1 || k > names.size()) throw new QueryException("ORDER BY position " + k + " is out of range");
            return k - 1;
        }
        if (e instanceof Ast.Ident id) {
            for (int i = 0; i < names.size(); i++) if (names.get(i).equalsIgnoreCase(id.name())) return i;
        }
        for (int i = 0; i < astItems.size(); i++) if (Objects.equals(astItems.get(i), e)) return i;
        throw new QueryException("ORDER BY must name a selected column, alias, or position");
    }

    private static boolean hasAgg(Expr e) {
        return switch (e) {
            case Agg a -> true;
            case Bin b -> hasAgg(b.left()) || hasAgg(b.right());
            case Not n -> hasAgg(n.operand());
            case Neg n -> hasAgg(n.operand());
            default -> false;
        };
    }

    private Expr replaceAggs(Expr e, List<Agg> aggs, List<Col> groupBy) {
        return switch (e) {
            case Agg a -> {
                int i = aggs.indexOf(a);
                if (i < 0) { aggs.add(a); i = aggs.size() - 1; }
                yield new Col("$agg" + i, a.type());
            }
            case Col c -> {
                if (!groupBy.contains(c)) throw new QueryException("Column " + c.name() + " must be in GROUP BY or inside an aggregate");
                yield c;
            }
            case Bin b -> new Bin(b.op(), replaceAggs(b.left(), aggs, groupBy), replaceAggs(b.right(), aggs, groupBy), b.type());
            case Not n -> new Not(replaceAggs(n.operand(), aggs, groupBy));
            case Neg n -> new Neg(replaceAggs(n.operand(), aggs, groupBy));
            case Lit l -> l;
        };
    }

    private Expr bind(Ast.Node n, boolean aggsAllowed) {
        return switch (n) {
            case Ast.Ident id -> {
                for (Field f : schema) if (f.name().equalsIgnoreCase(id.name())) yield new Col(f.name(), f.type());
                throw new QueryException("Unknown column '" + id.name() + "'. Columns: " + schema.stream().map(Field::name).toList());
            }
            case Ast.Num num -> num.text().contains(".")
                    ? new Lit(Double.parseDouble(num.text()), DataType.DOUBLE)
                    : new Lit(parseLong(num.text()), DataType.LONG);
            case Ast.Str s -> new Lit(s.value(), DataType.STRING);
            case Ast.Bool b -> new Lit(b.value(), DataType.BOOLEAN);
            case Ast.Unary u -> {
                Expr x = bind(u.operand(), aggsAllowed);
                if (u.op().equals("NOT")) {
                    if (x.type() != DataType.BOOLEAN) throw new QueryException("NOT needs a true/false value");
                    yield new Not(x);
                }
                if (!x.type().isNumeric()) throw new QueryException("Can't negate a " + x.type());
                yield new Neg(x);
            }
            case Ast.Binary b -> binary(Op.of(b.op()), bind(b.left(), aggsAllowed), bind(b.right(), aggsAllowed));
            case Ast.Call c -> {
                if (!aggsAllowed) throw new QueryException("Aggregates like " + c.name() + " aren't allowed here");
                AggFn fn;
                try { fn = AggFn.valueOf(c.name()); } catch (IllegalArgumentException e) { throw new QueryException("Unknown function " + c.name()); }
                if (c.arg() instanceof Ast.Star) {
                    if (fn != AggFn.COUNT) throw new QueryException(fn + "(*) isn't valid; only COUNT(*)");
                    yield new Agg(fn, null, DataType.LONG);
                }
                Expr arg = bind(c.arg(), false);
                yield switch (fn) {
                    case COUNT -> new Agg(fn, arg, DataType.LONG);
                    case SUM -> { requireNumeric(arg, "SUM"); yield new Agg(fn, arg, arg.type()); }
                    case AVG -> { requireNumeric(arg, "AVG"); yield new Agg(fn, arg, DataType.DOUBLE); }
                    case MIN, MAX -> {
                        if (arg.type() == DataType.BOOLEAN) throw new QueryException(fn + " doesn't work on true/false values");
                        yield new Agg(fn, arg, arg.type());
                    }
                };
            }
            case Ast.Star s -> throw new QueryException("* is only allowed as SELECT * or COUNT(*)");
        };
    }

    private static long parseLong(String text) {
        try { return Long.parseLong(text); } catch (NumberFormatException e) { throw new QueryException("Number too large: " + text); }
    }

    private static void requireNumeric(Expr e, String fn) {
        if (!e.type().isNumeric()) throw new QueryException(fn + " needs a number, got " + e.type());
    }

    static Expr binary(Op op, Expr l, Expr r) {
        if (op == Op.AND || op == Op.OR) {
            if (l.type() != DataType.BOOLEAN || r.type() != DataType.BOOLEAN) throw new QueryException(op + " needs true/false values on both sides");
            return new Bin(op, l, r, DataType.BOOLEAN);
        }
        if (op.isArithmetic()) {
            if (!l.type().isNumeric() || !r.type().isNumeric()) throw new QueryException("Can't use " + op.symbol + " on " + l.type() + " and " + r.type());
            DataType t = op == Op.DIV || l.type() == DataType.DOUBLE || r.type() == DataType.DOUBLE ? DataType.DOUBLE : DataType.LONG;
            return new Bin(op, l, r, t);
        }
        boolean ok = (l.type().isNumeric() && r.type().isNumeric()) || l.type() == r.type();
        if (!ok) throw new QueryException("Can't compare " + l.type() + " with " + r.type());
        if (l.type() == DataType.BOOLEAN && op != Op.EQ && op != Op.NE) throw new QueryException("True/false values only support = and !=");
        return new Bin(op, l, r, DataType.BOOLEAN);
    }
}
