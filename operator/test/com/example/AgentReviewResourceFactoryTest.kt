package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentReviewResourceFactoryTest {
    @Test
    fun `builds owned review agent ConfigMap and Job`() {
        val resources = AgentReviewResourceFactory.create(request(), "review-agent:1")

        assertEquals("agent-review-request-42", resources.configMap.metadata.name)
        assertEquals("agent-review-request-42", resources.job.metadata.name)
        assertEquals("default", resources.job.metadata.namespace)
        assertEquals("review-agent:1", resources.job.spec.template.spec.containers.single().image)
        assertEquals("review-agent", resources.job.spec.template.spec.serviceAccountName)
        assertEquals(0, resources.job.spec.backoffLimit)
        assertEquals("Never", resources.job.spec.template.spec.restartPolicy)
        assertEquals(
            "classpath:/,file:/config/review.yaml",
            resources.job.spec.template.spec.containers.single().env.single().value,
        )
        assertEquals("SPRING_CONFIG_LOCATION", resources.job.spec.template.spec.containers.single().env.single().name)
        assertTrue(resources.configMap.immutable)
        assertEquals("review.yaml", resources.configMap.data.keys.single())
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
