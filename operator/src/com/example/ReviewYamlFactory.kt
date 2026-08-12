package com.example

import tools.jackson.dataformat.yaml.YAMLMapper

object ReviewYamlFactory {
    private val mapper: YAMLMapper = YAMLMapper.builder().findAndAddModules().build()

    fun create(desiredState: DesiredAgentReviewState, baseName: String): String {

        val review =
            Review(
                repository = Repository(desiredState.repositoryUrl),
                pr = desiredState.pr,
                kubernetes =
                    Review.Kubernetes(
                        namespace = desiredState.namespace,
                        name = baseName,
                        ownerReference =
                            Review.OwnerReference(
                                apiVersion = "example.com/v1",
                                kind = "AgentReviewRequest",
                                name = desiredState.requestName,
                                uid = desiredState.uid,
                            ),
                    ),
            )
        return mapper.writeValueAsString(mapOf("review" to review))
    }
}
