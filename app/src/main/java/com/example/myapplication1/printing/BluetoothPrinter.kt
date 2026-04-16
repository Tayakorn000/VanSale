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

                // รีเซ็ตเครื่องพิมพ์ให้พร้อม
                outputStream.write(byteArrayOf(0x1b, 0x40))
                Thread.sleep(50)

                // 🌟 สร้างภาพใบเสร็จพร้อมระบบตัดคำ และ Margin ป้องกันข้อความแหว่ง 🌟
                val receiptBitmap = generateReceiptBitmap(docTitle, customer, quantities, products, driverId, orderId, routeId, poNumber, cvCode)

                // ส่งรูปภาพไปปริ้น
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
    // 🌟 ระบบวาดใบเสร็จเป็นรูปภาพ (อัปเกรดใหม่: มี Margin ซ้ายขวา + ตัดคำฉลาด)
    // =========================================================================
    private fun generateReceiptBitmap(
        docTitle: String, customer: Customer, quantities: Map<Long, Int>,
        products: List<Product>, driverId: String, orderId: Long, routeId: String,
        poNumber: String, cvCode: String
    ): Bitmap {
        val width = 576 // กว้างมาตรฐาน 80mm
        val marginX = 40f // 🌟 ขอบปลอดภัย ป้องกันข้อความโดนตัดซ้ายขวา
        val printWidth = width - (marginX * 2) // พื้นที่เขียนตัวหนังสือจริงๆ

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

        // ฟังก์ชันวาดข้อความจัดกลาง (ตัดคำอัตโนมัติ)
        fun drawCenterWrap(text: String, p: Paint) {
            val textPaint = TextPaint(p)
            val layout = StaticLayout(text, textPaint, printWidth.toInt(), Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false)
            canvas.save()
            canvas.translate(marginX, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 6f
        }

        // ฟังก์ชันวาดข้อความชิดซ้าย (ตัดคำอัตโนมัติ)
        fun drawLeftWrap(text: String, p: Paint) {
            val textPaint = TextPaint(p)
            val layout = StaticLayout(text, textPaint, printWidth.toInt(), Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false)
            canvas.save()
            canvas.translate(marginX, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 6f
        }

        // ฟังก์ชันวาดบรรทัดที่มี ซ้าย-ขวา
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

        // ฟังก์ชันวาดตารางสินค้า
        fun drawItem(name: String, qty: String, price: String, p: Paint) {
            val qtyWidth = p.measureText(qty)
            val priceWidth = p.measureText(price)

            val priceX = width - marginX - priceWidth
            val qtyX = width - marginX - 140f - qtyWidth // ล็อคตำแหน่งช่องจำนวน

            val maxNameWidth = printWidth - 160f

            val textPaint = TextPaint(p)
            val layout = StaticLayout(name, textPaint, maxNameWidth.toInt(), Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false)

            val baseline = y + layout.getLineBaseline(0)
            canvas.drawText(qty, qtyX, baseline, p)
            canvas.drawText(price, priceX, baseline, p)

            canvas.save()
            canvas.translate(marginX, y)
            layout.draw(canvas)
            canvas.restore()

            y += layout.height + 8f
        }

        // --- เริ่มวาดหัวบิล ---
        docTitle.split("\n").forEach { drawCenterWrap(it, boldPaint) }
        y += 10f
        drawCenterWrap("บริษัท ตั้งเจริญมีนบุรี จำกัด", boldPaint)
        drawCenterWrap("291,291/1 ถนนเจริญพัฒนา แขวงบางชัน เขตคลองสามวา", paint)
        drawCenterWrap("กรุงเทพมหานคร 10510", paint)
        drawCenterWrap("เลขประจำตัวผู้เสียภาษี 0105546047517 (สำนักงานใหญ่)", paint)
        drawLine()

        // --- ข้อมูลบิล ---
        val currentDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("th", "TH")).format(Date())
        val prefix = if (docTitle.contains("ใบกำกับภาษี")) "V" else ""
        val invoiceNo = if (orderId > 0) String.format("%s%s%07d", prefix, routeId, orderId) else "......................"

        drawRow("เลขที่ $invoiceNo", "วันที่ $currentDate", paint)
        drawLine()

        val storeName = customer.store_name ?: "ลูกค้าทั่วไป"
        drawLeftWrap("ลูกค้า: $storeName", boldPaint)

        if (!customer.address.isNullOrEmpty()) {
            drawLeftWrap("ที่อยู่: ${customer.address.replace("\n", " ")}", paint)
        }
        if (!customer.tax_id.isNullOrEmpty()) {
            drawLeftWrap("เลขผู้เสียภาษี: ${customer.tax_id}", paint)
        }

        val branch = if (!customer.branch_code.isNullOrEmpty()) customer.branch_code else "-"
        drawLeftWrap("สาขา: $branch", paint)

        if (cvCode.isNotEmpty()) drawLeftWrap("CV.CODE: $cvCode", boldPaint)
        if (poNumber.isNotEmpty()) drawLeftWrap("เลขที่ PO: $poNumber", boldPaint)

        drawLine()

        // --- หัวตารางสินค้า ---
        val headerY = y + boldPaint.textSize
        canvas.drawText("รายการ", marginX, headerY, boldPaint)

        val qtyHeader = "จำนวน"
        val priceHeader = "จำนวนเงิน"
        canvas.drawText(qtyHeader, width - marginX - 140f - boldPaint.measureText(qtyHeader), headerY, boldPaint)
        canvas.drawText(priceHeader, width - marginX - boldPaint.measureText(priceHeader), headerY, boldPaint)
        y += boldPaint.textSize + 12f
        drawLine()

        // --- รายการสินค้า ---
        var total = 0.0
        quantities.filter { it.value > 0 }.forEach { (pId, qty) ->
            val product = products.find { it.id == pId }
            if (product != null) {
                val subtotal = product.price * qty
                total += subtotal
                val cleanName = product.name.replace("\n", " ").replace("\r", "")
                drawItem(cleanName, String.format("%,d", qty), String.format("%,.2f", subtotal), paint)
            }
        }
        drawLine()

        // --- สรุปยอด ---
        val netTotal = total
        val vat = netTotal * 7 / 107
        val totalBeforeVat = netTotal - vat

        drawRow("รวมเงิน", String.format("%,.2f", totalBeforeVat), paint)
        drawRow("ภาษีมูลค่าเพิ่ม", String.format("%,.2f", vat), paint)
        drawRow("ยอดเงินสุทธิ", String.format("%,.2f", netTotal), boldPaint)
        drawLine()

        // --- ช่องลายเซ็น (เว้นพื้นที่ประทับตรา) ---
        y += 250f // เว้นที่กว้างๆ สำหรับปั๊มตราบริษัท

        // 1. วาดบรรทัดผู้รับของ (ชิดซ้าย)
        val signText1 = "ผู้รับของ ...................."
        canvas.drawText(signText1, marginX, y, paint)

        // 2. เว้นระยะห่างลงมาบรรทัดใหม่ (ปรับตัวเลข 150f ได้ตามต้องการ)
        y += 150f

        // 3. วาดบรรทัดผู้ส่งของ (ชิดขวา)
        val signText2 = "ผู้ส่งของ ...................."
        canvas.drawText(signText2, width - marginX - paint.measureText(signText2), y, paint)

        y += 80f

        // ตัดกระดาษให้พอดีกับความยาวจริง
        return Bitmap.createBitmap(tempBitmap, 0, 0, width, y.toInt())
    }

    // =========================================================================
    // 🌟 ระบบแปลงภาพเป็นรหัสเครื่องพิมพ์ (Raster Image)
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