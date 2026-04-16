package com.example.myapplication1.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "order_items")
data class OrderItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val order_id: Long,
    val product_id: Long,
    val quantity: Int,
    val subtotal: Double
)
