# DIFME — run from inside a Codespace.
SHELL := /bin/bash
CLUSTER ?= difme
PROFILE ?= core
REPO    ?= https://github.com/pratikmohanty1294/difme.git
APPS     = $(shell cat deploy/profiles/$(PROFILE).txt 2>/dev/null | grep -v '^\#')

.PHONY: help cluster destroy up down status ui verify test build tla lint

help:            ## List targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-10s %s\n",$$1,$$2}'
	@echo "  Profiles: $$(ls deploy/profiles | sed 's/.txt//' | tr '\n' ' ')"

cluster:         ## Create the k3d cluster: Cilium (kube-proxy replacement), Argo CD, dev secrets
	K3D_FIX_DNS=0 k3d cluster create --config deploy/bootstrap/k3d.yaml --volume $(CURDIR)/deploy/bootstrap/k3s-resolv.conf:/etc/difme/resolv.conf@server:* --volume $(CURDIR)/deploy/bootstrap/k3d-entrypoint-cilium.sh:/bin/k3d-entrypoint-cilium.sh@all
	bash deploy/bootstrap/install.sh $(REPO)
	bash deploy/bootstrap/dev-secrets.sh

destroy:         ## Delete the cluster
	k3d cluster delete $(CLUSTER)

up:              ## Enable a profile, e.g. make up PROFILE=streaming
	@for a in $(APPS); do echo "▶ $$a"; kubectl apply -f deploy/argocd/apps/$$a.yaml; done
	@echo "Argo CD is syncing. Check with: make status"

down:            ## Disable a profile and free resources
	@for a in $(APPS); do kubectl -n argocd delete application $$a --ignore-not-found; done

status:          ## Argo CD app health plus pod memory
	@kubectl -n argocd get applications -o custom-columns=APP:.metadata.name,SYNC:.status.sync.status,HEALTH:.status.health.status
	@kubectl top nodes 2>/dev/null || true

ui:              ## Port-forward Argo CD, Grafana and Temporal UIs
	kubectl -n argocd port-forward svc/argocd-server 8080:443 & \
	kubectl -n observability port-forward svc/grafana 3000:80 & \
	kubectl -n temporal port-forward svc/temporal-web 8233:8080 & wait

build:           ## Build all services
	mvn -q -B -f services/core/pom.xml package -DskipTests
	cd services/hub && cargo build --release
	cd services/attestation && cargo build --release
	cd edge/core && cargo build --release
	cd backoffice && pnpm install && pnpm turbo build

test:            ## Unit plus contract tests
	mvn -q -B -f services/core/pom.xml verify
	cd services/hub && cargo test
	cd edge/core && cargo test
	cd backoffice && pnpm turbo test

tla:             ## Model-check every TLA+ spec
	bash specs/tla/check.sh

lint:            ## Semgrep, Buf, Spectral, AsyncAPI
	semgrep --config policies/semgrep --error services/
	cd api/proto && buf lint && buf breaking --against '.git#branch=main,subdir=api/proto'
	spectral lint api/openapi/*.yaml
	asyncapi validate api/asyncapi/difme.asyncapi.yaml

verify: lint tla test   ## Run every local quality gate
