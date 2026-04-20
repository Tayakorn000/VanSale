package com.example.myapplication1.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    @JvmSuppressWildcards
    suspend fun insert(product: Product): Long

    // Methods for updating price and deleting products
    @Update
    @JvmSuppressWildcards
    suspend fun update(product: Product): Int

    @Delete
    @JvmSuppressWildcards
    suspend fun delete(product: Product): Int

    @Query("SELECT * FROM products")
    fun getAllProducts(): Flow<List<Product>>

    @Query("SELECT * FROM products")
    @JvmSuppressWildcards
    suspend fun getAllProductsSync(): List<Product>
}