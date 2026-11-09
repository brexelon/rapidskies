package us.rapidskies.common.db;

import java.sql.SQLException;

/** Unchecked wrapper so database failures can travel through {@code CompletableFuture}s. */
public final class DatabaseException extends RuntimeException {

    public DatabaseException(SQLException cause) {
        super(cause.getMessage(), cause);
    }

    @Override
    public synchronized SQLException getCause() {
        return (SQLException) super.getCause();
    }
}
