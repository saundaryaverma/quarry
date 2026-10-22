package quarry;

/** A problem with the user's query: bad syntax, unknown column, type mismatch. */
public class QueryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public QueryException(String message) {
        super(message);
    }
}
