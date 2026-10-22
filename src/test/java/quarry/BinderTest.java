package quarry;

import static quarry.TestRunner.*;

import java.util.List;

import quarry.plan.Binder;
import quarry.plan.Plan;
import quarry.sql.Parser;
import quarry.storage.Field;

class BinderTest {
    static final List<Field> SCHEMA = List.of(new Field("id", DataType.LONG), new Field("price", DataType.DOUBLE),
            new Field("name", DataType.STRING));

    static Plan bind(String sql) { return Binder.bind(Parser.parse(sql), SCHEMA); }

    static void testColumnsAreCaseInsensitive() {
        Plan p = bind("SELECT ID, Price FROM t");
        Plan.Project pr = (Plan.Project) p;
        eq(List.of("id", "price"), pr.names());
    }

    static void testTypeErrors() {
        fails(() -> bind("SELECT nope FROM t"), "Unknown column 'nope'");
        fails(() -> bind("SELECT name + 1 FROM t"), "Can't use +");
        fails(() -> bind("SELECT id FROM t WHERE name > 3"), "Can't compare STRING with LONG");
        fails(() -> bind("SELECT id FROM t WHERE id"), "WHERE must be a true/false condition");
        fails(() -> bind("SELECT SUM(name) FROM t"), "SUM needs a number");
        fails(() -> bind("SELECT id FROM t WHERE SUM(id) > 1"), "aren't allowed here");
        fails(() -> bind("SELECT SUM(SUM(id)) FROM t"), "aren't allowed here");
        fails(() -> bind("SELECT MEDIAN(id) FROM t"), "Unknown function");
        fails(() -> bind("SELECT SUM(*) FROM t"), "only COUNT(*)");
    }

    static void testGroupingRules() {
        fails(() -> bind("SELECT name, COUNT(*) FROM t"), "must be in GROUP BY");
        fails(() -> bind("SELECT * FROM t GROUP BY name"), "SELECT *");
        fails(() -> bind("SELECT name FROM t GROUP BY id + 1"), "column names only");
        bind("SELECT name, COUNT(*), SUM(price) / COUNT(*) FROM t GROUP BY name");  // fine
    }

    static void testOrderBy() {
        fails(() -> bind("SELECT id FROM t ORDER BY 2"), "out of range");
        fails(() -> bind("SELECT id FROM t ORDER BY price"), "ORDER BY must name");
        Plan.Sort s = (Plan.Sort) bind("SELECT id, price * 2 AS p2 FROM t ORDER BY p2 DESC, 1");
        eq(1, s.keys().get(0).column());
        eq(0, s.keys().get(1).column());
        // an ORDER BY expression that matches a select item
        Plan.Sort s2 = (Plan.Sort) bind("SELECT id, SUM(price) FROM t GROUP BY id ORDER BY SUM(price)");
        eq(1, s2.keys().get(0).column());
    }

    static void testDivisionIsAlwaysDouble() {
        Plan.Project pr = (Plan.Project) bind("SELECT id / 2, id * 2 FROM t");
        eq(DataType.DOUBLE, pr.exprs().get(0).type());
        eq(DataType.LONG, pr.exprs().get(1).type());
    }
}
