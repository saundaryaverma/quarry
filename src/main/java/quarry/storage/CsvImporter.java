package quarry.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import quarry.DataType;

/**
 * Converts a CSV file (header row required) into a .qry table. Each column's type is inferred:
 * LONG if every value is an integer, DOUBLE if every value is a number, otherwise STRING.
 * Handles quoted fields with commas and doubled quotes.
 */
public final class CsvImporter {
    private CsvImporter() {}

    public static List<Field> importCsv(Path csv, Path out, int rowGroupSize) throws IOException {
        List<String> header;
        List<List<String>> cols = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(csv)) {
            String line = r.readLine();
            if (line == null) throw new IOException("Empty CSV: " + csv);
            header = split(line.replace("﻿", ""));
            for (int i = 0; i < header.size(); i++) cols.add(new ArrayList<>());
            int lineNo = 1;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                List<String> vals = split(line);
                if (vals.size() != header.size()) {
                    throw new IOException("Line " + lineNo + ": expected " + header.size() + " fields, got " + vals.size());
                }
                for (int i = 0; i < vals.size(); i++) cols.get(i).add(vals.get(i));
            }
        }
        List<Field> schema = new ArrayList<>();
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            List<String> vals = cols.get(i);
            DataType t = infer(vals);
            schema.add(new Field(header.get(i).trim(), t));
            columns.add(switch (t) {
                case LONG -> new Column.Longs(vals.stream().mapToLong(v -> Long.parseLong(v.trim())).toArray());
                case DOUBLE -> new Column.Doubles(vals.stream().mapToDouble(v -> Double.parseDouble(v.trim())).toArray());
                default -> new Column.Strings(vals.toArray(String[]::new));
            });
        }
        TableWriter.write(out, schema, columns, rowGroupSize);
        return schema;
    }

    static DataType infer(List<String> vals) {
        boolean allLong = !vals.isEmpty(), allNum = !vals.isEmpty();
        for (String v : vals) {
            String s = v.trim();
            if (allLong) {
                try { Long.parseLong(s); } catch (NumberFormatException e) { allLong = false; }
            }
            if (!allLong && allNum) {
                try { Double.parseDouble(s); } catch (NumberFormatException e) { allNum = false; }
            }
            if (!allNum) break;
        }
        return allLong ? DataType.LONG : allNum ? DataType.DOUBLE : DataType.STRING;
    }

    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else cur.append(ch);
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
