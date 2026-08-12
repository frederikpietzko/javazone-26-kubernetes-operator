package com.example

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.Resource
import org.springframework.stereotype.Component

class AgentReviewResourceConflict(message: String) : IllegalStateException(message)

interface AgentReviewResourceGateway {
    fun observe(namespace: String, baseName: String): ObservedAgentReviewResources

    fun validateDesired(resources: AgentReviewResources, observed: ObservedAgentReviewResources)

    fun createDependencies(resources: AgentReviewResources)

    fun createMissing(resources: AgentReviewResources)
}

@Component
class Fabric8AgentReviewResourceGateway(private val client: KubernetesClient) :
    AgentReviewResourceGateway {
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
        observed: ObservedAgentReviewResources,
    ) {
        observed.configMap?.let {
            requireMatch(it, resources.configMap, AgentReviewResourceMatcher::configMapMatches)
        }
        observed.job?.let {
            requireMatch(it, resources.job, AgentReviewResourceMatcher::jobMatches)
        }
    }

    override fun createDependencies(resources: AgentReviewResources) {
        ensure(
            resources.configMap,
            client
                .configMaps()
                .inNamespace(namespace(resources.configMap))
                .withName(name(resources.configMap)),
            AgentReviewResourceMatcher::configMapMatches,
        )
    }

    override fun createMissing(resources: AgentReviewResources) {
        createDependencies(resources)
        ensure(
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

    private fun <T : HasMetadata> ensure(
        desired: T,
        resource: Resource<T>,
        matches: (T, T) -> Boolean,
    ) {
        val existing = resource.get()
        if (existing == null) {
            client.resource(desired).create()
            return
        }
        requireMatch(existing, desired, matches)
    }

    private fun <T : HasMetadata> requireMatch(
        existing: T,
        desired: T,
        matches: (T, T) -> Boolean,
    ) {
        if (!matches(existing, desired)) {
            throw AgentReviewResourceConflict(
                "existing ${desired.kind ?: desired.javaClass.simpleName} ${name(desired)} does not match desired resource"
            )
        }
    }

    private fun namespace(resource: HasMetadata): String =
        requireNotNull(resource.metadata?.namespace) { "resource namespace is required" }

    private fun name(resource: HasMetadata): String =
        requireNotNull(resource.metadata?.name) { "resource name is required" }
}
