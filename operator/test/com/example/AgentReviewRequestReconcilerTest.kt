package com.example

import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import kotlin.reflect.full.findAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestOperatorConfiguration::class)
class AgentReviewRequestReconcilerTest {
    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Test
    fun `registers AgentReviewRequest controller`() {
        assertNotNull(applicationContext.getBean(AgentReviewRequestReconciler::class.java))
    }

    @Test
    fun `new request produces EnsureResources decision`() {
        val reconciler = AgentReviewRequestReconciler(FakeGateway(), AgentReviewProperties("review-agent:1"))
        val decision = reconciler.reconcileOnce(
            request(),
            ObservedAgentReviewResources(null, null, null, null, null, null),
        )
        assertIs<LifecycleDecision.EnsureResources>(decision)
    }

    @Test
    fun `terminal result produces Successful decision without resource creation`() {
        val result = ReviewResultCR().apply {
            status = ReviewResultStatus().also { it.status = "Completed" }
        }
        val reconciler = AgentReviewRequestReconciler(FakeGateway(), AgentReviewProperties("review-agent:1"))
        val decision = reconciler.reconcileOnce(request(), observedWithCompletedJob(result))
        assertEquals("Successful", assertIs<LifecycleDecision.Successful>(decision).status.phase)
    }

    @Test
    fun `informer registrations cover owned resources without polling`() {
        val reconciler = AgentReviewRequestReconciler(FakeGateway(), AgentReviewProperties("review-agent:1"))
        @Suppress("UNCHECKED_CAST")
        val cache = Mockito.mock(io.javaoperatorsdk.operator.processing.event.source.IndexerResourceCache::class.java)
            as io.javaoperatorsdk.operator.processing.event.source.IndexerResourceCache<AgentReviewRequestCR>
        @Suppress("UNCHECKED_CAST")
        val configuration = Mockito.mock(io.javaoperatorsdk.operator.api.config.ControllerConfiguration::class.java)
            as io.javaoperatorsdk.operator.api.config.ControllerConfiguration<AgentReviewRequestCR>
        val context: EventSourceContext<AgentReviewRequestCR> = EventSourceContext(
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
    fun `reconcile validates desired resources before waiting`() {
        val gateway = FakeGateway(observedWithActiveJob())
        val reconciler = AgentReviewRequestReconciler(gateway, AgentReviewProperties("review-agent:1"))
        val primary = request().apply {
            status = AgentReviewRequestStatus().also {
                it.phase = "InProgress"
                it.jobName = "agent-review-request-42"
                it.configMapName = "agent-review-request-42"
                it.reviewResultName = "agent-review-request-42"
            }
        }
        reconciler.reconcile(primary, Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java) as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>)
        assertTrue(gateway.validated)
    }

    @Test
    fun `conflicting result owner produces Error without creation`() {
        val result = ReviewResultCR().apply { metadata = request().metadata }
        result.metadata.ownerReferences = listOf(
            io.fabric8.kubernetes.api.model.OwnerReferenceBuilder()
                .withApiVersion("example.com/v1")
                .withKind("AgentReviewRequest")
                .withName("other-request")
                .withUid("other-uid")
                .build(),
        )
        val gateway = FakeGateway(observedWithActiveJob(result))
        val reconciler = AgentReviewRequestReconciler(gateway, AgentReviewProperties("review-agent:1"))
        val primary = request()
        reconciler.reconcile(primary, Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java) as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>)
        assertEquals("Error", primary.status?.phase)
        assertNull(gateway.created)
    }

    @Test
    fun `missing job after dependent resources does not create a replacement`() {
        val gateway = FakeGateway(observedWithActiveJob().copy(job = null))
        val reconciler = AgentReviewRequestReconciler(gateway, AgentReviewProperties("review-agent:1"))
        reconciler.reconcile(request(), Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java) as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>)
        assertNull(gateway.created)
    }

    @Test
    fun `unchanged status does not request another patch`() {
        val status = AgentReviewRequestStatus().also {
            it.phase = "InProgress"
            it.jobName = "agent-review-request-42"
            it.configMapName = "agent-review-request-42"
            it.reviewResultName = "agent-review-request-42"
        }
        assertTrue(sameStatus(status, AgentReviewRequestStatus().also {
            it.phase = "InProgress"
            it.jobName = "agent-review-request-42"
            it.configMapName = "agent-review-request-42"
            it.reviewResultName = "agent-review-request-42"
        }))
        val primary = request().apply { this.status = status }
        assertTrue(patchStatusIfChanged(primary, status).isNoUpdate)
        val reconciler = AgentReviewRequestReconciler(FakeGateway(observedWithActiveJob()), AgentReviewProperties("review-agent:1"))
        assertTrue(reconciler.reconcile(primary, Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java) as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>).isNoUpdate)
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
    private val resources: ObservedAgentReviewResources = ObservedAgentReviewResources(null, null, null, null, null, null),
) : AgentReviewResourceGateway {
    var created: AgentReviewResources? = null
    var validated = false

    override fun observe(namespace: String, baseName: String): ObservedAgentReviewResources = resources

    override fun validateDesired(resources: AgentReviewResources, observed: ObservedAgentReviewResources) {
        validated = true
    }

    override fun createMissing(resources: AgentReviewResources) {
        created = resources
    }
}
