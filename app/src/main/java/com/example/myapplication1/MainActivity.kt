package com.example.myapplication1

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope

import com.example.myapplication1.data.db.*
import com.example.myapplication1.printing.DBluetoothPrinter
import com.example.myapplication1.ui.billing.BillingViewModel
import com.example.myapplication1.ui.theme.MyApplication1Theme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// SCRIPT_URL ถูกกำหนดผ่าน BuildConfig จากไฟล์ local.properties (ไม่อัปขึ้น git)
val SCRIPT_URL: String = if (BuildConfig.SCRIPT_URL.isNotEmpty()) BuildConfig.SCRIPT_URL 
                         else "https://script.google.com/macros/s/AKfycbxBaZFmW3SGF_H-wEVtKNzTTHDIw2z8PkBLdu_-W1z6VmrW81iilq-KGps8n6sjqqEv/exec"

enum class AppScreen { BILLING, ADMIN, HISTORY }

data class PrintData(val customer: Customer, val quantities: Map<Long, Int>, val orderId: Long, val poNumber: String, val cvCode: String)

data class RouteInfo(val id: String, val name: String) {
    val display: String get() = if (name.isNotBlank() && name != id) "$id — $name" else id
}

fun parseRoutes(json: String?): List<RouteInfo> {
    return try {
        val arr = JSONArray(json ?: "[]")
        List(arr.length()) { i ->
            when (val v = arr.get(i)) {
                is JSONObject -> RouteInfo(v.optString("id", v.optString("route_id", "")), v.optString("name", v.optString("description", "")))
                else -> RouteInfo(v.toString(), "")
            }
        }.filter { it.id.isNotBlank() }
    } catch (e: Exception) { emptyList() }
}

fun lookupRouteName(context: Context, routeId: String): String {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    return parseRoutes(prefs.getString("routes_list", "[]")).find { it.id == routeId }?.name ?: ""
}

// Mutex กันอัปโหลดชนกันระหว่าง auto-sync กับปุ่มปริ้น
val syncMutex = Mutex()

// ตรวจว่ามีเครือข่ายที่มีความสามารถ INTERNET หรือไม่ (ไม่ probe, เร็ว, ใช้ใน auto-sync)
fun hasNetwork(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) { false }
}

// เช็คอินเทอร์เน็ตจริง (สำหรับไอคอนสถานะ):
// 1) ถ้า Android ตั้ง VALIDATED แล้ว → เชื่อ (เร็ว, ไม่ใช้เน็ต)
// 2) ไม่งั้น → probe generate_204
// 3) probe ล้มเหลว → fallback: เชื่อแค่ว่ามี INTERNET capability
suspend fun hasRealInternet(context: Context): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: run { Log.d("VanSync", "no active network"); return@withContext false }
            val caps = cm.getNetworkCapabilities(network) ?: return@withContext false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                Log.d("VanSync", "no INTERNET capability"); return@withContext false
            }
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                Log.d("VanSync", "VALIDATED → online"); return@withContext true
            }
            val url = URL("http://clients3.google.com/generate_204")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            conn.disconnect()
            Log.d("VanSync", "probe code=$code")
            code == 204
        } catch (e: Exception) {
            // probe ล้มเหลว (carrier block / firewall) → เชื่อ capability flag แทน
            Log.d("VanSync", "probe failed: ${e.message} — fallback to capability check")
            hasNetwork(context)
        }
    }
}

suspend fun downloadEmployeesFromCloud(context: Context): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL("$SCRIPT_URL?action=getEmployees")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            if (conn.responseCode == 200) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream))
                val response = java.lang.StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) { response.append(line) }
                reader.close()
                val jsonArray = JSONArray(response.toString())
                val db = AppDatabase.getDatabase(context)
                val emps = mutableListOf<Employee>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    emps.add(Employee(obj.getString("emp_id"), obj.getString("name"), obj.getString("role")))
                }
                db.employeeDao().deleteAll()
                db.employeeDao().insertAll(emps)
                true
            } else false
        } catch (e: Exception) { false }
    }
}

suspend fun downloadRoutesFromCloud(context: Context): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL("$SCRIPT_URL?action=getRoutes")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            if (conn.responseCode == 200) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream))
                val response = java.lang.StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) { response.append(line) }
                reader.close()
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("routes_list", response.toString()).apply()
                true
            } else false
        } catch (e: Exception) { false }
    }
}

suspend fun downloadCustomersFromCloud(context: Context): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL(SCRIPT_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            if (conn.responseCode == 200) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream))
                val response = java.lang.StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) { response.append(line) }
                reader.close()
                val jsonArray = JSONArray(response.toString())
                val db = AppDatabase.getDatabase(context)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val storeName = obj.optString("store_name", "")
                    val branchCode = obj.optString("branch_code", "")
                    val taxId = obj.optString("tax_id", "")
                    val addressNo = obj.optString("address_no", "")
                    val building = obj.optString("building", "")
                    val road = obj.optString("road", "")
                    val subDistrict = obj.optString("sub_district", "")
                    val district = obj.optString("district", "") // ดึงเขตมาด้วย
                    val province = obj.optString("province", "")
                    val zipCode = obj.optString("zip_code", "")
                    val printDelivery = obj.optBoolean("print_delivery", true)
                    val printTax = obj.optBoolean("print_tax", false)

                    // ใส่คำนำหน้า แขวง/เขต และเว้นวรรคให้ห่างสวยงาม
                    val cleanSubDist = if (subDistrict.isNotEmpty() && !subDistrict.contains("แขวง") && !subDistrict.contains("ตำบล")) "แขวง$subDistrict" else subDistrict
                    val cleanDist = if (district.isNotEmpty() && !district.contains("เขต") && !district.contains("อำเภอ")) "เขต$district" else district
                    
                    val finalAddress = listOf(addressNo, building, road, cleanSubDist, cleanDist, province, zipCode)
                        .filter { it.isNotBlank() }
                        .joinToString("   ") // เว้น 3 เคาะตามที่ต้องการ

                    val existing = db.customerDao().getCustomerByBranch(storeName, branchCode)
                    if (existing != null) {
                        db.customerDao().update(existing.copy(tax_id = taxId, address = finalAddress, print_delivery = printDelivery, print_tax = printTax))
                    } else {
                        db.customerDao().insert(Customer(store_name = storeName, branch_code = branchCode, tax_id = taxId, address = finalAddress, print_delivery = printDelivery, print_tax = printTax))
                    }
                }
                true
            } else false
        } catch (e: Exception) { false }
    }
}

