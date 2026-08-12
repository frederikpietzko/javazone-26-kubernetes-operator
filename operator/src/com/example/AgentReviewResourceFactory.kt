package com.example

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.batch.v1.Job

data class AgentReviewResources(
    val configMap: ConfigMap,
    val job: Job,
)

object AgentReviewResourceFactory {
    private const val REVIEW_CONFIG_KEY = "review.yaml"
    private const val REVIEW_CONFIG_MOUNT = "/config/review.yaml"
    private const val SPRING_CONFIG_LOCATION = "classpath:/,file:/config/review.yaml"
    private const val REVIEW_AGENT_SERVICE_ACCOUNT = "review-agent"

    fun create(
        request: AgentReviewRequestCR,
        image: String,
        openAiBaseUrl: String = "http://127.0.0.1:11434",
    ): AgentReviewResources {
        val metadata = requireNotNull(request.metadata) { "request metadata is required" }
        val namespace = requireNotNull(metadata.namespace) { "request namespace is required" }
        val requestName = requireNotNull(metadata.name) { "request name is required" }
        val requestUid = requireNotNull(metadata.uid) { "request UID is required" }
        val baseName = ResourceNameGenerator.baseName(requestName)
        val ownerMetadata = objectMeta {
            this.name = baseName
            this.namespace = namespace
            this.ownerReferences =
                listOf(
                    ownerReference {
                        apiVersion = "example.com/v1"
                        kind = "AgentReviewRequest"
                        name = requestName
                        uid = requestUid
                        controller = true
                        blockOwnerDeletion = false
                    }
                )
        }

        val configMap = configMap {
            this.metadata = ownerMetadata
            this.immutable = true
            this.data[REVIEW_CONFIG_KEY] = ReviewYamlFactory.create(request, baseName)
        }

        val job = job {
            this.metadata = ownerMetadata
            spec = jobSpec {
                backoffLimit = 0
                template = podTemplate {
                    spec = podSpec {
                        serviceAccountName = REVIEW_AGENT_SERVICE_ACCOUNT
                        restartPolicy = "Never"
                        containers = listOf(reviewAgentContainer(image, openAiBaseUrl))
                        volumes =
                            listOf(
                                volume {
                                    name = REVIEW_CONFIG_KEY
                                    this.configMap = configMapVolumeSource {
                                        name = baseName
                                    }
                                }
                            )
                    }
                }
            }
        }
        return AgentReviewResources(configMap, job)
    }

    private fun reviewAgentContainer(image: String, openAiBaseUrl: String): Container = container {
        name = "review-agent"
        this.image = image
        env =
            listOf(
                envVar {
                    name = "SPRING_CONFIG_LOCATION"
                    value = SPRING_CONFIG_LOCATION
                },
                envVar {
                    name = "REVIEW_AGENT_OPENAI_BASE_URL"
                    value = openAiBaseUrl
                },
            )
        volumeMounts =
            listOf(
                volumeMount {
                    name = REVIEW_CONFIG_KEY
                    mountPath = REVIEW_CONFIG_MOUNT
                    subPath = REVIEW_CONFIG_KEY
                    readOnly = true
                }
            )
    }
}
