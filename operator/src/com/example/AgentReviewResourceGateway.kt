package com.example

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.Resource
import org.springframework.stereotype.Component

class AgentReviewResourceConflict(message: String) : IllegalStateException(message)

interface AgentReviewResourceGateway {
    fun observe(namespace: String, baseName: String): ObservedAgentReviewResources
    fun validateDesired(resources: AgentReviewResources, observed: ObservedAgentReviewResources)
    fun createMissing(resources: AgentReviewResources)
}

@Component
class Fabric8AgentReviewResourceGateway(
    private val client: KubernetesClient,
) : AgentReviewResourceGateway {
    override fun observe(namespace: String, baseName: String): ObservedAgentReviewResources =
        ObservedAgentReviewResources(
            configMap = client.configMaps().inNamespace(namespace).withName(baseName).get(),
            serviceAccount = client.serviceAccounts().inNamespace(namespace).withName("$baseName-agent").get(),
            role = client.rbac().roles().inNamespace(namespace).withName("$baseName-agent").get(),
            roleBinding = client.rbac().roleBindings().inNamespace(namespace).withName("$baseName-agent").get(),
            job = client.batch().jobs().inNamespace(namespace).withName(baseName).get(),
            reviewResult = client.resources(ReviewResultCR::class.java).inNamespace(namespace).withName(baseName).get(),
        )

    override fun validateDesired(resources: AgentReviewResources, observed: ObservedAgentReviewResources) {
        observed.configMap?.let { requireMatch(it, resources.configMap, AgentReviewResourceMatcher::configMapMatches) }
        observed.serviceAccount?.let { requireMatch(it, resources.serviceAccount, AgentReviewResourceMatcher::serviceAccountMatches) }
        observed.role?.let { requireMatch(it, resources.role, AgentReviewResourceMatcher::roleMatches) }
        observed.roleBinding?.let { requireMatch(it, resources.roleBinding, AgentReviewResourceMatcher::roleBindingMatches) }
        observed.job?.let { requireMatch(it, resources.job, AgentReviewResourceMatcher::jobMatches) }
    }

    override fun createMissing(resources: AgentReviewResources) {
        ensure(resources.configMap, client.configMaps().inNamespace(namespace(resources.configMap)).withName(name(resources.configMap)), AgentReviewResourceMatcher::configMapMatches)
        ensure(resources.serviceAccount, client.serviceAccounts().inNamespace(namespace(resources.serviceAccount)).withName(name(resources.serviceAccount)), AgentReviewResourceMatcher::serviceAccountMatches)
        ensure(resources.role, client.rbac().roles().inNamespace(namespace(resources.role)).withName(name(resources.role)), AgentReviewResourceMatcher::roleMatches)
        ensure(resources.roleBinding, client.rbac().roleBindings().inNamespace(namespace(resources.roleBinding)).withName(name(resources.roleBinding)), AgentReviewResourceMatcher::roleBindingMatches)
        ensure(resources.job, client.batch().jobs().inNamespace(namespace(resources.job)).withName(name(resources.job)), AgentReviewResourceMatcher::jobMatches)
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
                "existing ${desired.kind ?: desired.javaClass.simpleName} ${name(desired)} does not match desired resource",
            )
        }
    }

    private fun namespace(resource: HasMetadata): String =
        requireNotNull(resource.metadata?.namespace) { "resource namespace is required" }

    private fun name(resource: HasMetadata): String =
        requireNotNull(resource.metadata?.name) { "resource name is required" }
}
