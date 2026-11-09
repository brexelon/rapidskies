package us.rapidskies.common.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import us.rapidskies.common.testing.TestServices;

@EnabledIf("us.rapidskies.common.testing.TestServices#dockerAvailable")
class MigratorIT {

    /** Serves migration scripts from memory. */
    private static ClassLoader scripts(Map<String, String> files) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                String s = files.get(name);
                return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static int applied(Database db, String module) throws Exception {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM rapid_schema_history WHERE module = ?")) {
                ps.setString(1, module);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    @Test
    void appliesOnceThenVerifiesChecksums() throws Exception {
        Database db = TestServices.database();
        String dir = "db/migration/it_demo/";
        ClassLoader v1 = scripts(Map.of(
                dir + "index", "V1__demo.sql\n",
                dir + "V1__demo.sql", "CREATE TABLE it_demo (id INT PRIMARY KEY);\nINSERT INTO it_demo VALUES (1);\n"));
        db.migrate(v1, "it_demo");
        db.migrate(v1, "it_demo"); // second run is a no-op
        assertEquals(1, applied(db, "it_demo"));

        ClassLoader v2 = scripts(Map.of(
                dir + "index", "V1__demo.sql\nV2__more.sql\n",
                dir + "V1__demo.sql", "CREATE TABLE it_demo (id INT PRIMARY KEY);\nINSERT INTO it_demo VALUES (1);\n",
                dir + "V2__more.sql", "INSERT INTO it_demo VALUES (2);"));
        db.migrate(v2, "it_demo");
        assertEquals(2, applied(db, "it_demo"));

        ClassLoader edited = scripts(Map.of(
                dir + "index", "V1__demo.sql\n",
                dir + "V1__demo.sql", "CREATE TABLE it_demo (id BIGINT PRIMARY KEY);\n"));
        assertThrows(IllegalStateException.class, () -> db.migrate(edited, "it_demo"),
                "an edited, already-applied script stops startup");
    }

    @Test
    void coreSchemaIsApplied() throws Exception {
        Database db = TestServices.database();
        assertEquals(Migrator.discover(getClass().getClassLoader(), "core").size(), applied(db, "core"));
    }
}
