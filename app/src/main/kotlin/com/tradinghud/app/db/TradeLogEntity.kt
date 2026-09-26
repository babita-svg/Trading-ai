package com.tradinghud.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "closed_trades")
data class TradeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val isWin: Boolean,
    /** Stored as String to preserve BigDecimal precision. Always positive. */
    val amountInr: String,
)
