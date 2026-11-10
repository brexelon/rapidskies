package us.rapidskies.market.custody;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;

/**
 * A journal of items that are between an inventory and the database (PLAN.md §4.2,
 * anti-duplication), so a crash at any moment neither loses nor duplicates an item.
 *
 * <p>An inventory lives in the server's memory and the auction house in MariaDB, so moving an
 * item between them can't be one transaction. Each move is written here first, on the main
 * thread and synced to disk, and deleted once it's settled:
 * <ul>
 *   <li>{@link Kind#OUTGOING}, listing: the item is taken from the inventory, journaled, and
 *       the player's data saved (so a restart can't bring it back), then the listing is
 *       inserted. If the server dies before the insert commits, {@link #recover} finds no
 *       listing with the entry's token and puts the item in the seller's collection bin.</li>
 *   <li>{@link Kind#INCOMING}, collecting: the item is journaled, its bin row deleted, then it's
 *       put into the inventory and the player's data saved. If the server dies after the
 *       delete but before the save, {@link #recover} finds the row gone and puts it back.</li>
 * </ul>
 * The one thing left open is a crash between the player-data save and the journal delete,
 * two consecutive statements on the main thread.
 *
 * <p>Each server has its own journal (in its plugin folder), recovered when it starts and
 * retried every few seconds while the database is unreachable. Entries in use by a running
 * move are {@link #busy} and left alone by recovery.
 */
public final class Custody {

    public enum Kind {
        /** From an inventory into a new listing. */
        OUTGOING,
        /** From a collection bin into an inventory. */
        INCOMING
    }

    /**
     * One item in transit. {@code id} names the journal file: an OUTGOING entry's id is the
     * listing token, an INCOMING entry's is {@code in-<storedId>}. {@code item} is the
     * serialized stack.
     */
    public record Entry(String id, Kind kind, String mode, UUID owner, byte[] item, String itemName, int amount,
                        long storedId) {

        public static Entry outgoing(UUID token, String mode, UUID owner, byte[] item, String itemName, int amount) {
            return new Entry(token.toString(), Kind.OUTGOING, mode, owner, item, itemName, amount, 0);
        }

        public static Entry incoming(long storedId, String mode, UUID owner, byte[] item, String itemName,
                                     int amount) {
            return new Entry("in-" + storedId, Kind.INCOMING, mode, owner, item, itemName, amount, storedId);
        }

        /** The listing token of an OUTGOING entry. */
        public UUID token() {
            return UUID.fromString(id);
        }
    }

    /** What recovery does with an entry, against the database. */
    public interface Resolver {
        /** Whether the listing with this token exists (in any state). */
        boolean listed(UUID token) throws Exception;

        /** Whether the bin row still exists (the collection never happened). */
        boolean stillStored(long storedId) throws Exception;

        /** Puts the item in the owner's bin under {@code token}, once (an INSERT on a unique token). */
        void restore(String token, Entry entry) throws Exception;
    }

    private record Json(String id, String kind, String mode, String owner, String item, String itemName, int amount,
                        long storedId) {
    }

    private static final Gson GSON = new Gson();

    private final Path dir;
    private final Logger logger;
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    public Custody(Path dir, Logger logger) throws IOException {
        this.dir = dir;
        this.logger = logger;
        Files.createDirectories(dir);
    }

    /**
     * Writes {@code entry} durably and marks it busy. Main thread; throws if the disk write
     * fails, in which case the caller must not move the item.
     */
    public void hold(Entry entry) throws IOException {
        Json j = new Json(entry.id(), entry.kind().name(), entry.mode(), entry.owner().toString(),
                Base64.getEncoder().encodeToString(entry.item()), entry.itemName(), entry.amount(), entry.storedId());
        Path tmp = dir.resolve(entry.id() + ".tmp");
        Files.writeString(tmp, GSON.toJson(j), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
        try {
            Files.move(tmp, file(entry.id()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file(entry.id()), StandardCopyOption.REPLACE_EXISTING);
        }
        busy.add(entry.id());
    }

    /** The move settled: forgets the entry. */
    public void release(String id) {
        busy.remove(id);
        try {
            Files.deleteIfExists(file(id));
        } catch (IOException e) {
            // Harmless: recovery settles it again (both checks are idempotent).
            logger.warn("Couldn't delete custody entry {}: {}", id, e.getMessage());
        }
    }

    /** The move stopped without settling (database error, player gone): recovery takes over. */
    public void unbusy(String id) {
        busy.remove(id);
    }

    public boolean busy(String id) {
        return busy.contains(id);
    }

    /**
     * Whether an entry with this id exists at all, busy or waiting for recovery. A new move
     * of the same item must wait until it's settled: starting one would overwrite the entry
     * and, on release, delete the only record of an earlier unfinished move.
     */
    public boolean pending(String id) {
        return busy.contains(id) || Files.exists(file(id));
    }

    /** Entries on disk, busy or not. */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    Json j = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Json.class);
                    out.add(new Entry(j.id(), Kind.valueOf(j.kind()), j.mode(), UUID.fromString(j.owner()),
                            Base64.getDecoder().decode(j.item()), j.itemName(), j.amount(), j.storedId()));
                } catch (IOException | JsonParseException | IllegalArgumentException | NullPointerException e) {
                    logger.error("Unreadable custody entry {} is kept for staff to look at: {}", f, e.toString());
                }
            }
        } catch (IOException e) {
            logger.error("Couldn't list the custody journal {}: {}", dir, e.getMessage());
        }
        return out;
    }

    /**
     * Settles every entry that isn't busy, for {@code mode} only (a journal copied between
     * servers of different modes is left alone). Blocking: async only. Returns how many were
     * settled; entries the database couldn't answer for stay for the next run.
     */
    public int recover(String mode, Resolver resolver) {
        int settled = 0;
        for (Entry e : entries()) {
            if (busy.contains(e.id()) || !mode.equals(e.mode())) {
                continue;
            }
            try {
                switch (e.kind()) {
                    case OUTGOING -> {
                        if (!resolver.listed(e.token())) {
                            resolver.restore("custody:" + e.id(), e);
                            logger.info("Custody: listing {} of {} never went through; returned {} x{} to their bin",
                                    e.id(), e.owner(), e.itemName(), e.amount());
                        }
                    }
                    case INCOMING -> {
                        if (!resolver.stillStored(e.storedId())) {
                            resolver.restore("restore:" + e.storedId(), e);
                            logger.info("Custody: {} x{} taken from {}'s bin never reached them; put it back",
                                    e.itemName(), e.amount(), e.owner());
                        }
                    }
                }
                // Checked again: a move may have started on this entry meanwhile (it can't, as
                // ids are unique per move, but recovery must never delete a live entry).
                if (!busy.contains(e.id())) {
                    Files.deleteIfExists(file(e.id()));
                    settled++;
                }
            } catch (Exception ex) {
                logger.warn("Custody entry {} not settled yet: {}", e.id(), ex.toString());
            }
        }
        return settled;
    }

    private Path file(String id) {
        return dir.resolve(id + ".json");
    }
}
