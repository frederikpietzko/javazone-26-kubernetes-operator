package com.example

import com.example.AgentReviewRequestStatus.Companion.ERROR_PHASE
import com.example.AgentReviewRequestStatus.Companion.SUCCESSFUL_PHASE
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
        desiredState: DesiredAgentReviewState,
    ): LifecycleDecision =
        lifecycle.decide(
            request = primary,
            observed = observed,
            desiredState = desiredState,
        )

    @Suppress("ReturnCount")
    override fun reconcile(
        primary: AgentReviewRequestCR,
        context: Context<AgentReviewRequestCR>,
    ): UpdateControl<AgentReviewRequestCR> {
        val desiredState =
            when (val result = checkPreconditions(primary)) {
                is PreconditionResult.Invalid -> return result.update
                is PreconditionResult.Valid -> result.state
            }

        val (metadata, namespace, requestName) = desiredState

        val baseName = nameGenerator.generateName(requestName)
        val observed = agentReviewClient.observe(namespace, baseName)
        val desired =
            agentReviewFactory.create(
                desiredState = desiredState,
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

        val decision =
            reconcileOnce(
                primary = primary,
                observed = observed,
                desiredState = desiredState,
            )

        return when (decision) {
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

fun AgentReviewRequestStatus.updateIfChanged(
    current: AgentReviewRequestCR
): UpdateControl<AgentReviewRequestCR> =
    if (this == current.status) {
        UpdateControl.noUpdate()
    } else {
        current.status = this
        UpdateControl.patchStatus(current)
    }

data class DesiredAgentReviewState(
    val metadata: ObjectMeta,
    val namespace: String,
    val requestName: String,
    val uid: String,
    val repositoryUrl: String,
    val pr: String,
)

private sealed interface PreconditionResult {
    data class Valid(val state: DesiredAgentReviewState) : PreconditionResult

    data class Invalid(val update: UpdateControl<AgentReviewRequestCR>) : PreconditionResult
}

private fun checkPreconditions(primary: AgentReviewRequestCR): PreconditionResult {
    val metadata = requireNotNull(primary.metadata) { "request metadata is required" }
    val namespace = requireNotNull(metadata.namespace) { "request namespace is required" }
    val requestName = requireNotNull(primary.metadata.name) { "request name is required" }
    val uid = requireNotNull(metadata.uid) { "request UID is required" }
    val repositoryUrl = primary.spec.repository?.url
    val pr = primary.spec.pr

    return when {
        namespace != "default" -> PreconditionResult.Invalid(UpdateControl.noUpdate())

        primary.status?.phase in [SUCCESSFUL_PHASE, ERROR_PHASE] ->
            PreconditionResult.Invalid(UpdateControl.noUpdate())

        repositoryUrl == null ->
            PreconditionResult.Invalid(
                AgentReviewRequestStatus.error("repository URL is required")
                    .updateIfChanged(primary)
            )

        pr == null ->
            PreconditionResult.Invalid(
                AgentReviewRequestStatus.error("PR number is required").updateIfChanged(primary)
            )

        else ->
            PreconditionResult.Valid(
                DesiredAgentReviewState(
                    metadata,
                    namespace,
                    requestName,
                    uid,
                    repositoryUrl,
                    pr,
                )
            )
    }
}
