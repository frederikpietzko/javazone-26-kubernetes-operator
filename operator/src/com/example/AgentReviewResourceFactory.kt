package com.example

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder
import io.fabric8.kubernetes.api.model.rbac.Role
import io.fabric8.kubernetes.api.model.rbac.RoleBinding
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder
import io.fabric8.kubernetes.api.model.ServiceAccount
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder

data class AgentReviewResources(
    val configMap: ConfigMap,
    val serviceAccount: ServiceAccount,
    val role: Role,
    val roleBinding: RoleBinding,
    val job: Job,
)

object AgentReviewResourceFactory {
    private const val RESULT_API_GROUP = "example.com"
    private const val RESULT_RESOURCE = "reviewresults"
    private const val REVIEW_CONFIG_KEY = "review.yaml"
    private const val REVIEW_CONFIG_MOUNT = "/config/review.yaml"
    private const val SPRING_CONFIG_LOCATION = "classpath:/,file:/config/review.yaml"
    private const val SERVICE_ACCOUNT_SUFFIX = "-agent"

    fun create(request: AgentReviewRequestCR, image: String): AgentReviewResources {
        val metadata = requireNotNull(request.metadata) { "request metadata is required" }
        val namespace = requireNotNull(metadata.namespace) { "request namespace is required" }
        val requestName = requireNotNull(metadata.name) { "request name is required" }
        val requestUid = requireNotNull(metadata.uid) { "request UID is required" }
        val baseName = ResourceNameGenerator.baseName(requestName)
        val agentName = baseName + SERVICE_ACCOUNT_SUFFIX
        val ownerReference = OwnerReferenceBuilder()
            .withApiVersion("example.com/v1")
            .withKind("AgentReviewRequest")
            .withName(requestName)
            .withUid(requestUid)
            .withController(true)
            .withBlockOwnerDeletion(false)
            .build()

        val configMap = ConfigMapBuilder()
            .withMetadata(ownerMetadata(baseName, namespace, ownerReference))
            .withImmutable(true)
            .addToData(REVIEW_CONFIG_KEY, ReviewYamlFactory.create(request, baseName))
            .build()
        val serviceAccount = ServiceAccountBuilder()
            .withMetadata(ownerMetadata(agentName, namespace, ownerReference))
            .build()
        val role = RoleBuilder()
            .withMetadata(ownerMetadata(agentName, namespace, ownerReference))
            .withRules(
                PolicyRuleBuilder()
                    .withApiGroups(RESULT_API_GROUP)
                    .withResources(RESULT_RESOURCE)
                    .withVerbs("get", "create", "update", "patch")
                    .build(),
                PolicyRuleBuilder()
                    .withApiGroups(RESULT_API_GROUP)
                    .withResources("$RESULT_RESOURCE/status")
                    .withVerbs("get", "update", "patch")
                    .build(),
            )
            .build()
        val roleBinding = RoleBindingBuilder()
            .withMetadata(ownerMetadata(agentName, namespace, ownerReference))
            .withRoleRef(
                RoleRefBuilder()
                    .withApiGroup("rbac.authorization.k8s.io")
                    .withKind("Role")
                    .withName(agentName)
                    .build(),
            )
            .withSubjects(
                SubjectBuilder()
                    .withKind("ServiceAccount")
                    .withName(agentName)
                    .withNamespace(namespace)
                    .build(),
            )
            .build()
        val job = JobBuilder()
            .withMetadata(ownerMetadata(baseName, namespace, ownerReference))
            .withSpec(
                JobSpecBuilder()
                    .withBackoffLimit(0)
                    .withTemplate(
                        PodTemplateSpecBuilder()
                            .withSpec(
                                PodSpecBuilder()
                                    .withServiceAccountName(agentName)
                                    .withRestartPolicy("Never")
                                    .withContainers(reviewAgentContainer(image))
                                    .withVolumes(
                                        VolumeBuilder()
                                            .withName(REVIEW_CONFIG_KEY)
                                            .withConfigMap(
                                                ConfigMapVolumeSourceBuilder()
                                                    .withName(baseName)
                                                    .build(),
                                            )
                                            .build(),
                                    )
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

        return AgentReviewResources(configMap, serviceAccount, role, roleBinding, job)
    }

    private fun reviewAgentContainer(image: String): Container = ContainerBuilder()
        .withName("review-agent")
        .withImage(image)
        .withEnv(
            EnvVarBuilder()
                .withName("SPRING_CONFIG_LOCATION")
                .withValue(SPRING_CONFIG_LOCATION)
                .build(),
        )
        .withVolumeMounts(
            VolumeMountBuilder()
                .withName(REVIEW_CONFIG_KEY)
                .withMountPath(REVIEW_CONFIG_MOUNT)
                .withSubPath(REVIEW_CONFIG_KEY)
                .withReadOnly(true)
                .build(),
        )
        .build()

    private fun ownerMetadata(name: String, namespace: String, ownerReference: io.fabric8.kubernetes.api.model.OwnerReference): ObjectMeta =
        ObjectMetaBuilder()
            .withName(name)
            .withNamespace(namespace)
            .withOwnerReferences(ownerReference)
            .build()
}
