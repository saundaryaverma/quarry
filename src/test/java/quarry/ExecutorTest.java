package quarry;

import static quarry.TestRunner.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import quarry.Engine.Options;
import quarry.plan.Optimizer.Rules;
import quarry.storage.Column;
import quarry.storage.Field;
import quarry.storage.TableWriter;

class ExecutorTest {
    static final Rules PUSHDOWN_OFF = new Rules(true, false, true, false);
    static final Rules ZONE_MAPS_OFF = new Rules(true, true, true, false);

    /** 20,000 rows in row groups of 1,000. day is sorted (0..99); bucket is random-looking; note is wide. */
    static Engine engine() throws Exception {
        Path dir = Files.createTempDirectory("quarry-test");
        int n = 20_000;
        long[] id = new long[n], day = new long[n], bucket = new long[n];
        String[] note = new String[n];
        for (int i = 0; i < n; i++) {
            id[i] = i;
            day[i] = i / 200;
            bucket[i] = (i * 7919L) % 1000;
            note[i] = "note-" + i + "-" + "x".repeat(40);
        }
        TableWriter.write(dir.resolve("events.qry"),
                List.of(new Field("id", DataType.LONG), new Field("day", DataType.LONG), new Field("bucket", DataType.LONG), new Field("note", DataType.STRING)),
                List.of(new Column.Longs(id), new Column.Longs(day), new Column.Longs(bucket), new Column.Strings(note)), 1000);
        return new Engine(dir);
    }

    static void testZoneMapsSkipRowGroupsOnASortedColumn() throws Exception {
        Engine e = engine();
        String q = "SELECT COUNT(*) FROM events WHERE day >= 40 AND day < 45";
        Engine.Result on = e.execute(q, new Options(Rules.ALL, 2)), off = e.execute(q, new Options(ZONE_MAPS_OFF, 2));
        eq(1000L, on.get(0, 0));
        eq(1000L, off.get(0, 0));
        eq(19, on.stats().rowGroupsSkipped());     // only group 8 (rows 8000-8999, days 40-44) can match
        eq(0, off.stats().rowGroupsSkipped());
        check(on.stats().bytesRead() * 10 < off.stats().bytesRead(), "zone maps should cut reads by >10x");
    }

    static void testLateMaterializationReadsWideColumnsOnlyWhereRowsMatch() throws Exception {
        Engine e = engine();
        // id < 3 matches in the first row group only; zone maps are off, so every group's id column is read,
        // but the wide note column should be read for just that one group.
        String q = "SELECT id, note FROM events WHERE id < 3";
        Engine.Result late = e.execute(q, new Options(ZONE_MAPS_OFF, 1));
        Engine.Result eager = e.execute(q, new Options(PUSHDOWN_OFF, 1));
        eq(3, late.rows());
        eq(late.rowList(), eager.rowList());
        eq(19, late.stats().rowGroupsNoMatch());
        check(late.stats().bytesRead() * 5 < eager.stats().bytesRead(),
                "late " + late.stats().bytesRead() + " vs eager " + eager.stats().bytesRead());
    }

    static void testLimitWithoutOrderStopsEarly() throws Exception {
        Engine e = engine();
        Engine.Result r = e.execute("SELECT id FROM events LIMIT 5", new Options(Rules.ALL, 2));
        eq(List.of(List.of(0L), List.of(1L), List.of(2L), List.of(3L), List.of(4L)), r.rowList());
        check(r.stats().rowsScanned() <= 2000, "scanned " + r.stats().rowsScanned());
    }

    static void testAggregatesOverNoRows() throws Exception {
        Engine e = engine();
        eq(List.of(List.of(0L, 0L)), e.execute("SELECT COUNT(*), SUM(day) FROM events WHERE day > 1000").rowList());
        eq(0, e.execute("SELECT COUNT(*), MAX(day) FROM events WHERE day > 1000").rows());
        eq(0, e.execute("SELECT day, COUNT(*) FROM events WHERE day > 1000 GROUP BY day").rows());
    }

    static void testGroupByOrderByLimit() throws Exception {
        Engine e = engine();
        Engine.Result r = e.execute("SELECT day, COUNT(*) AS n, MIN(id), MAX(note) FROM events WHERE bucket < 500 GROUP BY day ORDER BY day DESC LIMIT 2");
        eq(2, r.rows());
        eq(99L, r.get(0, 0));
        eq(98L, r.get(1, 0));
        eq(List.of("day", "n", "MIN(id)", "MAX(note)"), r.names());
    }

    static void testExplainAndErrors() throws Exception {
        Engine e = engine();
        Engine.Result r = e.execute("EXPLAIN SELECT id FROM events WHERE day = 3");
        eq("plan", r.names().get(0));
        check(r.get(1, 0).toString().contains("zone_maps=on"), r.format(10));
        fails(() -> e.execute("SELECT id FROM nope"), "Unknown table 'nope'");
    }

    static void testFormat() throws Exception {
        Engine e = engine();
        String out = e.execute("SELECT day, COUNT(*) AS n FROM events WHERE day < 2 GROUP BY day ORDER BY day").format(10);
        eq("""
                day | n
                ----+----
                  0 | 200
                  1 | 200
                (2 rows)""", out);
    }
}
