package com.example

import io.fabric8.kubernetes.client.KubernetesClient
import org.springframework.stereotype.Component

interface AgentReviewClient {
    fun observe(namespace: String, baseName: String): ObservedAgentReviewResources
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
}
