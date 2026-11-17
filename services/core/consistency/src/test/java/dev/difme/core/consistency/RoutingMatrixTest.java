package dev.difme.core.consistency;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RoutingMatrixTest {
    @Test
    void everyLevelIsRouted() {
        for (Consistency c : Consistency.values()) assertNotNull(RoutingMatrix.route(c), c.name());
    }

    @Test
    void matchesPdfSection14() {
        assertEquals(Backend.TIGERBEETLE, RoutingMatrix.route(Consistency.STRICT));
        assertEquals(Backend.FOUNDATIONDB, RoutingMatrix.route(Consistency.LINEARIZABLE));
        assertEquals(Backend.YUGABYTE_FOLLOWER, RoutingMatrix.route(Consistency.EVENTUAL));
    }

    @Test
    void defaultsToEventualOutsideAnInterceptedCall() {
        assertEquals(Consistency.EVENTUAL, ConsistencyContext.current());
    }
}
