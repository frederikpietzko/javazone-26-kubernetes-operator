package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentReviewResourceFactoryTest {
    private val factory = AgentReviewResourceFactory(ResourceNameGenerator())

    @Test
    fun `passes configured OpenAI base URL to review agent Job`() {
        val resources = factory.create(request(), "review-agent:1", "https://api.example.test/v1")

        assertEquals(
            "https://api.example.test/v1",
            resources.job.spec.template.spec.containers.single().env.first { it.name == "REVIEW_AGENT_OPENAI_BASE_URL" }.value,
        )
    }

    @Test
    fun `builds owned review agent ConfigMap and Job`() {
        val resources = factory.create(request(), "review-agent:1")

        assertEquals("agent-review-request-42", resources.configMap.metadata.name)
        assertEquals("agent-review-request-42", resources.job.metadata.name)
        assertEquals("default", resources.job.metadata.namespace)
        assertEquals("review-agent:1", resources.job.spec.template.spec.containers.single().image)
        assertEquals("review-agent", resources.job.spec.template.spec.serviceAccountName)
        assertEquals(0, resources.job.spec.backoffLimit)
        assertEquals("Never", resources.job.spec.template.spec.restartPolicy)
        val environment = resources.job.spec.template.spec.containers.single().env
        assertEquals(2, environment.size)
        assertEquals("classpath:/,file:/config/review.yaml", environment.first { it.name == "SPRING_CONFIG_LOCATION" }.value)
        assertEquals("http://127.0.0.1:11434", environment.first { it.name == "REVIEW_AGENT_OPENAI_BASE_URL" }.value)
        assertTrue(resources.configMap.immutable)
        assertEquals("review.yaml", resources.configMap.data.keys.single())
        assertEquals("review-config", resources.job.spec.template.spec.volumes.single().name)
        assertEquals("review-config", resources.job.spec.template.spec.containers.single().volumeMounts.single().name)
        assertTrue(resources.job.spec.template.spec.containers.single().volumeMounts.single().readOnly)
        assertEquals("review.yaml", resources.job.spec.template.spec.containers.single().volumeMounts.single().subPath)
        assertEquals("/config/review.yaml", resources.job.spec.template.spec.containers.single().volumeMounts.single().mountPath)

        listOf(resources.configMap.metadata, resources.job.metadata).forEach { metadata ->
            val owner = metadata.ownerReferences.single()
            assertEquals("request-42", owner.name)
            assertEquals("uid-42", owner.uid)
            assertEquals("AgentReviewRequest", owner.kind)
            assertEquals("example.com/v1", owner.apiVersion)
            assertTrue(owner.controller)
            assertFalse(owner.blockOwnerDeletion)
        }
    }
}
