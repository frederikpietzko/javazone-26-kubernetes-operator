package com.example

import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.io.File

@SpringBootApplication
@ConfigurationPropertiesScan
class Application {
    @Value($$"${config}") lateinit var kubeconfig: String

    @Bean
    fun kubernetesClient(): KubernetesClient {
        val kubeconfig = File(kubeconfig)
        requireNotNull(kubeconfig.isFile) {
            "Kubeconfig file not found: ${kubeconfig.absolutePath}"
        }
        val config = Config.fromKubeconfig(kubeconfig)
        return KubernetesClientBuilder().withConfig(config).build()
    }
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
