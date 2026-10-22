package quarry.storage;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import quarry.DataType;

/**
 * Writes the Quarry columnar file format (.qry), modeled loosely on Parquet:
 *
 * <pre>
 *   "QRY1"
 *   row group 0: column chunk 0, column chunk 1, ...
 *   row group 1: ...
 *   footer: schema, then for each row group its row count and, per column, offset, length, min, max
 *   footer length (int)
 *   "QRY1"
 * </pre>
 *
 * Longs and doubles are stored as fixed-width values. Strings are dictionary-encoded: each chunk
 * stores its distinct values once, then an int code per row. Because the footer records where every
 * chunk starts and what range of values it holds, a reader can fetch just the columns a query uses
 * and skip row groups whose min/max rule them out.
 */
public final class TableWriter implements AutoCloseable {
    public static final byte[] MAGIC = "QRY1".getBytes(StandardCharsets.US_ASCII);

    private final List<Field> schema;
    private final CountingStream out;
    private final DataOutputStream data;
    private final List<RowGroupMeta> rowGroups = new ArrayList<>();

    record ChunkMeta(long offset, int length, Stats stats) {}

    record RowGroupMeta(int rows, List<ChunkMeta> chunks) {}

    public TableWriter(Path path, List<Field> schema) throws IOException {
        this.schema = List.copyOf(schema);
        this.out = new CountingStream(new BufferedOutputStream(Files.newOutputStream(path), 1 << 20));
        this.data = new DataOutputStream(out);
        data.write(MAGIC);
    }

    /** Appends one row group. Every column must have the same number of rows. */
    public void writeRowGroup(List<Column> columns) throws IOException {
        if (columns.size() != schema.size()) throw new IllegalArgumentException("column count mismatch");
        int rows = columns.get(0).size();
        List<ChunkMeta> chunks = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            Column col = columns.get(c);
            if (col.size() != rows) throw new IllegalArgumentException("ragged row group");
            if (col.type() != schema.get(c).type()) throw new IllegalArgumentException("type mismatch in " + schema.get(c).name());
            long start = out.count;
            writeChunk(col);
            chunks.add(new ChunkMeta(start, (int) (out.count - start), col.stats()));
        }
        rowGroups.add(new RowGroupMeta(rows, chunks));
    }

    private void writeChunk(Column col) throws IOException {
        switch (col) {
            case Column.Longs l -> { for (long v : l.values()) data.writeLong(v); }
            case Column.Doubles d -> { for (double v : d.values()) data.writeDouble(v); }
            case Column.Strings s -> {
                Map<String, Integer> dict = new LinkedHashMap<>();
                int[] codes = new int[s.values().length];
                for (int i = 0; i < codes.length; i++) {
                    codes[i] = dict.computeIfAbsent(s.values()[i], k -> dict.size());
                }
                data.writeInt(dict.size());
                for (String v : dict.keySet()) writeString(v);
                for (int code : codes) data.writeInt(code);
            }
        }
    }

    private void writeString(String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        data.writeInt(b.length);
        data.write(b);
    }

    private void writeStat(DataType type, Comparable<?> v) throws IOException {
        switch (type) {
            case LONG -> data.writeLong((Long) v);
            case DOUBLE -> data.writeDouble((Double) v);
            case STRING -> writeString((String) v);
            default -> throw new IllegalStateException();
        }
    }

    @Override
    public void close() throws IOException {
        long footerStart = out.count;
        data.writeInt(schema.size());
        for (Field f : schema) {
            writeString(f.name());
            data.writeByte(f.type().ordinal());
        }
        data.writeInt(rowGroups.size());
        for (RowGroupMeta rg : rowGroups) {
            data.writeInt(rg.rows());
            for (int c = 0; c < schema.size(); c++) {
                ChunkMeta m = rg.chunks().get(c);
                data.writeLong(m.offset());
                data.writeInt(m.length());
                writeStat(schema.get(c).type(), m.stats().min());
                writeStat(schema.get(c).type(), m.stats().max());
            }
        }
        data.writeInt((int) (out.count - footerStart));
        data.write(MAGIC);
        data.close();
    }

    /** Writes a whole table, splitting it into row groups of the given size. */
    public static void write(Path path, List<Field> schema, List<Column> columns, int rowGroupSize) throws IOException {
        int rows = columns.isEmpty() ? 0 : columns.get(0).size();
        try (TableWriter w = new TableWriter(path, schema)) {
            for (int start = 0; start < rows; start += rowGroupSize) {
                int n = Math.min(rowGroupSize, rows - start);
                int[] idx = new int[n];
                for (int i = 0; i < n; i++) idx[i] = start + i;
                List<Column> slice = new ArrayList<>();
                for (Column c : columns) slice.add(c.gather(idx, n));
                w.writeRowGroup(slice);
            }
        }
    }

    private static final class CountingStream extends OutputStream {
        private final OutputStream inner;
        long count;

        CountingStream(OutputStream inner) { this.inner = inner; }

        @Override public void write(int b) throws IOException { inner.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { inner.write(b, off, len); count += len; }
        @Override public void flush() throws IOException { inner.flush(); }
        @Override public void close() throws IOException { inner.close(); }
    }

}
