package com.example

import io.fabric8.kubernetes.api.model.batch.v1.Job

const val IN_PROGRESS_PHASE = "InProgress"
const val SUCCESSFUL_PHASE = "Successful"
const val ERROR_PHASE = "Error"

sealed interface LifecycleDecision {
    data class EnsureResources(val status: AgentReviewRequestStatus) : LifecycleDecision
    data class Wait(val status: AgentReviewRequestStatus) : LifecycleDecision
    data class Successful(val status: AgentReviewRequestStatus) : LifecycleDecision
    data class Error(val status: AgentReviewRequestStatus) : LifecycleDecision
    data object Noop : LifecycleDecision
}

data class ObservedAgentReviewResources(
    val configMap: io.fabric8.kubernetes.api.model.ConfigMap?,
    val serviceAccount: io.fabric8.kubernetes.api.model.ServiceAccount?,
    val role: io.fabric8.kubernetes.api.model.rbac.Role?,
    val roleBinding: io.fabric8.kubernetes.api.model.rbac.RoleBinding?,
    val job: Job?,
    val reviewResult: ReviewResultCR?,
)

object AgentReviewLifecycle {
    private const val MISSING_RESOURCE_MESSAGE = "owned review-agent resource disappeared after processing started"
    private const val MISSING_RESULT_MESSAGE = "review-agent Job completed without publishing a result"
    private const val FAILED_JOB_MESSAGE = "review-agent Job failed"
    private const val FAILED_RESULT_MESSAGE = "review-agent reported failure"
    private const val MISSING_JOB_MESSAGE = "review-agent Job disappeared after dependent resources were created"

    fun decide(
        request: AgentReviewRequestCR,
        observed: ObservedAgentReviewResources,
    ): LifecycleDecision {
        val currentPhase = request.status?.phase
        if (currentPhase == SUCCESSFUL_PHASE || currentPhase == ERROR_PHASE) {
            return LifecycleDecision.Noop
        }

        val names = desiredNames(request)
        val status = status(phase = IN_PROGRESS_PHASE, names = names)
        val allResourcesExist = observed.hasAllDependentResources()

        if (currentPhase == IN_PROGRESS_PHASE && !allResourcesExist) {
            return LifecycleDecision.Error(status(ERROR_PHASE, names, MISSING_RESOURCE_MESSAGE))
        }

        if (observed.job == null && observed.hasAllNonJobResources()) {
            return LifecycleDecision.Error(status(ERROR_PHASE, names, MISSING_JOB_MESSAGE))
        }

        observed.job?.let { job ->
            if (job.failed()) {
                return LifecycleDecision.Error(
                    status(ERROR_PHASE, names, job.failureMessage()),
                )
            }
        }

        observed.reviewResult?.let { result ->
            val resultStatus = result.status?.status
            return when (resultStatus) {
                "Completed" -> LifecycleDecision.Successful(
                    status(
                        SUCCESSFUL_PHASE,
                        names,
                        null,
                    ).also { it.reviewResultName = result.metadata?.name ?: names.reviewResultName },
                )

                "Failed" -> LifecycleDecision.Error(
                    status(
                        ERROR_PHASE,
                        names,
                        result.status?.error ?: FAILED_RESULT_MESSAGE,
                    ).also { it.reviewResultName = result.metadata?.name ?: names.reviewResultName },
                )

                else -> LifecycleDecision.Wait(
                    status(IN_PROGRESS_PHASE, names).also {
                        it.reviewResultName = result.metadata?.name ?: names.reviewResultName
                    },
                )
            }
        }

        observed.job?.let { job ->
            if (job.completed()) {
                return LifecycleDecision.Error(status(ERROR_PHASE, names, MISSING_RESULT_MESSAGE))
            }
        }

        return if (allResourcesExist) {
            LifecycleDecision.Wait(status)
        } else {
            LifecycleDecision.EnsureResources(status)
        }
    }

    private fun desiredNames(request: AgentReviewRequestCR): DesiredNames {
        val requestName = requireNotNull(request.metadata?.name) { "request name is required" }
        val baseName = ResourceNameGenerator.baseName(requestName)
        return DesiredNames(baseName, baseName, baseName)
    }

    private fun status(phase: String, names: DesiredNames, message: String? = null): AgentReviewRequestStatus =
        AgentReviewRequestStatus().also {
            it.phase = phase
            it.message = message
            it.jobName = names.jobName
            it.configMapName = names.configMapName
            it.reviewResultName = names.reviewResultName
        }

    private data class DesiredNames(
        val jobName: String,
        val configMapName: String,
        val reviewResultName: String,
    )
}

private fun ObservedAgentReviewResources.hasAllDependentResources(): Boolean =
    hasAllNonJobResources() && job != null

private fun ObservedAgentReviewResources.hasAllNonJobResources(): Boolean =
    configMap != null && serviceAccount != null && role != null && roleBinding != null

private fun Job.failed(): Boolean =
    status?.failed?.let { it > 0 } == true ||
        status?.conditions?.any { it.type == "Failed" && it.status == "True" } == true

private fun Job.completed(): Boolean =
    status?.succeeded?.let { it > 0 } == true ||
        status?.conditions?.any { it.type == "Complete" && it.status == "True" } == true

private fun Job.failureMessage(): String =
    status?.conditions
        ?.firstOrNull { it.type == "Failed" && it.status == "True" }
        ?.message
        ?.takeIf { it.isNotBlank() }
        ?: "review-agent Job failed"
