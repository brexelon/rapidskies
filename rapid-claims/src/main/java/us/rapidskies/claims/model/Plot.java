package us.rapidskies.claims.model;

import java.util.UUID;

/** A town chunk handed to a resident (PLAN.md §4.1), who controls it with their own trust list. */
public record Plot(ChunkKey chunk, UUID town, UUID resident) {
}
