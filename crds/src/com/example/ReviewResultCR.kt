package com.example

import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Kind
import io.fabric8.kubernetes.model.annotation.Plural
import io.fabric8.kubernetes.model.annotation.Version

class ReviewResultSpec {
    var file: String? = null
    var comments: List<ReviewComment>? = null
}

class ReviewResultStatus {
    var status: String? = null
    var error: String? = null
}

class ReviewComment {
    var lines: List<Int>? = null
    var comment: String? = null
}

@Group("example.com")
@Version("v1")
@Kind("ReviewResult")
@Plural("reviewresults")
class ReviewResultCR : CustomResource<ReviewResultSpec, ReviewResultStatus>(), Namespaced
