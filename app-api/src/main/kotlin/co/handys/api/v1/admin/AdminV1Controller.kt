package co.handys.api.v1.admin

import co.handys.api.common.ApiException
import co.handys.api.v1.admin.request.UpdateHkRequest
import co.handys.api.v1.admin.response.UnitHkResponse
import co.handys.common.domain.ReasonCode
import co.handys.property.api.PropertyApi
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@RestController
@RequestMapping("/api/v1/admin/units/{unitId}")
@Tag(name = "Admin")
class AdminV1Controller(
    private val propertyApi: PropertyApi,
) {
    @PostMapping("/hk")
    @Operation(summary = "유닛 HK 상태 갱신", description = "Dirty / Cleaning / Ready")
    fun updateHk(
        @PathVariable unitId: String,
        @RequestBody body: UpdateHkRequest,
    ): UnitHkResponse {
        val unit = propertyApi.updateHkStatus(unitId, body.hkStatus)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ReasonCode.NOT_FOUND, "Unit not found")
        return UnitHkResponse(unitId = unit.unitId, hkStatus = unit.hkStatus)
    }
}
