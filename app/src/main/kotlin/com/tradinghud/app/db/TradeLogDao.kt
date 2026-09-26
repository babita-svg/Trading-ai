package com.tradinghud.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import java.math.BigDecimal

@Dao
interface TradeLogDao {
    @Insert
    suspend fun insert(entity: TradeLogEntity)

    /**
     * Returns the sum of all loss amounts on or after [startOfDayMillis].
     * Returns "0" if there are no losses today.
     */
    @Query(
        "SELECT COALESCE(SUM(CAST(amountInr AS REAL)), 0) " +
        "FROM closed_trades " +
        "WHERE isWin = 0 AND timestampMillis >= :startOfDayMillis"
    )
    suspend fun sumLossesToday(startOfDayMillis: Long): Double

    /** Convenience: convert the raw Double sum to BigDecimal for the risk engine. */
    suspend fun sumLossesTodayBd(startOfDayMillis: Long): BigDecimal =
        BigDecimal(sumLossesToday(startOfDayMillis).toString())
}
