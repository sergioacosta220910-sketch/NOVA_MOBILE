package com.nova.assistant

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val Bg = Color(0xFF010409)
private val Panel = Color(0xFF07111A)
private val Gold = Color(0xFFFFB700)
private val GoldLight = Color(0xFFFFD15C)
private val TextMain = Color(0xFFD3CBB8)
private val TextMuted = Color(0xFF948A71)
private val Red = Color(0xFFB94B55)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NovaApp(this) }
    }
}

data class ChatLine(val role: String, val text: String)
data class Attachment(val path: String, val name: String, val size: Long)
data class NovaFile(val name: String, val path: String, val isDir: Boolean, val size: Long)
data class NovaEvent(val id: Int, val title: String, val date: String, val time: String, val description: String, val type: String)

class NovaApi(private val context: Context) {
    private val prefs = context.getSharedPreferences("nova_config", Context.MODE_PRIVATE)
    var baseUrl: String
        get() = prefs.getString("server_url", "http://192.168.1.100:8000") ?: "http://192.168.1.100:8000"
        set(value) { prefs.edit().putString("server_url", value.trim().trimEnd('/')).apply() }

    private var cookie: String?
        get() = prefs.getString("session_cookie", null)
        set(value) { prefs.edit().putString("session_cookie", value).apply() }

    private fun request(path: String, method: String = "GET", body: ByteArray? = null, contentType: String? = null): Pair<Int, ByteArray> {
        val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
        conn.requestMethod = method
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.setRequestProperty("Accept", "application/json")
        cookie?.let { conn.setRequestProperty("Cookie", it) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType ?: "application/json")
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        if (path == "/api/login" && code in 200..299) {
            conn.getHeaderField("Set-Cookie")?.substringBefore(";")?.let { cookie = it }
        }
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        conn.disconnect()
        return code to bytes
    }

    private fun json(path: String, method: String = "GET", data: JSONObject? = null): JSONObject {
        val (code, bytes) = request(path, method, data?.toString()?.toByteArray(Charsets.UTF_8))
        val result = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrElse { JSONObject() }
        if (code !in 200..299) throw Exception(result.optString("detail", "Error HTTP $code"))
        return result
    }

