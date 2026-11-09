package us.rapidskies.common.grant;

import java.time.Instant;
import java.util.Objects;

/**
 * How long the permission system holds a rank or permission for a player: until an
 * instant, or permanently. Absence of a hold is {@code Optional.empty()}.
 */
public record Hold(Instant until) {

    public static final Hold PERMANENT = new Hold(null);

    public static Hold until(Instant until) {
        return new Hold(Objects.requireNonNull(until));
    }

    public boolean permanent() {
        return until == null;
    }

    /** False for a timed hold that has already run out. */
    public boolean liveAt(Instant now) {
        return permanent() || until.isAfter(now);
    }
}
