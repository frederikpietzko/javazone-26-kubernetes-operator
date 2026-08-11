# Review Agent Container Image Design

**Date:** 2026-08-11

## Goal

Package `review-agent` as a Docker image with Java 21, GitHub CLI, shell utilities, Git, and common command-line tools. Publish it as `docker.io/jbfpietzko/review-agent:latest`.

## Image

Add `review-agent/Dockerfile` using a multi-stage build:

1. Builder stage uses JDK 21, copies repository source, and runs:

   ```bash
   ./kotlin package --module review-agent --platform jvm --format executable-jar
   ```

2. Runtime stage uses Eclipse Temurin JRE 21, installs `bash`, `grep`, `findutils`, `sed`, `gawk`, `git`, `gh`, `curl`, `jq`, `openssh-client`, and CA certificates.
3. Copy `build/tasks/_review-agent_executableJarJvm/review-agent-jvm-executable.jar` to the runtime image.
4. Run as a non-root user with Java executable-jar entrypoint.

Add `.dockerignore` entries for Git metadata, build output, IDE files, and every `application-local.yaml` so local credentials never enter the image build context.

## AI base URL configuration

`review-agent/resources/application.yaml` uses required environment substitution:

```yaml
spring:
  ai:
    openai:
      base-url: "${REVIEW_AGENT_OPENAI_BASE_URL}"
```

`review-agent/resources/application-local.yaml` overrides the same Spring property with the existing local proxy URL containing `${TOKEN}`. Local development activates the `local` profile. Container deployments provide `REVIEW_AGENT_OPENAI_BASE_URL` through their runtime environment.

Update operator demo configuration to use `docker.io/jbfpietzko/review-agent:latest`.

## Verification and publication

Run Kotlin tests/checks, build the image, inspect its metadata and installed tools, tag it as `docker.io/jbfpietzko/review-agent:latest`, and push it to Docker Hub. Do not print or commit credentials. If Docker Hub authentication is unavailable, stop before push and report the exact authentication requirement.