    suspend fun login(url: String, password: String) {
        baseUrl = url
        val result = json("/api/login", "POST", JSONObject().put("password", password))
        if (!result.optBoolean("success", true)) throw Exception("No se pudo iniciar sesión")
    }
    suspend fun auth(): Boolean = try { json("/api/auth").optBoolean("authenticated") } catch (_: Exception) { false }
    suspend fun logout() { runCatching { json("/api/logout", "POST", JSONObject()) }; cookie = null }
    suspend fun newSession(): Int = json("/api/session", "POST", JSONObject()).optInt("conversation_id")
    suspend fun history(id: Int): List<ChatLine> {
        val a = json("/api/session/$id/messages").optJSONArray("messages") ?: JSONArray()
        return (0 until a.length()).map { val o = a.getJSONObject(it); ChatLine(o.optString("role"), o.optString("content")) }
    }
    suspend fun sendChat(id: Int, message: String, attachments: List<Attachment>): String {
        val body = JSONObject().put("message", message).put("conversation_id", id)
            .put("attachments", JSONArray(attachments.map { it.path }))
        return json("/api/chat", "POST", body).optString("response", "No recibí respuesta.")
    }
    suspend fun upload(uri: Uri, displayName: String, temp: Boolean): Attachment {
        val boundary = "NovaBoundary${System.currentTimeMillis()}"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("No se pudo leer el archivo")
        val safeName = displayName.replace("\"", "_")
        val prefix = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"files\"; filename=\"$safeName\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val (code, response) = request(if (temp) "/api/files/temp/upload" else "/api/files/upload", "POST",
            prefix + bytes + suffix, "multipart/form-data; boundary=$boundary")
        val obj = runCatching { JSONObject(response.toString(Charsets.UTF_8)) }.getOrElse { JSONObject() }
        if (code !in 200..299) throw Exception(obj.optString("detail", "Error al subir archivo"))
        val arr = obj.optJSONArray("files") ?: JSONArray()
        if (arr.length() == 0) throw Exception("El servidor no devolvió el archivo")
        val item = arr.getJSONObject(0)
        return Attachment(item.optString("path"), item.optString("name", displayName), item.optLong("size", bytes.size.toLong()))
    }
    suspend fun events(date: String, upcoming: Boolean = false): List<NovaEvent> {
        val path = if (upcoming) "/api/events/upcoming" else "/api/events?date=${enc(date)}"
        val a = json(path).optJSONArray("events") ?: JSONArray()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            NovaEvent(o.optInt("id"), o.optString("title"), o.optString("date"), o.optString("time"),
                o.optString("description"), o.optString("type", "evento"))
        }
    }
    suspend fun createEvent(title: String, date: String, time: String, description: String, type: String) {
        json("/api/events", "POST", JSONObject().put("title", title).put("date", date)
            .put("time", time.ifBlank { JSONObject.NULL }).put("description", description)
            .put("type", type).put("reminder", true))
    }
    suspend fun deleteEvent(id: Int) { json("/api/events/$id", "DELETE") }
    suspend fun listFiles(path: String): List<NovaFile> {
        val q = "/api/files?area=storage&path=${enc(path)}&sort=name&order=asc"
        val a = json(q).optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            NovaFile(o.optString("name"), o.optString("path"), o.optBoolean("is_dir", o.optBoolean("is_directory")),
                o.optLong("size"))
        }
    }
    suspend fun createFolder(path: String) {
        json("/api/files/folder", "POST", JSONObject().put("path", path).put("area", "storage"))
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

@Composable
fun NovaApp(context: Context) {
    val api = remember { NovaApi(context) }
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(api.baseUrl) }
    var password by remember { mutableStateOf("") }
    var logged by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("chat") }
    var conversation by remember { mutableIntStateOf(0) }
    var chat by remember { mutableStateOf(listOf<ChatLine>()) }
    var message by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf(listOf<Attachment>()) }
    var events by remember { mutableStateOf(listOf<NovaEvent>()) }
    var eventDate by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    var upcoming by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(listOf<NovaFile>()) }
    var filePath by remember { mutableStateOf("") }
    var showEventDialog by remember { mutableStateOf(false) }
    var eventTitle by remember { mutableStateOf("") }
    var eventTime by remember { mutableStateOf("") }
    var eventDescription by remember { mutableStateOf("") }
    var eventType by remember { mutableStateOf("evento") }

    fun refreshChat() {
        scope.launch { runCatching { chat = api.history(conversation) }.onFailure { error = it.message ?: "Error" } }
    }
    fun refreshEvents() {
        scope.launch { runCatching { events = api.events(eventDate, upcoming) }.onFailure { error = it.message ?: "Error" } }
    }
    fun refreshFiles() {
        scope.launch { runCatching { files = api.listFiles(filePath) }.onFailure { error = it.message ?: "Error" } }
    }

    LaunchedEffect(Unit) {
        if (api.auth()) {
            logged = true
            runCatching {
                conversation = api.newSession()
                chat = api.history(conversation)
                events = api.events(eventDate)
                files = api.listFiles("")
            }.onFailure { error = it.message ?: "Error" }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            loading = true
            try {
                val uploaded = uris.map { uri ->
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val ix = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && ix >= 0) c.getString(ix) else "archivo"
                    } ?: "archivo"
                    api.upload(uri, name, true)
                }
                attachments = attachments + uploaded
            } catch (e: Exception) { error = e.message ?: "No se pudo adjuntar" }
            loading = false
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Gold, onPrimary = Bg, background = Bg, surface = Panel,
        onBackground = TextMain, onSurface = TextMain, error = Red
    )) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF06101A), Bg)))) {
            if (!logged) {
                LoginScreen(server, { server = it }, password, { password = it }, loading, error) {
                    scope.launch {
                        loading = true; error = ""
                        try {
                            api.login(server, password)
                            conversation = api.newSession()
                            chat = api.history(conversation)
                            events = api.events(eventDate)
                            files = api.listFiles("")
                            logged = true; password = ""
                        } catch (e: Exception) { error = e.message ?: "No se pudo conectar" }
                        loading = false
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Header(onLogout = {
                        scope.launch { api.logout(); logged = false; chat = emptyList(); conversation = 0 }
                    })
                    Box(Modifier.weight(1f)) {
                        when (page) {
                            "chat" -> ChatScreen(chat, message, { message = it }, attachments,
                                { picker.launch(arrayOf("*/*")) }, { index -> attachments = attachments.filterIndexed { i, _ -> i != index } },
                                loading, {
                                    val msg = message.trim().ifBlank { if (attachments.isNotEmpty()) "Analiza los archivos que te adjunté." else "" }
                                    if (msg.isNotEmpty() && !loading) scope.launch {
                                        loading = true
                                        chat = chat + ChatLine("user", msg)
                                        message = ""
                                        val sent = attachments
                                        attachments = emptyList()
                                        try {
                                            val answer = api.sendChat(conversation, msg, sent)
                                            chat = chat + ChatLine("assistant", answer)
                                        } catch (e: Exception) {
                                            chat = chat + ChatLine("assistant", "No pude conectar con N.O.V.A.: ${e.message}")
                                        }
                                        loading = false
                                    }
                                }, { scope.launch {
                                    try { conversation = api.newSession(); chat = listOf(ChatLine("assistant", "Hola, ¿en qué puedo ayudarte?")) }
                                    catch (e: Exception) { error = e.message ?: "Error" }
                                }})
                            }
                            "events" -> EventsScreen(events, eventDate, upcoming, { eventDate = it; upcoming = false; refreshEvents() },
                                { upcoming = !upcoming; refreshEvents() }, { showEventDialog = true }, { id ->
                                    scope.launch { runCatching { api.deleteEvent(id); refreshEvents() }.onFailure { error = it.message ?: "Error" } }
                                }, { refreshEvents() })
                            else -> FilesScreen(files, filePath, { filePath = it; refreshFiles() }, {
                                val parent = filePath.substringBeforeLast("/", "")
                                filePath = parent; refreshFiles()
                            }, {
                                val name = "Nueva carpeta"
                                scope.launch { runCatching {
                                    api.createFolder(if (filePath.isBlank()) name else "$filePath/$name")
                                    refreshFiles()
                                }.onFailure { error = it.message ?: "Error" } }
                            }, { refreshFiles() })
                        }
                    }
                    NavigationBar(containerColor = Color(0xFF030A10), contentColor = Gold) {
                        NavigationBarItem(selected = page == "chat", onClick = { page = "chat" }, icon = { Icon(Icons.Default.Chat, null) }, label = { Text("Chat") })
                        NavigationBarItem(selected = page == "events", onClick = { page = "events"; refreshEvents() }, icon = { Icon(Icons.Default.Event, null) }, label = { Text("Eventos") })
                        NavigationBarItem(selected = page == "files", onClick = { page = "files"; refreshFiles() }, icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Archivos") })
                    }
                }
                if (showEventDialog) {
                    AlertDialog(onDismissRequest = { showEventDialog = false },
                        title = { Text("Nuevo evento", color = Gold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                NovaField(eventTitle, { eventTitle = it }, "Título")
                                NovaField(eventDate, { eventDate = it }, "Fecha (AAAA-MM-DD)")
                                NovaField(eventTime, { eventTime = it }, "Hora (HH:MM)")
                                NovaField(eventType, { eventType = it }, "Tipo")
                                NovaField(eventDescription, { eventDescription = it }, "Descripción")
                            }
                        },
                        confirmButton = { TextButton(onClick = {
                            scope.launch {
                                runCatching { api.createEvent(eventTitle, eventDate, eventTime, eventDescription, eventType); showEventDialog = false; eventTitle = ""; refreshEvents() }
                                    .onFailure { error = it.message ?: "Error" }
                            }
                        }) { Text("GUARDAR", color = Gold) } },
                        dismissButton = { TextButton(onClick = { showEventDialog = false }) { Text("CANCELAR") } },
                        containerColor = Panel
                    )
                }
                if (error.isNotBlank()) {
                    AlertDialog(onDismissRequest = { error = "" }, confirmButton = { TextButton(onClick = { error = "" }) { Text("OK", color = Gold) } },
                        title = { Text("Aviso", color = Gold) }, text = { Text(error) }, containerColor = Panel)
                }
            }
        }
    }
}

