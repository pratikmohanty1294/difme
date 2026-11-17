package dev.difme.process.api;

import dev.difme.process.fluxnova.FluxnovaClient;
import dev.difme.process.fluxnova.Variables;
import dev.difme.process.formio.FormioClient;
import dev.difme.process.rules.FieldRule;
import dev.difme.process.rules.FieldRules;
import dev.difme.process.rules.RuleService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Form rules for live UX and authoritative submission (ADR-008). */
@Path("/v1")
@RunOnVirtualThread
public class FormRulesResource {
    public record EvaluateRequest(Map<String, Object> data) {}
    public record SubmitRequest(Map<String, Object> data, String taskId) {}
    public record LimitRequest(String channel, String riskLevel) {}

    @Inject RuleService rules;
    @Inject @RestClient FormioClient forms;
    @Inject @RestClient FluxnovaClient engine;
    @ConfigProperty(name = "difme.formio.api-key") String formioKey;

    @POST @Path("/form-rules/{formPath}")
    public Map<String, FieldRule> evaluate(@PathParam("formPath") String formPath, EvaluateRequest req) {
        return rules.fieldRules(formPath, req.data());
    }

    @POST @Path("/forms/{formPath}/submit")
    public Response submit(@PathParam("formPath") String formPath, SubmitRequest req) {
        Map<String, FieldRule> r = rules.fieldRules(formPath, req.data());
        List<FieldRules.Violation> violations = FieldRules.validate(r, req.data());
        if (!violations.isEmpty()) {
            return Response.status(422).entity(Map.of("violations", violations)).build();
        }
        Map<String, Object> saved = forms.submit(formPath, formioKey, Map.of("data", req.data()));
        String submissionId = String.valueOf(saved.get("_id"));
        if (req.taskId() != null) {
            engine.completeTask(req.taskId(), new FluxnovaClient.CompleteTask(Variables.typed(req.data())));
            return Response.ok(Map.of("submissionId", submissionId, "taskCompleted", req.taskId())).build();
        }
        RuleService.FormMeta meta = rules.formMeta(formPath);
        if (meta.processKey() != null) {
            Map<String, Object> started = engine.start(meta.processKey(),
                    new FluxnovaClient.StartProcess(submissionId, Variables.typed(req.data())));
            return Response.ok(Map.of("submissionId", submissionId, "processInstanceId", started.get("id"))).build();
        }
        return Response.ok(Map.of("submissionId", submissionId)).build();
    }

    /** Business config lookup used by Temporal activities (payment-limits DMN). */
    @POST @Path("/decisions/payment-limits")
    public Map<String, Object> limits(LimitRequest req) {
        var rows = rules.evaluate("payment-limits", null, Map.of("channel", req.channel(), "riskLevel", req.riskLevel()));
        Object limit = rows.isEmpty() ? 0L : rows.get(0).get("dailyLimitMinor");
        return Map.of("dailyLimitMinor", ((Number) limit).longValue());
    }
}