suspend fun uploadToGoogleSheet(
    deliveryNo: String, taxNo: String, date: String,
    store: String, branch: String,
    cvCode: String, poNumber: String,
    taxId: String,
    itemsJson: JSONArray, itemsText: String,
    total: Double, empId: String,
    driverName: String = "", routeId: String = ""
): Boolean {
    return try {
        val url = URL(SCRIPT_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        // เรียงตามคอลัมน์ในชีท (A→K):
        // เลขที่ใบส่งของ | เลขใบกำกับภาษี | วันที่ | ชื่อลูกค้า | สาขา | CV.CODE | เลขที่ P.O | เลขผู้เสียภาษี | รายการสินค้า | ยอดรวม | รหัสพนักงาน
        val jsonObject = JSONObject().apply {
            put("deliveryNo", deliveryNo ?: "-")   // A
            put("taxNo", taxNo ?: "-")             // B
            put("date", date ?: "")               // C
            put("storeName", store ?: "")          // D
            put("branch", branch ?: "")            // E
            put("cvCode", cvCode ?: "")            // F
            put("poNumber", poNumber ?: "")        // G
            put("taxId", taxId ?: "")              // H
            put("itemsText", itemsText ?: "")      // I
            put("total", total.toString())         // J
            put("empId", empId ?: "")              // K
            put("items", itemsJson)                // สำรอง array
            put("driverName", driverName)
            put("routeId", routeId)
        }
        val jsonInputString = jsonObject.toString()
        conn.outputStream.use { os ->
            val input = jsonInputString.toByteArray(Charsets.UTF_8)
            os.write(input, 0, input.size)
        }
        val responseCode = conn.responseCode
        conn.disconnect()
        val ok = responseCode in 200..299
        Log.d("VanSync", "upload $deliveryNo → code=$responseCode ok=$ok")
        ok
    } catch (e: Exception) {
        Log.d("VanSync", "upload failed: ${e.message}")
        false
    }
}

suspend fun syncPendingCloudOrders(context: Context, driverId: String, routeId: String) {
    // ไม่มีเน็ตเลย → skip. Captive portal ให้ POST เป็นคนตัดสิน (timeout/failed → retry รอบหน้า)
    if (!hasNetwork(context)) { Log.d("VanSync", "skip: no network"); return }

    syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val unsyncedOrders = db.orderDao().getUnsyncedOrders()
            Log.d("VanSync", "unsynced orders: ${unsyncedOrders.size}")
            if (unsyncedOrders.isEmpty()) return@withContext

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            // อ่าน offset ใน Mutex — ให้เลขบิลที่อัปตรงกับที่ปริ้น
            val offset = prefs.getLong("invoice_offset", 0L)

            val driverName = db.employeeDao().getEmployeeById(driverId)?.name ?: ""

            val allCustomers = db.customerDao().getAllCustomersSync()
            val allProducts = db.productDao().getAllProducts().first()
            for (order in unsyncedOrders) {
                val customer = allCustomers.find { it.id == order.customer_id } ?: continue
                val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(order.timestamp))

                val actualInvoiceNo = order.id + offset
                val baseInvoice = String.format("%s%07d", routeId, actualInvoiceNo)

                val deliveryNo = if (customer.print_delivery) baseInvoice else "-"
                val taxNo = if (customer.print_tax) "V$baseInvoice" else "-"

                // ดึงรายการสินค้าของบิลนี้ สร้างทั้งรูปแบบ array (items) และ string (itemsText)
                val items = db.orderItemDao().getItemsByOrderId(order.id)
                val itemsJson = JSONArray()
                val itemsTextBuilder = StringBuilder()
                for (it in items) {
                    val product = allProducts.find { p -> p.id == it.product_id }
                    val name = product?.name ?: "สินค้า#${it.product_id}"
                    val price = product?.price ?: 0.0
                    itemsJson.put(JSONObject().apply {
                        put("name", name)
                        put("qty", it.quantity)
                        put("price", price)
                        put("subtotal", it.subtotal)
                    })
                    if (itemsTextBuilder.isNotEmpty()) itemsTextBuilder.append(", ")
                    itemsTextBuilder.append("$name x${it.quantity} (${"%.0f".format(it.subtotal)}฿)")
                }

                val isSuccess = uploadToGoogleSheet(
                    deliveryNo, taxNo, dateStr,
                    customer.store_name, customer.branch_code,
                    order.cv_code, order.po_number,
                    customer.tax_id,
                    itemsJson, itemsTextBuilder.toString(),
                    order.total_amount, driverId,
                    driverName, routeId
                )
                if (isSuccess) {
                    db.orderDao().markOrderAsSynced(order.id)
                }
            }
        }
    }
}

suspend fun exportCDOrganizerToPhone(context: Context, db: AppDatabase, products: List<Product>, driverId: String, routeId: String): Pair<Boolean, String> {
    return withContext(Dispatchers.IO) {
        try {
            val orders = db.orderDao().getAllOrdersSync()
            val items = db.orderItemDao().getAllOrderItemsSync()
            val customers = db.customerDao().getAllCustomersSync()

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val offset = prefs.getLong("invoice_offset", 0L)

            val csvBuilder = StringBuilder()
            csvBuilder.append("เลขบิลขาย,วันที่ขาย,รหัสลูกค้า,ชื่อลูกค้า,รหัสพนักงานขาย,คลังสินค้า,รหัสสินค้า,ชื่อสินค้า,จำนวน,ราคาต่อหน่วย,จำนวนเงินรวม\n")

            val todayStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())

            orders.filter { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(it.timestamp)) == todayStr }.forEach { order ->
                val customer = customers.find { it.id == order.customer_id }
                val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(order.timestamp))
                val orderItems = items.filter { it.order_id == order.id }
                val prefix = if (customer?.print_tax == true) "V" else ""

                val actualInvoiceNo = order.id + offset
                val invoiceNoStr = String.format("%s%s%07d", prefix, routeId, actualInvoiceNo)

                orderItems.forEach { item ->
                    val product = products.find { it.id == item.product_id }
                    val custCode = customer?.id?.toString() ?: ""
                    val custName = customer?.store_name ?: "ลูกค้าทั่วไป"
                    val whCode = "1"
                    val prodCode = product?.id?.toString() ?: ""
                    val prodName = product?.name ?: "สินค้าไม่ทราบชื่อ"
                    val qty = item.quantity
                    val price = product?.price ?: 0.0
                    val total = item.subtotal
                    csvBuilder.append("$invoiceNoStr,$dateStr,$custCode,$custName,$driverId,$whCode,$prodCode,$prodName,$qty,$price,$total\n")
                }
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            val fileName = "cd_export_${driverId}_$timeStamp.csv"
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/VanSale_Export")
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Files.getContentUri("external")
            }
            val fileUri = resolver.insert(collection, contentValues)
            if (fileUri != null) {
                resolver.openOutputStream(fileUri)?.use { outputStream ->
                    outputStream.write(csvBuilder.toString().toByteArray(charset("TIS-620")))
                }
                Pair(true, "สร้างไฟล์ $fileName สำเร็จ!")
            } else {
                Pair(false, "ไม่สามารถสร้างไฟล์ในระบบได้")
            }
        } catch (e: Exception) {
            Pair(false, "เกิดข้อผิดพลาด: ${e.message}")
        }
    }
}

