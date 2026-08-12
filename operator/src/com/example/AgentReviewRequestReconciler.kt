package com.example

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration
import io.javaoperatorsdk.operator.api.reconciler.*
import io.javaoperatorsdk.operator.processing.event.source.EventSource
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource
import io.javaoperatorsdk.operator.processing.event.source.informer.Mappers
import org.springframework.stereotype.Component

internal val AGENT_REVIEW_EVENT_SOURCE_NAMES =
    listOf(
        "configmaps",
        "jobs",
        "reviewresults",
    )

@Component
@ControllerConfiguration(name = "agent-review-request")
class AgentReviewRequestReconciler(
    private val agentReviewClient: AgentReviewClient,
    private val properties: AgentReviewProperties,
    private val nameGenerator: ResourceNameGenerator,
    private val lifecycle: AgentReviewLifecycle,
    private val agentReviewFactory: AgentReviewResourceFactory,
) : Reconciler<AgentReviewRequestCR> {
    fun reconcileOnce(
        primary: AgentReviewRequestCR,
        observed: ObservedAgentReviewResources,
    ): LifecycleDecision = lifecycle.decide(primary, observed)

    @Suppress("ReturnCount")
    override fun reconcile(
        primary: AgentReviewRequestCR,
        context: Context<AgentReviewRequestCR>,
    ): UpdateControl<AgentReviewRequestCR> {
        val reconciliationRequest =
            when (val result = checkPreconditions(primary)) {
                is PreconditionResult.Invalid -> return result.update
                is PreconditionResult.Valid -> result.request
            }

        val (metadata, namespace, requestName) = reconciliationRequest

        val baseName = nameGenerator.generateName(requestName)
        val observed = agentReviewClient.observe(namespace, baseName)
        val desired =
            agentReviewFactory.create(
                request = primary,
                image = properties.image,
                openAiBaseUrl = properties.openAiBaseUrl,
            )

        val conflicts = agentReviewClient.validateDesired(desired, metadata, observed)
        if (conflicts.isNotEmpty()) {
            return AgentReviewRequestStatus.stateConflict(
                    conflicts.joinToString { it.message },
                    baseName,
                )
                .updateIfChanged(primary)
        }

        return when (val decision = reconcileOnce(primary, observed)) {
            is LifecycleDecision.EnsureResources -> {
                val conflict = agentReviewClient.createMissing(desired)
                if (conflict is ResourceComparisonResult.Conflict) {
                    return AgentReviewRequestStatus.stateConflict(conflict.message, baseName)
                        .updateIfChanged(primary)
                }
                decision.status.updateIfChanged(primary)
            }

            is LifecycleDecision.Wait,
            is LifecycleDecision.Successful,
            is LifecycleDecision.Error -> decision.status.updateIfChanged(primary)
            LifecycleDecision.Noop -> UpdateControl.noUpdate()
        }
    }

    override fun prepareEventSources(
        context: EventSourceContext<AgentReviewRequestCR>
    ): List<EventSource<*, AgentReviewRequestCR>> =
        listOf(
            informer("configmaps", ConfigMap::class.java, context),
            informer("jobs", Job::class.java, context),
            informer("reviewresults", ReviewResultCR::class.java, context),
        )

    private fun <R : HasMetadata> informer(
        name: String,
        resourceClass: Class<R>,
        context: EventSourceContext<AgentReviewRequestCR>,
    ): EventSource<R, AgentReviewRequestCR> =
        InformerEventSource(
            InformerEventSourceConfiguration.from(resourceClass, AgentReviewRequestCR::class.java)
                .withName(name)
                .withSecondaryToPrimaryMapper(
                    Mappers.fromOwnerReferences(AgentReviewRequestCR::class.java)
                )
                .withNamespaces("default")
                .build(),
            context,
        )
}

internal fun updateIfStatusChanged(
    current: AgentReviewRequestCR,
    desired: AgentReviewRequestStatus,
): UpdateControl<AgentReviewRequestCR> {
    if (sameStatus(current.status, desired)) {
        return UpdateControl.noUpdate()
    }
    current.status = desired
    return UpdateControl.patchStatus(current)
}

fun AgentReviewRequestStatus.updateIfChanged(
    current: AgentReviewRequestCR
): UpdateControl<AgentReviewRequestCR> =
    if (sameStatus(current.status, this)) {
        UpdateControl.noUpdate()
    } else {
        current.status = this
        UpdateControl.patchStatus(current)
    }

internal fun sameStatus(
    current: AgentReviewRequestStatus?,
    desired: AgentReviewRequestStatus,
): Boolean =
    current != null &&
        current.phase == desired.phase &&
        current.message == desired.message &&
        current.jobName == desired.jobName &&
        current.configMapName == desired.configMapName &&
        current.reviewResultName == desired.reviewResultName

private data class ReconciliationRequest(
    val metadata: ObjectMeta,
    val namespace: String,
    val requestName: String,
)

private sealed interface PreconditionResult {
    data class Valid(val request: ReconciliationRequest) : PreconditionResult

    data class Invalid(val update: UpdateControl<AgentReviewRequestCR>) : PreconditionResult
}

private fun checkPreconditions(primary: AgentReviewRequestCR): PreconditionResult {
    val metadata = requireNotNull(primary.metadata) { "request metadata is required" }
    val namespace = requireNotNull(metadata.namespace) { "request namespace is required" }
    val requestName = requireNotNull(primary.metadata.name) { "request name is required" }
    requireNotNull(metadata.uid) { "request UID is required" }

    return when {
        namespace != "default" -> PreconditionResult.Invalid(UpdateControl.noUpdate())

        primary.status?.phase in [SUCCESSFUL_PHASE, ERROR_PHASE] ->
            PreconditionResult.Invalid(UpdateControl.noUpdate())

        primary.spec.repository?.url == null ->
            PreconditionResult.Invalid(
                AgentReviewRequestStatus.error("repository URL is required")
                    .updateIfChanged(primary)
            )

        primary.spec.pr == null ->
            PreconditionResult.Invalid(
                AgentReviewRequestStatus.error("PR number is required").updateIfChanged(primary)
            )

        else -> PreconditionResult.Valid(ReconciliationRequest(metadata, namespace, requestName))
    }
}
