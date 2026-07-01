package com.example.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionStats(
    val acceptCount: Int = 0,
    val rejectCount: Int = 0,
    val totalEarnings: Double = 0.0,
    val sessionStartMs: Long = System.currentTimeMillis(),
    val lastAcceptedPrice: Double = 0.0,
    val lastRejectedReason: String = ""
) {
    fun sessionUptimeMs(): Long = System.currentTimeMillis() - sessionStartMs
}

object RideAutomationLogger {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(message: String) {
        val timeStamp = dateFormat.format(Date())
        val formattedMsg = "[$timeStamp] $message"
        val currentList = _logs.value.toMutableList()
        currentList.add(0, formattedMsg)
        if (currentList.size > 200) {
            currentList.removeAt(currentList.lastIndex)
        }
        _logs.value = currentList
    }

    fun recordAccept(price: Double, currencySymbol: String = "₹") {
        val current = _stats.value
        _stats.value = current.copy(
            acceptCount = current.acceptCount + 1,
            totalEarnings = current.totalEarnings + price,
            lastAcceptedPrice = price
        )
        log("✅ ACCEPTED — ${currencySymbol}${price} | Session: ${_stats.value.acceptCount} accepted, ${_stats.value.rejectCount} rejected")
    }

    fun recordReject(reason: String) {
        val current = _stats.value
        _stats.value = current.copy(
            rejectCount = current.rejectCount + 1,
            lastRejectedReason = reason
        )
    }

    fun resetSession() {
        _stats.value = SessionStats()
        _logs.value = emptyList()
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
