package quarry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import quarry.plan.Optimizer.Rules;
import quarry.storage.Column;
import quarry.storage.Field;
import quarry.storage.TableWriter;

/**
 * Generates a synthetic sales table and times a few queries with the optimizer off, on, and on
 * with more threads. It also checks that every configuration returns the same answer.
 *
 * Usage: quarry bench [--rows N] [--threads N] [--row-group N] [--runs N]
 */
public final class Benchmark {
    private Benchmark() {}

    static final String[] REGIONS = {"north", "south", "east", "west", "central", "pacific", "mountain", "atlantic"};
    static final String[] CATEGORIES = {"grocery", "electronics", "apparel", "home", "toys", "books", "garden", "sports",
            "beauty", "auto", "pharmacy", "office", "pets", "music", "games", "tools", "baby", "jewelry", "shoes",
            "outdoor", "kitchen", "crafts", "travel", "health"};

    static final String[][] QUERIES = {
            {"full scan + group by", "SELECT region, COUNT(*) AS n, SUM(amount) AS total, AVG(qty) AS avg_qty FROM sales GROUP BY region ORDER BY region"},
            {"one week of data", "SELECT category, SUM(amount * qty) AS revenue FROM sales WHERE day >= 180 AND day < 187 GROUP BY category ORDER BY revenue DESC LIMIT 5"},
            {"rare matches, wide output", "SELECT id, day, region, category, qty, amount FROM sales WHERE amount > 999.99 ORDER BY id LIMIT 10"},
            {"two-column filter", "SELECT COUNT(*) FROM sales WHERE region = 'west' AND qty >= 9"},
    };

    public static void main(String[] args) throws IOException {
        long rows = Long.parseLong(Cli.opt(args, "--rows", "10000000"));
        int maxThreads = Integer.parseInt(Cli.opt(args, "--threads", String.valueOf(Runtime.getRuntime().availableProcessors())));
        int rowGroup = Integer.parseInt(Cli.opt(args, "--row-group", "65536"));
        int runs = Integer.parseInt(Cli.opt(args, "--runs", "5"));

        Path dir = Files.createTempDirectory("quarry-bench");
        Path file = dir.resolve("sales.qry");
        long t0 = System.nanoTime();
        generate(file, rows, rowGroup, 42);
        System.out.printf("Generated %,d rows in %.1f s (%,d MB on disk, row groups of %,d)%n%n",
                rows, (System.nanoTime() - t0) / 1e9, Files.size(file) >> 20, rowGroup);

        Engine engine = new Engine(dir);
        record Config(String name, Engine.Options options) {}
        List<Config> configs = new ArrayList<>(List.of(
                new Config("no optimizer, 1 thread", new Engine.Options(Rules.NONE, 1)),
                new Config("optimizer, 1 thread", new Engine.Options(Rules.ALL, 1))));
        if (maxThreads > 1) configs.add(new Config("optimizer, " + maxThreads + " threads", new Engine.Options(Rules.ALL, maxThreads)));

        System.out.println("| Query | Configuration | Median time | Data read | Row groups skipped |");
        System.out.println("|---|---|---:|---:|---:|");
        for (String[] q : QUERIES) {
            Engine.Result baseline = null;
            for (Config c : configs) {
                for (int i = 0; i < 2; i++) engine.execute(q[1], c.options());  // JIT warm-up
                long[] times = new long[runs];
                Engine.Result r = null;
                for (int i = 0; i < runs; i++) {
                    r = engine.execute(q[1], c.options());
                    times[i] = r.nanos();
                }
                Arrays.sort(times);
                if (baseline == null) baseline = r;
                else if (!sameAnswer(baseline, r)) {
                    throw new IllegalStateException("Configurations disagree on: " + q[1] + "\n" + baseline.format(50) + "\nvs\n" + r.format(50));
                }
                System.out.printf("| %s | %s | %,.0f ms | %,.1f MB | %d of %d |%n", q[0], c.name(), times[runs / 2] / 1e6,
                        r.stats().bytesRead() / 1048576.0, r.stats().rowGroupsSkipped(), r.stats().rowGroups());
            }
        }
        System.out.println("\nAll configurations returned the same answers (doubles to 1 part in a billion).");
        Files.delete(file);
        Files.delete(dir);
    }

    /**
     * Same rows in the same order. Doubles may differ in the last few bits: a parallel SUM adds the
     * same numbers in a different order, and floating-point addition isn't associative.
     */
    static boolean sameAnswer(Engine.Result a, Engine.Result b) {
        List<List<Object>> x = a.rowList(), y = b.rowList();
        if (x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i++) {
            for (int j = 0; j < x.get(i).size(); j++) {
                Object u = x.get(i).get(j), v = y.get(i).get(j);
                if (u instanceof Double d && v instanceof Double e) {
                    if (Math.abs(d - e) > 1e-9 * Math.max(Math.abs(d), Math.abs(e)) && !d.equals(e)) return false;
                } else if (!u.equals(v)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * id counts up, day goes from 0 to 364 in order (so the table is sorted by time, as event data
     * usually is), region, category, qty and amount are random. amount is rounded to cents.
     */
    static void generate(Path file, long rows, int rowGroup, long seed) throws IOException {
        List<Field> schema = List.of(new Field("id", DataType.LONG), new Field("day", DataType.LONG),
                new Field("region", DataType.STRING), new Field("category", DataType.STRING),
                new Field("qty", DataType.LONG), new Field("amount", DataType.DOUBLE));
        SplittableRandom rnd = new SplittableRandom(seed);
        try (TableWriter w = new TableWriter(file, schema)) {
            for (long start = 0; start < rows; start += rowGroup) {
                int n = (int) Math.min(rowGroup, rows - start);
                long[] id = new long[n], day = new long[n], qty = new long[n];
                String[] region = new String[n], cat = new String[n];
                double[] amount = new double[n];
                for (int i = 0; i < n; i++) {
                    long row = start + i;
                    id[i] = row;
                    day[i] = row * 365 / rows;
                    region[i] = REGIONS[rnd.nextInt(REGIONS.length)];
                    cat[i] = CATEGORIES[rnd.nextInt(CATEGORIES.length)];
                    qty[i] = 1 + rnd.nextInt(10);
                    amount[i] = Math.round(rnd.nextDouble(1.0, 1000.0) * 100) / 100.0;
                }
                w.writeRowGroup(List.of(new Column.Longs(id), new Column.Longs(day), new Column.Strings(region),
                        new Column.Strings(cat), new Column.Longs(qty), new Column.Doubles(amount)));
            }
        }
    }
}
