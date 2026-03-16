# 📄 AGENTS.md — Agentes del Sistema Llar

Describe cada módulo activo en la arquitectura de Llar. Los agentes se comunican únicamente a través del `BusEventos` mediante objetos `Evento(tipo, origen, datos)`.

> **Estado de implementación**: ✅ implementado · 🔨 parcial · 📋 planificado

---

## 🧠 Core

### Cerebro (Orquestador)
**Tipo**: Core · **Fichero**: `core/Cerebro.kt` · **Estado**: ✅

Escucha todos los eventos y orquesta las respuestas. Mantiene modo descanso, modo lista y el ID de sesión. Antes de llamar al LLM, recupera el historial de la sesión y lo pasa como lista de mensajes estructurados (`[{role, content}]`).

| Suscribe | Publica |
|---|---|
| `*` (todos) | `voz.hablar`, `avatar.expresar`, `lista.*`, `llm.solicitar`, `memoria.*` |

**Variables de estado**: `modoApuntarCompra`, `modoDescanso`, `nombreUsuario`, `usuarioActivo`, `sesionId`

---

### Memoria
**Tipo**: Core · **Fichero**: `core/Memoria.kt` · **Estado**: ✅

SQLite con 4 tablas:

| Tabla | Contenido |
|---|---|
| `hechos` | Clave-valor: `nombre_usuario`, `usuario_activo` |
| `conversaciones` | Historial de mensajes con `sesion_id`, `usuario`, `mensaje`, `metadatos` (JSON), `fecha` |
| `lista_compra` | Items con flag `comprado` |
| `recuerdos` | Texto compactado + embedding JSON + metadatos |

**BD**: versión 3. Migración incremental (no destruye datos al actualizar).

| Suscribe | Publica |
|---|---|
| `memoria.guardar`, `memoria.recuperar` | `memoria.respuesta` |
| `memoria.guardar_mensaje`, `memoria.obtener_historial` | `memoria.historial_recuperado` |
| `memoria.guardar_recuerdo_vectorial` | — |
| `lista.añadir`, `lista.consultar`, `lista.limpiar` | `lista.compra_actualizada` |

---

### MemoriaSemantica
**Tipo**: Core · **Fichero**: `core/MemoriaSemantica.kt` · **Estado**: ✅

Genera embeddings vectoriales llamando a Ollama (`all-minilm:latest`, `/api/embeddings`). El vector resultante se guarda a través de `memoria.guardar_recuerdo_vectorial`. Al recibir `memoria.buscar_contexto`, pide a Ollama el vector de la pregunta y lo pasa a Memoria mediante `memoria.buscar_recuerdos` para hacer la búsqueda matemática (Similitud Coseno).

| Suscribe | Publica |
|---|---|
| `memoria.vectorizar`, `memoria.buscar_contexto` | `memoria.guardar_recuerdo_vectorial`, `memoria.buscar_recuerdos`, `memoria.recuerdos_recuperados` |

---

### GestorModulos
**Tipo**: Core · **Fichero**: `core/GestorModulos.kt` · **Estado**: 📋

Carga y descarga dinámica de módulos. Actualmente los módulos se instancian directamente en `MainActivity`.

---

## 👂 Sentidos

### Oído (Vosk)
**Tipo**: Sentido · **Fichero**: `sentidos/Oido.kt` · **Estado**: ✅

Reconocimiento de voz offline con Vosk (`vosk-model-small-es-0.42`). Se pausa cuando Llar habla (`voz.hablar` / `voz.empezado`) y reanuda 1500ms después de `voz.finalizado` para evitar capturar el eco del TTS.

| Suscribe | Publica |
|---|---|
| `oido.activar_modo_escucha`, `oido.desactivar` | `voz.comando` |
| `voz.hablar`, `voz.empezado`, `voz.finalizado` | `oido.amplitud` |

**Config**: SAMPLE_RATE = 16000 Hz, delay reanudación = 1500ms

### SentidoBluetooth
**Tipo**: Sentido · **Fichero**: `sentidos/SentidoBluetooth.kt` · **Estado**: ✅

Detecta conexiones y desconexiones Bluetooth usando un `BroadcastReceiver`. Extrae el nombre del dispositivo para que el Cerebro actúe de forma proactiva (ej: coche, auriculares).

| Suscribe | Publica |
|---|---|
| — | `bluetooth.conectado`, `bluetooth.desconectado` |

---

### WakeWordDetector (Porcupine)
**Tipo**: Sentido · **Fichero**: `sentidos/WakeWordDetector.kt` · **Estado**: ✅

Detecta la palabra de activación con Porcupine. Actualmente usa `BuiltInKeyword.PORCUPINE`. Se pausa mientras Llar habla para evitar falsas detecciones.

| Suscribe | Publica |
|---|---|
| `voz.empezado`, `voz.finalizado` | `wakeword.detectada` |

**Config**: AccessKey en `gradle.properties` → `PICOVOICE_ACCESS_KEY`

---

## 🖐️ Efectores

### Avatar
**Tipo**: Efector · **Fichero**: `efectores/Avatar.kt` · **Estado**: ✅

