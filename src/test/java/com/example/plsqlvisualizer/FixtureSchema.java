package com.example.plsqlvisualizer;

import com.example.plsqlvisualizer.db.DictionaryClient;
import com.example.plsqlvisualizer.extract.DictionarySnapshot;
import com.example.plsqlvisualizer.graph.IrBuilder;
import com.example.plsqlvisualizer.model.Ir;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Assumptions;

/**
 * Connects the tests to the fixture schema built by
 * {@code scripts/oracle-bootstrap.sh}.
 *
 * <p>The extractor's whole job is reading a real data dictionary, so these tests
 * need a real database — there is nothing meaningful to mock. When Oracle is not
 * running the tests are skipped rather than failed, so a plain {@code mvn test}
 * on a laptop without Docker still passes.
 */
public final class FixtureSchema {

    public static final String URL = System.getenv()
            .getOrDefault("ORACLE_URL", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1");
    public static final String USER = System.getenv().getOrDefault("ORACLE_USER", "tscope_test");
    public static final String PASSWORD = System.getenv().getOrDefault("ORACLE_PASSWORD", "tscope");

    /** The fixture entry point — the procedure every other fixture hangs off. */
    public static final String ENTRY_POINT = "PKG_ORDER.SUBMIT";

    private static Ir cached;

    private FixtureSchema() {
    }

    /** Skips the calling test when the fixture database is not reachable. */
    public static void requireDatabase() {
        try (var ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
            // reachable
        } catch (SQLException e) {
            Assumptions.abort("Fixture Oracle not reachable at " + URL
                    + " — run ./scripts/oracle-bootstrap.sh. (" + e.getMessage() + ")");
        }
    }

    /** The IR extracted from the fixture schema, built once for the whole suite. */
    public static Ir ir() throws SQLException {
        requireDatabase();
        if (cached == null) {
            try (DictionaryClient client = DictionaryClient.connect(URL, USER, PASSWORD)) {
                cached = new IrBuilder(new DictionarySnapshot(client)).build(ENTRY_POINT);
            }
        }
        return cached;
    }
}
