package com.example.myapplication1.data.db

import androidx.room.*

@Dao
interface EmployeeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    @JvmSuppressWildcards
    suspend fun insertAll(employees: List<Employee>): List<Long>

    @Query("DELETE FROM employees")
    @JvmSuppressWildcards
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM employees WHERE emp_id = :empId LIMIT 1")
    @JvmSuppressWildcards
    suspend fun getEmployeeById(empId: String): Employee?
}