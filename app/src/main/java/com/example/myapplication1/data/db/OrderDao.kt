package com.example.myapplication1.data.db

import androidx.room.*

@Dao
interface OrderDao {
    @Insert
    @JvmSuppressWildcards
    suspend fun insert(order: Order): Long

    @Query("SELECT * FROM orders")
    @JvmSuppressWildcards
    suspend fun getAllOrdersSync(): List<Order>

    @Query("SELECT * FROM orders WHERE is_synced = 0")
    @JvmSuppressWildcards
    suspend fun getUnsyncedOrders(): List<Order>

    @Query("UPDATE orders SET is_synced = 1 WHERE id = :orderId")
    @JvmSuppressWildcards
    suspend fun markOrderAsSynced(orderId: Long): Int
}