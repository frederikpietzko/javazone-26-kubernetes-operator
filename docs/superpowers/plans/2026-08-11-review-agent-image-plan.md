# Review Agent Image Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and publish a generic Java 21 `review-agent` image containing GitHub CLI and shell tooling, with configurable OpenAI base URL.

**Architecture:** Amper packages `review-agent` as a Spring Boot executable JAR in a JDK builder stage. A Temurin JRE runtime image installs GitHub CLI and common repository/shell tools, runs as non-root, and receives `REVIEW_AGENT_OPENAI_BASE_URL` at runtime. Local development overrides the URL through `application-local.yaml`.

**Tech Stack:** Kotlin Toolchain CLI, Amper, Spring Boot 4.1.0, Java 21, Docker, Eclipse Temurin, Ubuntu apt packages, Docker Hub.

## Global Constraints

- Run `kotlin --help` before the first project command; use `./kotlin` for subsequent project commands.
- Use `tools.jackson.*`/Jackson 3 already present; no unrelated dependency changes.
- Image name is exactly `docker.io/jbfpietzko/review-agent:latest`.
- Runtime image includes Java 21, `bash`, `grep`, `find`, `sed`, `awk`, `git`, `gh`, `curl`, `jq`, `ssh`, and CA certificates.
- Runtime runs as non-root and starts `/app/review-agent.jar` with `java -jar`.
- `REVIEW_AGENT_OPENAI_BASE_URL` is required in packaged `application.yaml`.
- `application-local.yaml` retains local proxy override and is excluded from Docker context/image.
- Never print, copy, commit, or bake local token values into image layers.
- Preserve unrelated `.idea/vcs.xml` and `.idea/workspace.xml` changes.
- Do not add Helm, live-cluster tests, or unrelated operator changes.

---

### Task 1: Configure runtime base URL and Docker build context

**Files:**
- Modify: `review-agent/resources/application.yaml`
- Modify: `review-agent/resources/application-local.yaml`
- Modify: `operator/resources/application.yaml`
- Create: `.dockerignore`
- Create: `review-agent/Dockerfile`
- Create: `review-agent/test/com/example/ReviewAgentPackagingTest.kt`

**Interfaces:**
- Environment variable: `REVIEW_AGENT_OPENAI_BASE_URL`.
- Local override: `spring.ai.openai.base-url` in `review-agent/resources/application-local.yaml`.
- Published image: `docker.io/jbfpietzko/review-agent:latest`.
- Docker build context: repository root; Dockerfile: `review-agent/Dockerfile`.

- [ ] **Step 1: Add failing packaging/configuration tests**

Create `ReviewAgentPackagingTest.kt`:

```kotlin
package com.example

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewAgentPackagingTest {
    @Test
    fun `application config requires runtime OpenAI base URL`() {
        val application = repositoryFile("review-agent/resources/application.yaml").readText()
        assertTrue(application.contains("base-url: \"\${REVIEW_AGENT_OPENAI_BASE_URL}\""))
        assertFalse(application.contains("127.0.0.1:19516"))
    }

    @Test
    fun `local profile supplies local OpenAI base URL`() {
        val local = repositoryFile("review-agent/resources/application-local.yaml").readText()
        assertTrue(local.contains("spring:"))
        assertTrue(local.contains("openai:"))
        assertTrue(local.contains("base-url:"))
        assertTrue(local.contains("127.0.0.1:19516"))
    }

    @Test
    fun `dockerfile packages executable jar and installs required tools`() {
        val dockerfile = repositoryFile("review-agent/Dockerfile").readText()
        assertTrue(dockerfile.contains("eclipse-temurin:21-jdk-jammy"))
        assertTrue(dockerfile.contains("eclipse-temurin:21-jre-jammy"))
        assertTrue(dockerfile.contains("./kotlin package --module review-agent --platform jvm --format executable-jar"))
        listOf("bash", "grep", "findutils", "gawk", "git", "gh", "curl", "jq", "openssh-client").forEach {
            assertTrue(dockerfile.contains(it), "missing runtime tool: $it")
        }
        assertTrue(dockerfile.contains("USER app"))
        assertTrue(dockerfile.contains("review-agent-jvm-executable.jar"))
    }

    @Test
    fun `dockerignore excludes credentials and build artifacts`() {
        val dockerignore = repositoryFile(".dockerignore").readText()
        assertTrue(dockerignore.contains("**/application-local.yaml"))
        assertTrue(dockerignore.contains("build/"))
        assertTrue(dockerignore.contains(".git/"))
    }
    private fun repositoryFile(path: String): File =
        listOf(File(path), File("../$path"))
            .firstOrNull(File::exists)
            ?: error("$path not found from test working directory")
}
```

- [ ] **Step 2: Run focused tests and verify expected failure**

Run:

```bash
./kotlin test --include-module review-agent --include-classes com.example.ReviewAgentPackagingTest
```

Expected: compilation/test failure because Dockerfile, `.dockerignore`, and environment configuration are not implemented.

- [ ] **Step 3: Configure production and local URLs**

Change `review-agent/resources/application.yaml` to:

```yaml
spring:
  ai:
    openai:
      base-url: "${REVIEW_AGENT_OPENAI_BASE_URL}"
```

Keep existing OpenAI model, reasoning effort, and dummy API key. Add to `review-agent/resources/application-local.yaml` without exposing or changing the existing token:

```yaml
spring:
  ai:
    openai:
      base-url: "http://127.0.0.1:19516/wire/${TOKEN}/codex/openai"
```

Local runs must activate Spring profile `local`; container runs provide `REVIEW_AGENT_OPENAI_BASE_URL` through the environment. Update `operator/resources/application.yaml` to:

```yaml
agent-review:
  image: docker.io/jbfpietzko/review-agent:latest
```

