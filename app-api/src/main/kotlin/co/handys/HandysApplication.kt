package co.handys

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["co.handys"])
class HandysApplication

fun main(args: Array<String>) {
    runApplication<HandysApplication>(*args)
}
