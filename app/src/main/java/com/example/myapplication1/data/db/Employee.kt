package com.example.myapplication1.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "employees")
data class Employee(
    @PrimaryKey val emp_id: String,
    val name: String,
    val role: String
)