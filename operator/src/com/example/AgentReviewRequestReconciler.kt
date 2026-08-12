package com.example

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
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
    private val gateway: AgentReviewResourceGateway,
    private val properties: AgentReviewProperties,
) : Reconciler<AgentReviewRequestCR> {
    fun reconcileOnce(
        primary: AgentReviewRequestCR,
        observed: ObservedAgentReviewResources,
    ): LifecycleDecision = AgentReviewLifecycle.decide(primary, observed)

    override fun reconcile(
        primary: AgentReviewRequestCR,
        context: Context<AgentReviewRequestCR>,
    ): UpdateControl<AgentReviewRequestCR> {
        val currentPhase = primary.status?.phase
        if (currentPhase == SUCCESSFUL_PHASE || currentPhase == ERROR_PHASE) {
            return UpdateControl.noUpdate()
        }

        val metadata = requireNotNull(primary.metadata) { "request metadata is required" }
        val namespace = requireNotNull(metadata.namespace) { "request namespace is required" }
        if (namespace != "default") {
            return UpdateControl.noUpdate()
        }
        val requestName = requireNotNull(metadata.name) { "request name is required" }
        requireNotNull(metadata.uid) { "request UID is required" }
        if (primary.spec?.repository?.url == null) {
            return patchStatusIfChanged(primary, terminalErrorStatus("repository URL is required"))
        }
        if (primary.spec.pr == null) {
            return patchStatusIfChanged(
                primary,
                terminalErrorStatus("pull request number is required"),
            )
        }

        val baseName = ResourceNameGenerator.baseName(requestName)
        val observed = gateway.observe(namespace, baseName)
        val desired =
            AgentReviewResourceFactory.create(primary, properties.image, properties.openAiBaseUrl)
        try {
            gateway.validateDesired(desired, observed)
        } catch (conflict: AgentReviewResourceConflict) {
            return patchStatusIfChanged(primary, conflictStatus(baseName, conflict))
        }
        val result = observed.reviewResult
        if (result != null && !hasExpectedOwner(result, metadata.name, metadata.uid)) {
            val deletionTimestamp = result.metadata?.deletionTimestamp
            if (deletionTimestamp != null) {
                throw AgentReviewResourceConflict(
                    "review result $baseName has conflicting owner and is terminating since $deletionTimestamp"
                )
            }
            return patchStatusIfChanged(
                primary,
                AgentReviewRequestStatus().also {
                    it.phase = ERROR_PHASE
                    it.message = "review result $baseName has a conflicting owner"
                    it.reviewResultName = result.metadata?.name
                    it.jobName = baseName
                    it.configMapName = baseName
                },
            )
        }

        when (val decision = reconcileOnce(primary, observed)) {
            is LifecycleDecision.EnsureResources -> {
                try {
                    if (primary.status?.phase == null && observed.job == null) {
                        gateway.createDependencies(desired)
                    } else {
                        gateway.createMissing(desired)
                    }
                } catch (conflict: AgentReviewResourceConflict) {
                    return patchStatusIfChanged(primary, conflictStatus(baseName, conflict))
                }
                return patchStatusIfChanged(primary, decision.status)
            }

            is LifecycleDecision.Wait -> return patchStatusIfChanged(primary, decision.status)
            is LifecycleDecision.Successful -> return patchStatusIfChanged(primary, decision.status)
            is LifecycleDecision.Error -> return patchStatusIfChanged(primary, decision.status)
            LifecycleDecision.Noop -> return UpdateControl.noUpdate()
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

    private fun hasExpectedOwner(
        result: ReviewResultCR,
        requestName: String?,
        requestUid: String?,
    ): Boolean =
        result.metadata?.ownerReferences?.any { owner ->
            owner.apiVersion == "example.com/v1" &&
                owner.kind == "AgentReviewRequest" &&
                owner.name == requestName &&
                owner.uid == requestUid
        } == true
}

private fun terminalErrorStatus(message: String): AgentReviewRequestStatus =
    AgentReviewRequestStatus().also {
        it.phase = ERROR_PHASE
        it.message = message
    }

private fun conflictStatus(
    baseName: String,
    conflict: AgentReviewResourceConflict,
): AgentReviewRequestStatus =
    AgentReviewRequestStatus().also {
        it.phase = ERROR_PHASE
        it.message =
            "resource $baseName conflicts with desired state: ${conflict.message ?: "unknown conflict"}"
        it.jobName = baseName
        it.configMapName = baseName
        it.reviewResultName = baseName
    }

internal fun patchStatusIfChanged(
    primary: AgentReviewRequestCR,
    desired: AgentReviewRequestStatus,
): UpdateControl<AgentReviewRequestCR> {
    if (sameStatus(primary.status, desired)) {
        return UpdateControl.noUpdate()
    }
    primary.status = desired
    return UpdateControl.patchStatus(primary)
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
