package com.example.myapplication1.ui.billing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.myapplication1.data.db.AppDatabase

class BillingViewModel(application: Application) : AndroidViewModel(application) {


    private val db = AppDatabase.getDatabase(application)
    val allCustomers = db.customerDao().getAllCustomers()
    val allProducts = db.productDao().getAllProducts()
}