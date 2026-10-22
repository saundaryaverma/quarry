package quarry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import quarry.exec.Executor;
import quarry.plan.Optimizer;
import quarry.storage.CsvImporter;
import quarry.storage.Field;

/** Command-line interface. Run with no arguments for usage. */
public final class Cli {
    private Cli() {}

    static final String USAGE = """
            Usage:
              quarry import <file.csv> <table> [--data DIR] [--row-group N]
              quarry query "<sql>" [--data DIR] [--threads N] [--no-optimize] [--stats]
              quarry shell [--data DIR]
              quarry bench [--rows N] [--threads N]

            Tables are stored as DIR/<table>.qry (DIR defaults to ./data).
            Prefix a query with EXPLAIN to see the optimized plan instead of running it.
            """;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { System.out.print(USAGE); return; }
        Path data = Path.of(opt(args, "--data", "data"));
        try {
            switch (args[0]) {
                case "import" -> {
                    if (args.length < 3) throw new QueryException("import needs a CSV file and a table name");
                    Files.createDirectories(data);
                    int rg = Integer.parseInt(opt(args, "--row-group", "65536"));
                    Path out = new Engine(data).tablePath(args[2]);
                    List<Field> schema = CsvImporter.importCsv(Path.of(args[1]), out, rg);
                    System.out.println("Wrote " + out + " with columns " + schema);
                }
                case "query" -> {
                    if (args.length < 2) throw new QueryException("query needs a SQL string");
                    Optimizer.Rules rules = has(args, "--no-optimize") ? Optimizer.Rules.NONE : Optimizer.Rules.ALL;
                    int threads = Integer.parseInt(opt(args, "--threads", String.valueOf(Runtime.getRuntime().availableProcessors())));
                    run(new Engine(data), args[1], new Engine.Options(rules, threads), has(args, "--stats"));
                }
                case "shell" -> shell(new Engine(data));
                case "bench" -> Benchmark.main(args);
                default -> System.out.print(USAGE);
            }
        } catch (QueryException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    static void run(Engine engine, String sql, Engine.Options options, boolean stats) {
        Engine.Result r = engine.execute(sql, options);
        System.out.println(r.format(50));
        if (stats && r.stats() != null) {
            Executor.Stats s = r.stats();
            System.out.printf("%.1f ms, %d of %d row groups skipped by zone maps, %d had no match, %,d rows scanned, %,d bytes read%n",
                    r.nanos() / 1e6, s.rowGroupsSkipped(), s.rowGroups(), s.rowGroupsNoMatch(), s.rowsScanned(), s.bytesRead());
        }
    }

    private static void shell(Engine engine) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Quarry shell. One query per line; \\q to quit.");
        String line;
        while (true) {
            System.out.print("quarry> ");
            System.out.flush();
            if ((line = in.readLine()) == null || line.strip().equals("\\q")) break;
            if (line.isBlank()) continue;
            try {
                run(engine, line, Engine.Options.defaults(), true);
            } catch (QueryException e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    static String opt(String[] args, String name, String dflt) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(name)) return args[i + 1];
        return dflt;
    }

    static boolean has(String[] args, String name) {
        for (String a : args) if (a.equals(name)) return true;
        return false;
    }
}