@Composable
fun LoginScreen(server: String, onServer: (String) -> Unit, password: String, onPassword: (String) -> Unit,
                loading: Boolean, error: String, onLogin: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        NovaOrb(190)
        Spacer(Modifier.height(10.dp))
        Text("N.O.V.A.", color = TextMain, fontSize = 28.sp, letterSpacing = 7.sp, fontWeight = FontWeight.Light)
        Text("ASISTENTE PERSONAL", color = TextMuted, fontSize = 10.sp, letterSpacing = 3.sp)
        Spacer(Modifier.height(32.dp))
        NovaField(server, onServer, "URL del servidor (ej. http://192.168.1.10:8000)")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = password, onValueChange = onPassword, modifier = Modifier.fillMaxWidth(),
            label = { Text("Contraseña del servidor") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = TextMuted, focusedLabelColor = Gold))
        if (error.isNotBlank()) Text(error, color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(18.dp))
        Button(onClick = onLogin, enabled = !loading, modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg), shape = RoundedCornerShape(12.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Bg, strokeWidth = 2.dp) else Text("CONECTAR", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text("La app se conecta al servidor existente. No duplica la IA ni la memoria.", color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
fun Header(onLogout: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xDD030A10)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NovaOrb(38)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("N.O.V.A.", color = GoldLight, fontSize = 17.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
            Text("ASISTENTE", color = TextMuted, fontSize = 9.sp, letterSpacing = 2.sp)
        }
        IconButton(onClick = onLogout) { Icon(Icons.Default.Logout, "Cerrar sesión", tint = TextMuted) }
    }
}

