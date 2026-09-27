package com.tradinghud.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import java.math.BigDecimal

@Dao
interface TradeLogDao {
    @Insert
    suspend fun insert(entity: TradeLogEntity)

    @Query(
        "SELECT amountInr FROM closed_trades " +
        "WHERE isWin = 0 AND timestampMillis >= :startOfDayMillis"
    )
    suspend fun getLossAmountsToday(startOfDayMillis: Long): List<String>

    /**
     * Computes the exact sum of all loss amounts on or after [startOfDayMillis]
     * purely in Kotlin using BigDecimal to avoid any floating-point round-trip loss.
     */
    suspend fun sumLossesTodayBd(startOfDayMillis: Long): BigDecimal {
        val amounts = getLossAmountsToday(startOfDayMillis)
        return amounts.fold(BigDecimal.ZERO) { acc, amt ->
            acc.add(BigDecimal(amt))
        }
    }
}
