package com.example

import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobSpec

fun ownerReference(block: OwnerReference.() -> Unit): OwnerReference {
    return OwnerReference().apply(block)
}

fun configMap(block: ConfigMap.() -> Unit): ConfigMap {
    return ConfigMap().apply(block)
}

fun objectMeta(block: ObjectMeta.() -> Unit): ObjectMeta {
    return ObjectMeta().apply(block)
}

fun job(block: Job.() -> Unit): Job {
    return Job().apply(block)
}

fun jobSpec(block: JobSpec.() -> Unit): JobSpec {
    return JobSpec().apply(block)
}

fun podTemplate(block: PodTemplateSpec.() -> Unit): PodTemplateSpec {
    return PodTemplateSpec().apply(block)
}

fun podSpec(block: PodSpec.() -> Unit): PodSpec {
    return PodSpec().apply(block)
}

fun volume(block: Volume.() -> Unit): Volume {
    return Volume().apply(block)
}

fun volumeMount(block: VolumeMount.() -> Unit): VolumeMount {
    return VolumeMount().apply(block)
}

fun configMapVolumeSource(block: ConfigMapVolumeSource.() -> Unit): ConfigMapVolumeSource {
    return ConfigMapVolumeSource().apply(block)
}

fun container(block: Container.() -> Unit): Container {
    return Container().apply(block)
}

fun envVar(block: EnvVar.() -> Unit): EnvVar {
    return EnvVar().apply(block)
}
