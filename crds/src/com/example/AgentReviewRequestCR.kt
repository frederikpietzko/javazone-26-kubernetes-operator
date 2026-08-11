package com.example

import io.fabric8.generator.annotation.Pattern
import io.fabric8.generator.annotation.Required
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Kind
import io.fabric8.kubernetes.model.annotation.Plural
import io.fabric8.kubernetes.model.annotation.Version

class AgentReviewRepository {
    @field:Required
    @field:Pattern("^https://[^\\s]+$")
    var url: String? = null
}

class AgentReviewRequestSpec {
    @field:Required
    var repository: AgentReviewRepository? = null

    @field:Required
    @field:Pattern("^[0-9]+$")
    var pr: String? = null
}

class AgentReviewRequestStatus {
    var phase: String? = null
    var message: String? = null
    var jobName: String? = null
    var configMapName: String? = null
    var reviewResultName: String? = null
}

@Group("example.com")
@Version("v1")
@Kind("AgentReviewRequest")
@Plural("agentreviewrequests")
class AgentReviewRequestCR : CustomResource<AgentReviewRequestSpec, AgentReviewRequestStatus>(), Namespaced
