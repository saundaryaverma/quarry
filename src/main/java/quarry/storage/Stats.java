package quarry.storage;

/**
 * Min and max of one column in one row group. The scanner uses these to skip whole row groups
 * that can't contain a matching row (a "zone map"), without reading their data.
 * Values are Long, Double, or String depending on the column type.
 */
public record Stats(Comparable<?> min, Comparable<?> max) {}
