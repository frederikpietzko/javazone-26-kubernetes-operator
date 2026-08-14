package com.example

import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.javaoperatorsdk.operator.api.config.ConfigurationServiceOverrider
import io.javaoperatorsdk.operator.api.config.LeaderElectionConfigurationBuilder
import java.io.File
import java.util.function.Consumer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

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

    @Bean
    fun operatorConfiguration(): Consumer<ConfigurationServiceOverrider> = Consumer { overrider ->
        val leaderConfig =
            LeaderElectionConfigurationBuilder.aLeaderElectionConfiguration("review-agent-operator")
                .withLeaseNamespace("default")
                .build()
        overrider.withLeaderElectionConfiguration(leaderConfig)
    }
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
