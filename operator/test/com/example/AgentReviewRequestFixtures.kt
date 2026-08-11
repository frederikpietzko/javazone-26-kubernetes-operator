package com.example

import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder

fun request(): AgentReviewRequestCR = AgentReviewRequestCR().apply {
    metadata = ObjectMetaBuilder()
        .withName("request-42")
        .withNamespace("reviews")
        .withUid("uid-42")
        .build()
    spec = AgentReviewRequestSpec().apply {
        repository = AgentReviewRepository().apply {
            url = "https://github.com/example/repository.git"
        }
        pr = "42"
    }
}

fun observedWithActiveJob(result: ReviewResultCR? = null): ObservedAgentReviewResources =
    observed(job = JobBuilder().withStatus(JobStatusBuilder().withActive(1).build()).build(), result = result)

fun observedWithCompletedJob(result: ReviewResultCR?): ObservedAgentReviewResources =
    observed(job = JobBuilder().withStatus(JobStatusBuilder().withSucceeded(1).build()).build(), result = result)

fun observedWithFailedJob(): ObservedAgentReviewResources =
    observed(job = JobBuilder().withStatus(JobStatusBuilder().withFailed(1).build()).build())

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
        .withNamespace("reviews")
        .withOwnerReferences(ownerReference)
        .build()
    result?.metadata = result.metadata ?: metadata("agent-review-request-42")
    job.metadata = job.metadata ?: metadata("agent-review-request-42")
    return ObservedAgentReviewResources(
        configMap = ConfigMapBuilder().withMetadata(metadata("agent-review-request-42")).build(),
        serviceAccount = ServiceAccountBuilder().withMetadata(metadata("agent-review-request-42-agent")).build(),
        role = RoleBuilder().withMetadata(metadata("agent-review-request-42-agent")).build(),
        roleBinding = RoleBindingBuilder().withMetadata(metadata("agent-review-request-42-agent")).build(),
        job = job,
        reviewResult = result,
    )
}
