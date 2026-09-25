package co.handys.inventory.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.beans.factory.annotation.Value
import java.time.Clock

@Configuration
class InventoryConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun inventoryClock(): Clock = Clock.systemUTC()
}

@Configuration
@ConditionalOnProperty(name = ["handys.inventory.redis.enabled"], havingValue = "true")
class InventoryRedisConfiguration(
    @Value("\${spring.data.redis.host:localhost}") private val host: String,
    @Value("\${spring.data.redis.port:6379}") private val port: Int,
    @Value("\${spring.data.redis.database:1}") private val database: Int,
) {
    @Bean
    fun inventoryLettuceConnectionFactory(): LettuceConnectionFactory {
        val config = RedisStandaloneConfiguration(host, port)
        config.database = database
        return LettuceConnectionFactory(config).also { it.afterPropertiesSet() }
    }

    @Bean
    fun stringRedisTemplate(inventoryLettuceConnectionFactory: LettuceConnectionFactory): StringRedisTemplate =
        StringRedisTemplate(inventoryLettuceConnectionFactory)
}
