package quarry;

import static quarry.TestRunner.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import quarry.storage.Column;
import quarry.storage.CsvImporter;
import quarry.storage.Field;
import quarry.storage.TableReader;
import quarry.storage.TableWriter;

class StorageTest {
    static void testRoundTripWithRowGroupsAndStats() throws Exception {
        Path f = Files.createTempFile("quarry", ".qry");
        int n = 1000;
        long[] ids = new long[n];
        double[] xs = new double[n];
        String[] ss = new String[n];
        for (int i = 0; i < n; i++) { ids[i] = i * 3L - 500; xs[i] = i % 7 == 0 ? Double.NaN : i / 4.0; ss[i] = "v" + (i % 13) + "é"; }
        List<Field> schema = List.of(new Field("id", DataType.LONG), new Field("x", DataType.DOUBLE), new Field("s", DataType.STRING));
        TableWriter.write(f, schema, List.of(new Column.Longs(ids), new Column.Doubles(xs), new Column.Strings(ss)), 128);
        try (TableReader r = new TableReader(f)) {
            eq(schema, r.schema());
            eq(8, r.rowGroupCount());
            eq((long) n, r.totalRows());
            eq(1000 - 7 * 128, r.rowCount(7));
            int row = 0;
            for (int g = 0; g < r.rowGroupCount(); g++) {
                long[] gi = ((Column.Longs) r.readColumn(g, 0)).values();
                double[] gx = ((Column.Doubles) r.readColumn(g, 1)).values();
                String[] gs = ((Column.Strings) r.readColumn(g, 2)).values();
                for (int i = 0; i < gi.length; i++, row++) {
                    eq(ids[row], gi[i]);
                    check(Double.compare(xs[row], gx[i]) == 0, "double mismatch at " + row);
                    eq(ss[row], gs[i]);
                }
                eq(gi[0], r.stats(g, 0).min());
                eq(gi[gi.length - 1], r.stats(g, 0).max());
            }
            // NaN must count as the largest double, or zone maps would skip it (see Column.Doubles.stats)
            eq(Double.NaN, r.stats(0, 1).max());
            check(r.bytesRead() > 0, "bytes counted");
        }
        Files.delete(f);
    }

    static void testFileWithoutMagicIsRejected() throws Exception {
        Path f = Files.createTempFile("quarry", ".qry");
        Files.writeString(f, "this is not a quarry table at all");
        try (TableReader r = new TableReader(f)) {
            throw new AssertionError("opened a bad file");
        } catch (java.io.IOException e) {
            check(e.getMessage().contains("Not a Quarry file"), e.getMessage());
        }
        Files.delete(f);
    }

    static void testCsvImportInfersTypesAndHandlesQuotes() throws Exception {
        Path csv = Files.createTempFile("quarry", ".csv"), out = Files.createTempFile("quarry", ".qry");
        Files.writeString(csv, "﻿id,score,label,mixed\n1,2.5,\"a, b\",7\n2,3,\"say \"\"hi\"\"\",x\n\n3,-1e2,plain,9\n");
        List<Field> schema = CsvImporter.importCsv(csv, out, 2);
        eq(List.of(new Field("id", DataType.LONG), new Field("score", DataType.DOUBLE),
                new Field("label", DataType.STRING), new Field("mixed", DataType.STRING)), schema);
        try (TableReader r = new TableReader(out)) {
            eq(2, r.rowGroupCount());
            String[] labels = ((Column.Strings) r.readColumn(0, 2)).values();
            eq("a, b", labels[0]);
            eq("say \"hi\"", labels[1]);
            eq(-100.0, ((Column.Doubles) r.readColumn(1, 1)).values()[0]);
        }
        Files.writeString(csv, "a,b\n1,2\n3\n");
        try {
            CsvImporter.importCsv(csv, out, 2);
            throw new AssertionError("ragged CSV accepted");
        } catch (java.io.IOException e) {
            check(e.getMessage().contains("Line 3"), e.getMessage());
        }
        Files.delete(csv);
        Files.delete(out);
    }
}
