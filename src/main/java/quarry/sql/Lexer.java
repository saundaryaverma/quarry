package quarry.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import quarry.QueryException;

/** Splits SQL text into tokens. */
public final class Lexer {
    public enum Kind { KEYWORD, IDENT, NUMBER, STRING, SYMBOL, EOF }

    public record Token(Kind kind, String text, int position) {}

    static final Set<String> KEYWORDS = Set.of("SELECT", "FROM", "WHERE", "GROUP", "BY", "ORDER", "ASC", "DESC",
            "LIMIT", "AND", "OR", "NOT", "AS", "TRUE", "FALSE", "EXPLAIN");

    private Lexer() {}

    public static List<Token> tokenize(String sql) {
        List<Token> out = new ArrayList<>();
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            int start = i;
            if (Character.isLetter(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
                String word = sql.substring(start, i);
                String upper = word.toUpperCase();
                out.add(KEYWORDS.contains(upper) ? new Token(Kind.KEYWORD, upper, start) : new Token(Kind.IDENT, word, start));
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)))) {
                while (i < n && Character.isDigit(sql.charAt(i))) i++;
                if (i < n && sql.charAt(i) == '.') {
                    i++;
                    while (i < n && Character.isDigit(sql.charAt(i))) i++;
                }
                out.add(new Token(Kind.NUMBER, sql.substring(start, i), start));
            } else if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (true) {
                    if (i >= n) throw new QueryException("Unclosed string starting at position " + start);
                    char ch = sql.charAt(i);
                    if (ch == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') { sb.append('\''); i += 2; }
                    else if (ch == '\'') { i++; break; }
                    else { sb.append(ch); i++; }
                }
                out.add(new Token(Kind.STRING, sb.toString(), start));
            } else if (c == '"') {
                // Quoted identifier, for column names with spaces or that clash with keywords.
                int end = sql.indexOf('"', i + 1);
                if (end < 0) throw new QueryException("Unclosed quoted name starting at position " + start);
                if (end == i + 1) throw new QueryException("Empty quoted name at position " + start);
                out.add(new Token(Kind.IDENT, sql.substring(i + 1, end), start));
                i = end + 1;
            } else {
                String two = i + 1 < n ? sql.substring(i, i + 2) : "";
                if (two.equals("<=") || two.equals(">=") || two.equals("!=") || two.equals("<>")) {
                    out.add(new Token(Kind.SYMBOL, two.equals("<>") ? "!=" : two, start));
                    i += 2;
                } else if ("=<>+-*/(),;".indexOf(c) >= 0) {
                    out.add(new Token(Kind.SYMBOL, String.valueOf(c), start));
                    i++;
                } else {
                    throw new QueryException("Unexpected character '" + c + "' at position " + start);
                }
            }
        }
        out.add(new Token(Kind.EOF, "", n));
        return out;
    }
}
