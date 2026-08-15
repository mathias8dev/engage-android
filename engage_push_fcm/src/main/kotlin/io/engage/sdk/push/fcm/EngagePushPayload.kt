package io.engage.sdk.push.fcm

import io.engage.sdk.EngageLogger

internal data class EngagePushPayload(
    val deliveryId: String,
    val messageId: String,
    val actionType: String,
    val actionValue: String?,
    val actionArguments: Map<String, String>,
    val title: String?,
    val body: String?,
    val imageUrl: String?,
    val channelKey: String?,
    val categoryKey: String?,
    val notificationTag: String?,
    val data: Map<String, String>,
) {
    companion object {
        fun from(data: Map<String, String>): EngagePushPayload? {
            if (data.keys.none { it.startsWith(ENGAGE_KEY_PREFIX) }) {
                EngageLogger.verbose("Push", "payload ignored reason=not_engage")
                return null
            }
            val deliveryId = data[DELIVERY_ID]?.takeIf(String::isNotBlank) ?: run {
                EngageLogger.warn("Push", "payload rejected reason=missing_delivery_id keys=${data.keys.sorted()}")
                return null
            }
            val messageId = data[MESSAGE_ID]?.takeIf(String::isNotBlank) ?: run {
                EngageLogger.warn("Push", "payload rejected reason=missing_message_id deliveryId=$deliveryId")
                return null
            }
            return EngagePushPayload(
                deliveryId = deliveryId,
                messageId = messageId,
                actionType = data[ACTION_TYPE] ?: "OPEN_APP",
                actionValue = data[ACTION_VALUE],
                actionArguments = data.entries
                    .filter { it.key.startsWith(ACTION_ARGUMENT_PREFIX) }
                    .associate { it.key.removePrefix(ACTION_ARGUMENT_PREFIX) to it.value },
                title = data[TITLE],
                body = data[BODY],
                imageUrl = data[IMAGE_URL],
                channelKey = data[CHANNEL_KEY],
                categoryKey = data[CATEGORY_KEY],
                notificationTag = data[NOTIFICATION_TAG],
                data = data,
            ).also { payload ->
                EngageLogger.debug(
                    "Push",
                    "payload parsed deliveryId=$deliveryId messageId=$messageId actionType=${payload.actionType} " +
                        "customKeys=${payload.customData().keys.sorted()} hasImage=${payload.imageUrl != null}",
                )
            }
        }

        private const val ENGAGE_KEY_PREFIX = "engage_"
        private const val DELIVERY_ID = "engage_delivery_id"
        private const val MESSAGE_ID = "engage_message_id"
        private const val ACTION_TYPE = "engage_action_type"
        private const val ACTION_VALUE = "engage_action_value"
        private const val ACTION_ARGUMENT_PREFIX = "engage_action_arg_"
        private const val TITLE = "engage_title"
        private const val BODY = "engage_body"
        private const val IMAGE_URL = "engage_image_url"
        private const val CHANNEL_KEY = "engage_channel_key"
        private const val CATEGORY_KEY = "engage_category_key"
        private const val NOTIFICATION_TAG = "engage_notification_tag"
    }
}
