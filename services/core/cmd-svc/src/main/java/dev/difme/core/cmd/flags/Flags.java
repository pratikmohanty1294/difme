package dev.difme.core.cmd.flags;

import dev.openfeature.contrib.providers.flagd.Config;
import dev.openfeature.contrib.providers.flagd.FlagdOptions;
import dev.openfeature.contrib.providers.flagd.FlagdProvider;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** OpenFeature + flagd (PDF §6.3): saga gate, kill switches. Evaluation is in-process (~0.1ms, §12). */
@ApplicationScoped
public class Flags {
    @ConfigProperty(name = "difme.flagd.host") String host;
    @ConfigProperty(name = "difme.flagd.port") int port;
    private Client client;

    void start(@Observes StartupEvent ev) {
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        // In-process resolver: flags stream from flagd's sync port and are evaluated locally (microseconds), so a
        // payment never waits on the network for a flag. The default RPC resolver made one remote call per payment
        // with a 500 ms deadline, and paid that deadline in full whenever flagd was unreachable (measured 2026-11-19).
        api.setProvider(new FlagdProvider(FlagdOptions.builder().resolverType(Config.Resolver.IN_PROCESS).host(host).port(port).build()));
        client = api.getClient("cmd-svc");
    }

    public boolean useTemporalSaga(String channel) {
        return client.getBooleanValue("use-temporal-saga", true,
                new ImmutableContext(Map.of("channel", new Value(channel))));
    }

    public String killedSagaStep() {
        return client.getStringValue("kill-saga-step", "");
    }
}
