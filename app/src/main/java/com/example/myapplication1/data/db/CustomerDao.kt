package com.example.myapplication1.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    @JvmSuppressWildcards
    suspend fun insert(customer: Customer): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    @JvmSuppressWildcards
    suspend fun insertAll(customers: List<Customer>): List<Long>

    // 🌟 เติม : Int ให้มันคืนค่าว่าแก้ไขไปกี่แถว (ฆ่าบัค V)
    @Update
    @JvmSuppressWildcards
    suspend fun update(customer: Customer): Int

    // 🌟 เติม : Int ให้มันคืนค่าว่าลบไปกี่แถว (ฆ่าบัค V)
    @Delete
    @JvmSuppressWildcards
    suspend fun delete(customer: Customer): Int

    @Query("SELECT * FROM customers ORDER BY id DESC")
    fun getAllCustomers(): Flow<List<Customer>>

    @Query("SELECT * FROM customers")
    @JvmSuppressWildcards
    suspend fun getAllCustomersSync(): List<Customer>

    @Query("SELECT * FROM customers WHERE store_name = :store AND branch_code = :branch LIMIT 1")
    @JvmSuppressWildcards
    suspend fun getCustomerByBranch(store: String, branch: String): Customer?
}