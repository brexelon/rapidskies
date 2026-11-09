package us.rapidskies.common.friend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import us.rapidskies.common.friend.FriendRepository.AcceptResult;
import us.rapidskies.common.friend.FriendRepository.RequestResult;
import us.rapidskies.common.profile.ProfileRepository;
import us.rapidskies.common.testing.TestServices;

@EnabledIf("us.rapidskies.common.testing.TestServices#dockerAvailable")
class FriendRepositoryIT {

    private static final int LIMIT = 100;

    private FriendRepository friends;
    private UUID steve;
    private UUID alex;

    @BeforeEach
    void setUp() throws Exception {
        TestServices.truncate("friends", "friend_requests", "players");
        friends = new FriendRepository(TestServices.database());
        ProfileRepository profiles = new ProfileRepository(TestServices.database());
        steve = profiles.recordLogin(UUID.randomUUID(), "Steve", "hub-1").uuid();
        alex = profiles.recordLogin(UUID.randomUUID(), "Alex", "hub-1").uuid();
    }

    @Test
    void requestThenAcceptMakesBothFriends() throws Exception {
        assertEquals(RequestResult.SENT, friends.request(steve, alex, LIMIT, LIMIT));
        assertEquals(RequestResult.ALREADY_SENT, friends.request(steve, alex, LIMIT, LIMIT));
        assertEquals("Steve", friends.incoming(alex).getFirst().name());

        assertEquals(AcceptResult.ACCEPTED, friends.accept(alex, steve, LIMIT, LIMIT));
        assertTrue(friends.areFriends(steve, alex));
        assertTrue(friends.areFriends(alex, steve));
        assertEquals("Alex", friends.friends(steve).getFirst().name());
        assertTrue(friends.incoming(alex).isEmpty());
        assertEquals(RequestResult.ALREADY_FRIENDS, friends.request(alex, steve, LIMIT, LIMIT));
    }

    @Test
    void crossedRequestsAcceptEachOther() throws Exception {
        friends.request(steve, alex, LIMIT, LIMIT);
        assertEquals(RequestResult.ACCEPTED, friends.request(alex, steve, LIMIT, LIMIT));
        assertTrue(friends.areFriends(steve, alex));
    }

    @Test
    void removeEndsBothSides() throws Exception {
        friends.request(steve, alex, LIMIT, LIMIT);
        friends.accept(alex, steve, LIMIT, LIMIT);
        assertTrue(friends.remove(alex, steve));
        assertFalse(friends.areFriends(steve, alex));
        assertEquals(0, friends.count(steve));
        assertFalse(friends.remove(alex, steve));
    }

    @Test
    void denyDropsTheRequest() throws Exception {
        friends.request(steve, alex, LIMIT, LIMIT);
        assertTrue(friends.deny(alex, steve));
        assertEquals(AcceptResult.NO_REQUEST, friends.accept(alex, steve, LIMIT, LIMIT));
    }

    @Test
    void limitsApplyToBothSides() throws Exception {
        assertEquals(RequestResult.SELF, friends.request(steve, steve, LIMIT, LIMIT));
        friends.request(steve, alex, LIMIT, LIMIT);
        assertEquals(AcceptResult.LIMIT_OTHER, friends.accept(alex, steve, LIMIT, 0));
        assertEquals(AcceptResult.LIMIT_SELF, friends.accept(alex, steve, 0, LIMIT));
        assertFalse(friends.areFriends(steve, alex));
        assertEquals(RequestResult.LIMIT_SELF, friends.request(alex, UUID.randomUUID(), 0, LIMIT));
    }

    @Test
    void outgoingRequestsAreCapped() throws Exception {
        for (int i = 0; i < FriendRepository.MAX_OUTGOING_REQUESTS; i++) {
            assertEquals(RequestResult.SENT, friends.request(steve, UUID.randomUUID(), LIMIT, LIMIT));
        }
        assertEquals(RequestResult.LIMIT_SELF, friends.request(steve, alex, LIMIT, LIMIT));
    }
}
