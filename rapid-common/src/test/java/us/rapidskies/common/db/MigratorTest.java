package us.rapidskies.common.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MigratorTest {

    @Test
    void splitsStatementsAndDropsCommentLines() {
        String script = """
                -- header; with a semicolon
                CREATE TABLE a (id INT);
                  -- indented comment
                CREATE TABLE b (
                    name VARCHAR(10) DEFAULT 'x;y'
                );

                INSERT INTO a VALUES (1);""";
        List<String> statements = Migrator.statements(script);
        assertEquals(3, statements.size(), statements.toString());
        assertTrue(statements.get(1).contains("'x;y'"), "a ';' not at the end of a line stays in the statement");
    }

    @Test
    void coreMigrationsAreIndexedInOrder() {
        var migrations = Migrator.discover(getClass().getClassLoader(), "core");
        assertTrue(!migrations.isEmpty());
        for (int i = 0; i < migrations.size(); i++) {
            assertEquals(i + 1, migrations.get(i).version(), "versions are contiguous from 1");
        }
    }
}
