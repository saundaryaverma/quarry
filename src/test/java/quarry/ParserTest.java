package quarry;

import static quarry.TestRunner.*;

import quarry.sql.Ast;
import quarry.sql.Parser;

class ParserTest {
    static void testFullQuery() {
        Ast.Select s = Parser.parse("EXPLAIN select a, SUM(b) as total from T where a > 1 and not c = 'x' group by a order by 2 desc, a limit 10;");
        check(s.explain(), "EXPLAIN flag");
        eq("T", s.table());
        eq(2, s.items().size());
        eq("total", s.items().get(1).alias());
        eq(1, s.groupBy().size());
        eq(2, s.orderBy().size());
        check(s.orderBy().get(0).descending(), "first key descending");
        eq(10L, s.limit());
    }

    static void testPrecedence() {
        // AND binds tighter than OR, * tighter than +, comparisons tighter than NOT
        Ast.Select s = Parser.parse("SELECT a FROM t WHERE a = 1 OR b = 2 AND NOT c + d * 2 > 3");
        Ast.Binary or = (Ast.Binary) s.where();
        eq("OR", or.op());
        Ast.Binary and = (Ast.Binary) or.right();
        eq("AND", and.op());
        Ast.Unary not = (Ast.Unary) and.right();
        Ast.Binary gt = (Ast.Binary) not.operand();
        eq(">", gt.op());
        Ast.Binary plus = (Ast.Binary) gt.left();
        eq("+", plus.op());
        eq("*", ((Ast.Binary) plus.right()).op());
    }

    static void testStringEscapesAndQuotedIdentifiers() {
        Ast.Select s = Parser.parse("SELECT \"my col\" FROM t WHERE name = 'O''Brien'");
        eq(new Ast.Ident("my col"), s.items().get(0).expr());
        eq(new Ast.Str("O'Brien"), ((Ast.Binary) s.where()).right());
    }

    static void testErrors() {
        fails(() -> Parser.parse("SELECT FROM t"), "position");
        fails(() -> Parser.parse("SELECT a FROM"), "position");
        fails(() -> Parser.parse("SELECT a FROM t WHERE"), "position");
        fails(() -> Parser.parse("SELECT a FROM t LIMIT x"), "LIMIT");
        fails(() -> Parser.parse("SELECT a FROM t extra"), "Unexpected");
        fails(() -> Parser.parse("SELECT 'abc FROM t"), "string");
    }
}
