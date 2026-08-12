package com.example

import com.example.AgentReviewRequestStatus.Companion.ERROR_PHASE
import com.example.AgentReviewRequestStatus.Companion.IN_PROGRESS_PHASE
import com.example.AgentReviewRequestStatus.Companion.SUCCESSFUL_PHASE
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.batch.v1.Job
import org.springframework.stereotype.Component

const val JOB_CREATION_PENDING_MESSAGE = "review-agent dependencies created; creating Job"

sealed interface LifecycleDecision {
    val status: AgentReviewRequestStatus

    data class EnsureResources(override val status: AgentReviewRequestStatus) : LifecycleDecision

    data class Wait(override val status: AgentReviewRequestStatus) : LifecycleDecision

    data class Successful(override val status: AgentReviewRequestStatus) : LifecycleDecision

    data class Error(override val status: AgentReviewRequestStatus) : LifecycleDecision

    data object Noop : LifecycleDecision {
        override val status: AgentReviewRequestStatus = AgentReviewRequestStatus()
    }
}

data class ObservedAgentReviewResources(
    val configMap: ConfigMap?,
    val job: Job?,
    val reviewResult: ReviewResultCR?,
)

@Component
class AgentReviewLifecycle(private val nameGenerator: ResourceNameGenerator) {
    companion object {
        private const val MISSING_RESOURCE_MESSAGE =
            "owned review-agent resource disappeared after processing started"
        private const val MISSING_RESULT_MESSAGE =
            "review-agent Job completed without publishing a result"
        private const val FAILED_RESULT_MESSAGE = "review-agent reported failure"
        private const val MISSING_JOB_MESSAGE =
            "review-agent Job disappeared after dependent resources were created"
    }

    @Suppress("ReturnCount")
    fun decide(
        request: AgentReviewRequestCR,
        desiredState: DesiredAgentReviewState,
        observed: ObservedAgentReviewResources,
    ): LifecycleDecision {
        if (request.status?.phase in [SUCCESSFUL_PHASE, ERROR_PHASE]) return LifecycleDecision.Noop

        val names = desiredNames(desiredState)

        val missingResourceDecision = checkForMissingResources(request, observed, names)
        if (missingResourceDecision != null) {
            return missingResourceDecision
        }

        val jobFailedDecision = checkIfJobFailed(observed, names)
        if (jobFailedDecision != null) {
            return jobFailedDecision
        }

        val reviewResultDecision = checkReviewResultStatus(observed, names)
        if (reviewResultDecision != null) {
            return reviewResultDecision
        }

        val inProgressStatus =
            AgentReviewRequestStatus.inProgress(
                jobName = names.jobName,
                configMapName = names.configMapName,
                reviewResultName = names.reviewResultName,
            )

        val creationPendingStatus = inProgressStatus.copy(message = JOB_CREATION_PENDING_MESSAGE)

        return when {
            observed.hasAllDependentResources() -> LifecycleDecision.Wait(inProgressStatus)

            request.status?.phase == null && observed.job == null ->
                LifecycleDecision.EnsureResources(creationPendingStatus)

            else -> LifecycleDecision.EnsureResources(inProgressStatus)
        }
    }

    private fun checkIfResultMissing(
        observed: ObservedAgentReviewResources,
        names: DesiredNames,
    ): LifecycleDecision? {
        val result = observed.reviewResult
        val job = observed.job
        return if (result == null && job != null && job.completed()) {
            LifecycleDecision.Error(
                AgentReviewRequestStatus.error(
                    message = MISSING_RESULT_MESSAGE,
                    jobName = names.jobName,
                    configMapName = names.configMapName,
                    reviewResultName = names.reviewResultName,
                )
            )
        } else null
    }

