# Quarry

![tests](https://github.com/saundaryaverma/quarry/actions/workflows/ci.yml/badge.svg)

Quarry is a small analytical SQL engine in Java 21, written from scratch with no dependencies. It stores tables in its own columnar file format, parses a subset of SQL, optimizes the plan, and runs it with a vectorized, multi-threaded executor.

I built it to learn how query engines decide what work to skip. Most of the code is about that: reading only the columns a query needs, skipping blocks of data whose min/max rule them out, and not loading wide columns until a row is known to match.

```
$ java -jar build/quarry.jar import examples/trips.csv trips
$ java -jar build/quarry.jar query "SELECT city, COUNT(*) AS trips, SUM(fare) AS revenue
    FROM trips WHERE day >= 3 AND miles > 1 + 1 GROUP BY city ORDER BY revenue DESC"

city     | trips | revenue
---------+-------+--------
New York |     2 |    68.0
Boston   |     2 |    35.8
Chicago  |     1 |    15.4
(3 rows)
```

## Results on 10 million rows

`java -jar build/quarry.jar bench` generates a 10M-row sales table (381 MB, 153 row groups of 65,536 rows) and times four queries three ways. Median of 5 runs after warm-up, on a 2-vCPU cloud VM (Intel Xeon 2.1 GHz), JDK 21:

| Query | No optimizer, 1 thread | Optimizer, 1 thread | Optimizer, 2 threads | Data read (no opt → opt) |
|---|---:|---:|---:|---:|
| Full scan + GROUP BY region | 588 ms | 449 ms | 238 ms | 381 → 191 MB |
| One week of data (`day >= 180 AND day < 187`) | 346 ms | 13 ms | 15 ms | 381 → 7 MB |
| Rare matches, six output columns | 293 ms | 83 ms | 79 ms | 381 → 100 MB |
| Two-column filter, `COUNT(*)` | 398 ms | 166 ms | 103 ms | 381 → 115 MB |

What's behind each row:

- **Full scan:** projection pruning reads 3 of 6 columns, halving I/O. The second thread nearly halves the time again, since row groups are independent and partial aggregates merge cheaply.
- **One week of data:** the table is sorted by `day`, as event data usually is. Zone maps skip 149 of 153 row groups without reading them: 26x faster.
- **Rare matches:** zone maps skip 113 groups whose max `amount` is too low. In the rest, only the `amount` column is read until a row matches (late materialization). Two threads don't help here; the work left is small.
- **Two-column filter:** pruning plus pushdown. There's nothing for zone maps to skip, because `region` and `qty` are random.

Every configuration's answer is checked against the others before the timing is printed.

## How a query runs

```
SQL text
  │  Lexer, Parser            recursive descent → AST
  ▼
AST
  │  Binder                   resolve names, type-check, rewrite aggregates → logical plan
  ▼
Logical plan                  Scan → Filter → Aggregate → Project → Sort → Limit
  │  Optimizer                constant folding, predicate pushdown, projection pruning, zone maps
  ▼
Optimized plan
  │  Executor                 one pipeline per row group, spread over a thread pool
  ▼
Rows
```

`EXPLAIN` shows the plan. Here is the query above, without and with the optimizer:

```
Sort [#3 DESC]                                  Sort [#3 DESC]
  Project city, $agg0 AS trips, ...               Project city, $agg0 AS trips, ...
    Aggregate group_by=[city] aggs=[...]            Aggregate group_by=[city] aggs=[...]
      Filter ((day >= 3) AND (miles > (1 + 1)))       Scan trips columns=[city, day, miles, fare]
        Scan trips columns=[trip_id, city,                 filter=((day >= 3) AND (miles > 2))
             day, miles, fare, driver]                     zone_maps=on
```

`1 + 1` was folded to `2`, the filter moved into the scan, and the scan dropped the two columns nothing uses.

### Storage (`storage/`)

A `.qry` file is split into row groups, and each row group holds one chunk per column, loosely like Parquet. Longs and doubles are fixed-width. Strings are dictionary-encoded per chunk: distinct values once, then an int code per row. The footer records each chunk's offset, length, min and max. Opening a table reads only the footer, and every later read is a positional read of one chunk, safe from many threads at once.

### Optimizer (`plan/Optimizer.java`)

Every rule can be turned off on its own. That is how the benchmark measures each one and how the tests check that none of them change results.

- **Constant folding.** Evaluates constant sub-expressions at plan time and simplifies `x AND TRUE`, `x OR TRUE`, and the like. A filter that folds to `TRUE` is removed.
- **Predicate pushdown.** Moves the WHERE clause into the scan, so it runs before the other columns are loaded.
- **Projection pruning.** Walks the plan top-down, collecting the columns each operator needs, and tells the scan to read only those. `SELECT COUNT(*)` reads no column data at all.
- **Zone maps.** Before reading a row group, the scanner checks its min/max against the predicate (`exec/ZoneMaps.java`) and skips it if no row could match. This handles `AND`, `OR`, and column-vs-constant comparisons in either order; anything else counts as "might match."

### Executor (`exec/`)

- **Vectorized evaluation.** Expressions run a column at a time over primitive arrays: one tight loop per operator, not one boxed value per row. Column-vs-constant comparisons have specialized loops, and string equality checks object identity first, since values from the same dictionary-encoded chunk share one `String` object.
- **Late materialization.** With a pushed-down predicate, the scan reads only the predicate's columns, computes which rows match, and reads the remaining columns only for row groups where something matched.
- **Parallel pipelines.** Scan, filter, and either a partial aggregate or the projection run per row group on a thread pool. Partial hash tables are merged at the end (two-phase aggregation), and sort and limit run once on the combined result. `LIMIT` without `ORDER BY` runs in waves and stops once it has enough rows.
- **Hash aggregation.** Aggregate state lives in flat primitive arrays indexed by group id, so updating a SUM is `sums[group[i]] += x[i]`.

## How it's tested

`./build.sh` compiles everything and runs 28 tests with a small built-in runner, so the project needs nothing but a JDK.

The main test is **differential**. `DifferentialTest` generates random SQL against a table built to cause trouble:

- negative zero, NaN and division by zero
- empty strings and non-ASCII text
- a sorted column, so zone maps fire

It runs each query under 7 optimizer and thread settings and checks every answer against `Reference.java`. That's a deliberately naive interpreter: it loads every row and evaluates one boxed value at a time, with no optimizer, vectors, threads, or zone maps.

How results are compared:

- Without `ORDER BY`, as multisets.
- With `ORDER BY`, by checking that the output is sorted and the sort keys match row for row.
- With `LIMIT`, by checking that every returned row exists in the full result.

CI runs 600 random queries on every push, then a 3,000-query pass (21,000 engine runs).

To check that the tests can actually fail, I planted six bugs one at a time. The tests caught every one:

| Planted bug | Caught by |
|---|---|
| Zone map treats `x > k` too aggressively | zone-map property test, differential test |
| `<=` fast path compares with `<` | differential test |
| Merging partial COUNTs overwrites instead of adding | differential test |
| Double min/max stats ignore NaN | zone-map property test |
| Projection pruning drops a needed column | differential test |
| Scanner drops row groups with exactly one match | differential test |

The NaN one was a real bug I found while writing the executor. The stats code used `<` and `>`, which ignore NaN, while comparisons use `Double.compare`, which ranks NaN above every number. So a row group containing NaN could be skipped for `x > 5` even though NaN matches. The zone-map property test brute-forces thousands of small random row groups to check that the zone map never skips one with a matching row.

## Supported SQL

```sql
[EXPLAIN] SELECT expr [AS alias], ... | *
FROM table
[WHERE condition]
[GROUP BY column, ...]
[ORDER BY output_column | alias | position [ASC|DESC], ...]
[LIMIT n]
```

- **Types:** `LONG`, `DOUBLE`, `STRING`, inferred from the CSV on import.
- **Operators:** `+ - * /`, `= != <> < <= > >=`, `AND OR NOT`, unary minus.
- **Aggregates:** `COUNT(*)`, `COUNT(x)`, `SUM`, `AVG`, `MIN`, `MAX`, usable inside expressions such as `SUM(a) / COUNT(*)`.
- **Names:** column names are case-insensitive. Use `"double quotes"` for names with spaces.

## Design decisions and limits

- **No NULLs.** This kept the executor simple. The cost shows up in aggregates over zero rows: with no `GROUP BY`, `COUNT` and `SUM` return 0, but `AVG`, `MIN` and `MAX` have no value, so those queries return no rows.
- **One table per query.** There are no joins. A hash join is the next thing I'd add, with pushdown through the join.
- **Integer arithmetic.** `+ - *` on LONGs wraps on overflow (Java semantics), and `/` always returns a DOUBLE, so `7 / 2 = 3.5`.
- **Boxed group keys.** The hash aggregate boxes its group keys, and that lookup is the biggest cost in the GROUP BY benchmark. A primitive open-addressing table, or grouping directly on dictionary codes, would be the first optimization.
- **No spilling.** The aggregate and sort keep their state in memory.
- **Sorting.** The sort is a full stable sort. A top-N heap would make `ORDER BY ... LIMIT` cheaper.

## Running it

Requires JDK 21+.

```bash
./build.sh                                   # compile, test, build build/quarry.jar
java -jar build/quarry.jar import data.csv sales
java -jar build/quarry.jar query "SELECT ... FROM sales" --stats
java -jar build/quarry.jar query "..." --no-optimize --threads 1
java -jar build/quarry.jar shell             # one query per line
java -jar build/quarry.jar bench --rows 10000000
```

`--stats` prints the time, row groups skipped, rows scanned, and bytes read.

## Layout

```
src/main/java/quarry/
  sql/       Lexer, Parser, Ast
  plan/      Binder, Expr, Plan, Optimizer, Scalar
  storage/   TableWriter, TableReader, Column, CsvImporter
  exec/      Executor, Evaluator, HashAggregate, ZoneMaps, Vec, Batch
  Engine.java, Cli.java, Benchmark.java
src/test/java/quarry/
  DifferentialTest, Reference (row-at-a-time interpreter), and unit tests per layer
```

About 2,500 lines of engine code and 1,000 lines of tests.
