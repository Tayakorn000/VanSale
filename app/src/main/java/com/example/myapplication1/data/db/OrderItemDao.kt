package com.example.myapplication1.data.db

import androidx.room.*

@Dao
interface OrderItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    @JvmSuppressWildcards
    suspend fun insertAll(orderItems: List<OrderItem>): List<Long>

    @Update
    @JvmSuppressWildcards
    suspend fun update(item: OrderItem): Int

    @Delete
    @JvmSuppressWildcards
    suspend fun delete(item: OrderItem): Int

    @Query("DELETE FROM order_items WHERE order_id = :orderId")
    @JvmSuppressWildcards
    suspend fun deleteByOrderId(orderId: Long): Int

    @Query("SELECT * FROM order_items")
    @JvmSuppressWildcards
    suspend fun getAllOrderItemsSync(): List<OrderItem>

    @Query("SELECT * FROM order_items WHERE order_id = :orderId")
    @JvmSuppressWildcards
    suspend fun getItemsByOrderId(orderId: Long): List<OrderItem>
}
