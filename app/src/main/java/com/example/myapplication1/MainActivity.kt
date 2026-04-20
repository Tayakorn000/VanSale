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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.style.TextOverflow
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

// SCRIPT_URL is defined via BuildConfig from local.properties (not uploaded to git)
val SCRIPT_URL: String = "https://script.google.com/macros/s/AKfycbxrvCVB1XHUhYnE-A6rGkW_5Qy4mxfBif4esWtYJnZJAh9A3l-pzpCjaEXPrsb6-TKN5Q/exec"

enum class AppScreen { BILLING, ADMIN, HISTORY }

data class PrintData(val customer: Customer, val quantities: Map<Long, Int>, val orderId: Long, val poNumber: String, val cvCode: String)

data class RouteInfo(val id: String, val name: String) {
    val display: String get() = if (name.isNotBlank() && name != id) "$id — $name" else id
}

/**
 * Parses route information from a JSON string.
 */
fun parseRoutes(json: String?): List<RouteInfo> {
    return try {
        val arr = JSONArray(json ?: "[]")
        List(arr.length()) { i ->
            when (val v = arr.get(i)) {
                is JSONObject -> {
                    // Extract values based on Thai keys sent from Apps Script or original keys
                    val id = v.optString("สาย", v.optString("id", ""))
                    val name = v.optString("ชื่อสาย", v.optString("name", ""))
                    RouteInfo(id, name)
                }
                else -> RouteInfo(v.toString(), "")
            }
        }.filter { it.id.isNotBlank() }
    } catch (e: Exception) { emptyList() }
}

/**
 * Looks up a route name by its ID from shared preferences.
 */
fun lookupRouteName(context: Context, routeId: String): String {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    return parseRoutes(prefs.getString("routes_list", "[]")).find { it.id == routeId }?.name ?: ""
}

// Mutex to prevent concurrent uploads between auto-sync and print button
val syncMutex = Mutex()

/**
 * Checks if the device has a network connection with internet capability.
 * This is a fast check used for auto-sync.
 */
fun hasNetwork(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) { false }
}

/**
 * Performs a deep check for actual internet connectivity.
 * 1) Trust VALIDATED capability if available (fast, no probe).
 * 2) Otherwise, probe google.com/generate_204.
 * 3) Fallback to basic internet capability check if probe fails.
 */
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
            // Probe failed (carrier block / firewall) → fallback to capability flag
            Log.d("VanSync", "probe failed: ${e.message} — fallback to capability check")
            hasNetwork(context)
        }
    }
}

/**
 * Downloads employee data from the cloud and updates the local database.
 */
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

/**
 * Downloads route information from the cloud and saves it to shared preferences.
 */
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

/**
 * Downloads customer data from the cloud and updates/inserts into the local database.
 */
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
                    val district = obj.optString("district", "")
                    val province = obj.optString("province", "")
                    val zipCode = obj.optString("zip_code", "")
                    val printDelivery = obj.optBoolean("print_delivery", true)
                    val printTax = obj.optBoolean("print_tax", false)

                    // Standardize sub-district and district naming conventions
                    val cleanSubDist = if (subDistrict.isNotEmpty() && !subDistrict.contains("แขวง") && !subDistrict.contains("ตำบล")) "แขวง$subDistrict" else subDistrict
                    val cleanDist = if (district.isNotEmpty() && !district.contains("เขต") && !district.contains("อำเภอ")) "เขต$district" else district
                    
                    // Construct final address with 3-space separation as requested
                    val finalAddress = listOf(addressNo, building, road, cleanSubDist, cleanDist, province, zipCode)
                        .filter { it.isNotBlank() }
                        .joinToString("   ")

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

/**
 * Uploads order details to Google Sheet.
 */
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
        
        // Data format corresponding to sheet columns (A to K)
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
            put("items", itemsJson)                // backup array
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

/**
 * Synchronizes pending orders to the cloud.
 */
suspend fun syncPendingCloudOrders(context: Context, driverId: String, routeId: String) {
    // Skip if no network connectivity
    if (!hasNetwork(context)) { Log.d("VanSync", "skip: no network"); return }

    syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val unsyncedOrders = db.orderDao().getUnsyncedOrders()
            Log.d("VanSync", "unsynced orders: ${unsyncedOrders.size}")
            if (unsyncedOrders.isEmpty()) return@withContext

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
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

                // Extract items for this order and format into JSON array and string
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

/**
 * Exports today's sales data to a CSV file in the phone's storage.
 */
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
                    val custName = customer?.store_name ?: "ทั่วไป"
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
                Pair(true, "Successfully created $fileName")
            } else {
                Pair(false, "Failed to create file in system")
            }
        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }
}

