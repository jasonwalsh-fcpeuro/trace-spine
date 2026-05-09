# Makefile for api service
# trace-spine API Gateway

.PHONY: all test lint repl build clean run deps help

SERVICE := api
TARGET_JAR := target/$(SERVICE).jar

# Default target
.DEFAULT_GOAL := help

#------------------------------------------------------------------------------
# Core Targets
#------------------------------------------------------------------------------

## test: Run tests with Kaocha
test:
	clojure -M:test

## lint: Run clj-kondo linting
lint:
	@if command -v clj-kondo >/dev/null 2>&1; then \
		clj-kondo --lint src test; \
	else \
		echo "clj-kondo not found, skipping lint"; \
	fi

## repl: Start nREPL with CIDER middleware
repl:
	clojure -M:dev:repl

## build: Build uberjar
build: $(TARGET_JAR)

$(TARGET_JAR):
	@mkdir -p target
	clojure -M:uberjar

## clean: Clean build artifacts
clean:
	rm -rf target .cpcache .clj-kondo/.cache

#------------------------------------------------------------------------------
# Development Targets
#------------------------------------------------------------------------------

## run: Run the service locally
run:
	clojure -M:run

## deps: Download all dependencies
deps:
	clojure -P
	clojure -P -M:dev:test:repl

## outdated: Check for outdated dependencies
outdated:
	clojure -M:outdated 2>/dev/null || clojure -Sdeps '{:deps {com.github.liquidz/antq {:mvn/version "RELEASE"}}}' -M -m antq.core

## docs: Generate API documentation
docs:
	clojure -M:docs

#------------------------------------------------------------------------------
# Testing Targets
#------------------------------------------------------------------------------

## test-watch: Run tests in watch mode
test-watch:
	clojure -M:test --watch

## test-unit: Run only unit tests
test-unit:
	clojure -M:test --focus :unit

## test-integration: Run only integration tests
test-integration:
	clojure -M:test --focus :integration

#------------------------------------------------------------------------------
# Help
#------------------------------------------------------------------------------

## help: Show this help message
help:
	@echo "$(SERVICE) service Makefile"
	@echo ""
	@echo "Usage: make [target]"
	@echo ""
	@echo "Targets:"
	@grep -E '^## ' $(MAKEFILE_LIST) | sed 's/## /  /' | column -t -s ':'
