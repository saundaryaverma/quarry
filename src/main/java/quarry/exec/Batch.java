package quarry.exec;

import java.util.HashMap;
import java.util.Map;

/**
 * A slice of rows, stored column by column and looked up by column name. rows is kept separately
 * because a batch can have no columns at all: SELECT COUNT(*) reads no column data.
 */
public record Batch(int rows, Map<String, Vec> columns) {

    public Vec column(String name) {
        Vec v = columns.get(name);
        if (v == null) throw new IllegalStateException("Column " + name + " was not read; the planner pruned it");
        return v;
    }

    /** Keeps only the selected rows. */
    public Batch gather(int[] sel, int count) {
        Map<String, Vec> out = new HashMap<>();
        for (Map.Entry<String, Vec> e : columns.entrySet()) out.put(e.getKey(), e.getValue().gather(sel, count));
        return new Batch(count, out);
    }
}
