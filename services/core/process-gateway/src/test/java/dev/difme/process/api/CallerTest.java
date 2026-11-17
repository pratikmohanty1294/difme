package dev.difme.process.api;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class CallerTest {
    private static String bearer(String payloadJson) {
        Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + e.encodeToString("{}".getBytes(StandardCharsets.UTF_8)) + "."
                + e.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    void readsUserAndGroupsFromVerifiedToken() {
        Caller c = Caller.from(bearer("{\"preferred_username\":\"asha\",\"groups\":[\"/compliance\",\"process-designers\"]}"));
        assertEquals("asha", c.user());
        assertEquals(List.of("compliance", "process-designers"), c.groups());
        c.require("process-designers");
    }

    @Test
    void anonymousWithoutToken() {
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> Caller.from(null).require("ops"));
    }
}
