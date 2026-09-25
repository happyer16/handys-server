package co.handys

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(scanBasePackages = ["co.handys"])
@EntityScan(basePackages = ["co.handys"])
@EnableJpaRepositories(basePackages = ["co.handys"])
class HandysApplication

fun main(args: Array<String>) {
    runApplication<HandysApplication>(*args)
}
