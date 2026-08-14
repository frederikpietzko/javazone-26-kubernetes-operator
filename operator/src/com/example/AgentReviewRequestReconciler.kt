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
    private val agentReviewClient: AgentReviewClient,
    private val properties: AgentReviewProperties,
    private val agentReviewFactory: AgentReviewResourceFactory,
    private val nameGenerator: ResourceNameGenerator,
) : Reconciler<AgentReviewRequestCR> {

    companion object {
        const val JOB_CREATION_PENDING_MESSAGE = "review-agent dependencies created; creating Job"
        const val JOB_IN_PROGRESS_MESSAGE = "review-agent Job is running"
        const val JOB_FAILED_MESSAGE = "review-agent Job failed"
        const val JOB_SUCCESSFUL_BUT_RESULT_MISSING =
            "review-agent Job completed successfully, but no ReviewResult found"
    }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    override fun reconcile(
        primary: AgentReviewRequestCR,
        context: Context<AgentReviewRequestCR>,
    ): UpdateControl<AgentReviewRequestCR> {
        val desiredState =
            when (val result = checkPreconditions(primary)) {
                is PreconditionResult.Invalid -> return result.update
                is PreconditionResult.Valid -> result.state
            }
        val name = nameGenerator.generateName(desiredState.requestName)
        val (configMap, job, reviewResult) = agentReviewClient.observe(desiredState.namespace, name)
        val desiredResources =
            agentReviewFactory.create(
                desiredState = desiredState,
                image = properties.image,
                openAiBaseUrl = properties.openAiBaseUrl,
            )

        val phase = primary.status?.phase

        if (configMap == null) {
            val configMap = context.client.resource(desiredResources.configMap).create()
            return AgentReviewRequestStatus.inProgress(
                    message = JOB_CREATION_PENDING_MESSAGE,
                    configMapName = configMap.metadata.name,
                    jobName = job?.metadata?.name ?: "unkown",
                    reviewResultName = reviewResult?.metadata?.name ?: "unknown",
                )
                .updateIfChanged(current = primary)
        }

        if (job == null) {
            val job = context.client.resource(desiredResources.job).create()
            return AgentReviewRequestStatus.inProgress(
                    message = JOB_CREATION_PENDING_MESSAGE,
                    configMapName = configMap.metadata.name,
                    jobName = job.metadata.name,
                    reviewResultName = reviewResult?.metadata?.name ?: "unknown",
                )
                .updateIfChanged(current = primary)
        }
        val status = job.identifyStatus()

        if (
            status == JobStatus.ACTIVE &&
                phase == AgentReviewRequestStatus.IN_PROGRESS_PHASE &&
                primary.status.message == JOB_CREATION_PENDING_MESSAGE
        ) {
            return AgentReviewRequestStatus.inProgress(
                    message = JOB_IN_PROGRESS_MESSAGE,
                    configMapName = configMap.metadata.name,
                    jobName = job.metadata.name,
                    reviewResultName = reviewResult?.metadata?.name ?: "unknown",
                )
                .updateIfChanged(current = primary)
        }

        if (status == JobStatus.FAILED && phase == AgentReviewRequestStatus.IN_PROGRESS_PHASE) {
            return AgentReviewRequestStatus.error(
                    message = JOB_FAILED_MESSAGE,
                    configMapName = configMap.metadata.name,
                    jobName = job.metadata.name,
                    reviewResultName = reviewResult?.metadata?.name ?: "unknown",
                )
                .updateIfChanged(current = primary)
        }
        if (reviewResult == null) {
            if (
                status == JobStatus.SUCCESSFUL &&
                    phase == AgentReviewRequestStatus.IN_PROGRESS_PHASE
            ) {
                return AgentReviewRequestStatus.error(
                        message = JOB_SUCCESSFUL_BUT_RESULT_MISSING,
                        configMapName = configMap.metadata.name,
                        jobName = job.metadata.name,
                        reviewResultName = "unknown",
                    )
                    .updateIfChanged(current = primary)
            }
            return UpdateControl.noUpdate()
        }

        if (status == JobStatus.SUCCESSFUL && phase == AgentReviewRequestStatus.IN_PROGRESS_PHASE) {
            return AgentReviewRequestStatus.success(
                    configMapName = configMap.metadata.name,
                    jobName = job.metadata.name,
                    reviewResultName = reviewResult.metadata.name,
                )
                .updateIfChanged(current = primary)
        }
        return UpdateControl.noUpdate()
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
