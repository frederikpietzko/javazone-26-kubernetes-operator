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
        val local = repositoryFile("config/application-local.yaml").readText()
        assertTrue(local.contains("spring:"))
        assertTrue(local.contains("openai:"))
        assertTrue(local.contains("base-url:"))
        assertTrue(local.contains("127.0.0.1:19516"))
        assertTrue(local.contains("\${TOKEN}"))
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
        assertTrue(dockerignore.contains("config/"))
        assertTrue(dockerignore.contains(".git/"))
    }

    private fun repositoryFile(path: String): File =
        listOf(File(path), File("../$path"))
            .firstOrNull(File::exists)
            ?: error("$path not found from test working directory")
}
