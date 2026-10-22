package quarry.storage;

import quarry.DataType;

/** An in-memory column of values. One of three concrete layouts, chosen by type. */
public sealed interface Column permits Column.Longs, Column.Doubles, Column.Strings {
    int size();

    DataType type();

    Stats stats();

    /** Returns a new column holding only the rows at the given indexes. */
    Column gather(int[] rows, int count);

    record Longs(long[] values) implements Column {
        public int size() { return values.length; }
        public DataType type() { return DataType.LONG; }
        public Stats stats() {
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            for (long v : values) { if (v < min) min = v; if (v > max) max = v; }
            return values.length == 0 ? new Stats(0L, 0L) : new Stats(min, max);
        }
        public Column gather(int[] rows, int count) {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) out[i] = values[rows[i]];
            return new Longs(out);
        }
    }

    record Doubles(double[] values) implements Column {
        public int size() { return values.length; }
        public DataType type() { return DataType.DOUBLE; }
        public Stats stats() {
            // Double.compare, not <, so NaN counts as the largest value. The executor compares the
            // same way; if the two disagreed, zone maps could skip a row group holding a NaN match.
            if (values.length == 0) return new Stats(0.0, 0.0);
            double min = values[0], max = values[0];
            for (double v : values) { if (Double.compare(v, min) < 0) min = v; if (Double.compare(v, max) > 0) max = v; }
            return new Stats(min, max);
        }
        public Column gather(int[] rows, int count) {
            double[] out = new double[count];
            for (int i = 0; i < count; i++) out[i] = values[rows[i]];
            return new Doubles(out);
        }
    }

    record Strings(String[] values) implements Column {
        public int size() { return values.length; }
        public DataType type() { return DataType.STRING; }
        public Stats stats() {
            String min = null, max = null;
            for (String v : values) {
                if (min == null || v.compareTo(min) < 0) min = v;
                if (max == null || v.compareTo(max) > 0) max = v;
            }
            return min == null ? new Stats("", "") : new Stats(min, max);
        }
        public Column gather(int[] rows, int count) {
            String[] out = new String[count];
            for (int i = 0; i < count; i++) out[i] = values[rows[i]];
            return new Strings(out);
        }
    }
}
