package com.example.myapplication1.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val store_name: String,
    val branch_code: String,
    val tax_id: String = "",
    val address: String = "",
    val print_delivery: Boolean = true,
    val print_tax: Boolean = true
)