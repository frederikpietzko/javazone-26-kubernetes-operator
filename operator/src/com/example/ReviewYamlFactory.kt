package com.example

import tools.jackson.dataformat.yaml.YAMLMapper

object ReviewYamlFactory {
    private val mapper: YAMLMapper = YAMLMapper.builder().findAndAddModules().build()

    fun create(request: AgentReviewRequestCR, baseName: String): String {
        val metadata = requireNotNull(request.metadata) { "request metadata is required" }
        val spec = requireNotNull(request.spec) { "request spec is required" }
        val repository = requireNotNull(spec.repository) { "request repository is required" }
        val repositoryUrl = requireNotNull(repository.url) { "request repository URL is required" }
        val pullRequest = requireNotNull(spec.pr) { "request PR is required" }
        val namespace = requireNotNull(metadata.namespace) { "request namespace is required" }
        val requestName = requireNotNull(metadata.name) { "request name is required" }
        val requestUid = requireNotNull(metadata.uid) { "request UID is required" }

        val review =
            Review(
                repository = Repository(repositoryUrl),
                pr = pullRequest,
                kubernetes =
                    Review.Kubernetes(
                        namespace = namespace,
                        name = baseName,
                        ownerReference =
                            Review.OwnerReference(
                                apiVersion = "example.com/v1",
                                kind = "AgentReviewRequest",
                                name = requestName,
                                uid = requestUid,
                            ),
                    ),
            )
        return mapper.writeValueAsString(mapOf("review" to review))
    }
}
