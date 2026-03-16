package com.bdw.llar.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bdw.llar.core.BusEventos
import com.bdw.llar.core.Cerebro
import com.bdw.llar.core.LlarForegroundService
import com.bdw.llar.core.Memoria
import com.bdw.llar.core.MemoriaSemantica
import com.bdw.llar.efectores.Avatar
import com.bdw.llar.efectores.LLMRemoto
import com.bdw.llar.efectores.Voz
import com.bdw.llar.efectores.CalendarioAndroid
import com.bdw.llar.modelo.Evento
import com.bdw.llar.sentidos.Oido
import com.bdw.llar.sentidos.WakeWordDetector
import android.util.Log
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private var wakeWordDetector: WakeWordDetector? = null
    private var oido: Oido? = null
    private var cerebro: Cerebro? = null
    private var memoria: Memoria? = null
    private var memoriaSemantica: MemoriaSemantica? = null
    private var llmRemoto: LLMRemoto? = null
    private var avatar: Avatar? = null
    private var voz: Voz? = null
    private var calendarioAndroid: CalendarioAndroid? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val permisosEsenciales = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permisosEsenciales.add(Manifest.permission.BLUETOOTH_SCAN)
            permisosEsenciales.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val esencialesConcedidos = permisosEsenciales.all { permissions[it] == true }

        if (esencialesConcedidos) {
            iniciarServicio()
            iniciarModulos()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LlarTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0A0A0A)
                ) {
                    LlarScreen(
                        onAvatarContainerReady = { container ->
                            if (avatar == null) {
                                avatar = Avatar(this@MainActivity, container)
                            }
                        },
                        onMicClick = {
                            BusEventos.publicar(Evento("oido.activar_modo_escucha", "ui", mapOf("duracion" to 7)))
                        },
                        onUserChange = { nuevoUsuario ->
                            // Guardar en BD para persistencia
                            BusEventos.publicar(Evento("memoria.guardar", "ui", mapOf("clave" to "usuario_activo", "valor" to nuevoUsuario)))
                            BusEventos.publicar(Evento("memoria.guardar", "ui", mapOf("clave" to "nombre_usuario", "valor" to nuevoUsuario)))
                            
                            // Avisar proactivamente a Cerebro para cambiar el scope de sesión
                            BusEventos.publicar(Evento("usuario.cambiado", "ui", mapOf("nuevo_usuario" to nuevoUsuario)))
                            BusEventos.publicar(Evento("voz.hablar", "ui", mapOf("texto" to "Hola $nuevoUsuario.")))
                        },
                        onCompactClick = {
                            BusEventos.publicar(Evento("memoria.compactar", "ui"))
                        }
                    )
                }
            }
        }

        solicitarPermisos()
    }

    private fun solicitarPermisos() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun iniciarModulos() {
        try {
            if (memoria == null) memoria = Memoria(this)
            if (memoriaSemantica == null) memoriaSemantica = MemoriaSemantica()
            if (cerebro == null) cerebro = Cerebro()
            if (llmRemoto == null) llmRemoto = LLMRemoto()
            if (voz == null) voz = Voz(this)
            if (calendarioAndroid == null) calendarioAndroid = CalendarioAndroid(this)
            if (oido == null) oido = Oido(this)
            if (wakeWordDetector == null) {
                wakeWordDetector = WakeWordDetector(this)
                wakeWordDetector?.iniciarEscucha()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error al iniciar módulos: ${e.message}")
        }
    }

    private fun iniciarServicio() {
        try {
            val intent = Intent(this, LlarForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error al iniciar servicio: ${e.message}")
        }
    }

    override fun onDestroy() {
        wakeWordDetector?.shutdown()
        oido?.shutdown()
        avatar?.shutdown()
        voz?.shutdown()
        memoriaSemantica?.shutdown()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlarScreen(
    onAvatarContainerReady: (FrameLayout) -> Unit,
    onMicClick: () -> Unit,
    onUserChange: (String) -> Unit,
    onCompactClick: () -> Unit
) {
    var ultimoEvento by remember { mutableStateOf("LISTO") }
    var showShoppingList by remember { mutableStateOf(false) }
    var itemsLista by remember { mutableStateOf<List<String>>(emptyList()) }
    var expandedUserMenu by remember { mutableStateOf(false) }
    var amplitudVoz by remember { mutableFloatStateOf(0f) }
    
    val view = LocalView.current
    val usuarios = listOf("Pedro", "Rebeca", "Sergio")
    var usuarioSeleccionado by remember { mutableStateOf(usuarios[0]) }

    // ANIMACIÓN DE PULSACIÓN DEL FUEGO
    val infiniteTransition = rememberInfiniteTransition(label = "fuego")
    val fireAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "alpha_fuego"
    )

    val fireGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFFFF4500).copy(alpha = fireAlpha), Color(0xFFFF8C00).copy(alpha = 0.2f), Color.Transparent),
        startY = 0f
    )

    DisposableEffect(Unit) {
        val suscriptor = object : BusEventos.Suscriptor {
            override fun alRecibirEvento(evento: Evento) {
                when (evento.tipo) {
                    "lista.compra_actualizada" -> {
                        itemsLista = (evento.datos["items"] as? List<*>)?.map { it.toString() } ?: emptyList()
                    }
                    "oido.amplitud" -> {
                        amplitudVoz = (evento.datos["valor"] as? Float) ?: 0f
                    }
                    else -> {
                        ultimoEvento = evento.tipo.substringAfterLast(".").uppercase()
                    }
                }
            }
        }
        BusEventos.suscribirTodo(suscriptor)
        BusEventos.publicar(Evento("lista.consultar", "ui"))
        onDispose { BusEventos.desuscribirTodo(suscriptor) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        IconButton(onClick = { 
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onMicClick() 
                        }) {
                            Icon(Icons.Filled.Mic, contentDescription = "Hablar", tint = Color(0xFFFF8C00))
                        }
                        IconButton(onClick = { 
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            BusEventos.publicar(Evento("lista.consultar", "ui"))
                            showShoppingList = true 
                        }) {
                            Icon(Icons.Filled.ShoppingCart, contentDescription = "Lista", tint = Color(0xFFFF8C00))
                        }
                    }
                },
                title = {
                    Text(
                        "LLAR", 
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 8.sp
                        ),
                        color = Color(0xFFFF4500)
                    )
                },
                actions = {
                    Row(modifier = Modifier.padding(end = 8.dp)) {
                        IconButton(onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onCompactClick()
                        }) {
                            Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = "Compactar", tint = Color(0xFFFF8C00))
                        }
                        Box {
                            IconButton(onClick = { expandedUserMenu = true }) {
                                Icon(Icons.Default.Person, contentDescription = "Usuario", tint = Color(0xFFFF8C00))
                            }
                            DropdownMenu(expanded = expandedUserMenu, onDismissRequest = { expandedUserMenu = false }) {
                                usuarios.forEach { user ->
                                    DropdownMenuItem(
                                        text = { Text(user) },
                                        onClick = {
                                            usuarioSeleccionado = user
                                            onUserChange(user)
                                            expandedUserMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color(0xFF050505))
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFF0A0A0A))) {
            
            // AURA DE FUEGO PULSANTE
            Box(modifier = Modifier.fillMaxWidth().height(200.dp).background(fireGradient))

            Column(modifier = Modifier.fillMaxSize()) {

                // ── MITAD SUPERIOR: info + visualizador ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)  // FIX #4: mitad superior ocupa el espacio restante
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "CONEXIÓN: $usuarioSeleccionado",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFF8C00).copy(alpha = 0.6f),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    VisualizadorEspectro(amplitud = amplitudVoz)
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = Color(0xFFFF4500).copy(alpha = 0.05f),
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF4500).copy(alpha = 0.15f))
                    ) {
                        Text(
                            ultimoEvento,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            color = Color(0xFFFF4500),
                            letterSpacing = 2.sp
                        )
                    }
                }

                // ── MITAD INFERIOR: Avatar (50% fijo) ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.5f)  // FIX #4: exactamente la mitad inferior de la pantalla
                        .clip(RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp))
                        .background(Color.Black.copy(alpha = 0.25f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            val emocionesInteractivas = listOf("carino", "soplar", "alegre")
                            BusEventos.publicar(Evento("avatar.expresar", "ui", mapOf("emocion" to emocionesInteractivas.random())))
                        }
                ) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )
                                post { onAvatarContainerReady(this) }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // SIDE PANEL PARA LISTA
            AnimatedVisibility(
                visible = showShoppingList,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.92f)
            ) {
                Surface(
                    tonalElevation = 20.dp,
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF050505).copy(alpha = 0.98f),
                    shape = RoundedCornerShape(topStart = 40.dp, bottomStart = 40.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF4500).copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(32.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("HOGAR", style = MaterialTheme.typography.titleLarge, color = Color(0xFFFF4500), fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { showShoppingList = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.DarkGray)
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp), color = Color(0xFFFF4500).copy(alpha = 0.15f))
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(itemsLista) { item ->
                                ListItem(
                                    headlineContent = { Text(item, color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Medium) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    trailingContent = {
                                        IconButton(onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            BusEventos.publicar(Evento("voz.hablar", "ui", mapOf("texto" to "He quitado $item de la lista")))
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Borrar", tint = Color(0xFF911F1F))
                                        }
                                    }
                                )
                            }
                        }
                        Button(
                            onClick = { 
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                BusEventos.publicar(Evento("lista.limpiar", "ui")) 
                            },
                            modifier = Modifier.fillMaxWidth().height(68.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7A1313)),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text("VACIAR TODO", fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VisualizadorEspectro(amplitud: Float) {
    val barCount = 12
    val animAmplitud by animateFloatAsState(
        targetValue = amplitud,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "anim_amplitud"
    )

    Row(
        modifier = Modifier.height(40.dp).fillMaxWidth(0.6f),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) {
            val heightFactor = (animAmplitud * (0.6f + Random.nextFloat() * 0.4f)).coerceIn(0.1f, 1f)
            
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(heightFactor)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFFF4500), Color(0xFFFF8C00))
                        )
                    )
            )
        }
    }
}