- [ ] **Step 4: Add Docker ignore rules**

Create `.dockerignore`:

```text
.git/
.idea/
.superpowers/
.pi-subagents/
.worktrees/
build/
**/application-local.yaml
**/*.log
.DS_Store
```

The local application file must be excluded even though it is ignored by Git; Docker uses its own context rules.

- [ ] **Step 5: Add multi-stage Dockerfile**

Create `review-agent/Dockerfile`:

```dockerfile
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
COPY . .
RUN ./kotlin package --module review-agent --platform jvm --format executable-jar

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install --no-install-recommends --yes \
        bash \
        ca-certificates \
        curl \
        findutils \
        gawk \
        git \
        gh \
        grep \
        jq \
        openssh-client \
        sed \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --uid 10001 --shell /bin/bash app
WORKDIR /app
COPY --from=build --chown=app:app \
    /workspace/build/tasks/_review-agent_executableJarJvm/review-agent-jvm-executable.jar \
    /app/review-agent.jar
USER app

ENTRYPOINT ["java", "-jar", "/app/review-agent.jar"]
```

Do not copy `application-local.yaml` into the image. Do not define `REVIEW_AGENT_OPENAI_BASE_URL` in the Dockerfile; deployment supplies it.

- [ ] **Step 6: Run focused tests and package review-agent**

Run:

```bash
./kotlin test --include-module review-agent --include-classes com.example.ReviewAgentPackagingTest
./kotlin package --module review-agent --platform jvm --format executable-jar
```

Expected: packaging test passes and executable JAR exists at `build/tasks/_review-agent_executableJarJvm/review-agent-jvm-executable.jar` with Spring Boot `JarLauncher` manifest.

- [ ] **Step 7: Commit packaging changes**

```bash
git add .dockerignore review-agent/Dockerfile review-agent/resources/application.yaml review-agent/resources/application-local.yaml review-agent/test/com/example/ReviewAgentPackagingTest.kt operator/resources/application.yaml
git commit -m "feat(review-agent): add configurable container image"
```

---

### Task 2: Build and inspect the image

**Files:**
- No source changes expected.
- Docker output: local image `docker.io/jbfpietzko/review-agent:latest`.

**Interfaces:**
- Docker build context is repository root.
- Image entrypoint is `java -jar /app/review-agent.jar`.
- Runtime environment variable is `REVIEW_AGENT_OPENAI_BASE_URL`.

- [ ] **Step 1: Run project verification**

Run:

```bash
kotlin --help
./kotlin check
./kotlin test
```

Expected: all modules pass; local configuration tests do not print token contents.

- [ ] **Step 2: Build image**

Run:

```bash
docker build --file review-agent/Dockerfile --tag docker.io/jbfpietzko/review-agent:latest .
```

Expected: multi-stage build succeeds and final image contains only runtime layer, executable JAR, and runtime tools.

- [ ] **Step 3: Inspect image metadata and tools without starting review workflow**

Run:

```bash
docker image inspect docker.io/jbfpietzko/review-agent:latest --format '{{.Config.User}} {{json .Config.Entrypoint}}'
docker run --rm --entrypoint bash docker.io/jbfpietzko/review-agent:latest -lc 'java -version && gh --version && git --version && grep --version | head -1 && bash --version | head -1 && jq --version'
docker run --rm --entrypoint bash docker.io/jbfpietzko/review-agent:latest -lc '! find /app -name application-local.yaml -print | grep -q .'
```

Expected: user is `app`, entrypoint is Java executable JAR, all tools report versions, and no local configuration file exists in `/app`.

- [ ] **Step 4: Commit no generated Docker artifacts**

Run:

```bash
git status --short
git diff --check
```

Expected: no generated files or unrelated `.idea` changes staged by image build.

---

### Task 3: Tag and push Docker Hub image

**Files:**
- No source changes expected.
- Remote image: `docker.io/jbfpietzko/review-agent:latest`.

- [ ] **Step 1: Check Docker authentication without exposing credentials**

Run:

```bash
docker info --format '{{json .}}' >/tmp/docker-info.json
docker image inspect docker.io/jbfpietzko/review-agent:latest >/dev/null
```

Do not print `/tmp/docker-info.json` or credential files. If Docker daemon or authentication is unavailable, stop and report the exact prerequisite before attempting push.

- [ ] **Step 2: Push image**

Run:

```bash
docker push docker.io/jbfpietzko/review-agent:latest
```

Expected: Docker Hub accepts the push under `jbfpietzko/review-agent`; record digest from command output without exposing credentials.

- [ ] **Step 3: Verify remote image metadata**

Run:

```bash
docker manifest inspect docker.io/jbfpietzko/review-agent:latest
```

Expected: manifest resolves to pushed image and includes the reported digest.

- [ ] **Step 4: Final repository verification**

Run:

```bash
git diff --check
git status --short
git log --oneline -5
```

Expected: only pre-existing `.idea/vcs.xml` and `.idea/workspace.xml` remain modified; no local credential file is tracked or changed.

---

## Plan Self-Review

- Spec coverage: Dockerfile, Java runtime, shell/GitHub tooling, non-root user, executable JAR packaging, Docker context credential exclusion, runtime OpenAI URL environment, local profile override, operator image reference, tests, image inspection, push, and digest verification are covered.
- Placeholder scan: no TBD/TODO/“implement later” steps; exact files, image names, commands, tool names, and expected results are specified.
- Type/config consistency: `REVIEW_AGENT_OPENAI_BASE_URL` matches `application.yaml`, local override matches Spring property, Dockerfile output path matches Amper executable-jar output, and operator image matches Docker Hub tag.
- Security: local token is never copied into Docker context/image; push commands never print credentials.
