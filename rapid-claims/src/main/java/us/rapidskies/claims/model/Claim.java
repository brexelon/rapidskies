package us.rapidskies.claims.model;

import java.time.Instant;
import java.util.UUID;

/** One claimed chunk. Immutable: a change replaces it in the cache. */
public record Claim(ChunkKey chunk, Owner owner, int flags, UUID claimedBy, Instant claimedAt) {

    public boolean flag(Flag flag) {
        return flag.in(flags);
    }

    public Claim withFlags(int newFlags) {
        return new Claim(chunk, owner, newFlags, claimedBy, claimedAt);
    }

    public Claim withOwner(Owner newOwner) {
        return new Claim(chunk, newOwner, flags, claimedBy, claimedAt);
    }
}
