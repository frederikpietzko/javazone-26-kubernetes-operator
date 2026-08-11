package com.example

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.ServiceAccount
import io.fabric8.kubernetes.api.model.rbac.Role
import io.fabric8.kubernetes.api.model.rbac.RoleBinding
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import io.javaoperatorsdk.operator.processing.event.source.EventSource
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource
import io.javaoperatorsdk.operator.processing.event.source.informer.Mappers
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration
import org.springframework.stereotype.Component

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
        val requestName = requireNotNull(metadata.name) { "request name is required" }
        requireNotNull(primary.spec?.repository?.url) { "repository URL is required" }
        requireNotNull(primary.spec?.pr) { "pull request number is required" }

        val baseName = ResourceNameGenerator.baseName(requestName)
        val observed = gateway.observe(namespace, baseName)
        val result = observed.reviewResult
        if (result != null && !hasExpectedOwner(result, metadata.name, metadata.uid)) {
            val deletionTimestamp = result.metadata?.deletionTimestamp
            if (deletionTimestamp != null) {
                throw AgentReviewResourceConflict(
                    "review result $baseName has conflicting owner and is terminating since $deletionTimestamp",
                )
            }
            primary.status = AgentReviewRequestStatus().also {
                it.phase = ERROR_PHASE
                it.message = "review result $baseName has a conflicting owner"
                it.reviewResultName = result.metadata?.name
                it.jobName = baseName
                it.configMapName = baseName
            }
            return UpdateControl.patchStatus(primary)
        }

        val decision = reconcileOnce(primary, observed)
        when (decision) {
            is LifecycleDecision.EnsureResources -> {
                gateway.createMissing(AgentReviewResourceFactory.create(primary, properties.image))
                primary.status = decision.status
                return UpdateControl.patchStatus(primary)
            }

            is LifecycleDecision.Wait -> {
                primary.status = decision.status
                return UpdateControl.patchStatus(primary)
            }

            is LifecycleDecision.Successful -> {
                primary.status = decision.status
                return UpdateControl.patchStatus(primary)
            }

            is LifecycleDecision.Error -> {
                primary.status = decision.status
                return UpdateControl.patchStatus(primary)
            }

            LifecycleDecision.Noop -> return UpdateControl.noUpdate()
        }
    }

    override fun prepareEventSources(
        context: EventSourceContext<AgentReviewRequestCR>,
    ): List<EventSource<*, AgentReviewRequestCR>> = listOf(
        informer("configmaps", ConfigMap::class.java, context),
        informer("serviceaccounts", ServiceAccount::class.java, context),
        informer("roles", Role::class.java, context),
        informer("rolebindings", RoleBinding::class.java, context),
        informer("jobs", Job::class.java, context),
        informer("reviewresults", ReviewResultCR::class.java, context),
    )

    private fun <R : HasMetadata> informer(
        name: String,
        resourceClass: Class<R>,
        context: EventSourceContext<AgentReviewRequestCR>,
    ): EventSource<R, AgentReviewRequestCR> = InformerEventSource(
        InformerEventSourceConfiguration.from(resourceClass, AgentReviewRequestCR::class.java)
            .withName(name)
            .withSecondaryToPrimaryMapper(Mappers.fromOwnerReferences(AgentReviewRequestCR::class.java))
            .withWatchAllNamespaces()
            .build(),
        context,
    )

    private fun hasExpectedOwner(
        result: ReviewResultCR,
        requestName: String?,
        requestUid: String?,
    ): Boolean = result.metadata?.ownerReferences?.any { owner ->
        owner.apiVersion == "example.com/v1" &&
            owner.kind == "AgentReviewRequest" &&
            owner.name == requestName &&
            owner.uid == requestUid
    } == true
}
