package co.handys.api.v1.channel.response

data class BookHotelResponse(
    val id: String,
    val status: String,
)

data class SyncResponse(
    val channelId: String,
    val ok: Boolean,
)
