package us.rapidskies.market.custody;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * The custody journal's crash recovery (PLAN.md §4.2, §11 test list: a crash in the middle
 * of listing): every combination of "the database got it" and "it didn't" ends with the item
 * in exactly one place.
 */
class CustodyTest {

    private final UUID steve = UUID.randomUUID();
    private final byte[] diamonds = {1, 2, 3, 4};

    @TempDir
    Path dir;

    /** A database stand-in: which tokens were listed, which bin rows exist, what was restored. */
    private static final class FakeDb implements Custody.Resolver {
        final Set<UUID> listed = new HashSet<>();
        final Set<Long> stored = new HashSet<>();
        final List<String> restored = new ArrayList<>();
        boolean down;

        @Override
        public boolean listed(UUID token) throws Exception {
            check();
            return listed.contains(token);
        }

        @Override
        public boolean stillStored(long storedId) throws Exception {
            check();
            return stored.contains(storedId);
        }

        @Override
        public void restore(String token, Custody.Entry entry) throws Exception {
            check();
            restored.add(token);
        }

        private void check() throws Exception {
            if (down) {
                throw new java.sql.SQLException("database down");
            }
        }
    }

    private Custody journal() throws Exception {
        return new Custody(dir, LoggerFactory.getLogger("custody-test"));
    }

    @Test
    void entriesSurviveARestartIntact() throws Exception {
        UUID token = UUID.randomUUID();
        journal().hold(Custody.Entry.outgoing(token, "horizons", steve, diamonds, "Diamond", 64));
        // A new instance is what the next start sees.
        List<Custody.Entry> after = journal().entries();
        assertEquals(1, after.size());
        Custody.Entry e = after.getFirst();
        assertEquals(token, e.token());
        assertEquals(Custody.Kind.OUTGOING, e.kind());
        assertEquals(steve, e.owner());
        assertArrayEquals(diamonds, e.item());
        assertEquals(64, e.amount());
    }

    @Test
    void aListingThatNeverCommittedGoesToTheBin() throws Exception {
        UUID token = UUID.randomUUID();
        journal().hold(Custody.Entry.outgoing(token, "horizons", steve, diamonds, "Diamond", 64));
        FakeDb db = new FakeDb();
        assertEquals(1, journal().recover("horizons", db));
        assertEquals(List.of("custody:" + token), db.restored);
        assertTrue(journal().entries().isEmpty());
    }

    @Test
    void aListingThatCommittedIsLeftAlone() throws Exception {
        UUID token = UUID.randomUUID();
        journal().hold(Custody.Entry.outgoing(token, "horizons", steve, diamonds, "Diamond", 64));
        FakeDb db = new FakeDb();
        db.listed.add(token);
        assertEquals(1, journal().recover("horizons", db));
        assertEquals(List.of(), db.restored, "the listing has the item: restoring it would duplicate it");
        assertTrue(journal().entries().isEmpty());
    }

    @Test
    void aCollectionThatTookTheRowButNeverDeliveredIsPutBack() throws Exception {
        journal().hold(Custody.Entry.incoming(42, "horizons", steve, diamonds, "Diamond", 64));
        FakeDb db = new FakeDb();
        assertEquals(1, journal().recover("horizons", db));
        assertEquals(List.of("restore:42"), db.restored);
    }

    @Test
    void aCollectionThatNeverTookTheRowChangesNothing() throws Exception {
        journal().hold(Custody.Entry.incoming(42, "horizons", steve, diamonds, "Diamond", 64));
        FakeDb db = new FakeDb();
        db.stored.add(42L);
        assertEquals(1, journal().recover("horizons", db));
        assertEquals(List.of(), db.restored, "still in the bin");
    }

    @Test
    void busyEntriesAreLeftToTheirMove() throws Exception {
        Custody custody = journal();
        UUID token = UUID.randomUUID();
        Custody.Entry e = Custody.Entry.outgoing(token, "horizons", steve, diamonds, "Diamond", 64);
        custody.hold(e);
        FakeDb db = new FakeDb();
        assertEquals(0, custody.recover("horizons", db), "a listing in flight isn't recovery's business");
        assertEquals(List.of(), db.restored);
        custody.unbusy(e.id());
        assertEquals(1, custody.recover("horizons", db));
        assertEquals(List.of("custody:" + token), db.restored);
    }

    @Test
    void anUnsettledMoveBlocksANewOneOfTheSameItem() throws Exception {
        Custody custody = journal();
        Custody.Entry e = Custody.Entry.incoming(42, "horizons", steve, diamonds, "Diamond", 64);
        custody.hold(e);
        custody.unbusy(e.id());
        assertTrue(custody.pending(e.id()), "waiting for recovery still counts");
        assertTrue(journal().pending(e.id()), "and after a restart");
        custody.recover("horizons", new FakeDb());
        assertFalse(custody.pending(e.id()));
    }

    @Test
    void releasedEntriesAreGone() throws Exception {
        Custody custody = journal();
        Custody.Entry e = Custody.Entry.incoming(7, "horizons", steve, diamonds, "Diamond", 64);
        custody.hold(e);
        custody.release(e.id());
        assertTrue(custody.entries().isEmpty());
        assertEquals(0, custody.recover("horizons", new FakeDb()));
    }

    @Test
    void aDatabaseOutageKeepsTheEntryForTheNextRun() throws Exception {
        journal().hold(Custody.Entry.outgoing(UUID.randomUUID(), "horizons", steve, diamonds, "Diamond", 64));
        FakeDb db = new FakeDb();
        db.down = true;
        assertEquals(0, journal().recover("horizons", db));
        assertEquals(1, journal().entries().size());
        db.down = false;
        assertEquals(1, journal().recover("horizons", db));
        assertTrue(journal().entries().isEmpty());
    }

    @Test
    void otherModesAreLeftAlone() throws Exception {
        journal().hold(Custody.Entry.outgoing(UUID.randomUUID(), "skyrealms", steve, diamonds, "Diamond", 64));
        assertEquals(0, journal().recover("horizons", new FakeDb()));
        assertEquals(1, journal().entries().size());
    }

    @Test
    void unreadableEntriesAreKeptForStaff() throws Exception {
        Custody custody = journal();
        Files.writeString(dir.resolve("broken.json"), "{not json");
        assertEquals(0, custody.recover("horizons", new FakeDb()));
        assertTrue(Files.exists(dir.resolve("broken.json")));
    }
}
