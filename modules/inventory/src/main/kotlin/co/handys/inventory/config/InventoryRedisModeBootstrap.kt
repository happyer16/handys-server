package co.handys.inventory.config

import co.handys.inventory.infrastructure.redis.InventoryRedisModeService
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class InventoryRedisModeBootstrap(
    private val modeService: InventoryRedisModeService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments?) {
        modeService.ensureRow()
    }
}
