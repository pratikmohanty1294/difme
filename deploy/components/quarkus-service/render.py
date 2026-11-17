#!/usr/bin/env python3
"""Expand core-services/services.yaml into Deployments + Services (run by `make render`, checked in CI)."""
import pathlib, yaml
ROOT = pathlib.Path(__file__).resolve().parents[3]
cm = yaml.safe_load((ROOT / "deploy/platform/core-platform/core-services/services.yaml").read_text(encoding="utf-8"))
svcs = yaml.safe_load(cm["data"]["services.yaml"])
docs = []
for s in svcs:
    labels = {"app": s["name"], "version": "stable", "difme/team": s["team"], "business.metric": s["metric"]}
    docs.append({
        "apiVersion": "apps/v1", "kind": "Deployment",
        "metadata": {"name": s["name"], "labels": {"app": s["name"]}},
        "spec": {
            "replicas": 1, "selector": {"matchLabels": {"app": s["name"]}},
            "template": {
                "metadata": {"labels": labels, "annotations": {
                    "profiles.grafana.com/cpu.scrape": "true", "profiles.grafana.com/cpu.port": str(s["port"])}},
                "spec": {
                    "serviceAccountName": s["name"],
                    "containers": [{
                        "name": "app",
                        "image": f"ghcr.io/pratikmohanty1294/{s['image']}:0.1.0",
                        "ports": [{"name": "http", "containerPort": s["port"]}, {"name": "grpc", "containerPort": 9000}],
                        "env": [
                            {"name": "QUARKUS_HTTP_PORT", "value": str(s["port"])},
                            {"name": "QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT", "value": "http://alloy.observability.svc:4317"},
                            {"name": "PYROSCOPE_SERVER_ADDRESS", "value": "http://pyroscope.observability.svc:4040"},
                            {"name": "FLAGD_HOST", "value": "flagd.core-platform.svc"},
                            {"name": "JAVA_TOOL_OPTIONS", "value": "-XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=75"}],
                        "readinessProbe": {"httpGet": {"path": "/q/health/ready", "port": s["port"]}},
                        "livenessProbe": {"httpGet": {"path": "/q/health/live", "port": s["port"]}},
                        "resources": {"requests": {"cpu": s["cpu"], "memory": s["mem"]}, "limits": {"memory": s["mem"]}},
                    }]}}}})
    docs.append({"apiVersion": "v1", "kind": "Service", "metadata": {"name": s["name"], "labels": {"app": s["name"]}},
                 "spec": {"selector": {"app": s["name"]},
                          "ports": [{"name": "http", "port": s["port"]}, {"name": "grpc", "port": 9000}]}})
out = ROOT / "deploy/components/quarkus-service/generated.yaml"
out.write_text("# generated — edit core-services/services.yaml\n" + yaml.safe_dump_all(docs, sort_keys=False), encoding="utf-8", newline="\n")
print(f"rendered {len(svcs)} services")
