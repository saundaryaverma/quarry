package quarry.sql;

import java.util.List;

/** The parsed (unresolved) form of a query, straight from the SQL text. */
public final class Ast {
    private Ast() {}

    public sealed interface Node permits Ident, Num, Str, Bool, Binary, Unary, Call, Star {}

    public record Ident(String name) implements Node {}

    public record Num(String text) implements Node {}

    public record Str(String value) implements Node {}

    public record Bool(boolean value) implements Node {}

    public record Binary(String op, Node left, Node right) implements Node {}

    public record Unary(String op, Node operand) implements Node {}

    /** A function call such as SUM(amount) or COUNT(*). */
    public record Call(String name, Node arg) implements Node {}

    public record Star() implements Node {}

    public record SelectItem(Node expr, String alias) {}

    public record OrderItem(Node expr, boolean descending) {}

    public record Select(boolean explain, List<SelectItem> items, String table, Node where,
                         List<Node> groupBy, List<OrderItem> orderBy, Long limit) {}
}
