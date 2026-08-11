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
import kotlin.test.assertFailsWith
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
    fun `missing job after normal processing does not create a replacement`() {
        val gateway = FakeGateway(observedWithActiveJob().copy(job = null))
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
        assertNull(gateway.created)
        assertEquals("Error", primary.status?.phase)
    }

    @Test
    fun `transient Job creation failure remains retryable during creation phase`() {
        val gateway = FakeGateway(observedWithActiveJob().copy(job = null), failJobCreationOnce = true)
        val reconciler = AgentReviewRequestReconciler(gateway, AgentReviewProperties("review-agent:1"))
        val primary = request().apply {
            status = AgentReviewRequestStatus().also {
                it.phase = "InProgress"
                it.message = JOB_CREATION_PENDING_MESSAGE
            }
        }
        assertFailsWith<RuntimeException> {
            reconciler.reconcile(primary, Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java) as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>)
        }
        assertEquals(JOB_CREATION_PENDING_MESSAGE, primary.status?.message)
        reconciler.reconcile(primary, Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java) as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>)
        assertNotNull(gateway.created)
    }

    @Test
    fun `missing repository URL becomes terminal Error`() {
        val reconciler = AgentReviewRequestReconciler(FakeGateway(), AgentReviewProperties("review-agent:1"))
        val primary = request().apply {
            spec.repository = null
        }
        reconciler.reconcile(primary, Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java) as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>)
        assertEquals("Error", primary.status?.phase)
        assertEquals("repository URL is required", primary.status?.message)
    }

    @Test
    fun `resource conflict becomes terminal Error`() {
        val gateway = FakeGateway(observedWithActiveJob(), conflictOnValidation = true)
        val reconciler = AgentReviewRequestReconciler(gateway, AgentReviewProperties("review-agent:1"))
        val primary = request()
        reconciler.reconcile(primary, Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java) as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>)
        assertEquals("Error", primary.status?.phase)
        assertTrue(primary.status?.message?.contains("resource agent-review-request-42 conflicts") == true)
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
    private val failJobCreationOnce: Boolean = false,
    private val conflictOnValidation: Boolean = false,
) : AgentReviewResourceGateway {
    var created: AgentReviewResources? = null
    var validated = false
    private var failedJobCreation = false

    override fun observe(namespace: String, baseName: String): ObservedAgentReviewResources = resources

    override fun validateDesired(resources: AgentReviewResources, observed: ObservedAgentReviewResources) {
        validated = true
        if (conflictOnValidation) {
            throw AgentReviewResourceConflict("existing Job agent-review-request-42 does not match desired resource")
        }
    }

    override fun createDependencies(resources: AgentReviewResources) {
        created = resources
    }

    override fun createMissing(resources: AgentReviewResources) {
        if (failJobCreationOnce && !failedJobCreation) {
            failedJobCreation = true
            throw RuntimeException("transient Job create failure")
        }
        created = resources
    }
}
