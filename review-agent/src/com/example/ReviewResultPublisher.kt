package com.example

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.client.KubernetesClient

interface ReviewResultPublisher {
    fun start()

    fun complete(result: ReviewResult)

    fun fail(exception: Exception)
}

class KubernetesReviewResultPublisher(
    private val client: KubernetesClient,
    private val target: Review.Kubernetes,
) : ReviewResultPublisher, AutoCloseable {
    private val namespacedResources =
        client.resources(ReviewResultCR::class.java).inNamespace(target.namespace)
    private val namedResource = namespacedResources.withName(target.name)
    private var created = false

    override fun start() {
        val reviewResult =
            ReviewResultCR().apply {
                metadata = ObjectMetaBuilder().withName(target.name).build()
                spec = ReviewResultSpec().also { it.comments = emptyList() }
                status = ReviewResultStatus().also { it.status = "InProgress" }
            }

        namespacedResources
            .resource(reviewResult)
            .fieldManager("review-agent")
            .forceConflicts()
            .serverSideApply()
        created = true
        updateStatus("InProgress", null)
    }

    override fun complete(result: ReviewResult) {
        val reviewResult =
            checkNotNull(namedResource.get()) {
                "ReviewResult ${target.namespace}/${target.name} disappeared before completion"
            }
        reviewResult.spec = result.toSpec()
        namespacedResources.resource(reviewResult).update()
        updateStatus("Completed", null)
    }

    override fun fail(exception: Exception) {
        if (!created) return

        updateStatus("Failed", formatError(exception))
    }

    private fun updateStatus(statusValue: String, error: String?) {
        namedResource.editStatus { reviewResult ->
            val status = reviewResult.status ?: ReviewResultStatus()
            status.status = statusValue
            status.error = error
            reviewResult.status = status
            reviewResult
        }
    }

    private fun formatError(exception: Exception): String {
        val exceptionName =
            exception::class.qualifiedName ?: exception::class.simpleName ?: "Exception"
        val message = exception.message?.takeIf { it.isNotBlank() } ?: "No exception message"
        return "$exceptionName: $message"
    }

    override fun close() {
        client.close()
    }
}
