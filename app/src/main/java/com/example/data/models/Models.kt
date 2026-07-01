package com.example.data.models

enum class ClickMode {
    FIXED_POINT,
    FOLLOW_CURSOR,
    TARGET_ELEMENT,
    SEQUENCE
}

enum class ClickType {
    SINGLE,
    DOUBLE,
    LONG_PRESS
}

enum class ActionType {
    CLICK,
    SWIPE
}

data class TemplateItem(
    val imagePath: String,
    val buttonText: String = ""
)

data class PriceConfig(
    val enabled: Boolean = false,
    val minPrice: Double = 10.0, // Default to 10 as requested
    val maxPrice: Double = Double.MAX_VALUE,
    val minPriceWithBonus: Double = 15.0, // Minimum price with bonus
    val currencySymbol: String = "₹",
    val acceptButtonKeywords: String = "Accept, Accept Ride, Confirm, Take, Go, Agree, Book, Start, Tap to Accept, Let's Go, ACCEPT, CONFIRM, GO, ACCEPT RIDE, TAP TO ACCEPT",
    val pickupKeywords: String = "",
    val dropKeywords: String = "",
    val templatesSerialized: String = "", // Stores serialized TemplateItem list: path1::text1||path2::text2
    val useTemplateMatching: Boolean = false,
    val vibrationTriggerEnabled: Boolean = false,
    val randomClickDelayMinMs: Long = 1L,
    val randomClickDelayMaxMs: Long = 30L,
    val minPickupDistance: Double = 0.0,
    val maxPickupDistance: Double = 99.0,
    val minDropDistance: Double = 0.0,
    val maxDropDistance: Double = 99.0
) {
    fun getTemplates(): List<TemplateItem> {
        if (templatesSerialized.isBlank()) return emptyList()
        return templatesSerialized.split("||").mapNotNull {
            val parts = it.split("::", limit = 2)
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                val path = parts[0]
                val text = if (parts.size > 1) parts[1] else ""
                TemplateItem(path, text)
            } else {
                null
            }
        }
    }
}

data class ClickConfig(
    val intervalMs: Long = 100,
    val maxClicks: Int = 0, // 0 = unlimited
    val clickMode: ClickMode = ClickMode.FIXED_POINT,
    val clickType: ClickType = ClickType.SINGLE,
    val fixedX: Float = 0f,
    val fixedY: Float = 0f,
    val targetText: String? = null,
    val targetViewId: String? = null,
    val randomizeInterval: Boolean = false,
    val stopOnScreenOff: Boolean = true,
    val priceConfig: PriceConfig = PriceConfig()
)

data class RecordedAction(
    val type: ActionType = ActionType.CLICK,
    val x: Float = 0f,
    val y: Float = 0f,
    val endX: Float? = null,
    val endY: Float? = null,
    val durationMs: Long = 50L,
    val delayAfterMs: Long = 500L
)