/**
 * Status icon indicating network connectivity.
 * Green for active internet, Gray for offline or captive portal.
 */
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
        contentDescription = if (online) "Online" else "Offline",
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

        // Kiosk configuration: immersive fullscreen, prevent screen timeout, hide system bars
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        try {
            startLockTask()
        } catch (e: Exception) {
            Log.e("KioskMode", "Device does not support Lock Task", e)
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

                    // Kiosk: Prevent exiting app via back button on main screen
                    BackHandler(enabled = currentScreen == AppScreen.BILLING) { /* Consumption */ }

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
                            AppScreen.ADMIN -> AdminScreen(driverId = driverId, routeId = routeId, userRole = userRole, onBack = { currentScreen = AppScreen.BILLING })
                            AppScreen.HISTORY -> HistoryScreen(driverId = driverId, driverName = driverName, routeId = routeId, userRole = userRole, onBack = { currentScreen = AppScreen.BILLING })
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
                db.productDao().insert(Product(name = "Ice Pack", price = 10.0))
                db.productDao().insert(Product(name = "Ice Square", price = 18.0))
                db.productDao().insert(Product(name = "Small Tube", price = 60.0))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoginSuccess: (String, String, String, String) -> Unit) {
    var empId by remember { mutableStateOf("") }
    var routeId by remember { mutableStateOf("") }
    var empName by remember { mutableStateOf("") }

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

    LaunchedEffect(empId) {
        if (empId.isNotEmpty()) {
            val emp = withContext(Dispatchers.IO) { db.employeeDao().getEmployeeById(empId) }
            empName = emp?.name ?: ""
        } else {
            empName = ""
        }
    }

    if (showSyncError) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("⚠️ Connection Failed!", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 24.sp) },
            text = { Text("Unable to sync data with server. Please check your internet connection and restart the app.", fontSize = 18.sp) },
            confirmButton = {
                Button(onClick = { showSyncError = false }) { Text("Dismiss (Work Offline)") }
            }
        )
    }

    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val savedRoutesStr = prefs.getString("routes_list", "[]")
    val routeOptions = remember(savedRoutesStr) { parseRoutes(savedRoutesStr) }
    val selectedRoute = remember(routeId, routeOptions) {
        routeOptions.find { it.id == routeId }
    }
    val selectedRouteName = selectedRoute?.name ?: ""

    fun performLogin() {
        if (routeId.isBlank()) { Toast.makeText(context, "Please specify route", Toast.LENGTH_SHORT).show(); return }
        if (empId == "9999") { onLoginSuccess("9999", "Administrator", "ADMIN", routeId); return }
        if (empId.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val emp = db.employeeDao().getEmployeeById(empId)
                withContext(Dispatchers.Main) {
                    if (emp != null) onLoginSuccess(emp.emp_id, emp.name, emp.role, routeId)
                    else Toast.makeText(context, "Invalid employee ID", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
        if (isCheckingData) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(0.98f),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.LocalShipping, 
                        null, 
                        modifier = Modifier.size(64.dp), 
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Van Sale", 
                        style = MaterialTheme.typography.headlineMedium, 
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    // Employee Row (Label | Input | Name)
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Employee ID", 
                            modifier = Modifier.weight(1f),
                            fontSize = 17.sp, 
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        OutlinedTextField(
                            value = empId,
                            onValueChange = { empId = it },
                            modifier = Modifier.width(100.dp),
                            textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        )
                        
                        // Employee name display
                        Box(modifier = Modifier.weight(1.2f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                            if (empName.isNotBlank()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFFF2F2F2),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = empName,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Route Row (Label | Input | Route Name)
                    var expanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Route", 
                            modifier = Modifier.weight(1f),
                            fontSize = 17.sp, 
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        
                        ExposedDropdownMenuBox(
                            expanded = expanded, 
                            onExpandedChange = { expanded = !expanded },
                            modifier = Modifier.width(100.dp)
                        ) {
                            OutlinedTextField(
                                value = routeId,
                                onValueChange = { },
                                readOnly = true,
                                modifier = Modifier.menuAnchor(),
                                textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                                shape = RoundedCornerShape(12.dp),
                                colors = TextFieldDefaults.outlinedTextFieldColors(
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                ),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                            )
                            
                            if (routeOptions.isNotEmpty()) {
                                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    routeOptions.forEach { opt ->
                                        DropdownMenuItem(
                                            text = { Text("Route ${opt.display}", fontSize = 14.sp) },
                                            onClick = { routeId = opt.id; expanded = false }
                                        )
                                    }
                                }
                            }
                        }

                        // Route name display
                        Box(modifier = Modifier.weight(1.2f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                            if (routeId.isNotBlank() && selectedRouteName.isNotBlank()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFFF2F2F2),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = selectedRouteName,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = { performLogin() },
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        shape = RoundedCornerShape(16.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                    ) { 
                        Text("Login", fontSize = 20.sp, fontWeight = FontWeight.Bold) 
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(driverId: String, driverName: String, routeId: String, userRole: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val isAdmin = userRole == "ADMIN" || driverId == "9999"

    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var customers by remember { mutableStateOf<List<Customer>>(emptyList()) }
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    var allItems by remember { mutableStateOf<List<OrderItem>>(emptyList()) }

    // Admin edit state
    var editingOrder by remember { mutableStateOf<Order?>(null) }
    var editQty by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    // Reprint state
    var reprintOrder by remember { mutableStateOf<Order?>(null) }
    var reprintIndex by remember { mutableIntStateOf(0) }
    var reprintQueue by remember { mutableStateOf<List<String>>(emptyList()) }
    var isPrinting by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            orders = db.orderDao().getAllOrdersSync().sortedByDescending { it.timestamp }
            customers = db.customerDao().getAllCustomersSync()
            products = db.productDao().getAllProducts().first()
            allItems = db.orderItemDao().getAllOrderItemsSync()
        }
    }
    LaunchedEffect(Unit) { reload() }

    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val offset = prefs.getLong("invoice_offset", 0L)

    // --- Admin Edit Dialog ---
    editingOrder?.let { order ->
        val customer = customers.find { it.id == order.customer_id }
        val orderItems = allItems.filter { it.order_id == order.id }
        AlertDialog(
            onDismissRequest = { editingOrder = null },
            title = { Text("Edit Bill #${order.id + offset}", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Store: ${customer?.store_name ?: "-"}", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    orderItems.forEach { item ->
                        val product = products.find { it.id == item.product_id }
                        val key = item.product_id
                        val currentQty = editQty[key] ?: item.quantity.toString()
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(product?.name ?: "Product#${item.product_id}", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("฿${product?.price ?: 0.0}/unit", fontSize = 12.sp, color = Color.Gray)
                            }
                            OutlinedTextField(
                                value = currentQty,
                                onValueChange = { v -> editQty = editQty + (key to v.filter { c -> c.isDigit() }) },
                                label = { Text("Qty") },
                                singleLine = true,
                                modifier = Modifier.width(90.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val updatedItems = orderItems.map { item ->
                            val newQty = editQty[item.product_id]?.toIntOrNull() ?: item.quantity
                            val price = products.find { it.id == item.product_id }?.price ?: 0.0
                            item.copy(quantity = newQty, subtotal = price * newQty)
                        }.filter { it.quantity > 0 }
                        db.orderItemDao().deleteByOrderId(order.id)
                        if (updatedItems.isNotEmpty()) db.orderItemDao().insertAll(updatedItems)
                        val newTotal = updatedItems.sumOf { it.subtotal }
                        db.orderDao().updateTotalAndUnSync(order.id, newTotal)
                        withContext(Dispatchers.Main) {
                            editingOrder = null
                            editQty = emptyMap()
                            Toast.makeText(context, "Saved — awaiting sync", Toast.LENGTH_SHORT).show()
                            reload()
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { OutlinedButton(onClick = { editingOrder = null; editQty = emptyMap() }) { Text("Cancel") } }
        )
    }

    // --- Reprint Queue Dialog ---
    reprintOrder?.let { order ->
        val customer = customers.find { it.id == order.customer_id }
        if (reprintQueue.isNotEmpty() && reprintIndex < reprintQueue.size && customer != null) {
            val docTitle = reprintQueue[reprintIndex]
            val orderItems = allItems.filter { it.order_id == order.id }
            val qtyMap = orderItems.associate { it.product_id to it.quantity }
            AlertDialog(
                onDismissRequest = { reprintOrder = null; reprintIndex = 0; reprintQueue = emptyList() },
                title = { Text("Reprint ${reprintIndex + 1}/${reprintQueue.size}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                text = { Text("Prepare printing:\n$docTitle\n\n📌 Remove old receipt before printing", fontSize = 16.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            isPrinting = true
                            scope.launch {
                                val ok = DBluetoothPrinter.printSingleReceipt(
                                    context = context,
                                    customer = customer,
                                    quantities = qtyMap,
                                    products = products,
                                    driverId = driverId,
                                    docTitle = docTitle,
                                    orderId = order.id + offset,
                                    routeId = routeId,
                                    poNumber = order.po_number,
                                    cvCode = order.cv_code
                                )
                                isPrinting = false
                                if (ok) {
                                    reprintIndex++
                                    if (reprintIndex >= reprintQueue.size) {
                                        reprintOrder = null; reprintIndex = 0; reprintQueue = emptyList()
                                        Toast.makeText(context, "Reprint successful", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (isPrinting) Color.Gray else MaterialTheme.colorScheme.primary)
                    ) {
                        if (isPrinting) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                        else Text("Print Now", fontSize = 16.sp)
                    }
                }
            )
        }
    }

    Scaffold(topBar = {
        CenterAlignedTopAppBar(
            title = { Text("Billing History", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
        )
    }) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            items(orders) { order ->
                val customer = customers.find { it.id == order.customer_id }
                val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(order.timestamp))
                val prefix = if (customer?.print_tax == true) "V" else ""
                val actualInvoiceNo = order.id + offset
                val invoiceNo = String.format("%s%s%07d", prefix, routeId, actualInvoiceNo)
                val orderItems = allItems.filter { it.order_id == order.id }

                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(invoiceNo, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(customer?.store_name ?: "-", fontWeight = FontWeight.Medium)
                                Text(dateStr, fontSize = 12.sp, color = Color.Gray)
                                Text("฿${"%.2f".format(order.total_amount)}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                if (!order.is_synced) Text("⏳ Awaiting sync", fontSize = 11.sp, color = Color(0xFFE65100))
                            }
                        }
                        if (orderItems.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            orderItems.forEach { item ->
                                val pName = products.find { it.id == item.product_id }?.name ?: "Product#${item.product_id}"
                                Text("• $pName × ${item.quantity} = ฿${"%.0f".format(item.subtotal)}", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Reprint button — accessible to all
                            OutlinedButton(
                                onClick = {
                                    val q = mutableListOf<String>()
                                    if (customer?.print_delivery == true) { q.add("Delivery Note\nDELIVERY NOTE"); q.add("Delivery Note\nDELIVERY NOTE (Merchant Copy)") }
                                    if (customer?.print_tax == true) { q.add("Tax Invoice/Receipt\nTAX INVOICE/RECEIPT"); q.add("Tax Invoice/Receipt\nTAX INVOICE/RECEIPT (Merchant Copy)") }
                                    if (q.isEmpty()) q.add("Delivery Note\nDELIVERY NOTE")
                                    reprintOrder = order; reprintQueue = q; reprintIndex = 0
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Print, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Reprint", fontSize = 13.sp)
                            }
                            // Edit button — Admin only
                            if (isAdmin) {
                                Button(
                                    onClick = {
                                        editQty = allItems.filter { it.order_id == order.id }.associate { it.product_id to it.quantity.toString() }
                                        editingOrder = order
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                                ) {
                                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Edit", fontSize = 13.sp)
                                }
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
fun AdminScreen(driverId: String, routeId: String, userRole: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val coroutineScope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Customers", "Products", "Invoice Settings")

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
                CenterAlignedTopAppBar(title = { Text("Admin Settings", fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } })
                TabRow(selectedTabIndex = selectedTabIndex) { tabs.forEachIndexed { index, title -> Tab(selected = selectedTabIndex == index, onClick = { selectedTabIndex = index }, text = { Text(title, fontWeight = FontWeight.Bold) }) } }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (selectedTabIndex == 0) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(if (editingCustomer == null) "Add New Customer" else "Edit Customer Info", fontWeight = FontWeight.Bold, fontSize = 18.sp); if (editingCustomer != null) { TextButton(onClick = { clearCustomerForm() }) { Text("Cancel") } } }
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = if (editingCustomer == null) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(16.dp)) {
                                OutlinedTextField(value = storeName, onValueChange = { storeName = it }, label = { Text("Store Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row { OutlinedTextField(value = branchCode, onValueChange = { branchCode = it }, label = { Text("Branch Code") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)); Spacer(modifier = Modifier.width(8.dp)); OutlinedTextField(value = taxId, onValueChange = { taxId = it }, label = { Text("Tax ID") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = printDelivery, onCheckedChange = { printDelivery = it }); Text("Delivery Note"); Spacer(modifier = Modifier.width(16.dp)); Checkbox(checked = printTax, onCheckedChange = { printTax = it }); Text("Tax Invoice") }
                                Spacer(modifier = Modifier.height(16.dp))
                                if (editingCustomer == null) { Button(onClick = { if (storeName.isBlank() || branchCode.isBlank()) return@Button; coroutineScope.launch { db.customerDao().insert(Customer(store_name = storeName.trim(), branch_code = branchCode.trim(), tax_id = taxId.trim(), address = address.trim(), print_delivery = printDelivery, print_tax = printTax)); Toast.makeText(context, "Added successfully!", Toast.LENGTH_SHORT).show(); clearCustomerForm() } }, modifier = Modifier.fillMaxWidth()) { Text("Save New Info") } } else { Row { Button(onClick = { coroutineScope.launch { db.customerDao().update(editingCustomer!!.copy(store_name = storeName.trim(), branch_code = branchCode.trim(), tax_id = taxId.trim(), address = address.trim(), print_delivery = printDelivery, print_tax = printTax)); Toast.makeText(context, "Updated successfully!", Toast.LENGTH_SHORT).show(); clearCustomerForm() } }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("Save Edits") }; Spacer(Modifier.width(8.dp)); Button(onClick = { coroutineScope.launch { db.customerDao().delete(editingCustomer!!); clearCustomerForm() } }, modifier = Modifier.weight(0.5f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.Delete, null) } } }
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("Tap to edit (${allCustomers.size})", fontWeight = FontWeight.Bold, color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    items(allCustomers) { customer -> Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { editingCustomer = customer; storeName = customer.store_name; branchCode = customer.branch_code; taxId = customer.tax_id; address = customer.address; printDelivery = customer.print_delivery; printTax = customer.print_tax }, colors = CardDefaults.cardColors(containerColor = Color.White)) { Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text(customer.store_name, fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("Branch: ${customer.branch_code}", fontSize = 14.sp, color = Color.Blue) }; Icon(Icons.Default.Edit, "Edit", tint = Color.LightGray) } } }
                }
            } else if (selectedTabIndex == 1) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(if (editingProduct == null) "Add New Product" else "Edit Product Price", fontWeight = FontWeight.Bold, fontSize = 18.sp); if (editingProduct != null) { TextButton(onClick = { clearProductForm() }) { Text("Cancel") } } }
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = if (editingProduct == null) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(16.dp)) {
                                OutlinedTextField(value = productName, onValueChange = { productName = it }, label = { Text("Product Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = productPrice, onValueChange = { productPrice = it }, label = { Text("Price (Baht)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                Spacer(modifier = Modifier.height(16.dp))
                                if (editingProduct == null) { Button(onClick = { val priceD = productPrice.toDoubleOrNull(); if (productName.isBlank() || priceD == null || priceD <= 0) return@Button; coroutineScope.launch { db.productDao().insert(Product(name = productName.trim(), price = priceD)); clearProductForm() } }, modifier = Modifier.fillMaxWidth()) { Text("Add Product") } } else { Row { Button(onClick = { val priceD = productPrice.toDoubleOrNull(); if (productName.isBlank() || priceD == null || priceD <= 0) return@Button; coroutineScope.launch { db.productDao().update(editingProduct!!.copy(name = productName.trim(), price = priceD)); clearProductForm() } }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("Save New Price") }; Spacer(Modifier.width(8.dp)); Button(onClick = { coroutineScope.launch { db.productDao().delete(editingProduct!!); clearProductForm() } }, modifier = Modifier.weight(0.5f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.Delete, null) } } }
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("All Products (${allProducts.size})", fontWeight = FontWeight.Bold, color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    items(allProducts) { product -> Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { editingProduct = product; productName = product.name; productPrice = product.price.toString() }, colors = CardDefaults.cardColors(containerColor = Color.White)) { Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text(product.name, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Price: ฿${product.price} / unit", fontSize = 14.sp, color = Color.Gray) }; Icon(Icons.Default.Edit, "Edit", tint = Color.LightGray) } } }
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
                        Text("Sync Invoice Number (New Installation)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Next invoice will be: $currentNextInvoice", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = nextBillNo, onValueChange = { nextBillNo = it },
                            label = { Text("Specify next invoice number (refer to Sheet)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            val desiredNext = nextBillNo.toLongOrNull()
                            if (desiredNext != null && desiredNext > 0) {
                                val newOffset = desiredNext - maxOrderId - 1
                                prefs.edit().putLong("invoice_offset", newOffset).apply()
                                Toast.makeText(context, "Updated next invoice number to $desiredNext", Toast.LENGTH_LONG).show()
                                nextBillNo = ""
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Update Invoice Number") }

                        Spacer(modifier = Modifier.height(32.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("💡 Why did the invoice number reset to 1?", fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Running code from Android Studio may delete the local database, resetting the invoice count.", fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("👉 Fix: Check the latest invoice number in Google Sheet (e.g., if it's 3, enter 4 here) and update. The next invoice will start from the specified number.", fontSize = 14.sp)
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
    val myCompanyAddress = "291,291/1 ถนนเจริญพัฒนา แขวงบางชัน เขตคลองสามวา กรุงเทพฯ 10510"
    val myCompanyTaxId = "0105546047517"

    // Auto-sync watcher: runs every 10 seconds + triggers on network recovery
    LaunchedEffect(driverId, routeId) {
        if (driverId.isEmpty()) return@LaunchedEffect

        // 1) Periodic sync task
        val tickerJob = launch {
            while (true) {
                try { syncPendingCloudOrders(context, driverId, routeId) } catch (_: Exception) {}
                delay(10_000L)
            }
        }

        // 2) Network state observer
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
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Export successful", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
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
            title = { Text("Printing document ${currentPrintIndex + 1} / ${printQueue.size}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
            text = { Text("Prepare printing:\n$currentJobTitle\n\n📌 Remove old receipt before printing", fontSize = 18.sp) },
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
                                    Toast.makeText(context, "Billing completed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(55.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isPrintingNow) Color.Gray else MaterialTheme.colorScheme.primary)
                ) {
                    if (isPrintingNow) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                    else Text("Print Now", fontSize = 18.sp)
                }
            }
        )
    }

    if (showPreviewDialog && !showPrintQueueDialog) {
        // Build document queue for preview
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

        // Projected invoice number calculation
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
                    Text("Receipt Preview (Page ${safeIndex + 1}/${previewDocs.size})", fontWeight = FontWeight.Bold)
                    if (previewDocs.size > 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row {
                            OutlinedButton(
                                onClick = { if (safeIndex > 0) previewIndex = safeIndex - 1 },
                                enabled = safeIndex > 0,
                                modifier = Modifier.weight(1f)
                            ) { Text("◀ Previous", fontSize = 12.sp) }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { if (safeIndex < previewDocs.size - 1) previewIndex = safeIndex + 1 },
                                enabled = safeIndex < previewDocs.size - 1,
                                modifier = Modifier.weight(1f)
                            ) { Text("Next ▶", fontSize = 12.sp) }
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
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                        .verticalScroll(scrollState)
                ) {
                    // Document title
                    currentDocTitle.split("\n").forEach { line ->
                        Text(line, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // Company header
                    Text(myCompanyName, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("291,291/1 ถนนเจริญพัฒนา แขวงบางชัน\nเขตคลองสามวา กรุงเทพฯ 10510", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 11.sp)
                    val hqLine = if (isTaxInvoice) "Tax ID $myCompanyTaxId (Head Office)" else "(Head Office)"
                    Text(hqLine, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 11.sp)

                    Spacer(modifier = Modifier.height(8.dp))
                    DashedDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // Invoice number and date
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("No. $displayInvoiceNo", modifier = Modifier.weight(1.1f), fontSize = 11.sp)
                        Text("Date $currentDateStr", modifier = Modifier.weight(0.9f), textAlign = TextAlign.End, fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    DashedDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // Customer details
                    Text("Customer: ${selectedGroup ?: "-"}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (currentAddress.isNotBlank()) {
                        var addr = currentAddress.replace("\n", " ").replace("(สำนักงานใหญ่)", "").trim()
                        if (selectedGroup?.contains("มหาชน") == true) addr = "$addr (Head Office)"
                        Text("Address: $addr", fontSize = 11.sp)
                    }
                    if (currentTaxId.isNotBlank() && isTaxInvoice) {
                        Text("Tax ID: $currentTaxId", fontSize = 11.sp)
                    }
                    
                    val branch = if (branchInput.isNotBlank()) branchInput else "-"
                    val rightParts = mutableListOf<String>()
                    if (cvCode.isNotEmpty()) rightParts.add("CV.CODE: $cvCode")
                    if (poNumber.isNotEmpty()) rightParts.add("P.O: $poNumber")
                    
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("Branch: $branch", modifier = Modifier.weight(1f), fontSize = 11.sp)
                        if (rightParts.isNotEmpty()) {
                            Text(rightParts.joinToString("  "), fontSize = 11.sp, textAlign = TextAlign.End)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    DashedDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // Table header
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("Description", modifier = Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Qty", modifier = Modifier.width(45.dp), textAlign = TextAlign.End, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Unit Price", modifier = Modifier.width(65.dp), textAlign = TextAlign.End, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Amount", modifier = Modifier.width(75.dp), textAlign = TextAlign.End, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    DashedDivider()
                    Spacer(modifier = Modifier.height(4.dp))

                    // Product items
                    quantities.filter { it.value > 0 }.forEach { (pId, qty) ->
                        val product = products.find { it.id == pId }
                        if (product != null) {
                            val subtotal = product.price * qty
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(product.name, modifier = Modifier.weight(1f), fontSize = 11.sp)
                                    Text(String.format("%,d", qty), modifier = Modifier.width(45.dp), textAlign = TextAlign.End, fontSize = 11.sp)
                                    Text(String.format("%,.2f", product.price), modifier = Modifier.width(65.dp), textAlign = TextAlign.End, fontSize = 11.sp)
                                    Text(String.format("%,.2f", subtotal), modifier = Modifier.width(75.dp), textAlign = TextAlign.End, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    DashedDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // Totals summary
                    val total = totalAmountState.doubleValue
                    if (isTaxInvoice) {
                        val vat = total * 7 / 107
                        val beforeVat = total - vat
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("Subtotal", modifier = Modifier.weight(1f), fontSize = 11.sp)
                            Text(String.format("%,.2f", beforeVat), textAlign = TextAlign.End, fontSize = 11.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("VAT 7%", modifier = Modifier.weight(1f), fontSize = 11.sp)
                            Text(String.format("%,.2f", vat), textAlign = TextAlign.End, fontSize = 11.sp)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("Net Total", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(String.format("%,.2f", total), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    DashedDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // Signatures
                    Text("Sender ($driverId) ....................", fontSize = 12.sp, modifier = Modifier.align(Alignment.Start))
                    Spacer(modifier = Modifier.height(100.dp))
                    Text("Receiver ....................", fontSize = 12.sp, modifier = Modifier.align(Alignment.Start))

                    Spacer(modifier = Modifier.height(24.dp))
                    val docs = mutableListOf<String>()
                    if (printDelivery) docs.add("Delivery Note (2 copies)")
                    if (printTax) docs.add("Tax Invoice (2 copies)")
                    Text("💡 Prepare printing: ${docs.joinToString(" and ")}", fontSize = 11.sp, color = Color(0xFFE53935), fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
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
                            if (printDelivery) { q.add("Delivery Note\nDELIVERY ORDER"); q.add("Delivery Note\nDELIVERY ORDER") }
                            if (printTax) { q.add("Tax Invoice/Receipt\nTAX INVOICE/RECEIPT"); q.add("Tax Invoice/Receipt\nTAX INVOICE/RECEIPT") }

                            printQueue = q
                            currentPrintIndex = 0
                            showPrintQueueDialog = true

                            // Trigger immediate sync
                            syncPendingCloudOrders(context, driverId, routeId)

                        } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Confirm Print (${if(printDelivery && printTax) 4 else 2} copies)") }
            },
            dismissButton = { OutlinedButton(onClick = { showPreviewDialog = false }, modifier = Modifier.fillMaxWidth()) { Text("Edit Bill") } }
        )
    }

    val routeName = remember(routeId) { lookupRouteName(context, routeId) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Text("New Bill", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    if (routeName.isNotBlank()) "Route $routeId — $routeName" else "Route $routeId",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (driverName.isNotBlank()) {
                            Text("Driver: $driverName", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                },
                actions = {
                    SyncStatusIcon()
                    Box {
                        IconButton(onClick = { showMenu = !showMenu }) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            val isAdmin = userRole == "ADMIN" || driverId == "9999"

                            // Menu items for all drivers
                            DropdownMenuItem(text = { Text("📜 Billing History / Reprint") }, onClick = { showMenu = false; onNavigateToHistory() })
                            DropdownMenuItem(text = { Text("☁️ Sync Pending Bills to Cloud") }, onClick = { showMenu = false; scope.launch { Toast.makeText(context, "Checking for pending bills...", Toast.LENGTH_SHORT).show(); syncPendingCloudOrders(context, driverId, routeId) } })

                            // Admin-only menu items
                            if (isAdmin) {
                                Divider()
                                DropdownMenuItem(text = { Text("1. Export Summary (Excel)") }, onClick = { showMenu = false; exportExcelLauncher.launch("Summary_Report.csv") })
                                DropdownMenuItem(
                                    text = { Text("2. Save CD Organizer file locally") },
                                    onClick = {
                                        showMenu = false
                                        scope.launch {
                                            Toast.makeText(context, "Generating file...", Toast.LENGTH_SHORT).show()
                                            val (isSuccess, message) = exportCDOrganizerToPhone(context, db, products, driverId, routeId)
                                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                )
                                DropdownMenuItem(text = { Text("3. 🔄 Fetch Updates from Cloud") }, onClick = {
                                    showMenu = false
                                    scope.launch {
                                        Toast.makeText(context, "Fetching data...", Toast.LENGTH_SHORT).show()
                                        downloadCustomersFromCloud(context)
                                        downloadEmployeesFromCloud(context)
                                        downloadRoutesFromCloud(context)
                                        Toast.makeText(context, "Data updated successfully", Toast.LENGTH_SHORT).show()
                                    }
                                })
                                DropdownMenuItem(text = { Text("⚙️ Admin Settings") }, onClick = { showMenu = false; onNavigateToAdmin() })
                                DropdownMenuItem(text = { Text("🔓 Unlock Kiosk Mode") }, onClick = {
                                    showMenu = false
                                    try {
                                        (context as? android.app.Activity)?.stopLockTask()
                                        Toast.makeText(context, "Kiosk mode unlocked", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Failed to unlock: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    Column { Text("Grand Total"); Text("฿ %.2f".format(totalAmountState.doubleValue), fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                    Button(onClick = {
                        if (selectedGroup.isNullOrBlank() || branchInput.isBlank() || totalAmountState.doubleValue <= 0) Toast.makeText(context, "Please complete all fields", Toast.LENGTH_SHORT).show()
                        else showPreviewDialog = true
                    }, modifier = Modifier.height(60.dp)) { Icon(Icons.Default.Print, null); Spacer(Modifier.width(8.dp)); Text("Print") }
                }
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            item {
                Text("1. Select Customer", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                SimpleDropdown("Customer Group", dynamicCustomerGroups, selectedGroup) { selectedGroup = it; branchInput = "" }
                Spacer(Modifier.height(8.dp))

                if (selectedGroup != null) {
                    if (cvCode.isNotEmpty()) {
                        OutlinedTextField(
                            value = cvCode, onValueChange = {},
                            label = { Text("CV.CODE (Auto)") },
                            readOnly = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (selectedGroup!!.contains("เอ็กซ์ตร้า") || selectedGroup!!.contains("แอ็กซ์ตร้า")) {
                        OutlinedTextField(
                            value = poNumber, onValueChange = { poNumber = it },
                            label = { Text("PO Number") },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    OutlinedTextField(
                        value = branchInput, 
                        onValueChange = { if (it.all { c -> c.isDigit() }) branchInput = it }, 
                        label = { Text("Branch Code") }, 
                        modifier = Modifier.fillMaxWidth(), 
                        singleLine = true, 
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("2. Select Products", fontWeight = FontWeight.Bold)
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
                Text("฿${product.price} / unit", color = Color.Gray)
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
            name == "7-11" || name.contains("CP ALL", ignoreCase = true) || name.contains("ซีพี ออลล์") -> "7-Eleven (CP ALL)"
            name == "CJ" || name.contains("ซี.เจ") || name.contains("ซีเจ") -> "CJ MORE"
            name == "Lotus" || name.contains("โลตัส") || name.contains("แอ็กซ์ตร้า") || name.contains("เอ็กซ์ตร้า") -> "Lotus's (CP Axtra)"
            else -> name
        }
    }

    ExposedDropdownMenuBox(expanded = exp, onExpandedChange = { exp = !exp }) {
        OutlinedTextField(
            value = if (selected != null) getFriendlyName(selected) else "Select group...",
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