package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentReviewResourceFactoryTest {
    @Test
    fun `builds owned review agent resources`() {
        val resources = AgentReviewResourceFactory.create(request(), "review-agent:1")

        assertEquals("agent-review-request-42", resources.configMap.metadata.name)
        assertEquals("reviews", resources.job.metadata.namespace)
        assertEquals("review-agent:1", resources.job.spec.template.spec.containers[0].image)
        assertEquals("agent-review-request-42-agent", resources.job.spec.template.spec.serviceAccountName)
        assertEquals(0, resources.job.spec.backoffLimit)
        assertEquals("Never", resources.job.spec.template.spec.restartPolicy)
        assertEquals(
            "classpath:/,file:/config/review.yaml",
            resources.job.spec.template.spec.containers[0].env.single().value,
        )
        assertEquals("SPRING_CONFIG_LOCATION", resources.job.spec.template.spec.containers[0].env.single().name)
        assertTrue(resources.configMap.immutable)
        assertEquals("review.yaml", resources.configMap.data.keys.single())
        assertEquals("reviewresults/status", resources.role.rules[1].resources.single())
        assertTrue(resources.job.spec.template.spec.containers[0].volumeMounts.single().readOnly)
        assertEquals("review.yaml", resources.job.spec.template.spec.containers[0].volumeMounts.single().subPath)
        assertEquals("/config/review.yaml", resources.job.spec.template.spec.containers[0].volumeMounts.single().mountPath)

        listOf(
            resources.configMap.metadata,
            resources.serviceAccount.metadata,
            resources.role.metadata,
            resources.roleBinding.metadata,
            resources.job.metadata,
        ).forEach { metadata ->
            val owner = metadata.ownerReferences.single()
            assertEquals("request-42", owner.name)
            assertEquals("uid-42", owner.uid)
            assertEquals("AgentReviewRequest", owner.kind)
            assertEquals("example.com/v1", owner.apiVersion)
            assertTrue(owner.controller)
            assertFalse(owner.blockOwnerDeletion)
        }

        assertEquals("agent-review-request-42-agent", resources.roleBinding.subjects.single().name)
        assertEquals("reviews", resources.roleBinding.subjects.single().namespace)
        assertEquals("ServiceAccount", resources.roleBinding.subjects.single().kind)
        assertEquals("agent-review-request-42-agent", resources.roleBinding.roleRef.name)
        assertEquals("rbac.authorization.k8s.io", resources.roleBinding.roleRef.apiGroup)
        assertEquals("Role", resources.roleBinding.roleRef.kind)
    }

    @Test
    fun `role grants only review result access`() {
        val role = AgentReviewResourceFactory.create(request(), "review-agent:1").role

        assertEquals(2, role.rules.size)
        assertEquals(setOf("reviewresults"), role.rules[0].resources.toSet())
        assertEquals(setOf("reviewresults/status"), role.rules[1].resources.toSet())
        assertEquals(setOf("example.com"), role.rules[0].apiGroups.toSet())
        assertEquals(setOf("example.com"), role.rules[1].apiGroups.toSet())
        assertEquals(setOf("get", "create", "update", "patch"), role.rules[0].verbs.toSet())
        assertEquals(setOf("get", "update", "patch"), role.rules[1].verbs.toSet())
        assertTrue(role.rules.none { it.verbs.contains("delete") })
    }

    private fun request(): AgentReviewRequestCR = AgentReviewRequestCR().apply {
        metadata = io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
            .withName("request-42")
            .withNamespace("reviews")
            .withUid("uid-42")
            .build()
        spec = AgentReviewRequestSpec().apply {
            repository = AgentReviewRepository().apply {
                url = "https://github.com/example/repository.git"
            }
            pr = "42"
        }
    }
}
