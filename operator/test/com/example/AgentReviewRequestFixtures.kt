package com.example

import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder

fun request(): AgentReviewRequestCR = AgentReviewRequestCR().apply {
    metadata = ObjectMetaBuilder()
        .withName("request-42")
        .withNamespace("default")
        .withUid("uid-42")
        .build()
    spec = AgentReviewRequestSpec().apply {
        repository = AgentReviewRepository().apply {
            url = "https://github.com/example/repository.git"
        }
        pr = "42"
    }
}

fun desiredState(request: AgentReviewRequestCR = request()): DesiredAgentReviewState {
    val metadata = requireNotNull(request.metadata)
    val spec = requireNotNull(request.spec)
    val repository = requireNotNull(spec.repository)
    return DesiredAgentReviewState(
        metadata = metadata,
        namespace = requireNotNull(metadata.namespace),
        requestName = requireNotNull(metadata.name),
        uid = requireNotNull(metadata.uid),
        repositoryUrl = requireNotNull(repository.url),
        pr = requireNotNull(spec.pr),
    )
}

fun observedWithActiveJob(result: ReviewResultCR? = null): ObservedAgentReviewResources =
    observed(job = JobBuilder().withStatus(JobStatusBuilder().withActive(1).build()).build(), result = result)

private fun observed(
    job: io.fabric8.kubernetes.api.model.batch.v1.Job,
    result: ReviewResultCR? = null,
): ObservedAgentReviewResources {
    val ownerReference = OwnerReferenceBuilder()
        .withApiVersion("example.com/v1")
        .withKind("AgentReviewRequest")
        .withName("request-42")
        .withUid("uid-42")
        .withController(true)
        .withBlockOwnerDeletion(false)
        .build()
    fun metadata(name: String) = ObjectMetaBuilder()
        .withName(name)
        .withNamespace("default")
        .withOwnerReferences(ownerReference)
        .build()
    result?.metadata = result.metadata ?: metadata("agent-review-request-42")
    job.metadata = job.metadata ?: metadata("agent-review-request-42")
    return ObservedAgentReviewResources(
        configMap = ConfigMapBuilder().withMetadata(metadata("agent-review-request-42")).build(),
        job = job,
        reviewResult = result,
    )
}
