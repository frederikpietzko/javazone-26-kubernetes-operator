package com.example

import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Version

class WebappSpec {
    var imageName: String? = null
}

class WebappStatus {
    var status: String? = null
}

@Group("example.com")
@Version("v1")
class Example : CustomResource<WebappSpec, WebappStatus>(), Namespaced
