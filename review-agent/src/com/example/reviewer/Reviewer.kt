package com.example.reviewer

import org.springframework.beans.factory.BeanRegistrarDsl

class Reviewer :
    BeanRegistrarDsl({
        registerBean<ReviewCommandLineRunner>()
    })
