package quarry.exec;

import java.util.Arrays;
import java.util.List;

import quarry.DataType;
import quarry.storage.Column;

/**
 * A column of values flowing between operators. Like storage.Column, but it can also hold the
 * true/false results of a predicate, and it is what the vectorized evaluator works on: every
 * operation runs as a tight loop over a primitive array instead of one boxed value at a time.
 */
public sealed interface Vec permits Vec.Longs, Vec.Doubles, Vec.Strs, Vec.Bools {
    int size();

    DataType type();

    /** Boxed value at row i. Used for printing, hashing group keys, and tests, never in hot loops. */
    Object get(int i);

    Vec gather(int[] rows, int count);

    static Vec of(Column c) {
        return switch (c) {
            case Column.Longs l -> new Longs(l.values());
            case Column.Doubles d -> new Doubles(d.values());
            case Column.Strings s -> new Strs(s.values());
        };
    }

    static Vec constant(Object value, DataType type, int n) {
        return switch (type) {
            case LONG -> { long[] a = new long[n]; Arrays.fill(a, (Long) value); yield new Longs(a); }
            case DOUBLE -> { double[] a = new double[n]; Arrays.fill(a, ((Number) value).doubleValue()); yield new Doubles(a); }
            case STRING -> { String[] a = new String[n]; Arrays.fill(a, (String) value); yield new Strs(a); }
            case BOOLEAN -> { boolean[] a = new boolean[n]; Arrays.fill(a, (Boolean) value); yield new Bools(a); }
        };
    }

    /** Builds a vector from boxed values, e.g. the keys of a hash table. */
    static Vec fromObjects(DataType type, List<?> values) {
        int n = values.size();
        return switch (type) {
            case LONG -> { long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = (Long) values.get(i); yield new Longs(a); }
            case DOUBLE -> { double[] a = new double[n]; for (int i = 0; i < n; i++) a[i] = (Double) values.get(i); yield new Doubles(a); }
            case STRING -> { String[] a = new String[n]; for (int i = 0; i < n; i++) a[i] = (String) values.get(i); yield new Strs(a); }
            case BOOLEAN -> { boolean[] a = new boolean[n]; for (int i = 0; i < n; i++) a[i] = (Boolean) values.get(i); yield new Bools(a); }
        };
    }

    static Vec concat(DataType type, List<Vec> parts) {
        int n = 0;
        for (Vec v : parts) n += v.size();
        int pos = 0;
        switch (type) {
            case LONG -> {
                long[] a = new long[n];
                for (Vec v : parts) { long[] s = ((Longs) v).v(); System.arraycopy(s, 0, a, pos, s.length); pos += s.length; }
                return new Longs(a);
            }
            case DOUBLE -> {
                double[] a = new double[n];
                for (Vec v : parts) { double[] s = ((Doubles) v).v(); System.arraycopy(s, 0, a, pos, s.length); pos += s.length; }
                return new Doubles(a);
            }
            case STRING -> {
                String[] a = new String[n];
                for (Vec v : parts) { String[] s = ((Strs) v).v(); System.arraycopy(s, 0, a, pos, s.length); pos += s.length; }
                return new Strs(a);
            }
            default -> {
                boolean[] a = new boolean[n];
                for (Vec v : parts) { boolean[] s = ((Bools) v).v(); System.arraycopy(s, 0, a, pos, s.length); pos += s.length; }
                return new Bools(a);
            }
        }
    }

    record Longs(long[] v) implements Vec {
        public int size() { return v.length; }
        public DataType type() { return DataType.LONG; }
        public Object get(int i) { return v[i]; }
        public Vec gather(int[] rows, int count) {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) out[i] = v[rows[i]];
            return new Longs(out);
        }
    }

    record Doubles(double[] v) implements Vec {
        public int size() { return v.length; }
        public DataType type() { return DataType.DOUBLE; }
        public Object get(int i) { return v[i]; }
        public Vec gather(int[] rows, int count) {
            double[] out = new double[count];
            for (int i = 0; i < count; i++) out[i] = v[rows[i]];
            return new Doubles(out);
        }
    }

    record Strs(String[] v) implements Vec {
        public int size() { return v.length; }
        public DataType type() { return DataType.STRING; }
        public Object get(int i) { return v[i]; }
        public Vec gather(int[] rows, int count) {
            String[] out = new String[count];
            for (int i = 0; i < count; i++) out[i] = v[rows[i]];
            return new Strs(out);
        }
    }

    record Bools(boolean[] v) implements Vec {
        public int size() { return v.length; }
        public DataType type() { return DataType.BOOLEAN; }
        public Object get(int i) { return v[i]; }
        public Vec gather(int[] rows, int count) {
            boolean[] out = new boolean[count];
            for (int i = 0; i < count; i++) out[i] = v[rows[i]];
            return new Bools(out);
        }
    }
}
