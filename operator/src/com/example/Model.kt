package com.example

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.batch.v1.Job

data class DesiredAgentReviewState(
    val metadata: ObjectMeta,
    val namespace: String,
    val requestName: String,
    val uid: String,
    val repositoryUrl: String,
    val pr: String,
)

data class ObservedAgentReviewResources(
    val configMap: ConfigMap?,
    val job: Job?,
    val reviewResult: ReviewResultCR?,
)

data class AgentReviewResources(
    val configMap: ConfigMap,
    val job: Job,
)
