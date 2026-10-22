package quarry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A minimal test runner, so the project builds with nothing but a JDK. Runs every static
 * method whose name starts with "test" in the listed classes and reports failures.
 */
public final class TestRunner {
    static final List<Class<?>> SUITES = List.of(ParserTest.class, BinderTest.class, StorageTest.class,
            ZoneMapTest.class, OptimizerTest.class, ExecutorTest.class, DifferentialTest.class);

    public static void main(String[] args) {
        int passed = 0, failed = 0;
        for (Class<?> suite : SUITES) {
            Method[] methods = suite.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method m : methods) {
                if (!m.getName().startsWith("test") || !Modifier.isStatic(m.getModifiers())) continue;
                String name = suite.getSimpleName() + "." + m.getName();
                long t = System.nanoTime();
                try {
                    m.setAccessible(true);
                    m.invoke(null);
                    passed++;
                    System.out.printf("  ok    %-55s %6.0f ms%n", name, (System.nanoTime() - t) / 1e6);
                } catch (InvocationTargetException e) {
                    failed++;
                    System.out.printf("  FAIL  %s%n", name);
                    Throwable c = e.getCause();
                    System.out.println("        " + c);
                    for (StackTraceElement el : c.getStackTrace()) {
                        if (el.getClassName().startsWith("quarry")) System.out.println("          at " + el);
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ---- assertions -------------------------------------------------------------------------

    static void check(boolean cond, String message) {
        if (!cond) throw new AssertionError(message);
    }

    static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
    }

    /** Asserts that the code throws a QueryException whose message contains the given text. */
    static void fails(Runnable r, String contains) {
        try {
            r.run();
        } catch (QueryException e) {
            if (!e.getMessage().contains(contains)) throw new AssertionError("error <" + e.getMessage() + "> should mention <" + contains + ">");
            return;
        }
        throw new AssertionError("expected a QueryException mentioning <" + contains + ">");
    }
}
