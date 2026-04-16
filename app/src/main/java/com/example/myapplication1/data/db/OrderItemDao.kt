package com.example.myapplication1.data.db

import androidx.room.*

@Dao
interface OrderItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    @JvmSuppressWildcards
    suspend fun insertAll(orderItems: List<OrderItem>): List<Long>

    @Query("SELECT * FROM order_items")
    @JvmSuppressWildcards
    suspend fun getAllOrderItemsSync(): List<OrderItem>
}