package com.example.reviewer

import com.example.Review
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder

fun Review.OwnerReference.toFabric8(): OwnerReference =
    OwnerReferenceBuilder()
        .withApiVersion(apiVersion)
        .withKind(kind)
        .withName(name)
        .withUid(uid)
        .withController(controller)
        .withBlockOwnerDeletion(blockOwnerDeletion)
        .build()