Reproduce vídeos MP4 en la mitad inferior de la pantalla según el estado emocional. El vídeo siempre hace loop. Cambia al vídeo `hablar` cuando Llar está hablando y vuelve a `neutral` al terminar.

| Emoción | Vídeo |
|---|---|
| `neutral` | `llar_wait.mp4` |
| `hablar` | `llar_speak.mp4` |
| `alegre` | `llar_alegria.mp4` |
| `pensar` | `llar_think.mp4` |
| `preocupado` | `llar_think.mp4` |
| `sorpresa` | `llar_surprise.mp4` |
| `enfado` | `llar_angry.mp4` |
| `dormir` | `llar_dormir.mp4` |
| `carino` | `llar_carino.mp4` |
| `soplar` | `llar_blowing.mp4` |

| Suscribe | Publica |
|---|---|
| `avatar.expresar`, `voz.empezado`, `voz.finalizado` | — |

**Layout**: ocupa la mitad inferior de la pantalla (50% de altura), siempre vertical.

---

### LLMRemoto (Ollama)
**Tipo**: Efector · **Fichero**: `efectores/LLMRemoto.kt` · **Estado**: ✅

Cliente HTTP de Ollama usando `/api/chat` con historial estructurado de mensajes. La personalidad y el system prompt los define el **modelfile** en Ollama, no este código.

| Suscribe | Publica |
|---|---|
| `llm.solicitar` (con `messages: List<{role,content}>`) | `llm.respuesta` (con `respuesta`, `emocion`) |
| — | `memoria.vectorizar` (tras compactación) |
| — | `memoria.compactacion_finalizada` |

**Config**: `ollamaUrl = "http://192.168.1.132:11434/api/chat"`, modelo = `llar`  
**Reintentos**: 3 intentos con backoff 1s/2s/4s  
**Timeouts**: connect=20s, read=90s

---

### CalendarioAndroid
**Tipo**: Efector/Sentido · **Fichero**: `efectores/CalendarioAndroid.kt` · **Estado**: ✅

Interactúa con el `CalendarContract` de Android (requiere permisos). Puede leer los eventos de las próximas N horas/días y añadir eventos nuevos al calendario local principal del usuario.

| Suscribe | Publica |
|---|---|
| `calendario.leer_eventos`, `calendario.crear_evento` | `calendario.eventos_leidos`, `voz.hablar` |

---

### Voz (TTS)
**Tipo**: Efector · **Fichero**: `efectores/Voz.kt` · **Estado**: ✅

Convierte texto a voz usando Android TextToSpeech (español ES). Emite `voz.empezado` y `voz.finalizado` para sincronizar Avatar y Oído.

| Suscribe | Publica |
|---|---|
| `voz.hablar` | `voz.empezado`, `voz.finalizado` |

**Config**: pitch=1.05, speechRate=1.0, idioma=es_ES

---

## 📦 Eventos de referencia

| Evento | Datos | Descripción |
|---|---|---|
| `voz.comando` | `{texto: String}` | Texto reconocido por Vosk |
| `wakeword.detectada` | — | Porcupine detectó la wake word |
| `llm.solicitar` | `{messages: List, usuario: String}` | Solicitud al LLM con historial |
| `llm.respuesta` | `{respuesta: String, emocion: String}` | Respuesta del LLM |
| `avatar.expresar` | `{emocion: String}` | Cambio de estado visual |
| `voz.hablar` | `{texto: String}` | Orden de síntesis de voz |
| `voz.empezado` / `voz.finalizado` | — | Sincronización TTS |
| `memoria.guardar` | `{clave, valor}` | Guardar hecho |
| `memoria.recuperar` | `{clave}` | Recuperar hecho |
| `memoria.guardar_mensaje` | `{usuario, mensaje, sesion_id, fecha}` | Guardar turno de chat |
| `memoria.obtener_historial` | `{limite, sesion_id?}` | Pedir historial de sesión |
| `lista.añadir` | `{item: String}` | Añadir a lista de compra |
| `lista.consultar` | — | Solicitar lista completa |
| `lista.compra_actualizada` | `{items: List<String>}` | Lista actualizada |
| `calendario.leer_eventos` | `{dias: Int}` | Petición para leer calendario |
| `calendario.eventos_leidos` | `{eventos: List<String>}` | Respuesta con eventos formateados |
| `calendario.crear_evento` | `{titulo: String, inicio_ms: Long, ...}` | Añadir evento nuevo |
| `bluetooth.conectado` | `{dispositivo: String}` | Disp. BT conectado |
| `memoria.buscar_contexto` | `{texto: String}` | Iniciar búsqueda semántica |
| `memoria.buscar_recuerdos` | `{vector: String}` | Petición matemática a SQLite |
| `memoria.recuerdos_recuperados` | `{recuerdos: List<String>}` | Top N de memoria semántica |
| `oido.amplitud` | `{valor: Float}` | Amplitud del micrófono (0-1) |
| `memoria.vectorizar` | `{texto, metadata}` | Generar embedding |
| `memoria.compactar` | — | Iniciar compactación del historial |
