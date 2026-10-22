package quarry;

/** Column types. Quarry has no NULLs; see README for what that means for aggregates. */
public enum DataType {
    LONG, DOUBLE, STRING, BOOLEAN;

    public boolean isNumeric() {
        return this == LONG || this == DOUBLE;
    }
}
