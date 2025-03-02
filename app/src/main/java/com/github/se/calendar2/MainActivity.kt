package com.github.se.calendar2

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.rememberAsyncImagePainter
import com.github.se.calendar2.ui.theme.Calendar2Theme
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.*

import java.time.DayOfWeek

val Context.dataStore by preferencesDataStore("day_colors")
val colors = listOf(
    Color(0xFFD32F2F) to "Horaire 1: 7h00-11h15 12h00-16h09", // Darker red
    Color(0xFF388E3C) to "Horaire 2: 7h00-11h09 16h00-20h15", // Darker green
    Color(0xFF1976D2) to "Horaire 3: 7h00-12h00 16h00-19h24", // Darker blue
    Color(0xFFFBC02D) to "Horaire 4: 9h06-11h15 12h00-18h15", // Bright yellow
    Color(0xFF8E24AA) to "Horaire 7: 7h45-12h15 17h21-21h15", // Deep magenta
    Color(0xFF757575) to "Horaire 8: 7h30-11h00 17h00-20h00", // Darker gray
    Color(0xFF7986CB) to "Horaire 9: 8h00-11h15 12h00-15h15", // Light gray
    Color(0xFF009688) to "Horaire de nuit \uD83C\uDF19", // Light gray
    Color(0xFF00BCD4) to "Jour férié 🎉" // **Bright cyan for holidays**
)

data class DayStyle(
    val color: Int? = null,
    val imageUri: String? = null
)


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Calendar2Theme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "calendar/{yearMonth}") {
                    composable("calendar/{yearMonth}") { backStackEntry ->
                        val yearMonth = backStackEntry.arguments?.getString("yearMonth")
                            ?.let { YearMonth.parse(it) } ?: YearMonth.now()
                        CalendarScreen(navController, yearMonth, this@MainActivity)
                    }
                }
            }
        }
    }

    fun getDayStyleFlow(month: String, day: Int): Flow<DayStyle> {
        val key = stringPreferencesKey("$month-$day")
        return dataStore.data.map { preferences ->
            val json = preferences[key]
            json?.let { deserializeDayStyle(it) } ?: DayStyle()
        }
    }

    fun setDayStyle(month: String, day: Int, dayStyle: DayStyle) {
        val key = stringPreferencesKey("$month-$day")
        lifecycleScope.launch {
            dataStore.edit { preferences ->
                preferences[key] = serializeDayStyle(dayStyle)
            }
        }
    }

}

fun serializeDayStyle(dayStyle: DayStyle): String {
    return Gson().toJson(dayStyle)
}

fun deserializeDayStyle(json: String): DayStyle {
    return Gson().fromJson(json, DayStyle::class.java)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(navController: NavController, currentMonth: YearMonth, activity: MainActivity) {
    val currentMonthKey = remember { "${currentMonth.year}-${currentMonth.monthValue}" }
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val calendarRef = remember { mutableStateOf<android.view.View?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${currentMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${currentMonth.year}") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigate("calendar/${currentMonth.minusMonths(1)}") }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Previous Month")
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("calendar/${currentMonth.plusMonths(1)}") }) {
                        Icon(imageVector = Icons.Default.ArrowForward, contentDescription = "Next Month")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Capture calendar using AndroidView
            AndroidView(
                factory = { ctx ->
                    android.widget.FrameLayout(ctx).apply {
                        setBackgroundColor(android.graphics.Color.WHITE)
                        val composeView = androidx.compose.ui.platform.ComposeView(ctx).apply {
                            setContent {
                                Column {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(7),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        items(DayOfWeek.values()) { dayOfWeek ->
                                            Text(
                                                text = dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2),
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier
                                                    .background(Color.LightGray)
                                                    .fillMaxWidth()
                                                    .padding(4.dp)
                                            )
                                        }
                                        items(currentMonth.atDay(1).dayOfWeek.value - 1) { Spacer(modifier = Modifier.size(40.dp)) }
                                        items(currentMonth.lengthOfMonth()) { day ->
                                            val dayStyleFlow = activity.getDayStyleFlow(currentMonthKey, day + 1)
                                            val dayStyle by dayStyleFlow.collectAsState(initial = DayStyle())

                                            DayBox(day = day + 1, dayStyle = dayStyle, onClick = {
                                                selectedDay = day + 1
                                                showDialog = true
                                            })
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Legend()
                                }
                            }
                        }
                        addView(composeView)
                        calendarRef.value = composeView
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = {
                calendarRef.value?.let { view ->
                    val bitmap = captureViewAsBitmap(view)
                    exportCalendar(context, bitmap)
                }
            }) {
                Text("Export Calendar as Image")
            }
        }

        if (showDialog && selectedDay != null) {
            StylePickerDialog(
                onDismissRequest = { showDialog = false },
                onStyleSelected = { style ->
                    activity.setDayStyle(currentMonthKey, selectedDay!!, style)
                    showDialog = false
                }
            )
        }
    }
}
fun captureViewAsBitmap(view: android.view.View): Bitmap {
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    canvas.drawColor(android.graphics.Color.WHITE)

    view.draw(canvas)

    return bitmap
}


fun exportCalendar(context: Context, bitmap: Bitmap?) {
    if (bitmap == null) {
        Toast.makeText(context, "Failed to capture calendar!", Toast.LENGTH_SHORT).show()
        return
    }

    val filename = "calendar_${System.currentTimeMillis()}.png"
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CalendarExports")
    }

    val contentResolver = context.contentResolver
    val imageUri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    imageUri?.let {
        contentResolver.openOutputStream(it)?.use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            Toast.makeText(context, "Calendar exported to Pictures/CalendarExports", Toast.LENGTH_SHORT).show()
        }
    }
}



@Composable
fun DayBox(day: Int, dayStyle: DayStyle, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .padding(4.dp)
            .clickable { onClick() }
            .background(dayStyle.color?.let { Color(it) } ?: Color.LightGray)
    ) {
        if (dayStyle.imageUri != null) {
            val painter = rememberAsyncImagePainter(dayStyle.imageUri)
            Image(
                painter = painter,
                contentDescription = "Day Image",
                modifier = Modifier.matchParentSize()
            )
        }

        Text(
            text = day.toString(),
            color = if (dayStyle.color != null) Color.White else Color.Black,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun Legend() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Text("Légende:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

        colors.forEach { (color, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(color)
                        .border(1.dp, Color.Black)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun StylePickerDialog(
    onDismissRequest: () -> Unit,
    onStyleSelected: (DayStyle) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Choose a style") },
        text = {
            Column {
                Text("Select a Color:")
                LazyColumn {
                    items(colors) { (color, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(4.dp).clickable {
                                onStyleSelected(DayStyle(color = color.toArgb()))
                            }
                        ) {
                            Box(modifier = Modifier.size(40.dp).background(color))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text("Cancel") }
        }
    )
}