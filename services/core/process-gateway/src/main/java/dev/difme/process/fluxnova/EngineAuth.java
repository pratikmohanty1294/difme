package dev.difme.process.fluxnova;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.eclipse.microprofile.config.ConfigProvider;

/** Basic-auth header for the engine REST API, from config (secret-backed env). */
public final class EngineAuth {
    private EngineAuth() {}

    public static String basic() {
        var c = ConfigProvider.getConfig();
        String raw = c.getValue("difme.fluxnova.user", String.class) + ":" + c.getOptionalValue("difme.fluxnova.password", String.class).orElse("");
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
