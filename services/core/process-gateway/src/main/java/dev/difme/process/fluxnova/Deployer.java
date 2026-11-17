package dev.difme.process.fluxnova;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Multipart deployment to the engine (POST /deployment/create, deploy-changed-only). */
@ApplicationScoped
public class Deployer {
    public record File(String name, String content) {}

    private final HttpClient http = HttpClient.newHttpClient();
    @ConfigProperty(name = "quarkus.rest-client.fluxnova.url") String base;
    @ConfigProperty(name = "difme.fluxnova.user") String user;
    @ConfigProperty(name = "difme.fluxnova.password") String password;
    final ObjectMapper json;

    public Deployer(ObjectMapper json) {
        this.json = json;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> deploy(String name, List<File> files) throws Exception {
        for (File f : files) {
            if (!f.name().matches("[A-Za-z0-9._-]+\\.(bpmn|dmn|cmmn)")) throw new IllegalArgumentException("bad file name " + f.name());
        }
        String boundary = "difme-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, boundary, "deployment-name", null, name);
        part(body, boundary, "deploy-changed-only", null, "true");
        part(body, boundary, "deployment-source", null, "process-studio");
        for (File f : files) part(body, boundary, f.name(), f.name(), f.content());
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        String auth = java.util.Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/deployment/create"))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300) throw new IllegalStateException("engine rejected deployment: " + r.body());
        return json.readValue(r.body(), Map.class);
    }

    private static void part(ByteArrayOutputStream out, String boundary, String field, String filename, String value) {
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\""
                + (filename != null ? "; filename=\"" + filename + "\"\r\nContent-Type: application/xml" : "") + "\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
