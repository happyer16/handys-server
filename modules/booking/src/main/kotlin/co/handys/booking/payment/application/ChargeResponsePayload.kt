package co.handys.booking.payment.application

/**
 * `idempotency_record.response_json` for the charge gate: a terminal record replays this body instead of
 * calling the gateway again (ADR-003). Values written here are gateway ids and reason codes, so a minimal
 * encoder is enough until a JSON mapper is wired into the module.
 */
internal object ChargeResponsePayload {
    private const val SUCCEEDED = "SUCCEEDED"
    private const val DECLINED = "DECLINED"

    fun encode(result: ChargeResult): String =
        when (result) {
            is ChargeResult.Succeeded ->
                """{"status":"$SUCCEEDED","pgPaymentId":"${result.pgPaymentId}","pgEventId":"${result.pgEventId}"}"""

            is ChargeResult.Declined ->
                """{"status":"$DECLINED","reason":"${result.reason}"}"""
        }

    fun decode(payload: String): ChargePaymentResult =
        when (val status = field(payload, "status")) {
            SUCCEEDED -> ChargePaymentResult.AlreadySucceeded(field(payload, "pgPaymentId"))
            DECLINED -> ChargePaymentResult.Declined(field(payload, "reason"))
            else -> throw IllegalStateException("unknown charge payload status: $status")
        }

    private fun field(payload: String, name: String): String =
        Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(payload)?.groupValues?.get(1)
            ?: throw IllegalStateException("charge payload is missing field '$name'")
}