    private fun checkReviewResultStatus(
        observed: ObservedAgentReviewResources,
        names: DesiredNames,
    ): LifecycleDecision? {
        val result = observed.reviewResult ?: return checkIfResultMissing(observed, names)

        val resultStatus = result.status?.status
        return when (resultStatus) {
            "Completed" ->
                LifecycleDecision.Successful(
                    AgentReviewRequestStatus.success(
                        jobName = names.jobName,
                        configMapName = names.configMapName,
                        reviewResultName = result.metadata?.name ?: names.reviewResultName,
                    )
                )

            "Failed" ->
                LifecycleDecision.Error(
                    AgentReviewRequestStatus.error(
                        message = result.status?.error ?: FAILED_RESULT_MESSAGE,
                        jobName = names.jobName,
                        configMapName = names.configMapName,
                        reviewResultName = result.metadata?.name ?: names.reviewResultName,
                    )
                )

            else ->
                LifecycleDecision.Wait(
                    AgentReviewRequestStatus.inProgress(
                        jobName = names.jobName,
                        configMapName = names.configMapName,
                        reviewResultName = result.metadata?.name ?: names.reviewResultName,
                    )
                )
        }
    }

    private fun checkForMissingResources(
        request: AgentReviewRequestCR,
        observed: ObservedAgentReviewResources,
        names: DesiredNames,
    ): LifecycleDecision? {
        val currentPhase = request.status?.phase
        val configMapExists = observed.configMap != null
        val jobExists = observed.job != null
        val jobIsMissing = configMapExists && !jobExists

        return when {
            currentPhase == IN_PROGRESS_PHASE && !configMapExists ->
                LifecycleDecision.Error(
                    AgentReviewRequestStatus.error(
                        message = MISSING_RESOURCE_MESSAGE,
                        jobName = names.jobName,
                        configMapName = names.configMapName,
                        reviewResultName = names.reviewResultName,
                    )
                )

            jobIsMissing && request.isMissingProgressMessage() -> {
                LifecycleDecision.EnsureResources(
                    AgentReviewRequestStatus.inProgress(
                        message = JOB_CREATION_PENDING_MESSAGE,
                        jobName = names.jobName,
                        configMapName = names.configMapName,
                        reviewResultName = names.reviewResultName,
                    )
                )
            }
            jobIsMissing -> {
                LifecycleDecision.Error(
                    AgentReviewRequestStatus.error(
                        message = MISSING_JOB_MESSAGE,
                        jobName = names.jobName,
                        configMapName = names.configMapName,
                        reviewResultName = names.reviewResultName,
                    )
                )
            }
            else -> null
        }
    }

    private fun checkIfJobFailed(
        observed: ObservedAgentReviewResources,
        names: DesiredNames,
    ): LifecycleDecision? {
        val job = observed.job
        if (job == null || !job.failed()) return null
        return LifecycleDecision.Error(
            AgentReviewRequestStatus.error(
                job.failureMessage(),
                jobName = names.jobName,
                configMapName = names.configMapName,
                reviewResultName = names.reviewResultName,
            )
        )
    }

    private fun AgentReviewRequestCR.isMissingProgressMessage(): Boolean {
        val currentPhase = status?.phase
        return currentPhase == null ||
            currentPhase == "Pending" ||
            (currentPhase == IN_PROGRESS_PHASE && status?.message == JOB_CREATION_PENDING_MESSAGE)
    }

    private fun desiredNames(state: DesiredAgentReviewState): DesiredNames {
        val baseName = nameGenerator.generateName(state.requestName)
        return DesiredNames(baseName, baseName, baseName)
    }

    private data class DesiredNames(
        val jobName: String,
        val configMapName: String,
        val reviewResultName: String,
    )
}

private fun ObservedAgentReviewResources.hasAllDependentResources(): Boolean =
    configMap != null && job != null

private fun Job.failed(): Boolean =
    status?.failed?.let { it > 0 } == true ||
        status?.conditions?.any { it.type == "Failed" && it.status == "True" } == true

private fun Job.completed(): Boolean =
    status?.succeeded?.let { it > 0 } == true ||
        status?.conditions?.any { it.type == "Complete" && it.status == "True" } == true

private fun Job.failureMessage(): String =
    status
        ?.conditions
        ?.firstOrNull { it.type == "Failed" && it.status == "True" }
        ?.message
        ?.takeIf { it.isNotBlank() } ?: "review-agent Job failed"
