package com.example

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.rbac.Role
import io.fabric8.kubernetes.api.model.rbac.RoleBinding
import io.fabric8.kubernetes.api.model.ServiceAccount
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.Resource
import org.springframework.stereotype.Component

class AgentReviewResourceConflict(message: String) : IllegalStateException(message)

interface AgentReviewResourceGateway {
    fun observe(namespace: String, baseName: String): ObservedAgentReviewResources
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

    override fun createMissing(resources: AgentReviewResources) {
        ensure(resources.configMap, client.configMaps().inNamespace(namespace(resources.configMap)).withName(name(resources.configMap))) {
            it.data == resources.configMap.data && it.immutable == resources.configMap.immutable
        }
        ensure(resources.serviceAccount, client.serviceAccounts().inNamespace(namespace(resources.serviceAccount)).withName(name(resources.serviceAccount))) {
            it.imagePullSecrets == resources.serviceAccount.imagePullSecrets &&
                it.secrets == resources.serviceAccount.secrets &&
                it.automountServiceAccountToken == resources.serviceAccount.automountServiceAccountToken
        }
        ensure(resources.role, client.rbac().roles().inNamespace(namespace(resources.role)).withName(name(resources.role))) {
            it.rules == resources.role.rules
        }
        ensure(resources.roleBinding, client.rbac().roleBindings().inNamespace(namespace(resources.roleBinding)).withName(name(resources.roleBinding))) {
            it.roleRef == resources.roleBinding.roleRef && it.subjects == resources.roleBinding.subjects
        }
        ensure(resources.job, client.batch().jobs().inNamespace(namespace(resources.job)).withName(name(resources.job))) {
            sameJobSpec(it, resources.job)
        }
    }

    private fun <T : io.fabric8.kubernetes.api.model.HasMetadata> ensure(
        desired: T,
        resource: Resource<T>,
        matches: (T) -> Boolean,
    ) {
        val existing = resource.get()
        if (existing == null) {
            client.resource(desired).create()
            return
        }
        if (!sameIdentity(existing, desired) || !matches(existing)) {
            throw AgentReviewResourceConflict(
                "existing ${desired.kind ?: desired.javaClass.simpleName} ${name(desired)} does not match desired resource",
            )
        }
    }

    private fun sameIdentity(
        existing: io.fabric8.kubernetes.api.model.HasMetadata,
        desired: io.fabric8.kubernetes.api.model.HasMetadata,
    ): Boolean =
        existing.metadata?.name == desired.metadata?.name &&
            existing.metadata?.namespace == desired.metadata?.namespace &&
            existing.metadata?.ownerReferences == desired.metadata?.ownerReferences

    private fun sameJobSpec(existing: Job, desired: Job): Boolean {
        val existingPod = existing.spec?.template?.spec
        val desiredPod = desired.spec?.template?.spec
        val existingContainer = existingPod?.containers?.singleOrNull()
        val desiredContainer = desiredPod?.containers?.singleOrNull()
        return existing.spec?.backoffLimit == desired.spec?.backoffLimit &&
            existingPod?.restartPolicy == desiredPod?.restartPolicy &&
            existingPod?.serviceAccountName == desiredPod?.serviceAccountName &&
            existingContainer?.name == desiredContainer?.name &&
            existingContainer?.image == desiredContainer?.image &&
            existingContainer?.env == desiredContainer?.env &&
            existingContainer?.volumeMounts == desiredContainer?.volumeMounts &&
            existingPod?.volumes?.map { it.name to it.configMap?.name } ==
                desiredPod?.volumes?.map { it.name to it.configMap?.name }
    }

    private fun namespace(resource: io.fabric8.kubernetes.api.model.HasMetadata): String =
        requireNotNull(resource.metadata?.namespace) { "resource namespace is required" }

    private fun name(resource: io.fabric8.kubernetes.api.model.HasMetadata): String =
        requireNotNull(resource.metadata?.name) { "resource name is required" }
}
