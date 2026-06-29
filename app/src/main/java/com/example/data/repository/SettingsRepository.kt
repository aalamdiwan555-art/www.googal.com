package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.models.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "autoclicker_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val INTERVAL_KEY = longPreferencesKey("click_interval")
        val MAX_CLICKS_KEY = intPreferencesKey("max_clicks")
        val CLICK_MODE_KEY = stringPreferencesKey("click_mode")
        val CLICK_TYPE_KEY = stringPreferencesKey("click_type")
        val FIXED_X_KEY = floatPreferencesKey("fixed_x")
        val FIXED_Y_KEY = floatPreferencesKey("fixed_y")
        val TARGET_TEXT_KEY = stringPreferencesKey("target_text")
        val TARGET_VIEW_ID_KEY = stringPreferencesKey("target_view_id")
        val RANDOMIZE_INTERVAL_KEY = booleanPreferencesKey("randomize_interval")
        val STOP_ON_SCREEN_OFF_KEY = booleanPreferencesKey("stop_on_screen_off")
        
        val MIN_PRICE_KEY = doublePreferencesKey("min_price")
        val MAX_PRICE_KEY = doublePreferencesKey("max_price")
        val PRICE_FILTER_ENABLED = booleanPreferencesKey("price_filter_enabled")
        val CURRENCY_SYMBOL = stringPreferencesKey("currency_symbol")
        val MIN_PRICE_WITH_BONUS_KEY = doublePreferencesKey("min_price_with_bonus")
        val PICKUP_KEYWORDS_KEY = stringPreferencesKey("pickup_keywords")
        val DROP_KEYWORDS_KEY = stringPreferencesKey("drop_keywords")
        val TEMPLATES_SERIALIZED_KEY = stringPreferencesKey("templates_serialized")
        val USE_TEMPLATE_MATCHING_KEY = booleanPreferencesKey("use_template_matching")
        val VIBRATION_TRIGGER_ENABLED_KEY = booleanPreferencesKey("vibration_trigger_enabled")
        val RANDOM_CLICK_DELAY_MIN_KEY = longPreferencesKey("random_click_delay_min")
        val RANDOM_CLICK_DELAY_MAX_KEY = longPreferencesKey("random_click_delay_max")
        val MIN_PICKUP_DISTANCE_KEY = doublePreferencesKey("min_pickup_distance")
        val MAX_PICKUP_DISTANCE_KEY = doublePreferencesKey("max_pickup_distance")
        val MIN_DROP_DISTANCE_KEY = doublePreferencesKey("min_drop_distance")
        val MAX_DROP_DISTANCE_KEY = doublePreferencesKey("max_drop_distance")
    }

    val settingsFlow: Flow<ClickConfig> = context.dataStore.data.map { prefs ->
        val modeStr = prefs[CLICK_MODE_KEY] ?: ClickMode.FIXED_POINT.name
        val clickMode = try { ClickMode.valueOf(modeStr) } catch (e: Exception) { ClickMode.FIXED_POINT }

        val typeStr = prefs[CLICK_TYPE_KEY] ?: ClickType.SINGLE.name
        val clickType = try { ClickType.valueOf(typeStr) } catch (e: Exception) { ClickType.SINGLE }

        ClickConfig(
            intervalMs = prefs[INTERVAL_KEY] ?: 500L,
            maxClicks = prefs[MAX_CLICKS_KEY] ?: 0,
            clickMode = clickMode,
            clickType = clickType,
            fixedX = prefs[FIXED_X_KEY] ?: 500f,
            fixedY = prefs[FIXED_Y_KEY] ?: 1000f,
            targetText = prefs[TARGET_TEXT_KEY],
            targetViewId = prefs[TARGET_VIEW_ID_KEY],
            randomizeInterval = prefs[RANDOMIZE_INTERVAL_KEY] ?: false,
            stopOnScreenOff = prefs[STOP_ON_SCREEN_OFF_KEY] ?: true,
            priceConfig = PriceConfig(
                enabled = prefs[PRICE_FILTER_ENABLED] ?: false,
                minPrice = prefs[MIN_PRICE_KEY] ?: 10.0,
                maxPrice = prefs[MAX_PRICE_KEY] ?: Double.MAX_VALUE,
                minPriceWithBonus = prefs[MIN_PRICE_WITH_BONUS_KEY] ?: 15.0,
                currencySymbol = prefs[CURRENCY_SYMBOL] ?: "₹",
                pickupKeywords = prefs[PICKUP_KEYWORDS_KEY] ?: "",
                dropKeywords = prefs[DROP_KEYWORDS_KEY] ?: "",
                templatesSerialized = prefs[TEMPLATES_SERIALIZED_KEY] ?: "",
                useTemplateMatching = prefs[USE_TEMPLATE_MATCHING_KEY] ?: false,
                vibrationTriggerEnabled = prefs[VIBRATION_TRIGGER_ENABLED_KEY] ?: false,
                randomClickDelayMinMs = prefs[RANDOM_CLICK_DELAY_MIN_KEY] ?: 1L,
                randomClickDelayMaxMs = prefs[RANDOM_CLICK_DELAY_MAX_KEY] ?: 30L,
                minPickupDistance = prefs[MIN_PICKUP_DISTANCE_KEY] ?: 0.0,
                maxPickupDistance = prefs[MAX_PICKUP_DISTANCE_KEY] ?: 99.0,
                minDropDistance = prefs[MIN_DROP_DISTANCE_KEY] ?: 0.0,
                maxDropDistance = prefs[MAX_DROP_DISTANCE_KEY] ?: 99.0
            )
        )
    }

    suspend fun updateSettings(config: ClickConfig) {
        context.dataStore.edit { prefs ->
            prefs[INTERVAL_KEY] = config.intervalMs
            prefs[MAX_CLICKS_KEY] = config.maxClicks
            prefs[CLICK_MODE_KEY] = config.clickMode.name
            prefs[CLICK_TYPE_KEY] = config.clickType.name
            prefs[FIXED_X_KEY] = config.fixedX
            prefs[FIXED_Y_KEY] = config.fixedY
            
            if (config.targetText != null) {
                prefs[TARGET_TEXT_KEY] = config.targetText
            } else {
                prefs.remove(TARGET_TEXT_KEY)
            }
            
            if (config.targetViewId != null) {
                prefs[TARGET_VIEW_ID_KEY] = config.targetViewId
            } else {
                prefs.remove(TARGET_VIEW_ID_KEY)
            }
            
            prefs[RANDOMIZE_INTERVAL_KEY] = config.randomizeInterval
            prefs[STOP_ON_SCREEN_OFF_KEY] = config.stopOnScreenOff
            
            prefs[PRICE_FILTER_ENABLED] = config.priceConfig.enabled
            prefs[MIN_PRICE_KEY] = config.priceConfig.minPrice
            prefs[MAX_PRICE_KEY] = config.priceConfig.maxPrice
            prefs[CURRENCY_SYMBOL] = config.priceConfig.currencySymbol
            prefs[MIN_PRICE_WITH_BONUS_KEY] = config.priceConfig.minPriceWithBonus
            prefs[PICKUP_KEYWORDS_KEY] = config.priceConfig.pickupKeywords
            prefs[DROP_KEYWORDS_KEY] = config.priceConfig.dropKeywords
            prefs[TEMPLATES_SERIALIZED_KEY] = config.priceConfig.templatesSerialized
            prefs[USE_TEMPLATE_MATCHING_KEY] = config.priceConfig.useTemplateMatching
            prefs[VIBRATION_TRIGGER_ENABLED_KEY] = config.priceConfig.vibrationTriggerEnabled
            prefs[RANDOM_CLICK_DELAY_MIN_KEY] = config.priceConfig.randomClickDelayMinMs
            prefs[RANDOM_CLICK_DELAY_MAX_KEY] = config.priceConfig.randomClickDelayMaxMs
            prefs[MIN_PICKUP_DISTANCE_KEY] = config.priceConfig.minPickupDistance
            prefs[MAX_PICKUP_DISTANCE_KEY] = config.priceConfig.maxPickupDistance
            prefs[MIN_DROP_DISTANCE_KEY] = config.priceConfig.minDropDistance
            prefs[MAX_DROP_DISTANCE_KEY] = config.priceConfig.maxDropDistance
        }
    }

    suspend fun updatePriceConfig(config: PriceConfig) {
        context.dataStore.edit { prefs ->
            prefs[PRICE_FILTER_ENABLED] = config.enabled
            prefs[MIN_PRICE_KEY] = config.minPrice
            prefs[MAX_PRICE_KEY] = config.maxPrice
            prefs[CURRENCY_SYMBOL] = config.currencySymbol
            prefs[MIN_PRICE_WITH_BONUS_KEY] = config.minPriceWithBonus
            prefs[PICKUP_KEYWORDS_KEY] = config.pickupKeywords
            prefs[DROP_KEYWORDS_KEY] = config.dropKeywords
            prefs[TEMPLATES_SERIALIZED_KEY] = config.templatesSerialized
            prefs[USE_TEMPLATE_MATCHING_KEY] = config.useTemplateMatching
            prefs[VIBRATION_TRIGGER_ENABLED_KEY] = config.vibrationTriggerEnabled
            prefs[RANDOM_CLICK_DELAY_MIN_KEY] = config.randomClickDelayMinMs
            prefs[RANDOM_CLICK_DELAY_MAX_KEY] = config.randomClickDelayMaxMs
            prefs[MIN_PICKUP_DISTANCE_KEY] = config.minPickupDistance
            prefs[MAX_PICKUP_DISTANCE_KEY] = config.maxPickupDistance
            prefs[MIN_DROP_DISTANCE_KEY] = config.minDropDistance
            prefs[MAX_DROP_DISTANCE_KEY] = config.maxDropDistance
        }
    }
}
