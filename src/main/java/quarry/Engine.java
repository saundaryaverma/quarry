package quarry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import quarry.exec.Executor;
import quarry.exec.Vec;
import quarry.plan.Binder;
import quarry.plan.Optimizer;
import quarry.plan.Plan;
import quarry.sql.Ast;
import quarry.sql.Parser;
import quarry.storage.TableReader;

/**
 * Entry point for running SQL. Tables live in one directory, one file per table: sales.qry is
 * the table "sales". A query goes through parse, bind, optimize, execute.
 */
public final class Engine {
    private final Path dataDir;

    public record Options(Optimizer.Rules rules, int threads) {
        public static Options defaults() {
            return new Options(Optimizer.Rules.ALL, Runtime.getRuntime().availableProcessors());
        }
    }

    /** Rows come back column by column. For EXPLAIN, a single STRING column "plan". */
    public record Result(List<String> names, List<Vec> columns, Executor.Stats stats, long nanos) {
        public int rows() { return columns.isEmpty() ? 0 : columns.get(0).size(); }

        public Object get(int row, int col) { return columns.get(col).get(row); }

        public List<List<Object>> rowList() {
            List<List<Object>> out = new ArrayList<>();
            for (int r = 0; r < rows(); r++) {
                List<Object> row = new ArrayList<>();
                for (Vec c : columns) row.add(c.get(r));
                out.add(row);
            }
            return out;
        }

        /** A plain-text table, like psql prints. */
        public String format(int maxRows) {
            int shown = Math.min(rows(), maxRows);
            List<String[]> cells = new ArrayList<>();
            cells.add(names.toArray(String[]::new));
            for (int r = 0; r < shown; r++) {
                String[] row = new String[columns.size()];
                for (int c = 0; c < columns.size(); c++) row[c] = show(get(r, c));
                cells.add(row);
            }
            int[] w = new int[names.size()];
            for (String[] row : cells) for (int c = 0; c < row.length; c++) w[c] = Math.max(w[c], row[c].length());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cells.size(); i++) {
                String[] row = cells.get(i);
                StringBuilder line = new StringBuilder();
                for (int c = 0; c < row.length; c++) {
                    if (c > 0) line.append(" | ");
                    boolean right = i > 0 && columns.get(c).type().isNumeric();
                    line.append(pad(row[c], w[c], right));
                }
                sb.append(line.toString().stripTrailing()).append('\n');
                if (i == 0) {
                    for (int c = 0; c < row.length; c++) { if (c > 0) sb.append("-+-"); sb.append("-".repeat(w[c])); }
                    sb.append('\n');
                }
            }
            sb.append('(').append(rows()).append(rows() == 1 ? " row" : " rows");
            if (shown < rows()) sb.append(", first ").append(shown).append(" shown");
            sb.append(')');
            return sb.toString();
        }

        private static String show(Object v) {
            if (v instanceof Double d) {
                if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.format("%.1f", d);
                return String.format("%.4f", d).replaceAll("0+$", "");
            }
            return String.valueOf(v);
        }

        private static String pad(String s, int w, boolean right) {
            String sp = " ".repeat(w - s.length());
            return right ? sp + s : s + sp;
        }
    }

    public Engine(Path dataDir) {
        this.dataDir = dataDir;
    }

    public Path tablePath(String table) {
        return dataDir.resolve(table.toLowerCase() + ".qry");
    }

    public Result execute(String sql) {
        return execute(sql, Options.defaults());
    }

    public Result execute(String sql, Options options) {
        long start = System.nanoTime();
        Ast.Select q = Parser.parse(sql);
        Path path = tablePath(q.table());
        if (!Files.exists(path)) throw new QueryException("Unknown table '" + q.table() + "' (looked for " + path + ")");
        try (TableReader reader = new TableReader(path)) {
            Plan plan = Optimizer.optimize(Binder.bind(q, reader.schema()), options.rules());
            if (q.explain()) {
                List<String> lines = List.of(Plan.explain(plan).split("\n"));
                return new Result(List.of("plan"), List.of(new Vec.Strs(lines.toArray(String[]::new))), null, System.nanoTime() - start);
            }
            Executor.Output out = Executor.run(plan, reader, options.threads());
            return new Result(out.names(), out.columns(), out.stats(), System.nanoTime() - start);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