@Composable
fun NovaOrb(size: Int) {
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().border(1.dp, Gold.copy(alpha = .35f), CircleShape))
        Box(Modifier.fillMaxSize(.82f).border(1.dp, Gold.copy(alpha = .7f), CircleShape))
        Box(Modifier.fillMaxSize(.62f).border(1.dp, GoldLight.copy(alpha = .8f), CircleShape))
        Box(Modifier.fillMaxSize(.38f).border(1.dp, Gold.copy(alpha = .55f), CircleShape))
        Box(Modifier.size((size * .07f).dp).background(Color(0xFFFFF6DF), CircleShape))
    }
}

@Composable
fun ChatScreen(lines: List<ChatLine>, message: String, onMessage: (String) -> Unit, attachments: List<Attachment>,
               onAttach: () -> Unit, onRemove: (Int) -> Unit, loading: Boolean, onSend: () -> Unit, onNew: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CONVERSACIÓN", color = TextMuted, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onNew) { Text("NUEVO CHAT", color = Gold, fontSize = 10.sp) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = false, contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(lines) { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.role == "user") Arrangement.End else Arrangement.Start) {
                    Surface(color = if (line.role == "user") Color(0xFF30250C) else Panel,
                        shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth(.88f)) {
                        Text(line.text, Modifier.padding(14.dp), color = TextMain, fontSize = 14.sp, lineHeight = 21.sp)
                    }
                }
            }
            if (loading) item { Text("N.O.V.A. está pensando…", color = Gold, fontSize = 12.sp) }
        }
        if (attachments.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                attachments.forEachIndexed { i, a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Adjunto: ${a.name}", color = TextMuted, modifier = Modifier.weight(1f), fontSize = 11.sp)
                        TextButton(onClick = { onRemove(i) }) { Text("Quitar", color = Red) }
                    }
                }
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAttach) { Icon(Icons.Default.AttachFile, "Adjuntar", tint = Gold) }
                BasicTextField(value = message, onValueChange = onMessage, modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = TextMain, fontSize = 14.sp),
                    decorationBox = { inner -> Box { if (message.isEmpty()) Text("Escribe un mensaje…", color = TextMuted, fontSize = 14.sp); inner() } })
                IconButton(onClick = onSend, enabled = !loading) { Icon(Icons.Default.Send, "Enviar", tint = Gold) }
            }
        }
    }
}

@Composable
fun EventsScreen(events: List<NovaEvent>, date: String, upcoming: Boolean, onDate: (String) -> Unit, onUpcoming: () -> Unit,
                 onAdd: () -> Unit, onDelete: (Int) -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CALENDARIO", color = Gold, fontSize = 18.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null, tint = Gold) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = date, onValueChange = onDate, modifier = Modifier.weight(1f), label = { Text("Fecha AAAA-MM-DD") },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = TextMuted))
            TextButton(onClick = onUpcoming) { Text(if (upcoming) "Por fecha" else "Próximos", color = Gold) }
        }
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("NUEVO EVENTO")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(events) { e ->
                Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title, color = TextMain, fontWeight = FontWeight.SemiBold)
                            Text("${e.date}  ${e.time}", color = Gold, fontSize = 11.sp)
                            if (e.description.isNotBlank()) Text(e.description, color = TextMuted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { onDelete(e.id) }) { Icon(Icons.Default.Delete, null, tint = Red) }
                    }
                }
            }
            if (events.isEmpty()) item { Text("No hay eventos para mostrar.", color = TextMuted, modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Composable
fun FilesScreen(files: List<NovaFile>, path: String, onOpen: (String) -> Unit, onBack: () -> Unit,
                onNewFolder: () -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ARCHIVOS", color = Gold, fontSize = 18.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null, tint = Gold) }
            IconButton(onClick = onBack, enabled = path.isNotBlank()) { Icon(Icons.Default.ArrowBack, null, tint = Gold) }
            IconButton(onClick = onNewFolder) { Icon(Icons.Default.CreateNewFolder, null, tint = Gold) }
        }
        Text("Storage / $path", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(files) { f ->
                Surface(color = Panel, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable {
                    if (f.isDir) onOpen(f.path)
                }) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (f.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = Gold)
                        Spacer(Modifier.width(12.dp))
                        Text(f.name, color = TextMain, modifier = Modifier.weight(1f), fontSize = 13.sp)
                        if (f.isDir) Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
                    }
                }
            }
            if (files.isEmpty()) item { Text("Esta carpeta está vacía.", color = TextMuted, modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Composable
fun NovaField(value: String, onValue: (String) -> Unit, label: String) {
    OutlinedTextField(value = value, onValueChange = onValue, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = TextMuted, focusedLabelColor = Gold))
}
