package com.example.myapplication1.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.example.myapplication1.data.db.Order
import com.example.myapplication1.data.db.OrderItem
import java.io.IOException

object CsvExporter {

    fun exportDailySalesToCsv(
        context: Context,
        fileName: String,
        orders: List<Order>,
        orderItems: List<List<OrderItem>>
    ) {
        val csvHeader = "OrderID,CustomerID,TotalAmount,Timestamp,ProductID,Quantity,Subtotal\n"
        val csvData = StringBuilder(csvHeader)

        orders.forEachIndexed { index, order ->
            val items = orderItems.getOrNull(index) ?: emptyList()
            items.forEach { item ->
                csvData.append("${order.id},${order.customer_id},${order.total_amount},${order.timestamp},${item.product_id},${item.quantity},${item.subtotal}\n")
            }
        }

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let {
                resolver.openOutputStream(it).use { outputStream ->
                    outputStream?.write(csvData.toString().toByteArray())
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
