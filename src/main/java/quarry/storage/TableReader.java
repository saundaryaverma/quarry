package quarry.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import quarry.DataType;

/**
 * Reads .qry files. Opening a file reads only the footer. Column chunks are read on demand,
 * one (row group, column) at a time, with positional reads that are safe to call from many
 * threads at once. bytesRead() reports how much column data queries have actually pulled from disk.
 */
public final class TableReader implements AutoCloseable {
    private final FileChannel channel;
    private final List<Field> schema;
    private final int[] rowCounts;
    private final long[][] offsets;
    private final int[][] lengths;
    private final Stats[][] stats;
    private final AtomicLong bytesRead = new AtomicLong();

    public TableReader(Path path) throws IOException {
        channel = FileChannel.open(path, StandardOpenOption.READ);
        long size = channel.size();
        ByteBuffer tail = read(size - 8, 8);
        int footerLen = tail.getInt();
        byte[] magic = new byte[4];
        tail.get(magic);
        if (!Arrays.equals(magic, TableWriter.MAGIC)) throw new IOException("Not a Quarry file: " + path);
        ByteBuffer f = read(size - 8 - footerLen, footerLen);
        int ncols = f.getInt();
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < ncols; i++) fields.add(new Field(readString(f), DataType.values()[f.get()]));
        schema = List.copyOf(fields);
        int ngroups = f.getInt();
        rowCounts = new int[ngroups];
        offsets = new long[ngroups][ncols];
        lengths = new int[ngroups][ncols];
        stats = new Stats[ngroups][ncols];
        for (int g = 0; g < ngroups; g++) {
            rowCounts[g] = f.getInt();
            for (int c = 0; c < ncols; c++) {
                offsets[g][c] = f.getLong();
                lengths[g][c] = f.getInt();
                DataType t = schema.get(c).type();
                stats[g][c] = new Stats(readStat(f, t), readStat(f, t));
            }
        }
    }

    public List<Field> schema() { return schema; }

    public int rowGroupCount() { return rowCounts.length; }

    public int rowCount(int group) { return rowCounts[group]; }

    public long totalRows() {
        long n = 0;
        for (int r : rowCounts) n += r;
        return n;
    }

    public Stats stats(int group, int column) { return stats[group][column]; }

    public int columnIndex(String name) {
        for (int i = 0; i < schema.size(); i++) if (schema.get(i).name().equalsIgnoreCase(name)) return i;
        return -1;
    }

    public long bytesRead() { return bytesRead.get(); }

    public void resetBytesRead() { bytesRead.set(0); }

    public Column readColumn(int group, int column) throws IOException {
        ByteBuffer b = read(offsets[group][column], lengths[group][column]);
        bytesRead.addAndGet(lengths[group][column]);
        int n = rowCounts[group];
        return switch (schema.get(column).type()) {
            case LONG -> {
                long[] v = new long[n];
                b.asLongBuffer().get(v);
                yield new Column.Longs(v);
            }
            case DOUBLE -> {
                double[] v = new double[n];
                b.asDoubleBuffer().get(v);
                yield new Column.Doubles(v);
            }
            case STRING -> {
                int dictSize = b.getInt();
                String[] dict = new String[dictSize];
                for (int i = 0; i < dictSize; i++) dict[i] = readString(b);
                String[] v = new String[n];
                for (int i = 0; i < n; i++) v[i] = dict[b.getInt()];
                yield new Column.Strings(v);
            }
            default -> throw new IllegalStateException();
        };
    }

    private ByteBuffer read(long position, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) {
            int r = channel.read(buf, position + buf.position());
            if (r < 0) throw new IOException("Unexpected end of file");
        }
        return buf.flip();
    }

    private static String readString(ByteBuffer b) {
        byte[] bytes = new byte[b.getInt()];
        b.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Comparable<?> readStat(ByteBuffer b, DataType t) {
        return switch (t) {
            case LONG -> b.getLong();
            case DOUBLE -> b.getDouble();
            case STRING -> readString(b);
            default -> throw new IllegalStateException();
        };
    }

    @Override
    public void close() throws IOException { channel.close(); }
}
