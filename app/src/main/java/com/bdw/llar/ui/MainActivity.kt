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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.bdw.llar.core.LlarForegroundService
import com.bdw.llar.core.GestorModulos
import com.bdw.llar.modelo.Evento
import android.util.Log
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private var gestor: GestorModulos? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val esenciales = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.CAMERA
        ).all { permissions[it] == true }

        if (esenciales) {
            iniciarServicio()
            gestor?.iniciarTodo(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gestor = GestorModulos(this)

        setContent {
            LlarTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
                    LlarScreen(
                        onAvatarContainerReady = { container -> gestor?.iniciarAvatar(container) },
                        onMicClick = {
                            BusEventos.publicar(Evento("oido.activar_modo_escucha", "ui", mapOf("duracion" to 7)))
                        },
                        onUserChange = { nuevoUsuario ->
                            BusEventos.publicar(Evento("usuario.cambiado", "ui", mapOf("nuevo_usuario" to nuevoUsuario)))
                        },
                        onCompactClick = {
                            BusEventos.publicar(Evento("memoria.compactar", "ui"))
                        },
                        onCameraClick = {
                            BusEventos.publicar(Evento("vision.capturar_foto", "ui"))
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
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun iniciarServicio() {
        val intent = Intent(this, LlarForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    override fun onDestroy() {
        gestor?.detenerTodo()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlarScreen(
    onAvatarContainerReady: (FrameLayout) -> Unit,
    onMicClick: () -> Unit,
    onUserChange: (String) -> Unit,
    onCompactClick: () -> Unit,
    onCameraClick: () -> Unit
) {
    var ultimoEvento by remember { mutableStateOf("LISTO") }
    var itemsLista by remember { mutableStateOf<List<String>>(emptyList()) }
    var expandedUserMenu by remember { mutableStateOf(false) }
    var amplitudVoz by remember { mutableFloatStateOf(0f) }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val usuarios = listOf("Pedro", "Rebeca", "Sergio")
    var usuarioSeleccionado by remember { mutableStateOf(usuarios[0]) }

    val fireAlpha by rememberInfiniteTransition().animateFloat(
        initialValue = 0.3f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(2500, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )

    val fireGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFFFF4500).copy(alpha = fireAlpha), Color(0xFFFF8C00).copy(alpha = 0.2f), Color.Transparent),
        startY = 0f
    )

    DisposableEffect(Unit) {
        val suscriptor = object : BusEventos.Suscriptor {
            override fun alRecibirEvento(evento: Evento) {
                when (evento.tipo) {
                    "lista.compra_actualizada" -> itemsLista = (evento.datos["items"] as? List<*>)?.map { it.toString() } ?: emptyList()
                    "oido.amplitud" -> amplitudVoz = (evento.datos["valor"] as? Float) ?: 0f
                    else -> ultimoEvento = evento.tipo.substringAfterLast(".").uppercase()
                }
            }
        }
        BusEventos.suscribirTodo(suscriptor)
        BusEventos.publicar(Evento("lista.consultar", "ui"))
        onDispose { BusEventos.desuscribirTodo(suscriptor) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF0F0F0F), drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("HERRAMIENTAS", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Color(0xFFFF4500))
                    IconButton(onClick = { scope.launch { drawerState.close() } }) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar menú", tint = Color(0xFFFF8C00))
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.1f))
                NavigationDrawerItem(
                    label = { Text("Analizar Entorno (Cámara)") }, selected = false,
                    onClick = { 
                        scope.launch { drawerState.close() }
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onCameraClick() 
                    },
                    icon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                    colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent, unselectedTextColor = Color.White, unselectedIconColor = Color(0xFFFF8C00))
                )
                NavigationDrawerItem(
                    label = { Text("Optimizar Memoria") }, selected = false,
                    onClick = { 
                        scope.launch { drawerState.close() }
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onCompactClick() 
                    },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                    colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent, unselectedTextColor = Color.White, unselectedIconColor = Color(0xFFFF8C00))
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menú", tint = Color(0xFFFF8C00))
                        }
                    },
                    title = {
                        Text("LLAR", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 8.sp), color = Color(0xFFFF4500))
                    },
                    actions = {
                        IconButton(onClick = { 
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            BusEventos.publicar(Evento("lista.consultar", "ui"))
                            scope.launch { drawerState.open() } // Abrir panel lateral para ver lista
                        }) {
                            BadgedBox(badge = { if(itemsLista.isNotEmpty()) Badge { Text(itemsLista.size.toString()) } }) {
                                Icon(Icons.Default.ShoppingCart, contentDescription = "Lista", tint = Color(0xFFFF8C00))
                            }
                        }
                        Box {
                            IconButton(onClick = { expandedUserMenu = true }) { Icon(Icons.Default.Person, contentDescription = "Usuario", tint = Color(0xFFFF8C00)) }
                            DropdownMenu(expanded = expandedUserMenu, onDismissRequest = { expandedUserMenu = false }) {
                                usuarios.forEach { user ->
                                    DropdownMenuItem(text = { Text(user) }, onClick = { usuarioSeleccionado = user; onUserChange(user); expandedUserMenu = false })
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color(0xFF050505))
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onMicClick() },
                    containerColor = Color(0xFFFF4500), contentColor = Color.White, shape = CircleShape
                ) { Icon(Icons.Filled.Mic, contentDescription = "Hablar", modifier = Modifier.size(32.dp)) }
            },
            floatingActionButtonPosition = FabPosition.Center
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFF0A0A0A))) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp).background(fireGradient))
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("CONEXIÓN: $usuarioSeleccionado", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFF8C00).copy(alpha = 0.6f))
                        Spacer(modifier = Modifier.height(16.dp))
                        VisualizadorEspectro(amplitud = amplitudVoz)
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(color = Color(0xFFFF4500).copy(alpha = 0.05f), shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF4500).copy(alpha = 0.15f))) {
                            Text(ultimoEvento, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Color(0xFFFF4500), letterSpacing = 2.sp)
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f).clip(RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp)).background(Color.Black.copy(alpha = 0.25f))) {
                        AndroidView(factory = { ctx -> FrameLayout(ctx).apply { post { onAvatarContainerReady(this) } } }, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(modifier = Modifier.height(72.dp))
                }
            }
        }
    }
}

@Composable
fun VisualizadorEspectro(amplitud: Float) {
    val barCount = 12
    val animAmplitud by animateFloatAsState(targetValue = amplitud, label = "anim_amplitud")
    Row(modifier = Modifier.height(40.dp).fillMaxWidth(0.6f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        repeat(barCount) {
            val heightFactor = (animAmplitud * (0.6f + Random.nextFloat() * 0.4f)).coerceIn(0.1f, 1f)
            Box(modifier = Modifier.width(4.dp).fillMaxHeight(heightFactor).clip(RoundedCornerShape(2.dp)).background(Brush.verticalGradient(listOf(Color(0xFFFF4500), Color(0xFFFF8C00)))))
        }
    }
}

@Composable
fun LlarTheme(content: @Composable () -> Unit) { MaterialTheme(typography = Typography(), content = content) }
