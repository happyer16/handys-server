package co.handys.batch

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["co.handys"])
@EntityScan(basePackages = ["co.handys"])
@EnableJpaRepositories(basePackages = ["co.handys"])
@EnableScheduling
class HandysBatchApplication

fun main(args: Array<String>) {
    runApplication<HandysBatchApplication>(*args)
}
