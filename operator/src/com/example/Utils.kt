package com.example

import com.example.AgentReviewRequestStatus.Companion.ERROR_PHASE
import com.example.AgentReviewRequestStatus.Companion.SUCCESSFUL_PHASE
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobSpec
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl

enum class JobStatus {
    ACTIVE,
    FAILED,
    SUCCESSFUL,
}

fun Job.identifyStatus(): JobStatus {
    if (status == null) return JobStatus.ACTIVE
    return when {
        (status.active ?: 0) > 0 -> JobStatus.ACTIVE
        (status.failed ?: 0) > 0 -> JobStatus.FAILED
        (status.succeeded ?: 0) > 0 -> JobStatus.SUCCESSFUL
        else -> JobStatus.ACTIVE
    }
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

sealed interface PreconditionResult {
    data class Valid(val state: DesiredAgentReviewState) : PreconditionResult

    data class Invalid(val update: UpdateControl<AgentReviewRequestCR>) : PreconditionResult
}

fun checkPreconditions(primary: AgentReviewRequestCR): PreconditionResult {
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

fun ownerReference(block: OwnerReference.() -> Unit): OwnerReference {
    return OwnerReference().apply(block)
}

fun configMap(block: ConfigMap.() -> Unit): ConfigMap {
    return ConfigMap().apply(block)
}

fun objectMeta(block: ObjectMeta.() -> Unit): ObjectMeta {
    return ObjectMeta().apply(block)
}

fun job(block: Job.() -> Unit): Job {
    return Job().apply(block)
}

fun jobSpec(block: JobSpec.() -> Unit): JobSpec {
    return JobSpec().apply(block)
}

fun podTemplate(block: PodTemplateSpec.() -> Unit): PodTemplateSpec {
    return PodTemplateSpec().apply(block)
}

fun podSpec(block: PodSpec.() -> Unit): PodSpec {
    return PodSpec().apply(block)
}

fun volume(block: Volume.() -> Unit): Volume {
    return Volume().apply(block)
}

fun volumeMount(block: VolumeMount.() -> Unit): VolumeMount {
    return VolumeMount().apply(block)
}

fun configMapVolumeSource(block: ConfigMapVolumeSource.() -> Unit): ConfigMapVolumeSource {
    return ConfigMapVolumeSource().apply(block)
}

fun container(block: Container.() -> Unit): Container {
    return Container().apply(block)
}

fun envVar(block: EnvVar.() -> Unit): EnvVar {
    return EnvVar().apply(block)
}
