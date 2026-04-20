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

    // Return number of rows updated
    @Update
    @JvmSuppressWildcards
    suspend fun update(customer: Customer): Int

    // Return number of rows deleted
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