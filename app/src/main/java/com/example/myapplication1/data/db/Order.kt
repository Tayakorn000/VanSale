package com.example.myapplication1.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "orders")
data class Order(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customer_id: Long,
    val total_amount: Double,
    val timestamp: Long,
    val is_synced: Boolean = false,
    val po_number: String = "",
    val cv_code: String = ""
)