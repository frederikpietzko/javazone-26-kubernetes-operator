package com.example

import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext
import kotlin.reflect.full.findAnnotation
import kotlin.test.*
import org.mockito.Mockito
import org.springframework.stereotype.Component

class AgentReviewRequestReconcilerTest {
    private fun newReconciler(
        gateway: AgentReviewClient = FakeGateway()
    ): AgentReviewRequestReconciler {
        val nameGenerator = ResourceNameGenerator()
        return AgentReviewRequestReconciler(
            agentReviewClient = gateway,
            properties = AgentReviewProperties("review-agent:1"),
            agentReviewFactory = AgentReviewResourceFactory(nameGenerator),
            nameGenerator = nameGenerator,
        )
    }

    @Test
    fun `unsupported namespace is rejected before resource access`() {
        val gateway = FakeGateway()
        val reconciler = newReconciler(gateway)
        val primary = request().apply { metadata.namespace = "other" }

        assertTrue(
            reconciler
                .reconcile(
                    primary,
                    Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java)
                        as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>,
                )
                .isNoUpdate
        )
        assertFalse(gateway.observed)
    }

    @Test
    fun `informer registrations are limited to default`() {
        val reconciler = newReconciler()
        @Suppress("UNCHECKED_CAST")
        val cache =
            Mockito.mock(
                io.javaoperatorsdk.operator.processing.event.source.IndexerResourceCache::class.java
            )
                as
                io.javaoperatorsdk.operator.processing.event.source.IndexerResourceCache<
                    AgentReviewRequestCR
                >
        @Suppress("UNCHECKED_CAST")
        val configuration =
            Mockito.mock(io.javaoperatorsdk.operator.api.config.ControllerConfiguration::class.java)
                as
                io.javaoperatorsdk.operator.api.config.ControllerConfiguration<AgentReviewRequestCR>
        val context: EventSourceContext<AgentReviewRequestCR> =
            EventSourceContext(
                cache,
                configuration,
                Mockito.mock(KubernetesClient::class.java),
                AgentReviewRequestCR::class.java,
            )
        val sources = reconciler.prepareEventSources(context)
        assertEquals(AGENT_REVIEW_EVENT_SOURCE_NAMES.size, sources.size)
        assertEquals(AGENT_REVIEW_EVENT_SOURCE_NAMES.toSet(), sources.map { it.name() }.toSet())
    }

    @Test
    fun `missing repository URL becomes terminal Error`() {
        val reconciler = newReconciler()
        val primary =
            request().apply {
                spec.repository = null
            }
        reconciler.reconcile(
            primary,
            Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java)
                as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>,
        )
        assertEquals("Error", primary.status?.phase)
        assertEquals("repository URL is required", primary.status?.message)
    }

    @Test
    fun `unchanged status does not request another patch`() {
        val status =
            AgentReviewRequestStatus().also {
                it.phase = "InProgress"
                it.jobName = "agent-review-request-42"
                it.configMapName = "agent-review-request-42"
                it.reviewResultName = "agent-review-request-42"
            }
        assertEquals(
            status,
            AgentReviewRequestStatus().also {
                it.phase = "InProgress"
                it.jobName = "agent-review-request-42"
                it.configMapName = "agent-review-request-42"
                it.reviewResultName = "agent-review-request-42"
            },
        )
        val primary = request().apply { this.status = status }
        assertTrue(status.updateIfChanged(primary).isNoUpdate)
        val reconciler = newReconciler(FakeGateway(observedWithActiveJob()))
        assertTrue(
            reconciler
                .reconcile(
                    primary,
                    Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java)
                        as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>,
                )
                .isNoUpdate
        )
    }

    @Test
    fun `reconciler is Spring discoverable`() {
        assertNotNull(AgentReviewRequestReconciler::class.findAnnotation<Component>())
        assertEquals(
            "agent-review-request",
            AgentReviewRequestReconciler::class.findAnnotation<ControllerConfiguration>()!!.name,
        )
    }
}

private class FakeGateway(
    private val resources: ObservedAgentReviewResources =
        ObservedAgentReviewResources(null, null, null)
) : AgentReviewClient {
    var observed = false

    override fun observe(namespace: String, baseName: String): ObservedAgentReviewResources {
        observed = true
        return resources
    }
}
