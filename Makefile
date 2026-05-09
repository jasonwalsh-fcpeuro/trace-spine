# Makefile for trace-spine
# Root-level orchestration for all platform services

.PHONY: all test lint clean repl help
.PHONY: test-all lint-all build-all clean-all
.PHONY: c4-check org-lint clj-lint
.PHONY: api wms payments wallet fraud metrics feature-flags

# Default target
.DEFAULT_GOAL := help

# Service directories
SERVICES := api wms payments wallet fraud metrics feature-flags
SERVICE_DIRS := $(addprefix platform/,$(SERVICES))

# Colors for output
BOLD := $(shell tput bold 2>/dev/null || echo "")
RESET := $(shell tput sgr0 2>/dev/null || echo "")
GREEN := $(shell tput setaf 2 2>/dev/null || echo "")
YELLOW := $(shell tput setaf 3 2>/dev/null || echo "")

#------------------------------------------------------------------------------
# Aggregate Targets
#------------------------------------------------------------------------------

## all: Build all services
all: build-all

## test: Run tests for all services
test: test-all

## lint: Run all linting (clj-kondo + org-lint)
lint: clj-lint org-lint

## clean: Clean all build artifacts
clean: clean-all

#------------------------------------------------------------------------------
# Service Build Targets
#------------------------------------------------------------------------------

## build-all: Build uberjars for all services
build-all:
	@echo "$(BOLD)Building all services...$(RESET)"
	@for svc in $(SERVICES); do \
		echo "$(GREEN)Building $$svc...$(RESET)"; \
		$(MAKE) -C platform/$$svc build || exit 1; \
	done
	@echo "$(GREEN)All services built successfully$(RESET)"

## test-all: Run tests for all services
test-all:
	@echo "$(BOLD)Testing all services...$(RESET)"
	@failed=0; \
	for svc in $(SERVICES); do \
		echo "$(GREEN)Testing $$svc...$(RESET)"; \
		$(MAKE) -C platform/$$svc test || failed=1; \
	done; \
	if [ $$failed -eq 1 ]; then \
		echo "$(YELLOW)Some tests failed$(RESET)"; \
		exit 1; \
	fi
	@echo "$(GREEN)All tests passed$(RESET)"

## clean-all: Clean build artifacts for all services
clean-all:
	@echo "$(BOLD)Cleaning all services...$(RESET)"
	@for svc in $(SERVICES); do \
		$(MAKE) -C platform/$$svc clean; \
	done
	@echo "$(GREEN)All services cleaned$(RESET)"

#------------------------------------------------------------------------------
# Linting Targets
#------------------------------------------------------------------------------

## clj-lint: Run clj-kondo on all Clojure services
clj-lint:
	@echo "$(BOLD)Running clj-kondo on all services...$(RESET)"
	@if ! command -v clj-kondo >/dev/null 2>&1; then \
		echo "$(YELLOW)clj-kondo not found. Install with: brew install borkdude/brew/clj-kondo$(RESET)"; \
		exit 1; \
	fi
	@for svc in $(SERVICES); do \
		echo "$(GREEN)Linting $$svc...$(RESET)"; \
		$(MAKE) -C platform/$$svc lint || exit 1; \
	done
	@echo "$(GREEN)All services linted successfully$(RESET)"

## org-lint: Validate org-mode files
org-lint:
	@echo "$(BOLD)Validating org-mode files...$(RESET)"
	@bin/org-lint
	@echo "$(GREEN)Org-mode validation complete$(RESET)"

#------------------------------------------------------------------------------
# C4 Diagram Validation
#------------------------------------------------------------------------------

## c4-check: Validate C4 architecture diagrams
c4-check:
	@echo "$(BOLD)Validating C4 diagrams...$(RESET)"
	@bin/c4-check
	@echo "$(GREEN)C4 diagram validation complete$(RESET)"

#------------------------------------------------------------------------------
# Individual Service Targets
#------------------------------------------------------------------------------

## api: Build and test api service
api:
	$(MAKE) -C platform/api test build

## wms: Build and test wms service
wms:
	$(MAKE) -C platform/wms test build

## payments: Build and test payments service
payments:
	$(MAKE) -C platform/payments test build

## wallet: Build and test wallet service
wallet:
	$(MAKE) -C platform/wallet test build

## fraud: Build and test fraud service
fraud:
	$(MAKE) -C platform/fraud test build

## metrics: Build and test metrics service
metrics:
	$(MAKE) -C platform/metrics test build

## feature-flags: Build and test feature-flags service
feature-flags:
	$(MAKE) -C platform/feature-flags test build

#------------------------------------------------------------------------------
# Development Targets
#------------------------------------------------------------------------------

## deps: Download dependencies for all services
deps:
	@echo "$(BOLD)Downloading dependencies for all services...$(RESET)"
	@for svc in $(SERVICES); do \
		echo "$(GREEN)Fetching deps for $$svc...$(RESET)"; \
		(cd platform/$$svc && clojure -P); \
	done
	@echo "$(GREEN)All dependencies downloaded$(RESET)"

## outdated: Check for outdated dependencies
outdated:
	@echo "$(BOLD)Checking for outdated dependencies...$(RESET)"
	@for svc in $(SERVICES); do \
		echo "$(GREEN)Checking $$svc...$(RESET)"; \
		(cd platform/$$svc && clojure -M:outdated 2>/dev/null || true); \
	done

#------------------------------------------------------------------------------
# CI Targets
#------------------------------------------------------------------------------

## ci: Run full CI pipeline (lint, test, c4-check)
ci: lint test c4-check
	@echo "$(GREEN)CI pipeline completed successfully$(RESET)"

## ci-quick: Quick CI (lint only)
ci-quick: lint
	@echo "$(GREEN)Quick CI completed$(RESET)"

#------------------------------------------------------------------------------
# Help
#------------------------------------------------------------------------------

## help: Show this help message
help:
	@echo "$(BOLD)trace-spine Makefile$(RESET)"
	@echo ""
	@echo "$(BOLD)Usage:$(RESET)"
	@echo "  make [target]"
	@echo ""
	@echo "$(BOLD)Main Targets:$(RESET)"
	@grep -E '^## ' $(MAKEFILE_LIST) | sed 's/## /  /' | column -t -s ':'
	@echo ""
	@echo "$(BOLD)Services:$(RESET)"
	@echo "  $(SERVICES)"
	@echo ""
	@echo "$(BOLD)Examples:$(RESET)"
	@echo "  make test        # Run all tests"
	@echo "  make lint        # Run all linting"
	@echo "  make api         # Build and test api service"
	@echo "  make ci          # Run full CI pipeline"
