package quarry.sql;

import java.util.ArrayList;
import java.util.List;

import quarry.QueryException;
import quarry.sql.Ast.*;
import quarry.sql.Lexer.Kind;
import quarry.sql.Lexer.Token;

/**
 * Recursive-descent parser for the supported SQL subset:
 *
 * <pre>
 *   [EXPLAIN] SELECT item [AS alias], ... FROM table
 *     [WHERE expr] [GROUP BY col, ...] [ORDER BY item [ASC|DESC], ...] [LIMIT n]
 * </pre>
 *
 * Operator precedence, lowest first: OR, AND, NOT, comparisons, + -, * /, unary minus.
 */
public final class Parser {
    private final List<Token> tokens;
    private int pos;

    private Parser(List<Token> tokens) { this.tokens = tokens; }

    public static Select parse(String sql) {
        Parser p = new Parser(Lexer.tokenize(sql));
        Select s = p.select();
        p.accept(Kind.SYMBOL, ";");
        if (p.peek().kind() != Kind.EOF) throw p.error("Unexpected '" + p.peek().text() + "'");
        return s;
    }

    private Select select() {
        boolean explain = accept(Kind.KEYWORD, "EXPLAIN");
        expect(Kind.KEYWORD, "SELECT");
        List<SelectItem> items = new ArrayList<>();
        do {
            Node e = peekIs(Kind.SYMBOL, "*") ? starItem() : expr();
            String alias = null;
            if (accept(Kind.KEYWORD, "AS")) alias = expectIdent();
            else if (peek().kind() == Kind.IDENT) alias = next().text();
            items.add(new SelectItem(e, alias));
        } while (accept(Kind.SYMBOL, ","));
        expect(Kind.KEYWORD, "FROM");
        String table = expectIdent();
        Node where = accept(Kind.KEYWORD, "WHERE") ? expr() : null;
        List<Node> groupBy = new ArrayList<>();
        if (accept(Kind.KEYWORD, "GROUP")) {
            expect(Kind.KEYWORD, "BY");
            do groupBy.add(expr()); while (accept(Kind.SYMBOL, ","));
        }
        List<OrderItem> orderBy = new ArrayList<>();
        if (accept(Kind.KEYWORD, "ORDER")) {
            expect(Kind.KEYWORD, "BY");
            do {
                Node e = expr();
                boolean desc = accept(Kind.KEYWORD, "DESC");
                if (!desc) accept(Kind.KEYWORD, "ASC");
                orderBy.add(new OrderItem(e, desc));
            } while (accept(Kind.SYMBOL, ","));
        }
        Long limit = null;
        if (accept(Kind.KEYWORD, "LIMIT")) {
            Token t = next();
            if (t.kind() != Kind.NUMBER || t.text().contains(".")) throw error("LIMIT needs a whole number");
            limit = Long.parseLong(t.text());
        }
        return new Select(explain, items, table, where, groupBy, orderBy, limit);
    }

    private Node starItem() {
        next();
        return new Star();
    }

    private Node expr() { return or(); }

    private Node or() {
        Node left = and();
        while (accept(Kind.KEYWORD, "OR")) left = new Binary("OR", left, and());
        return left;
    }

    private Node and() {
        Node left = not();
        while (accept(Kind.KEYWORD, "AND")) left = new Binary("AND", left, not());
        return left;
    }

    private Node not() {
        if (accept(Kind.KEYWORD, "NOT")) return new Unary("NOT", not());
        return comparison();
    }

    private Node comparison() {
        Node left = additive();
        Token t = peek();
        if (t.kind() == Kind.SYMBOL && List.of("=", "!=", "<", "<=", ">", ">=").contains(t.text())) {
            next();
            return new Binary(t.text(), left, additive());
        }
        return left;
    }

    private Node additive() {
        Node left = multiplicative();
        while (peekIs(Kind.SYMBOL, "+") || peekIs(Kind.SYMBOL, "-")) left = new Binary(next().text(), left, multiplicative());
        return left;
    }

    private Node multiplicative() {
        Node left = unary();
        while (peekIs(Kind.SYMBOL, "*") || peekIs(Kind.SYMBOL, "/")) left = new Binary(next().text(), left, unary());
        return left;
    }

    private Node unary() {
        if (accept(Kind.SYMBOL, "-")) return new Unary("-", unary());
        return primary();
    }

    private Node primary() {
        Token t = next();
        switch (t.kind()) {
            case NUMBER: return new Num(t.text());
            case STRING: return new Str(t.text());
            case KEYWORD:
                if (t.text().equals("TRUE")) return new Bool(true);
                if (t.text().equals("FALSE")) return new Bool(false);
                throw error("Unexpected keyword " + t.text(), t);
            case IDENT:
                if (accept(Kind.SYMBOL, "(")) {
                    Node arg = peekIs(Kind.SYMBOL, "*") ? starItem() : expr();
                    expect(Kind.SYMBOL, ")");
                    return new Call(t.text().toUpperCase(), arg);
                }
                return new Ident(t.text());
            case SYMBOL:
                if (t.text().equals("(")) {
                    Node e = expr();
                    expect(Kind.SYMBOL, ")");
                    return e;
                }
                throw error("Unexpected '" + t.text() + "'", t);
            default:
                throw error("Query ended too early", t);
        }
    }

    private Token peek() { return tokens.get(pos); }

    private Token next() { return tokens.get(pos++); }

    private boolean peekIs(Kind k, String text) { return peek().kind() == k && peek().text().equals(text); }

    private boolean accept(Kind k, String text) {
        if (peekIs(k, text)) { pos++; return true; }
        return false;
    }

    private void expect(Kind k, String text) {
        if (!accept(k, text)) throw error("Expected " + text + " but found '" + peek().text() + "'");
    }

    private String expectIdent() {
        Token t = next();
        if (t.kind() != Kind.IDENT) throw error("Expected a name but found '" + t.text() + "'", t);
        return t.text();
    }

    private QueryException error(String msg) { return error(msg, peek()); }

    private QueryException error(String msg, Token t) {
        return new QueryException(msg + " (at position " + t.position() + ")");
    }
}