// ไอคอนแสดงสถานะการเชื่อมต่อ — เขียวเมื่อมีอินเทอร์เน็ตจริง, เทาเมื่อออฟไลน์/ติด Captive Portal
@Composable
fun SyncStatusIcon() {
    val context = LocalContext.current
    var online by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            online = hasRealInternet(context)
            delay(10_000L)
        }
    }

    val tint by animateColorAsState(
        targetValue = if (online) Color(0xFF22C55E) else Color(0xFF9CA3AF),
        animationSpec = tween(durationMillis = 400),
        label = "syncTint"
    )

    Icon(
        imageVector = if (online) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
        contentDescription = if (online) "ออนไลน์" else "ออฟไลน์",
        tint = tint,
        modifier = Modifier.size(22.dp).padding(end = 4.dp)
    )
}

@Composable
fun DashedDivider() {
    Canvas(modifier = Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = Color.Gray,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        )
    }
}

class MainActivity1 : ComponentActivity() {
    private val billingViewModel: BillingViewModel by viewModels()
    private val requestBluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addSampleData(billingViewModel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBluetoothPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION))
        }

        // Kiosk: fullscreen immersive + ไม่ให้จอดับ + ซ่อน status/nav bar
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        try {
            startLockTask()
        } catch (e: Exception) {
            Log.e("KioskMode", "อุปกรณ์ไม่รองรับ Lock Task", e)
        }

        setContent {
            MyApplication1Theme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                    var isLoggedIn by remember { mutableStateOf(false) }
                    var driverId by remember { mutableStateOf("") }
                    var driverName by remember { mutableStateOf("") }
                    var userRole by remember { mutableStateOf("") }
                    var routeId by remember { mutableStateOf("") }
                    var currentScreen by remember { mutableStateOf(AppScreen.BILLING) }

                    // Kiosk: กดปุ่ม back ที่หน้าหลักไม่ออกจากแอป
                    BackHandler(enabled = currentScreen == AppScreen.BILLING) { /* สวาปาม */ }

                    if (!isLoggedIn) {
                        LoginScreen(onLoginSuccess = { id, name, role, route ->
                            driverId = id; driverName = name; userRole = role; routeId = route; isLoggedIn = true
                        })
                    } else {
                        when (currentScreen) {
                            AppScreen.BILLING -> BillingScreen(
                                viewModel = billingViewModel, driverId = driverId, driverName = driverName, routeId = routeId, userRole = userRole,
                                onNavigateToAdmin = { currentScreen = AppScreen.ADMIN },
                                onNavigateToHistory = { currentScreen = AppScreen.HISTORY }
                            )
                            AppScreen.ADMIN -> AdminScreen(onBack = { currentScreen = AppScreen.BILLING })
                            AppScreen.HISTORY -> HistoryScreen(driverId = driverId, routeId = routeId, onBack = { currentScreen = AppScreen.BILLING })
                        }
                    }
                }
            }
        }
    }

    private fun addSampleData(viewModel: BillingViewModel) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            if (db.productDao().getAllProducts().first().isEmpty()) {
                db.productDao().insert(Product(name = "น้ำแข็งแพ็ค", price = 10.0))
                db.productDao().insert(Product(name = "น้ำแข็งเหลี่ยม", price = 18.0))
                db.productDao().insert(Product(name = "หลอดเล็ก", price = 60.0))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoginSuccess: (String, String, String, String) -> Unit) {
    var empId by remember { mutableStateOf("") }
    var routeId by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }

    var isCheckingData by remember { mutableStateOf(true) }
    var showSyncError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val empSuccess = downloadEmployeesFromCloud(context)
        val routeSuccess = downloadRoutesFromCloud(context)
        val custSuccess = downloadCustomersFromCloud(context)
        if (!empSuccess || !routeSuccess || !custSuccess) {
            showSyncError = true
        }
        isCheckingData = false
    }

    if (showSyncError) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("⚠️ การเชื่อมต่อล้มเหลว!", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 24.sp) },
            text = { Text("ไม่สามารถซิงค์ข้อมูลกับเซิร์ฟเวอร์ได้\nกรุณาตรวจสอบอินเทอร์เน็ต (เน็ตมือถือหรือ Wi-Fi)\nและลองเข้าแอปใหม่อีกครั้ง", fontSize = 18.sp) },
            confirmButton = {
                Button(onClick = { showSyncError = false }) { Text("รับทราบ (ใช้งานออฟไลน์)") }
            }
        )
    }

    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val savedRoutesStr = prefs.getString("routes_list", "[]")
    val routeOptions = remember(savedRoutesStr) { parseRoutes(savedRoutesStr) }
    val selectedRouteName = remember(routeId, routeOptions) {
        routeOptions.find { it.id == routeId }?.name ?: ""
    }

    fun performLogin() {
        if (routeId.isBlank()) { Toast.makeText(context, "กรุณาระบุสาย", Toast.LENGTH_SHORT).show(); return }
        if (empId == "9999") { onLoginSuccess("9999", "ผู้ดูแลระบบ", "ADMIN", routeId); return }
        if (empId.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val emp = db.employeeDao().getEmployeeById(empId)
                withContext(Dispatchers.Main) {
                    if (emp != null) onLoginSuccess(emp.emp_id, emp.name, emp.role, routeId)
                    else Toast.makeText(context, "รหัสพนักงานไม่ถูกต้อง", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
        if (isCheckingData) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Card(modifier = Modifier.fillMaxWidth(0.9f), elevation = CardDefaults.cardElevation(defaultElevation = 12.dp), shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.LocalShipping, null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Van Sale", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(32.dp))

                    OutlinedTextField(
                        value = empId, onValueChange = { empId = it }, label = { Text("รหัสพนักงาน") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                        OutlinedTextField(
                            value = routeId, onValueChange = { routeId = it }, label = { Text("สายที่ขับ (เช่น 01, 02)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(), shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { performLogin() })
                        )
                        if (routeOptions.isNotEmpty()) {
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                routeOptions.forEach { opt ->
                                    DropdownMenuItem(
                                        text = { Text("สาย ${opt.display}") },
                                        onClick = { routeId = opt.id; expanded = false }
                                    )
                                }
                            }
                        }
                    }

                    if (selectedRouteName.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("สายนี้คือ", fontSize = 12.sp, color = Color.Gray)
                                Text(selectedRouteName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { performLogin() }, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(16.dp)) { Text("เข้าสู่ระบบ", fontSize = 18.sp) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(driverId: String, routeId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var customers by remember { mutableStateOf<List<Customer>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            orders = db.orderDao().getAllOrdersSync().sortedByDescending { it.timestamp }
            customers = db.customerDao().getAllCustomersSync()
        }
    }

    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val offset = prefs.getLong("invoice_offset", 0L)

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("ประวัติบิล", fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            items(orders) { order ->
                val customer = customers.find { it.id == order.customer_id }
                val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(order.timestamp))
                val prefix = if (customer?.print_tax == true) "V" else ""

                val actualInvoiceNo = order.id + offset
                val invoiceNo = String.format("%s%s%07d", prefix, routeId, actualInvoiceNo)

                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(invoiceNo, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(customer?.store_name ?: "-", fontWeight = FontWeight.Medium)
                            Text(dateStr, fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val coroutineScope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("จัดการสาขา", "ราคาสินค้า", "ตั้งค่าเลขบิล")

    val allCustomers by db.customerDao().getAllCustomers().collectAsState(initial = emptyList())
    var editingCustomer by remember { mutableStateOf<Customer?>(null) }
    var storeName by remember { mutableStateOf("") }
    var branchCode by remember { mutableStateOf("") }
    var taxId by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var printDelivery by remember { mutableStateOf(true) }
    var printTax by remember { mutableStateOf(true) }
    fun clearCustomerForm() { editingCustomer = null; storeName = ""; branchCode = ""; taxId = ""; address = ""; printDelivery = true; printTax = true }

    val allProducts by db.productDao().getAllProducts().collectAsState(initial = emptyList())
    var editingProduct by remember { mutableStateOf<Product?>(null) }
    var productName by remember { mutableStateOf("") }
    var productPrice by remember { mutableStateOf("") }
    fun clearProductForm() { editingProduct = null; productName = ""; productPrice = "" }

    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(title = { Text("ตั้งค่าระบบหลังบ้าน", fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } })
                TabRow(selectedTabIndex = selectedTabIndex) { tabs.forEachIndexed { index, title -> Tab(selected = selectedTabIndex == index, onClick = { selectedTabIndex = index }, text = { Text(title, fontWeight = FontWeight.Bold) }) } }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (selectedTabIndex == 0) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(if (editingCustomer == null) "เพิ่มสาขาใหม่" else "แก้ไขข้อมูลสาขา", fontWeight = FontWeight.Bold, fontSize = 18.sp); if (editingCustomer != null) { TextButton(onClick = { clearCustomerForm() }) { Text("ยกเลิก") } } }
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = if (editingCustomer == null) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(16.dp)) {
                                OutlinedTextField(value = storeName, onValueChange = { storeName = it }, label = { Text("ชื่อร้าน") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row { OutlinedTextField(value = branchCode, onValueChange = { branchCode = it }, label = { Text("รหัสสาขา") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)); Spacer(modifier = Modifier.width(8.dp)); OutlinedTextField(value = taxId, onValueChange = { taxId = it }, label = { Text("เลขผู้เสียภาษี") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("ที่อยู่") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = printDelivery, onCheckedChange = { printDelivery = it }); Text("ใบส่งของ"); Spacer(modifier = Modifier.width(16.dp)); Checkbox(checked = printTax, onCheckedChange = { printTax = it }); Text("ใบกำกับภาษี") }
                                Spacer(modifier = Modifier.height(16.dp))
                                if (editingCustomer == null) { Button(onClick = { if (storeName.isBlank() || branchCode.isBlank()) return@Button; coroutineScope.launch { db.customerDao().insert(Customer(store_name = storeName.trim(), branch_code = branchCode.trim(), tax_id = taxId.trim(), address = address.trim(), print_delivery = printDelivery, print_tax = printTax)); Toast.makeText(context, "เพิ่มสำเร็จ!", Toast.LENGTH_SHORT).show(); clearCustomerForm() } }, modifier = Modifier.fillMaxWidth()) { Text("บันทึกข้อมูลใหม่") } } else { Row { Button(onClick = { coroutineScope.launch { db.customerDao().update(editingCustomer!!.copy(store_name = storeName.trim(), branch_code = branchCode.trim(), tax_id = taxId.trim(), address = address.trim(), print_delivery = printDelivery, print_tax = printTax)); Toast.makeText(context, "แก้ไขเรียบร้อย!", Toast.LENGTH_SHORT).show(); clearCustomerForm() } }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("บันทึกแก้ไข") }; Spacer(Modifier.width(8.dp)); Button(onClick = { coroutineScope.launch { db.customerDao().delete(editingCustomer!!); clearCustomerForm() } }, modifier = Modifier.weight(0.5f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.Delete, null) } } }
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("แตะที่รายการเพื่อแก้ไข (${allCustomers.size})", fontWeight = FontWeight.Bold, color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    items(allCustomers) { customer -> Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { editingCustomer = customer; storeName = customer.store_name; branchCode = customer.branch_code; taxId = customer.tax_id; address = customer.address; printDelivery = customer.print_delivery; printTax = customer.print_tax }, colors = CardDefaults.cardColors(containerColor = Color.White)) { Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text(customer.store_name, fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("สาขา: ${customer.branch_code}", fontSize = 14.sp, color = Color.Blue) }; Icon(Icons.Default.Edit, "Edit", tint = Color.LightGray) } } }
                }
            } else if (selectedTabIndex == 1) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(if (editingProduct == null) "เพิ่มสินค้าใหม่" else "แก้ไขราคาสินค้า", fontWeight = FontWeight.Bold, fontSize = 18.sp); if (editingProduct != null) { TextButton(onClick = { clearProductForm() }) { Text("ยกเลิก") } } }
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = if (editingProduct == null) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(16.dp)) {
                                OutlinedTextField(value = productName, onValueChange = { productName = it }, label = { Text("ชื่อสินค้า") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = productPrice, onValueChange = { productPrice = it }, label = { Text("ราคา (บาท)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                Spacer(modifier = Modifier.height(16.dp))
                                if (editingProduct == null) { Button(onClick = { val priceD = productPrice.toDoubleOrNull(); if (productName.isBlank() || priceD == null || priceD <= 0) return@Button; coroutineScope.launch { db.productDao().insert(Product(name = productName.trim(), price = priceD)); clearProductForm() } }, modifier = Modifier.fillMaxWidth()) { Text("เพิ่มสินค้า") } } else { Row { Button(onClick = { val priceD = productPrice.toDoubleOrNull(); if (productName.isBlank() || priceD == null || priceD <= 0) return@Button; coroutineScope.launch { db.productDao().update(editingProduct!!.copy(name = productName.trim(), price = priceD)); clearProductForm() } }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("บันทึกราคาใหม่") }; Spacer(Modifier.width(8.dp)); Button(onClick = { coroutineScope.launch { db.productDao().delete(editingProduct!!); clearProductForm() } }, modifier = Modifier.weight(0.5f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.Delete, null) } } }
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("รายการสินค้าทั้งหมด (${allProducts.size})", fontWeight = FontWeight.Bold, color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    items(allProducts) { product -> Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { editingProduct = product; productName = product.name; productPrice = product.price.toString() }, colors = CardDefaults.cardColors(containerColor = Color.White)) { Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text(product.name, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("ราคา: ฿${product.price} / หน่วย", fontSize = 14.sp, color = Color.Gray) }; Icon(Icons.Default.Edit, "Edit", tint = Color.LightGray) } } }
                }
            } else {
                var nextBillNo by remember { mutableStateOf("") }
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val currentOffset = prefs.getLong("invoice_offset", 0L)

                var maxOrderId by remember { mutableStateOf(0L) }
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        val orders = db.orderDao().getAllOrdersSync()
                        maxOrderId = orders.maxOfOrNull { it.id } ?: 0L
                    }
                }
                val currentNextInvoice = maxOrderId + currentOffset + 1

                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    item {
                        Text("ซิงค์เลขบิล (กรณีลงแอปใหม่)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("บิลถัดไปที่จะออกคือ: $currentNextInvoice", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = nextBillNo, onValueChange = { nextBillNo = it },
                            label = { Text("ระบุเลขบิลถัดไปที่ต้องการ (ดูจากใน Sheet)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            val desiredNext = nextBillNo.toLongOrNull()
                            if (desiredNext != null && desiredNext > 0) {
                                val newOffset = desiredNext - maxOrderId - 1
                                prefs.edit().putLong("invoice_offset", newOffset).apply()
                                Toast.makeText(context, "อัปเดตเลขบิลถัดไปเป็น $desiredNext สำเร็จ!", Toast.LENGTH_LONG).show()
                                nextBillNo = ""
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("อัปเดตเลขบิล") }

                        Spacer(modifier = Modifier.height(32.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("💡 ทำไมเลขบิลถึงกลับมาเป็น 1 ?", fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("เวลาคุณกด Run โค้ดจาก Android Studio ฐานข้อมูลในมือถือจะถูกลบ ทำให้เลขบิลโดนรีเซ็ตกลับไปเริ่ม 1 ใหม่", fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("👉 วิธีแก้: ให้ดูเลขบิลล่าสุดใน Google Sheet เช่น ถ้าบิลล่าสุดคือ 3 ให้พิมพ์เลข 4 ในหน้านี้แล้วกดอัปเดต บิลต่อไปจะออกเลข 4 ทันทีครับ", fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingScreen(
    viewModel: BillingViewModel, driverId: String, driverName: String, routeId: String, userRole: String,
    onNavigateToAdmin: () -> Unit, onNavigateToHistory: () -> Unit
) {
    val context = LocalContext.current
    val products by viewModel.allProducts.collectAsState(initial = emptyList())
    val db = remember { AppDatabase.getDatabase(context) }
    val allCustomersFromDb by db.customerDao().getAllCustomers().collectAsState(initial = emptyList())
    val dynamicCustomerGroups = allCustomersFromDb.map { it.store_name }.distinct().sorted()

    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var branchInput by remember { mutableStateOf("") }
    var currentTaxId by remember { mutableStateOf("") }
    var currentAddress by remember { mutableStateOf("") }
    var printDelivery by remember { mutableStateOf(false) }
    var printTax by remember { mutableStateOf(false) }

    var poNumber by remember { mutableStateOf("") }
    var cvCode by remember { mutableStateOf("") }

    var showPreviewDialog by remember { mutableStateOf(false) }
    val quantities = remember { mutableStateMapOf<Long, Int>() }
    val totalAmountState = remember { mutableDoubleStateOf(0.0) }
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }

    var printQueue by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentPrintIndex by remember { mutableIntStateOf(0) }
    var isPrintingNow by remember { mutableStateOf(false) }
    var showPrintQueueDialog by remember { mutableStateOf(false) }

    var currentCustomerCache by remember { mutableStateOf<Customer?>(null) }
    var currentActualInvoiceNo by remember { mutableStateOf(0L) }

    val myCompanyName = "บริษัท ตั้งเจริญมีนบุรี จำกัด"
    val myCompanyAddress = "291,291/1 ถนนเจริญพัฒนา แขวงบางชัน เขตคลองสามวา กรุงเทพมหานคร 10510"
    val myCompanyTaxId = "0105546047517"

    // Auto-sync watcher: รันทุก 10 วินาที + ยิงทันทีเมื่อเน็ตกลับมา (ผ่าน NetworkCallback)
    LaunchedEffect(driverId, routeId) {
        if (driverId.isEmpty()) return@LaunchedEffect

        // 1) รอบตามเวลา — กันลืมในกรณี NetworkCallback ไม่ trigger
        val tickerJob = launch {
            while (true) {
                try { syncPendingCloudOrders(context, driverId, routeId) } catch (_: Exception) {}
                delay(10_000L)
            }
        }

        // 2) ฟังการเปลี่ยนแปลงของเครือข่าย — เน็ตกลับมาปุ๊บ sync ปั๊บ
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                Log.d("VanSync", "network onAvailable → trigger sync")
                launch {
                    try { syncPendingCloudOrders(context, driverId, routeId) } catch (_: Exception) {}
                }
            }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    launch {
                        try { syncPendingCloudOrders(context, driverId, routeId) } catch (_: Exception) {}
                    }
                }
            }
        }
        try { cm.registerNetworkCallback(request, callback) } catch (_: Exception) {}

        try { awaitCancellation() } finally {
            tickerJob.cancel()
            try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
    }

    LaunchedEffect(selectedGroup) {
        if (selectedGroup != null) {
            val groupName = selectedGroup!!
            if (groupName.contains("ซีพี ออลล์") || groupName.contains("ซีพีออล") || groupName.contains("CP ALL", ignoreCase = true)) {
                cvCode = "71755"
            } else {
                cvCode = ""
            }
            poNumber = ""
        }
    }

    LaunchedEffect(selectedGroup, branchInput) {
        if (selectedGroup != null && branchInput.isNotEmpty()) {
            val existingCust = db.customerDao().getCustomerByBranch(selectedGroup!!, branchInput)
            if (existingCust != null) {
                currentTaxId = existingCust.tax_id
                currentAddress = existingCust.address
                printDelivery = existingCust.print_delivery
                printTax = existingCust.print_tax
            }
        }
    }

    val exportExcelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val orders = db.orderDao().getAllOrdersSync()
                    val customers = db.customerDao().getAllCustomersSync()
                    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    val offset = prefs.getLong("invoice_offset", 0L)

                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write("Invoice No,Total Amount,Timestamp,Status\n".toByteArray(Charsets.UTF_8))
                        orders.forEach { order ->
                            val customer = customers.find { it.id == order.customer_id }
                            val prefix = if (customer?.print_tax == true) "V" else ""

                            val actualInvoiceNo = order.id + offset
                            val formattedInvoice = String.format("%s%s%07d", prefix, routeId, actualInvoiceNo)

                            val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(order.timestamp))
                            val status = if (order.is_synced) "Synced" else "Pending"
                            os.write("$formattedInvoice,${order.total_amount},$dateStr,$status\n".toByteArray(Charsets.UTF_8))
                        }
                    }
                    withContext(Dispatchers.Main) { Toast.makeText(context, "โหลดสรุปยอดสำเร็จ", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "เกิดข้อผิดพลาด: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    LaunchedEffect(quantities.toMap()) {
        totalAmountState.doubleValue = quantities.mapNotNull { (id, qty) -> products.find { it.id == id }?.price?.times(qty) }.sum()
    }

    if (showPrintQueueDialog && currentPrintIndex < printQueue.size) {
        val currentJobTitle = printQueue[currentPrintIndex]
        AlertDialog(
            onDismissRequest = { },
            title = { Text("กำลังพิมพ์เอกสาร ${currentPrintIndex + 1} / ${printQueue.size}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
            text = { Text("เตรียมพิมพ์:\n$currentJobTitle\n\n📌 กรุณาฉีกกระดาษใบเดิมออกก่อน\nแล้วกดปุ่มด้านล่างเพื่อพิมพ์ใบนี้", fontSize = 18.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        isPrintingNow = true
                        scope.launch {
                            val success = DBluetoothPrinter.printSingleReceipt(
                                context = context,
                                customer = currentCustomerCache!!,
                                quantities = quantities.toMap(),
                                products = products,
                                driverId = driverId,
                                docTitle = currentJobTitle,
                                orderId = currentActualInvoiceNo,
                                routeId = routeId,
                                poNumber = poNumber,
                                cvCode = cvCode
                            )
                            isPrintingNow = false
                            if (success) {
                                currentPrintIndex++
                                if (currentPrintIndex >= printQueue.size) {
                                    showPrintQueueDialog = false
                                    quantities.clear(); branchInput = ""; poNumber = ""
                                    Toast.makeText(context, "เปิดบิลสำเร็จ", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(55.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isPrintingNow) Color.Gray else MaterialTheme.colorScheme.primary)
                ) {
                    if (isPrintingNow) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                    else Text("พิมพ์ใบนี้เลย", fontSize = 18.sp)
                }
            }
        )
    }

    if (showPreviewDialog && !showPrintQueueDialog) {
        // สร้างลิสต์เอกสารที่จะพิมพ์ เพื่อ preview ให้ตรงใบจริงทุกใบ
        val previewDocs = remember(printDelivery, printTax) {
            buildList {
                if (printDelivery) { add("ใบส่งของ\nDELIVERY NOTE"); add("ใบส่งของ\nDELIVERY NOTE (สำเนาร้านค้า)") }
                if (printTax) { add("ใบกำกับภาษี/ใบเสร็จรับเงิน\nTAX INVOICE/RECEIPT"); add("ใบกำกับภาษี/ใบเสร็จรับเงิน\nTAX INVOICE/RECEIPT (สำเนาร้านค้า)") }
            }
        }
        var previewIndex by remember(previewDocs) { mutableIntStateOf(0) }
        val safeIndex = previewIndex.coerceIn(0, (previewDocs.size - 1).coerceAtLeast(0))
        val currentDocTitle = previewDocs.getOrNull(safeIndex) ?: "ใบส่งของ\nDELIVERY NOTE"
        val isTaxInvoice = currentDocTitle.contains("ใบกำกับภาษี")

        // เลขบิลที่จะโผล่บน preview = maxOrderId + offset + 1
        var projectedInvoice by remember { mutableStateOf("......................") }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val offset = prefs.getLong("invoice_offset", 0L)
                val maxId = db.orderDao().getAllOrdersSync().maxOfOrNull { it.id } ?: 0L
                val nextId = maxId + offset + 1
                projectedInvoice = String.format("%s%07d", routeId, nextId)
            }
        }
        val displayInvoiceNo = if (isTaxInvoice) "V$projectedInvoice" else projectedInvoice
        val currentDateStr = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("th", "TH")).format(Date()) }

        AlertDialog(
            onDismissRequest = { showPreviewDialog = false },
            title = {
                Column {
                    Text("พรีวิวใบเสร็จ (ใบที่ ${safeIndex + 1}/${previewDocs.size})", fontWeight = FontWeight.Bold)
                    if (previewDocs.size > 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row {
                            OutlinedButton(
                                onClick = { if (safeIndex > 0) previewIndex = safeIndex - 1 },
                                enabled = safeIndex > 0,
                                modifier = Modifier.weight(1f)
                            ) { Text("◀ ใบก่อนหน้า", fontSize = 12.sp) }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { if (safeIndex < previewDocs.size - 1) previewIndex = safeIndex + 1 },
                                enabled = safeIndex < previewDocs.size - 1,
                                modifier = Modifier.weight(1f)
                            ) { Text("ใบถัดไป ▶", fontSize = 12.sp) }
                        }
                    }
                }
            },
            text = {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .border(1.dp, Color.LightGray)
                        .padding(horizontal = 12.dp, vertical = 16.dp)
                        .verticalScroll(scrollState)
                ) {
                    // หัวเรื่องเอกสาร (ตรงกับบรรทัดแรกของใบจริง)
                    currentDocTitle.split("\n").forEach { line ->
                        Text(line, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    // หัวบริษัท
                    Text(myCompanyName, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(myCompanyAddress, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 11.sp)
                    Text("เลขประจำตัวผู้เสียภาษี $myCompanyTaxId (สำนักงานใหญ่)", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 11.sp)

                    Spacer(modifier = Modifier.height(6.dp))
                    DashedDivider()
                    Spacer(modifier = Modifier.height(6.dp))

                    // เลขที่บิล + วันที่
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("เลขที่ $displayInvoiceNo", modifier = Modifier.weight(1f), fontSize = 12.sp)
                        Text("วันที่ $currentDateStr", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    DashedDivider()
                    Spacer(modifier = Modifier.height(6.dp))

                    // ข้อมูลลูกค้า
                    Text("ลูกค้า: ${selectedGroup ?: "-"}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (currentAddress.isNotBlank()) {
                        Text("ที่อยู่: ${currentAddress.replace("\n", " ")}", fontSize = 12.sp)
                    }
                    if (currentTaxId.isNotBlank()) {
                        Text("เลขผู้เสียภาษี: $currentTaxId", fontSize = 12.sp)
                    }
                    Text("สาขา: ${if (branchInput.isNotBlank()) branchInput else "-"}", fontSize = 12.sp)
                    if (cvCode.isNotEmpty()) Text("CV.CODE: $cvCode", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (poNumber.isNotEmpty()) Text("เลขที่ PO: $poNumber", fontSize = 12.sp, fontWeight = FontWeight.Bold)

                    Spacer(modifier = Modifier.height(6.dp))
                    DashedDivider()
                    Spacer(modifier = Modifier.height(6.dp))

                    // หัวตาราง
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("รายการ", modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("จำนวน", modifier = Modifier.width(50.dp), textAlign = TextAlign.End, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("จำนวนเงิน", modifier = Modifier.width(80.dp), textAlign = TextAlign.End, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    DashedDivider()
                    Spacer(modifier = Modifier.height(4.dp))

                    // รายการสินค้า
                    quantities.filter { it.value > 0 }.forEach { (pId, qty) ->
                        val product = products.find { it.id == pId }
                        if (product != null) {
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(product.name, modifier = Modifier.weight(1f), fontSize = 12.sp)
                                Text("$qty", modifier = Modifier.width(50.dp), textAlign = TextAlign.End, fontSize = 12.sp)
                                Text(String.format("%,.2f", product.price * qty), modifier = Modifier.width(80.dp), textAlign = TextAlign.End, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    DashedDivider()
                    Spacer(modifier = Modifier.height(6.dp))

                    // สรุปยอด — โชว์ VAT เฉพาะใบกำกับภาษี (ตรงกับตัวปริ้น)
                    val vat = totalAmountState.doubleValue * 7 / 107
                    val beforeVat = totalAmountState.doubleValue - vat

                    if (isTaxInvoice) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("รวมเงิน", modifier = Modifier.weight(1f), fontSize = 12.sp)
                            Text(String.format("%,.2f", beforeVat), textAlign = TextAlign.End, fontSize = 12.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("ภาษีมูลค่าเพิ่ม", modifier = Modifier.weight(1f), fontSize = 12.sp)
                            Text(String.format("%,.2f", vat), textAlign = TextAlign.End, fontSize = 12.sp)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("ยอดเงินสุทธิ", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(String.format("%,.2f", totalAmountState.doubleValue), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    DashedDivider()

                    // พื้นที่ตราประทับ (ใบจริงเว้น ~250px)
                    Spacer(modifier = Modifier.height(80.dp))

                    // ผู้รับของ ชิดซ้าย
                    Text("ผู้รับของ ....................", fontSize = 12.sp, modifier = Modifier.align(Alignment.Start))
                    Spacer(modifier = Modifier.height(48.dp))
                    // ผู้ส่งของ ชิดขวา
                    Text("ผู้ส่งของ ....................", fontSize = 12.sp, modifier = Modifier.align(Alignment.End))

                    Spacer(modifier = Modifier.height(12.dp))
                    val docs = mutableListOf<String>()
                    if (printDelivery) docs.add("ใบส่งของ (2 ใบ)")
                    if (printTax) docs.add("ใบกำกับภาษี (2 ใบ)")
                    Text("💡 เตรียมพิมพ์: ${docs.joinToString(" และ ")}", fontSize = 12.sp, color = Color(0xFFE53935), fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showPreviewDialog = false
                    scope.launch {
                        try {
                            val custId = db.customerDao().insert(Customer(store_name = selectedGroup!!, branch_code = branchInput, tax_id = currentTaxId, address = currentAddress, print_delivery = printDelivery, print_tax = printTax))
                            val orderId = db.orderDao().insert(
                                Order(
                                    customer_id = custId,
                                    total_amount = totalAmountState.doubleValue,
                                    timestamp = System.currentTimeMillis(),
                                    cv_code = cvCode,
                                    po_number = poNumber
                                )
                            )
                            val items = quantities.filter { it.value > 0 }.map { (pId, qty) -> OrderItem(order_id = orderId, product_id = pId, quantity = qty, subtotal = (products.find { it.id == pId }?.price ?: 0.0) * qty) }
                            db.orderItemDao().insertAll(items)

                            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            val offset = prefs.getLong("invoice_offset", 0L)
                            currentActualInvoiceNo = orderId + offset

                            currentCustomerCache = Customer(store_name = selectedGroup!!, branch_code = branchInput, tax_id = currentTaxId, address = currentAddress)

                            val q = mutableListOf<String>()
                            if (printDelivery) { q.add("ใบส่งของ\nDELIVERY ORDER"); q.add("ใบส่งของ\nDELIVERY ORDER") }
                            if (printTax) { q.add("ใบกำกับภาษี/ใบเสร็จรับเงิน\nTAX INVOICE/RECEIPT"); q.add("ใบกำกับภาษี/ใบเสร็จรับเงิน\nTAX INVOICE/RECEIPT") }

                            printQueue = q
                            currentPrintIndex = 0
                            showPrintQueueDialog = true

                            // เรียกใช้ให้มันซิงค์หลังจากกดปุ่มยืนยันด้วย
                            syncPendingCloudOrders(context, driverId, routeId)

                        } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("ยืนยันการพิมพ์ (${if(printDelivery && printTax) 4 else 2} ใบ)") }
            },
            dismissButton = { OutlinedButton(onClick = { showPreviewDialog = false }, modifier = Modifier.fillMaxWidth()) { Text("แก้ไขบิล") } }
        )
    }

    val routeName = remember(routeId) { lookupRouteName(context, routeId) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Text("เปิดบิล", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    if (routeName.isNotBlank()) "สาย $routeId — $routeName" else "สาย $routeId",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (driverName.isNotBlank()) {
                            Text("คนขับ: $driverName", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                },
                actions = {
                    SyncStatusIcon()
                    Box {
                        IconButton(onClick = { showMenu = !showMenu }) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            val isAdmin = userRole == "ADMIN" || driverId == "9999"

                            // เมนูสำหรับคนขับทุกคน
                            DropdownMenuItem(text = { Text("📜 ประวัติบิล / ปริ้นย้อนหลัง") }, onClick = { showMenu = false; onNavigateToHistory() })
                            DropdownMenuItem(text = { Text("☁️ อัปโหลดบิลตกค้างขึ้น Cloud") }, onClick = { showMenu = false; scope.launch { Toast.makeText(context, "กำลังตรวจสอบบิลตกค้าง...", Toast.LENGTH_SHORT).show(); syncPendingCloudOrders(context, driverId, routeId) } })

                            // เมนูเฉพาะ Admin (ล็อกอินรหัส 9999)
                            if (isAdmin) {
                                Divider()
                                DropdownMenuItem(text = { Text("1. โหลดสรุปยอด (Excel)") }, onClick = { showMenu = false; exportExcelLauncher.launch("Summary_Report.csv") })
                                DropdownMenuItem(
                                    text = { Text("2. บันทึกไฟล์ CD Organizer ลงเครื่อง") },
                                    onClick = {
                                        showMenu = false
                                        scope.launch {
                                            Toast.makeText(context, "กำลังสร้างไฟล์...", Toast.LENGTH_SHORT).show()
                                            val (isSuccess, message) = exportCDOrganizerToPhone(context, db, products, driverId, routeId)
                                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                )
                                DropdownMenuItem(text = { Text("3. 🔄 โหลดข้อมูลอัปเดตจาก Cloud") }, onClick = {
                                    showMenu = false
                                    scope.launch {
                                        Toast.makeText(context, "กำลังโหลดข้อมูล...", Toast.LENGTH_SHORT).show()
                                        downloadCustomersFromCloud(context)
                                        downloadEmployeesFromCloud(context)
                                        downloadRoutesFromCloud(context)
                                        Toast.makeText(context, "โหลดข้อมูลสำเร็จ", Toast.LENGTH_SHORT).show()
                                    }
                                })
                                DropdownMenuItem(text = { Text("⚙️ ตั้งค่าระบบหลังบ้าน") }, onClick = { showMenu = false; onNavigateToAdmin() })
                                DropdownMenuItem(text = { Text("🔓 ปลดล็อก Kiosk (ออกจากแอป)") }, onClick = {
                                    showMenu = false
                                    try {
                                        (context as? android.app.Activity)?.stopLockTask()
                                        Toast.makeText(context, "ปลดล็อก Kiosk แล้ว", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "ปลดล็อกไม่สำเร็จ: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                })
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(modifier = Modifier.height(110.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("ยอดรวมทั้งสิ้น"); Text("฿ %.2f".format(totalAmountState.doubleValue), fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                    Button(onClick = {
                        if (selectedGroup.isNullOrBlank() || branchInput.isBlank() || totalAmountState.doubleValue <= 0) Toast.makeText(context, "กรุณากรอกให้ครบ", Toast.LENGTH_SHORT).show()
                        else showPreviewDialog = true
                    }, modifier = Modifier.height(60.dp)) { Icon(Icons.Default.Print, null); Spacer(Modifier.width(8.dp)); Text("สั่งพิมพ์") }
                }
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            item {
                Text("1. เลือกร้านค้า", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                SimpleDropdown("กลุ่มลูกค้า", dynamicCustomerGroups, selectedGroup) { selectedGroup = it; branchInput = "" }
                Spacer(Modifier.height(8.dp))

                if (selectedGroup != null) {
                    if (cvCode.isNotEmpty()) {
                        OutlinedTextField(
                            value = cvCode, onValueChange = {},
                            label = { Text("CV.CODE (ล็อคอัตโนมัติ)") },
                            readOnly = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (selectedGroup!!.contains("เอ็กซ์ตร้า") || selectedGroup!!.contains("แอ็กซ์ตร้า")) {
                        OutlinedTextField(
                            value = poNumber, onValueChange = { poNumber = it },
                            label = { Text("ระบุเลขที่ PO") },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    OutlinedTextField(value = branchInput, onValueChange = { if (it.all { c -> c.isDigit() }) branchInput = it }, label = { Text("ระบุเลขสาขา") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }

                Spacer(Modifier.height(16.dp))
                Text("2. เลือกสินค้า", fontWeight = FontWeight.Bold)
            }
            items(products) { product -> ProductStepperItem(product, quantities.getOrDefault(product.id, 0)) { quantities[product.id] = it } }
        }
    }
}

@Composable
fun ProductStepperItem(product: Product, quantity: Int, onQuantityChange: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(product.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("฿${product.price} / หน่วย", color = Color.Gray)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (quantity > 0) onQuantityChange(quantity - 1) }) { Icon(Icons.Rounded.Remove, null, tint = MaterialTheme.colorScheme.primary) }

                var textValue by remember(quantity) { mutableStateOf(quantity.toString()) }
                BasicTextField(
                    value = if (textValue == "0") "" else textValue,
                    onValueChange = { newValue ->
                        val digitsOnly = newValue.filter { it.isDigit() }
                        textValue = digitsOnly
                        onQuantityChange(digitsOnly.toIntOrNull() ?: 0)
                    },
                    modifier = Modifier.width(70.dp).background(Color(0xFF8FFFF3), RoundedCornerShape(8.dp)).padding(horizontal = 4.dp, vertical = 8.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, color = Color.Black),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                IconButton(onClick = { onQuantityChange(quantity + 1) }) { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleDropdown(label: String, options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    var exp by remember { mutableStateOf(false) }

    val getFriendlyName: (String) -> String = { name ->
        when {
            name.contains("ซีพี ออลล์") || name.contains("ซีพีออล") || name.contains("CP ALL", ignoreCase = true) -> "7-Eleven (ซีพี ออลล์)"
            name.contains("แอ็กซ์ตร้า") || name.contains("เอ็กซ์ตร้า") -> "Lotus's (ซีพี แอ็กซ์ตร้า)"

            name.contains("ซี.เจ") || name.contains("ซีเจ") || name.contains("CJ", ignoreCase = true) -> {
                when {
                    name.contains("มอร์") || name.contains("MORE", ignoreCase = true) -> "CJ MORE (ซีเจ มอร์)"
                    name.contains("ซูเปอร์มาร์เก็ต") || name.contains("Supermarket", ignoreCase = true) -> "CJ Supermarket"
                    name.contains("เอ็กซ์") || name.contains("CJX", ignoreCase = true) -> "CJX (ซีเจ เอ็กซ์)"
                    else -> "CJ Express"
                }
            }

            else -> name
        }
    }

    ExposedDropdownMenuBox(expanded = exp, onExpandedChange = { exp = !exp }) {
        OutlinedTextField(
            value = if (selected != null) getFriendlyName(selected) else "เลือกกลุ่ม...",
            onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = exp) }, modifier = Modifier.fillMaxWidth().menuAnchor(), shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(expanded = exp, onDismissRequest = { exp = false }) {
            options.forEach { realName ->
                DropdownMenuItem(
                    text = { Text(getFriendlyName(realName)) },
                    onClick = { onSelect(realName); exp = false }
                )
            }
        }
    }
}