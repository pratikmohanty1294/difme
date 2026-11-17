# DIFME — run from inside a Codespace.
SHELL := /bin/bash
CLUSTER ?= difme
PROFILE ?= core
IMAGE_DISK ?= $(shell df --output=avail /tmp 2>/dev/null | tail -1 | awk '$$1>50000000{print "/tmp/difme"}' | grep . || echo $(HOME)/.difme)
REPO    ?= https://github.com/pratikmohanty1294/difme.git
APPS     = $(shell cat deploy/profiles/$(PROFILE).txt 2>/dev/null | grep -v '^\#')

.PHONY: help registries prefetch cluster destroy up down status ui verify test build tla lint check
AREA    ?= all
MODULES ?=

check:           ## Fast pre-push checks for one area: make check AREA=core MODULES=cmd-svc
	bash ci/check.sh $(AREA) $(MODULES)

help:            ## List targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-10s %s\n",$$1,$$2}'
	@echo "  Profiles: $$(ls deploy/profiles | sed 's/.txt//' | tr '\n' ' ')"

registries:      ## Persistent pull-through image caches (survive `make destroy`)
	@for r in dockerio=https://registry-1.docker.io ghcr=https://ghcr.io quay=https://quay.io k8s=https://registry.k8s.io; do \
	  name=difme-$${r%%=*}; url=$${r#*=}; \
	  if k3d registry list -o json 2>/dev/null | grep -q "k3d-$$name\""; then echo "registry cache: $$name (exists)"; \
	  else k3d registry create $$name --port 0 --proxy-remote-url $$url -v $$name-cache:/var/lib/registry >/dev/null && echo "registry cache: $$name -> $$url (created)"; fi; \
	done

cluster: registries   ## Create the k3d cluster: Cilium, Argo CD, dev secrets (images via local caches)
	@# Image layers (re-fetchable, ~14 GB) go to the large ephemeral disk; volumes and registry caches stay on the
	@# persistent disk. After a Codespace stop the layers re-pull from the local caches (knowledge-base/cluster.md).
	mkdir -p $(IMAGE_DISK)/containerd
	K3D_FIX_DNS=0 k3d cluster create --config deploy/bootstrap/k3d.yaml \
	  --volume $(CURDIR)/deploy/bootstrap/k3s-resolv.conf:/etc/difme/resolv.conf@server:* \
	  --volume $(IMAGE_DISK)/containerd:/var/lib/rancher/k3s/agent/containerd@server:*
	bash deploy/bootstrap/install.sh $(REPO)
	bash deploy/bootstrap/dev-secrets.sh

destroy:         ## Delete the cluster
	k3d cluster delete $(CLUSTER)

prefetch:        ## Pre-pull a profile's images (parallel, retried) so pods never wait on the network
	bash ci/prefetch.sh $(PROFILE)

up: prefetch     ## Enable a profile wave by wave, e.g. make up PROFILE=streaming (images prefetched first)
	bash ci/up.sh $(PROFILE)
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
