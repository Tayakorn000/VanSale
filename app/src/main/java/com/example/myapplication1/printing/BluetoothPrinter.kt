package com.example.myapplication1.printing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

import com.example.myapplication1.data.db.Customer
import com.example.myapplication1.data.db.Product

object DBluetoothPrinter {
    private const val TAG = "BluetoothPrinter"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    suspend fun printSingleReceipt(
        context: Context,
        customer: Customer,
        quantities: Map<Long, Int>,
        products: List<Product>,
        driverId: String,
        docTitle: String,
        orderId: Long,
        routeId: String,
        poNumber: String = "",
        cvCode: String = ""
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter

            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                showToast(context, "กรุณาเปิดการเชื่อมต่อ Bluetooth")
                return@withContext false
            }

            val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
            if (pairedDevices.isNullOrEmpty()) {
                showToast(context, "ไม่พบเครื่องพิมพ์ที่จับคู่ไว้")
                return@withContext false
            }

            val printerDevice = pairedDevices.firstOrNull {
                it.name?.contains("printer", ignoreCase = true) == true || it.bluetoothClass.majorDeviceClass == 1536
            } ?: pairedDevices.first()

            var socket: BluetoothSocket? = null
            var outputStream: OutputStream? = null
            var isSuccess = false

            try {
                socket = printerDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothAdapter.cancelDiscovery()
                socket.connect()
                outputStream = socket.outputStream

                // Reset printer to default settings
                outputStream.write(byteArrayOf(0x1b, 0x40))
                Thread.sleep(50)

                // Generate receipt bitmap with word wrapping and safe margins to prevent text clipping
                val receiptBitmap = generateReceiptBitmap(docTitle, customer, quantities, products, driverId, orderId, routeId, poNumber, cvCode)

                // Send bitmap data to printer
                printBitmap(outputStream, receiptBitmap)

                isSuccess = true
                Thread.sleep(1000)

            } catch (e: Exception) {
                Log.e(TAG, "Printing failed", e)
                showToast(context, "เชื่อมต่อเครื่องพิมพ์ล้มเหลว")
            } finally {
                try {
                    outputStream?.close()
                    socket?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing socket", e)
                }
            }
            return@withContext isSuccess
        }
    }

    // =========================================================================
    // Receipt drawing system (Enhanced: includes left/right margins and intelligent word wrapping)
    // =========================================================================
    private fun generateReceiptBitmap(
        docTitle: String, customer: Customer, quantities: Map<Long, Int>,
        products: List<Product>, driverId: String, orderId: Long, routeId: String,
        poNumber: String, cvCode: String
    ): Bitmap {
        val width = 576 // Standard 80mm width
        val marginX = 20f // Safe margins to ensure text visibility
        val printWidth = width - (marginX * 2) // Actual drawable width

        val tempHeight = 3000
        val tempBitmap = Bitmap.createBitmap(width, tempHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tempBitmap)
        canvas.drawColor(Color.WHITE)

        var y = 20f

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            isAntiAlias = true
        }
        val boldPaint = Paint(paint).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 26f
        }

        // Helper to draw centered text with automatic wrapping
        fun drawCenterWrap(text: String, p: Paint) {
            val textPaint = TextPaint(p)
            val layout = StaticLayout(text, textPaint, printWidth.toInt(), Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false)
            canvas.save()
            canvas.translate(marginX, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 4f
        }

        // Helper to draw left-aligned text with automatic wrapping
        fun drawLeftWrap(text: String, p: Paint) {
            val textPaint = TextPaint(p)
            val layout = StaticLayout(text, textPaint, printWidth.toInt(), Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false)
            canvas.save()
            canvas.translate(marginX, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 4f
        }

        // Helper to draw a row with left and right aligned text
        fun drawRow(left: String, right: String, p: Paint) {
            val rightWidth = p.measureText(right)
            val maxLeftWidth = printWidth - rightWidth - 10f

            val textPaint = TextPaint(p)
            val layout = StaticLayout(left, textPaint, maxLeftWidth.toInt(), Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false)

            val baseline = y + layout.getLineBaseline(0)
            canvas.drawText(right, width - marginX - rightWidth, baseline, p)

            canvas.save()
            canvas.translate(marginX, y)
            layout.draw(canvas)
            canvas.restore()

            y += layout.height + 6f
        }

        fun drawLine() {
            y += 8f
            val dashPath = android.graphics.DashPathEffect(floatArrayOf(8f, 8f), 0f)
            val linePaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 2f
                pathEffect = dashPath
            }
            canvas.drawLine(marginX, y, width - marginX, y, linePaint)
            y += 16f
        }

        // Helper to draw item row with 3 columns: Qty / Unit Price / Subtotal
        fun drawItem(name: String, qty: String, unit: String, price: String, p: Paint) {
            val qtyWidth = p.measureText(qty)
            val unitWidth = p.measureText(unit)
            val priceWidth = p.measureText(price)

            val priceX = width - marginX - priceWidth
            val unitX = width - marginX - 120f - unitWidth   // Unit price column
            val qtyX = width - marginX - 240f - qtyWidth     // Quantity column

            val maxNameWidth = printWidth - 260f

            val textPaint = TextPaint(p)
            val layout = StaticLayout(name, textPaint, maxNameWidth.toInt(), Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false)

            val baseline = y + layout.getLineBaseline(0)
            canvas.drawText(qty, qtyX, baseline, p)
            canvas.drawText(unit, unitX, baseline, p)
            canvas.drawText(price, priceX, baseline, p)

            canvas.save()
            canvas.translate(marginX, y)
            layout.draw(canvas)
            canvas.restore()

            y += layout.height + 8f
        }

        // --- Draw Header ---
        docTitle.split("\n").forEach { drawCenterWrap(it, boldPaint) }
        y += 10f
        drawCenterWrap("บริษัท ตั้งเจริญมีนบุรี จำกัด", boldPaint)
        drawCenterWrap("291,291/1 ถนนเจริญพัฒนา แขวงบางชัน", paint)
        drawCenterWrap("เขตคลองสามวา กรุงเทพฯ 10510", paint)
        val hqLine = if (docTitle.contains("ใบกำกับภาษี")) "เลขผู้เสียภาษี 0105546047517 (สำนักงานใหญ่)" else "(สำนักงานใหญ่)"
        drawCenterWrap(hqLine, paint)
        drawLine()

        // --- Receipt Metadata ---
        val currentDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("th", "TH")).format(Date())
        val prefix = if (docTitle.contains("ใบกำกับภาษี")) "V" else ""
        val invoiceNo = if (orderId > 0) String.format("%s%s%07d", prefix, routeId, orderId) else "......................"

        drawRow("เลขที่ $invoiceNo", "วันที่ $currentDate", paint)
        drawLine()

        val storeName = customer.store_name ?: "ลูกค้าทั่วไป"
        drawLeftWrap("ลูกค้า: $storeName", boldPaint)

        if (!customer.address.isNullOrEmpty()) {
            var addr = customer.address.replace("\n", " ").replace("(สำนักงานใหญ่)", "").trim()
            // Add (Head Office) suffix for public companies per business rules
            if (storeName.contains("มหาชน")) {
                addr = "$addr (สำนักงานใหญ่)"
            }
            drawLeftWrap("ที่อยู่: $addr", paint)
        }
        // Customer Tax ID is displayed on Tax Invoices only
        if (!customer.tax_id.isNullOrEmpty() && docTitle.contains("ใบกำกับภาษี")) {
            drawLeftWrap("เลขประจำตัวผู้เสียภาษี: ${customer.tax_id}", paint)
        }

        val branch = if (!customer.branch_code.isNullOrEmpty()) customer.branch_code else "-"
        val rightParts = mutableListOf<String>()
        if (cvCode.isNotEmpty()) rightParts.add("CV.CODE: $cvCode")
        if (poNumber.isNotEmpty()) rightParts.add("P.O: $poNumber")

        if (rightParts.isNotEmpty()) {
            drawRow("สาขา: $branch", rightParts.joinToString("  "), paint)
        } else {
            drawLeftWrap("สาขา: $branch", paint)
        }

        drawLine()

        // --- Table Headers ---
        val headerY = y + boldPaint.textSize
        canvas.drawText("รายการ", marginX, headerY, boldPaint)

        val qtyHeader = "จำนวน"
        val unitHeader = "หน่วยละ"
        val priceHeader = "จำนวนเงิน"
        canvas.drawText(qtyHeader, width - marginX - 240f - boldPaint.measureText(qtyHeader), headerY, boldPaint)
        canvas.drawText(unitHeader, width - marginX - 120f - boldPaint.measureText(unitHeader), headerY, boldPaint)
        canvas.drawText(priceHeader, width - marginX - boldPaint.measureText(priceHeader), headerY, boldPaint)
        y += boldPaint.textSize + 12f
        drawLine()

        // --- Product Items ---
        var total = 0.0
        quantities.filter { it.value > 0 }.forEach { (pId, qty) ->
            val product = products.find { it.id == pId }
            if (product != null) {
                val subtotal = product.price * qty
                total += subtotal
                val cleanName = product.name.replace("\n", " ").replace("\r", "")
                drawItem(
                    cleanName,
                    String.format("%,d", qty),
                    String.format("%,.2f", product.price),
                    String.format("%,.2f", subtotal),
                    paint
                )
            }
        }
        drawLine()

        // --- Totals Summary ---
        val netTotal = total
        val isTaxInvoice = docTitle.contains("ใบกำกับภาษี")

        if (isTaxInvoice) {
            val vat = netTotal * 7 / 107
            val totalBeforeVat = netTotal - vat
            drawRow("รวมเงิน", String.format("%,.2f", totalBeforeVat), paint)
            drawRow("ภาษีมูลค่าเพิ่ม", String.format("%,.2f", vat), paint)
        }
        drawRow("ยอดเงินสุทธิ", String.format("%,.2f", netTotal), boldPaint)
        drawLine()

        // --- Signatures ---
        val senderText = "ผู้ส่งของ ($driverId) ...................."
        y += paint.textSize
        canvas.drawText(senderText, marginX, y, paint)
        y += 20f

        // Padding for stamp and receiver signature
        y += 220f
        val signText1 = "ผู้รับของ ...................."
        canvas.drawText(signText1, marginX, y, paint)

        y += 80f

        // Crop bitmap to actual content height
        return Bitmap.createBitmap(tempBitmap, 0, 0, width, y.toInt())
    }

    // =========================================================================
    // Conversion system: Bitmap to printer-specific Raster Image commands
    // =========================================================================
    private fun printBitmap(outputStream: OutputStream, bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val widthBytes = (width + 7) / 8

        val data = ByteArray(widthBytes * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)

                val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                if (luminance < 128) {
                    val byteIndex = y * widthBytes + (x / 8)
                    val bitIndex = 7 - (x % 8)
                    data[byteIndex] = (data[byteIndex].toInt() or (1 shl bitIndex)).toByte()
                }
            }
        }

        val command = ByteArray(8)
        command[0] = 0x1D
        command[1] = 0x76
        command[2] = 0x30
        command[3] = 0x00
        command[4] = (widthBytes and 0xFF).toByte()
        command[5] = ((widthBytes shr 8) and 0xFF).toByte()
        command[6] = (height and 0xFF).toByte()
        command[7] = ((height shr 8) and 0xFF).toByte()

        outputStream.write(command)
        outputStream.flush()

        val chunkSize = 1024
        var offset = 0
        while (offset < data.size) {
            val length = Math.min(chunkSize, data.size - offset)
            outputStream.write(data, offset, length)
            outputStream.flush()
            Thread.sleep(10)
            offset += length
        }

        outputStream.write(byteArrayOf(0x0A, 0x0A))
        outputStream.flush()
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}