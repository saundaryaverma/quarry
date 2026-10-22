package quarry;

import static quarry.TestRunner.*;

import java.util.List;

import quarry.plan.Binder;
import quarry.plan.Optimizer;
import quarry.plan.Optimizer.Rules;
import quarry.plan.Plan;
import quarry.sql.Parser;
import quarry.storage.Field;

class OptimizerTest {
    static final List<Field> SCHEMA = List.of(new Field("id", DataType.LONG), new Field("day", DataType.LONG),
            new Field("region", DataType.STRING), new Field("amount", DataType.DOUBLE), new Field("note", DataType.STRING));

    static String explain(String sql, Rules rules) {
        return Plan.explain(Optimizer.optimize(Binder.bind(Parser.parse(sql), SCHEMA), rules));
    }

    static void testAllRules() {
        String plan = explain("SELECT region, SUM(amount) FROM t WHERE day >= 10 * 3 AND 1 = 1 GROUP BY region", Rules.ALL);
        eq("""
                Project region, $agg0 AS SUM(amount)
                  Aggregate group_by=[region] aggs=[SUM(amount)]
                    Scan t columns=[day, region, amount] filter=(day >= 30) zone_maps=on
                """, plan);
    }

    static void testNoRules() {
        String plan = explain("SELECT region, SUM(amount) FROM t WHERE day >= 10 * 3 GROUP BY region", Rules.NONE);
        eq("""
                Project region, $agg0 AS SUM(amount)
                  Aggregate group_by=[region] aggs=[SUM(amount)]
                    Filter (day >= (10 * 3))
                      Scan t columns=[id, day, region, amount, note]
                """, plan);
    }

    static void testPruningKeepsSortAndProjectColumns() {
        String plan = explain("SELECT id, amount * 2 AS dbl FROM t WHERE region = 'west' ORDER BY dbl DESC LIMIT 3", Rules.ALL);
        eq("""
                Limit 3
                  Sort [#2 DESC]
                    Project id, (amount * 2) AS dbl
                      Scan t columns=[id, region, amount] filter=(region = 'west') zone_maps=on
                """, plan);
    }

    static void testCountStarReadsNoColumns() {
        String plan = explain("SELECT COUNT(*) FROM t", Rules.ALL);
        check(plan.contains("Scan t columns=[]"), plan);
    }

    static void testFoldingToFalseAndNegatives() {
        check(explain("SELECT id FROM t WHERE id > 5 AND 2 < 1", Rules.ALL).contains("filter=FALSE"), "AND FALSE folds");
        check(explain("SELECT id FROM t WHERE id > -(3 + 4)", Rules.ALL).contains("filter=(id > -7)"), "negation folds");
        check(!explain("SELECT id FROM t WHERE 1 = 1 OR id > 5", Rules.ALL).contains("filter"), "OR TRUE removes the filter");
    }

    static void testZoneMapsCanBeTurnedOffSeparately() {
        String plan = explain("SELECT id FROM t WHERE day = 4", new Rules(true, true, true, false));
        check(plan.contains("filter=(day = 4)") && !plan.contains("zone_maps"), plan);
    }
}
