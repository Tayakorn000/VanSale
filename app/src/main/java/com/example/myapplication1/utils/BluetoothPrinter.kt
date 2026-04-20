package com.example.myapplication1.utils

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.example.myapplication1.data.db.Customer
import com.example.myapplication1.data.db.Product
import java.text.SimpleDateFormat
import java.util.*

object BluetoothPrinter {

    @SuppressLint("MissingPermission")
    fun printReceipt(
        context: Context,
        customer: Customer,
        quantities: Map<Long, Int>,
        products: List<Product>,
        driverId: String,
        receiptType: String // Accept "DELIVERY" or "TAX" to determine header
    ) {
        // 1. Select the first available paired Bluetooth printer
        val connection = BluetoothPrintersConnections.selectFirstPaired()

        if (connection != null) {
            try {
                // 2. Initialize printer settings (203 DPI, 48mm paper width, 32 character limit)
                val printer = EscPosPrinter(connection, 203, 48f, 32)

                val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                val currentDate = sdf.format(Date())

                // 3. Process line items and calculate total
                var itemsContent = ""
                var total = 0.0

                quantities.forEach { (productId, qty) ->
                    val product = products.find { it.id == productId }
                    if (product != null && qty > 0) {
                        val subtotal = product.price * qty
                        total += subtotal
                        // [L] Left-aligned, [R] Right-aligned
                        itemsContent += "[L]${product.name}[R]x$qty [R]${"%.2f".format(subtotal)}\n"
                    }
                }

                // 4. Select header based on receipt type
                val headerText = if (receiptType == "TAX") {
                    "[C]<b><font size='big'>ใบกำกับภาษี</font></b>\n[C]<b>(Tax Invoice)</b>"
                } else {
                    "[C]<b><font size='big'>ใบส่งของ</font></b>\n[C]<b>(Delivery Note)</b>"
                }

                // 5. Calculate 7% inclusive VAT for Tax Invoices
                val vatText = if (receiptType == "TAX") {
                    val vat = total * 7 / 107
                    val subTotalNoVat = total - vat
                    """
                    [L]มูลค่าสินค้า: [R]${"%.2f".format(subTotalNoVat)}
                    [L]ภาษีมูลค่าเพิ่ม 7%: [R]${"%.2f".format(vat)}
                    """.trimIndent() + "\n"
                } else {
                    "" // Skip VAT display for delivery notes
                }

                // 6. Construct receipt layout using formatting tags ([C] Center, [L] Left, [R] Right, [B] Bold)
                val receiptText = """
                    $headerText
                    [C]--------------------------------
                    [L]<b>วันที่:</b> $currentDate
                    [L]<b>พนักงาน:</b> $driverId
                    [L]<b>ลูกค้า:</b> ${customer.store_name}
                    [L]<b>สาขา:</b> ${customer.branch_code}
                    [L]--------------------------------
                    $itemsContent
                    [L]--------------------------------
                    $vatText[R]<font size='tall'>ยอดรวมทั้งสิ้น: ฿${"%.2f".format(total)}</font>
                    [L]--------------------------------
                    [C]ขอบคุณที่ใช้บริการ
                    [C]ได้รับสินค้าครบถ้วนแล้ว
                    
                    
                    [L]ผู้รับสินค้า.........................
                    
                    
                """.trimIndent()

                printer.printFormattedText(receiptText)

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "เกิดข้อผิดพลาดในการพิมพ์: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "ไม่พบเครื่องพิมพ์ Bluetooth ที่จับคู่ไว้", Toast.LENGTH_SHORT).show()
        }
    }
}