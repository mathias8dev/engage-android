package io.engage.sdk.push.fcm

internal data class EngagePushPayload(
    val deliveryId: String,
    val messageId: String,
    val actionType: String,
    val actionValue: String?,
    val actionArguments: Map<String, String>,
    val data: Map<String, String>,
) {
    companion object {
        fun from(data: Map<String, String>): EngagePushPayload? {
            val deliveryId = data[DELIVERY_ID]?.takeIf(String::isNotBlank) ?: return null
            val messageId = data[MESSAGE_ID]?.takeIf(String::isNotBlank) ?: return null
            return EngagePushPayload(
                deliveryId = deliveryId,
                messageId = messageId,
                actionType = data[ACTION_TYPE] ?: "OPEN_APP",
                actionValue = data[ACTION_VALUE],
                actionArguments = data.entries
                    .filter { it.key.startsWith(ACTION_ARGUMENT_PREFIX) }
                    .associate { it.key.removePrefix(ACTION_ARGUMENT_PREFIX) to it.value },
                data = data,
            )
        }

        private const val DELIVERY_ID = "engage_delivery_id"
        private const val MESSAGE_ID = "engage_message_id"
        private const val ACTION_TYPE = "engage_action_type"
        private const val ACTION_VALUE = "engage_action_value"
        private const val ACTION_ARGUMENT_PREFIX = "engage_action_arg_"
    }
}

