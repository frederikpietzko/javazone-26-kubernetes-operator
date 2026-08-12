package com.example

import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext
import kotlin.reflect.full.findAnnotation
import kotlin.test.*
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component

@SpringBootTest
@Import(TestOperatorConfiguration::class)
class AgentReviewRequestReconcilerTest {
    private fun newReconciler(
        gateway: AgentReviewClient = FakeGateway()
    ): AgentReviewRequestReconciler {
        val nameGenerator = ResourceNameGenerator()
        return AgentReviewRequestReconciler(
            gateway,
            AgentReviewProperties("review-agent:1"),
            nameGenerator,
            AgentReviewLifecycle(nameGenerator),
            AgentReviewResourceFactory(nameGenerator),
        )
    }

    @Autowired lateinit var applicationContext: ApplicationContext

    @Test
    fun `registers AgentReviewRequest controller`() {
        assertNotNull(applicationContext.getBean(AgentReviewRequestReconciler::class.java))
    }

    @Test
    fun `new request produces EnsureResources decision`() {
        val reconciler = newReconciler()
        val decision =
            reconciler.reconcileOnce(
                request(),
                ObservedAgentReviewResources(null, null, null),
                desiredState(),
            )
        assertIs<LifecycleDecision.EnsureResources>(decision)
    }

    @Test
    fun `terminal result produces Successful decision without resource creation`() {
        val result =
            ReviewResultCR().apply {
                status = ReviewResultStatus().also { it.status = "Completed" }
            }
        val reconciler = newReconciler()
        val decision = reconciler.reconcileOnce(
            request(),
            observedWithCompletedJob(result),
            desiredState(),
        )
        assertEquals("Successful", assertIs<LifecycleDecision.Successful>(decision).status.phase)
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
    fun `reconcile validates desired resources before waiting`() {
        val gateway = FakeGateway(observedWithActiveJob())
        val reconciler = newReconciler(gateway)
        val primary =
            request().apply {
                status =
                    AgentReviewRequestStatus().also {
                        it.phase = "InProgress"
                        it.jobName = "agent-review-request-42"
                        it.configMapName = "agent-review-request-42"
                        it.reviewResultName = "agent-review-request-42"
                    }
            }
        reconciler.reconcile(
            primary,
            Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java)
                as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>,
        )
        assertTrue(gateway.validated)
    }

    @Test
    fun `conflicting result owner produces Error without creation`() {
        val result = ReviewResultCR().apply { metadata = request().metadata }
        result.metadata.ownerReferences =
            listOf(
                io.fabric8.kubernetes.api.model
                    .OwnerReferenceBuilder()
                    .withApiVersion("example.com/v1")
                    .withKind("AgentReviewRequest")
                    .withName("other-request")
                    .withUid("other-uid")
                    .build()
            )
        val gateway = FakeGateway(observedWithActiveJob(result))
        val reconciler = newReconciler(gateway)
        val primary = request()
        reconciler.reconcile(
            primary,
            Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java)
                as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>,
        )
        assertEquals("Error", primary.status?.phase)
        assertNull(gateway.created)
    }

    @Test
    fun `missing job after normal processing does not create a replacement`() {
        val gateway = FakeGateway(observedWithActiveJob().copy(job = null))
        val reconciler = newReconciler(gateway)
        val primary =
            request().apply {
                status =
                    AgentReviewRequestStatus().also {
                        it.phase = "InProgress"
                        it.jobName = "agent-review-request-42"
                        it.configMapName = "agent-review-request-42"
                        it.reviewResultName = "agent-review-request-42"
                    }
            }
        reconciler.reconcile(
            primary,
            Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java)
                as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>,
        )
        assertNull(gateway.created)
        assertEquals("Error", primary.status?.phase)
    }

    @Test
    fun `transient Job creation failure remains retryable during creation phase`() {
        val gateway =
            FakeGateway(observedWithActiveJob().copy(job = null), failJobCreationOnce = true)
        val reconciler = newReconciler(gateway)
        val primary =
            request().apply {
                status =
                    AgentReviewRequestStatus().also {
                        it.phase = "InProgress"
                        it.message = JOB_CREATION_PENDING_MESSAGE
                    }
            }
        assertFailsWith<RuntimeException> {
            reconciler.reconcile(
                primary,
                Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java)
                    as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>,
            )
        }
        assertEquals(JOB_CREATION_PENDING_MESSAGE, primary.status?.message)
        reconciler.reconcile(
            primary,
            Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java)
                as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>,
        )
        assertNotNull(gateway.created)
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
    fun `resource conflict becomes terminal Error`() {
        val gateway = FakeGateway(observedWithActiveJob(), conflictOnValidation = true)
        val reconciler = newReconciler(gateway)
        val primary = request()
        reconciler.reconcile(
            primary,
            Mockito.mock(io.javaoperatorsdk.operator.api.reconciler.Context::class.java)
                as io.javaoperatorsdk.operator.api.reconciler.Context<AgentReviewRequestCR>,
        )
        assertEquals("Error", primary.status?.phase)
        assertEquals(
            true,
            primary.status?.message?.contains("resource agent-review-request-42 conflicts"),
        )
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
        ObservedAgentReviewResources(null, null, null),
    private val failJobCreationOnce: Boolean = false,
    private val conflictOnValidation: Boolean = false,
) : AgentReviewClient {
    var created: AgentReviewResources? = null
    var validated = false
    var observed = false
    private var failedJobCreation = false

    override fun observe(namespace: String, baseName: String): ObservedAgentReviewResources {
        observed = true
        return resources
    }

    override fun validateDesired(
        resources: AgentReviewResources,
        metadata: io.fabric8.kubernetes.api.model.ObjectMeta,
        observed: ObservedAgentReviewResources,
    ): List<ResourceComparisonResult.Conflict> {
        validated = true
        if (conflictOnValidation) {
            return listOf(
                ResourceComparisonResult.Conflict(
                    "existing Job agent-review-request-42 does not match desired resource"
                )
            )
        }
        val result = observed.reviewResult
        if (
            result != null &&
                result.metadata?.ownerReferences?.none { owner ->
                    owner.apiVersion == "example.com/v1" &&
                        owner.kind == "AgentReviewRequest" &&
                        owner.name == metadata.name &&
                        owner.uid == metadata.uid
                } == true
        ) {
            return listOf(
                ResourceComparisonResult.Conflict(
                    "review result ${result.metadata?.name} has a conflicting owner"
                )
            )
        }
        return emptyList()
    }

    override fun createDependencies(resources: AgentReviewResources): ResourceComparisonResult {
        created = resources
        return ResourceComparisonResult.Equal
    }

    override fun createMissing(resources: AgentReviewResources): ResourceComparisonResult {
        if (failJobCreationOnce && !failedJobCreation) {
            failedJobCreation = true
            throw RuntimeException("transient Job create failure")
        }
        created = resources
        return ResourceComparisonResult.Equal
    }
}
