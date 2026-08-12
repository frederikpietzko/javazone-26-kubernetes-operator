package com.example

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.Resource
import org.springframework.stereotype.Component

class AgentReviewResourceConflict(message: String) : IllegalStateException(message)

interface AgentReviewClient {
    fun observe(namespace: String, baseName: String): ObservedAgentReviewResources

    fun validateDesired(
        resources: AgentReviewResources,
        metadata: ObjectMeta,
        observed: ObservedAgentReviewResources,
    ): List<ResourceComparisonResult.Conflict>

    fun createDependencies(resources: AgentReviewResources): ResourceComparisonResult

    fun createMissing(resources: AgentReviewResources): ResourceComparisonResult
}

sealed interface ResourceComparisonResult {
    data class Conflict(val message: String) : ResourceComparisonResult

    data object Equal : ResourceComparisonResult
}

@Component
class Fabric8AgentReviewClient(private val client: KubernetesClient) : AgentReviewClient {

    override fun observe(namespace: String, baseName: String): ObservedAgentReviewResources =
        ObservedAgentReviewResources(
            configMap = client.configMaps().inNamespace(namespace).withName(baseName).get(),
            job = client.batch().v1().jobs().inNamespace(namespace).withName(baseName).get(),
            reviewResult =
                client
                    .resources(ReviewResultCR::class.java)
                    .inNamespace(namespace)
                    .withName(baseName)
                    .get(),
        )

    override fun validateDesired(
        resources: AgentReviewResources,
        metadata: ObjectMeta,
        observed: ObservedAgentReviewResources,
    ): List<ResourceComparisonResult.Conflict> {
        val conflicts = mutableListOf<ResourceComparisonResult.Conflict>()
        observed.configMap?.let {
            val result =
                requireMatch(it, resources.configMap, AgentReviewResourceMatcher::configMapMatches)
            if (result is ResourceComparisonResult.Conflict) {
                conflicts.add(result)
            }
        }
        observed.job?.let {
            val result = requireMatch(it, resources.job, AgentReviewResourceMatcher::jobMatches)
            if (result is ResourceComparisonResult.Conflict) {
                conflicts.add(result)
            }
        }

        val result = observed.reviewResult
        if (result != null && !hasExpectedOwner(result, metadata.name, metadata.uid)) {
            val name = result.metadata.name
            val deletionTimestamp = result.metadata?.deletionTimestamp
            if (deletionTimestamp != null) {
                conflicts.add(
                    ResourceComparisonResult.Conflict(
                        "review result $name has conflicting owner and is terminating since $deletionTimestamp"
                    )
                )
            } else {
                conflicts.add(
                    ResourceComparisonResult.Conflict("review result $name has a conflicting owner")
                )
            }
        }
        return conflicts
    }

    override fun createDependencies(resources: AgentReviewResources): ResourceComparisonResult =
        ensure(
            resources.configMap,
            client
                .configMaps()
                .inNamespace(namespace(resources.configMap))
                .withName(name(resources.configMap)),
            AgentReviewResourceMatcher::configMapMatches,
        )

    override fun createMissing(resources: AgentReviewResources): ResourceComparisonResult {
        val conflict = createDependencies(resources)
        if (conflict is ResourceComparisonResult.Conflict) {
            return conflict
        }

        return ensure(
            resources.job,
            client
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace(resources.job))
                .withName(name(resources.job)),
            AgentReviewResourceMatcher::jobMatches,
        )
    }

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

    private fun <T : HasMetadata> ensure(
        desired: T,
        resource: Resource<T>,
        matches: (T, T) -> Boolean,
    ): ResourceComparisonResult {
        val existing = resource.get()
        if (existing == null) {
            client.resource(desired).create()
            return ResourceComparisonResult.Equal
        }
        return requireMatch(existing, desired, matches)
    }

    private fun <T : HasMetadata> requireMatch(
        existing: T,
        desired: T,
        matches: (T, T) -> Boolean,
    ): ResourceComparisonResult =
        if (!matches(existing, desired))
            ResourceComparisonResult.Conflict(
                "existing ${desired.kind ?: desired.javaClass.simpleName} ${name(desired)} does not match desired resource"
            )
        else ResourceComparisonResult.Equal

    private fun namespace(resource: HasMetadata): String =
        requireNotNull(resource.metadata?.namespace) { "resource namespace is required" }

    private fun name(resource: HasMetadata): String =
        requireNotNull(resource.metadata?.name) { "resource name is required" }
}
