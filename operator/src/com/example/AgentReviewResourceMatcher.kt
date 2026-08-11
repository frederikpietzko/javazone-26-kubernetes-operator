package com.example

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ServiceAccount
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.rbac.Role
import io.fabric8.kubernetes.api.model.rbac.RoleBinding

object AgentReviewResourceMatcher {
    fun configMapMatches(existing: ConfigMap, desired: ConfigMap): Boolean =
        sameIdentity(existing, desired) &&
            existing.data == desired.data &&
            existing.immutable == desired.immutable

    fun serviceAccountMatches(existing: ServiceAccount, desired: ServiceAccount): Boolean =
        sameIdentity(existing, desired) &&
            existing.imagePullSecrets == desired.imagePullSecrets &&
            existing.automountServiceAccountToken == desired.automountServiceAccountToken

    fun roleMatches(existing: Role, desired: Role): Boolean =
        sameIdentity(existing, desired) && existing.rules == desired.rules

    fun roleBindingMatches(existing: RoleBinding, desired: RoleBinding): Boolean =
        sameIdentity(existing, desired) &&
            existing.roleRef == desired.roleRef &&
            existing.subjects == desired.subjects

    fun jobMatches(existing: Job, desired: Job): Boolean =
        sameIdentity(existing, desired) && sameJobSpec(existing, desired)

    private fun sameIdentity(existing: HasMetadata, desired: HasMetadata): Boolean =
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
}
