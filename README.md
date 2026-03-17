# 🔥 Llar - Large Language Assistant Resident

![Demo](app/src/main/res/drawable/DEMO_LLAR.png)

**Llar** es un asistente personal inteligente, privado y modular para Android. Inspirado en el concepto asturiano del *llar* (el hogar, el fuego que protege y reúne a la familia), Llar te acompaña, te entiende y te ayuda — sin enviar nada a la nube.

---

## 🌟 Filosofía

- **Privacidad total**: todo el procesamiento ocurre en tus dispositivos.
- **Modular**: sentidos y efectores son módulos independientes conectados a un bus de eventos central.
- **Personalidad propia**: nombre, voz y memoria. Aprende de ti.
- **Hardware reciclado**: diseñado para funcionar en móviles y tablets viejos.

---

## 🚀 Estado actual (MVP implementado)

| Funcionalidad | Estado |
| --- | --- |
| Wake word "Llar" (Porcupine offline) | ✅ |
| Reconocimiento de voz local (Vosk, español) | ✅ |
| Síntesis de voz (TTS Android, voz femenina) | ✅ |
| Memoria persistente (SQLite: hechos + historial de chat) | ✅ |
| Historial de conversación pasado al LLM | ✅ |
| Lista de la compra por voz | ✅ |
| Avatar con vídeos MP4 por estado emocional | ✅ |
| IA local vía Ollama (modelo `llar`, modelfile propio) | ✅ |
| Memoria semántica vectorial (búsqueda local por coseno) | ✅ |
| Calendario (lectura/escritura) | ✅ |
| GPS / geocercas proactivas | 📋 planificado |
| Bluetooth proactivo | ✅ |

---

## 🧠 Arquitectura

Llar usa una **arquitectura orientada a eventos**. Todos los módulos se comunican por `BusEventos` (Singleton) con mensajes `Evento(tipo, origen, datos)`.

```
┌──────────────────────────────────────────────┐
│                  NÚCLEO (CORE)               │
│  ┌──────────┐  ┌──────────┐  ┌────────────┐ │
│  │ CEREBRO  │  │ MEMORIA  │  │ MEM.SEMÁNT │ │
│  │(orquesta)│  │(SQLite)  │  │(busqueda)  │ │
│  └────┬─────┘  └──────────┘  └────────────┘ │
│       │                                      │
│  ┌────▼──────────────────────────────────┐   │
│  │            BUS DE EVENTOS             │   │
│  └───────────────────────────────────────┘   │
└──────────────────────────────────────────────┘
      ▲            ▲            ▲           ▲       ▲
  [Oido]      [WakeWord]   [LLMRemoto]  [Avatar]  [Bluetooth]
  [Voz]                    [Calendario] [Lista]
```

---

## 🛠️ Tecnologías

| Componente | Tecnología |
| --- | --- |
| Lenguaje | Kotlin (Android) |
| UI | Jetpack Compose + Material 3 |
| Reconocimiento de voz | [Vosk](https://alphacephei.com/vosk/) `vosk-model-small-es-0.42` |
| Wake word | [Porcupine](https://picovoice.ai/) (usa BuiltInKeyword.PORCUPINE de momento) |
| Síntesis de voz | Android TextToSpeech |
| Base de datos | SQLite (hechos, historial de chat, lista de compra, recuerdos) |
| Modelo de lenguaje | Ollama local (`llar`, basado en Gemma3:1b con modelfile propio) |
| Embeddings | Ollama `all-minilm:latest` via `/api/embeddings` |
| HTTP | OkHttp 4 |
| Avatar | VideoView con vídeos MP4 en `res/raw/` |

---

## 📋 Requisitos previos

- **Android Studio** (versión estable reciente)
- **SDK mínimo**: API 24 (Android 7.0)
- **PC con [Ollama](https://ollama.com/)** en la misma red Wi-Fi, con el modelo `llar` cargado
- Conexión a internet solo para descargar dependencias iniciales

### Modelos Ollama necesarios

```bash
ollama pull all-minilm   # Para embeddings/memoria semántica (45MB)
# El modelo 'llar' es tuyo (modelfile propio basado en Gemma3:1b)
```

---

## 🔧 Instalación

1. **Clona el repositorio**

   ```bash
   git clone https://github.com/charran78/llar.git
   cd llar
   ```

2. **Descarga el modelo Vosk**
   - Descarga `vosk-model-small-es-0.42` desde [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models)
   - Descomprime y copia la carpeta a `app/src/main/assets/vosk-model-small-es-0.42/`

3. **Configura la IP del servidor Ollama**
   - En `LLMRemoto.kt` y `MemoriaSemantica.kt`, cambia `192.168.1.132` por la IP de tu PC.

4. **Configura la clave Porcupine** (opcional para wake word)
   - Obtén una AccessKey gratuita en [console.picovoice.ai](https://console.picovoice.ai/)
   - Añade en `gradle.properties`: `PICOVOICE_ACCESS_KEY=tu_clave_aqui`

5. **Abre en Android Studio y ejecuta** ▶️

---

## 🎮 Uso básico

| Comando | Acción |
| --- | --- |
| Di "Porcupine" (wake word) | Activa escucha |
| "Hola" | Saludo personalizado |
| "Añade a la lista" | Entra en modo lista de compra |
| "¿Qué tengo en la lista?" | Lee la lista de compra |
| "Vacía la lista" | Borra la lista |
| "¿Qué tengo hoy en el calendario?" | Lee los eventos de hoy |
| (Conectar auriculares Bluetooth) | Proactividad BT silenciosa |
| (Conectar Bluetooth coche) | Proactividad BT comunicativa |
| "Descansa" | Modo reposo del avatar |
| "Despierta" | Sale del modo reposo |
| Cualquier otra cosa | Enviado al LLM local |

---

## 📁 Estructura del proyecto

```
app/src/main/
├── java/com/bdw/llar/
│   ├── core/
│   │   ├── BusEventos.kt          — Bus central de mensajes (Singleton)
│   │   ├── Cerebro.kt             — Orquestador principal
│   │   ├── Memoria.kt             — SQLite: hechos, historial, lista, recuerdos
│   │   ├── MemoriaSemantica.kt    — Embeddings vectoriales (Ollama)
│   │   ├── GestorModulos.kt       — Carga dinámica de módulos
│   │   └── LlarForegroundService.kt
│   ├── sentidos/
│   │   ├── Oido.kt                — Reconocimiento de voz (Vosk)
│   │   └── WakeWordDetector.kt    — Wake word (Porcupine)
│   ├── efectores/
│   │   ├── Avatar.kt              — Avatar visual con vídeos MP4
│   │   ├── LLMRemoto.kt           — Cliente HTTP de Ollama (/api/chat)
│   │   └── Voz.kt                 — Síntesis de voz (TTS)
│   ├── modelo/
│   │   └── Evento.kt
│   └── ui/
│       ├── MainActivity.kt
│       └── Theme.kt
├── assets/
│   └── vosk-model-small-es-0.42/ — Modelo de voz offline
├── res/
│   ├── drawable/ico.png           — Icono de la app
│   └── raw/
│       ├── llar_alegria.mp4
│       ├── llar_angry.mp4
│       ├── llar_blowing.mp4
│       ├── llar_carino.mp4
│       ├── llar_dormir.mp4
│       ├── llar_speak.mp4
│       ├── llar_surprise.mp4
│       ├── llar_think.mp4
│       ├── llar_wait.mp4
│       └── llar_wait2.mp4
```
<div align="center">

      
<br />

<p align="center">
  <a href="https://pedromencias.netlify.app/">
    <img align="center" src="https://i.postimg.cc/GmX5PCN5/PF-ok2.png" width="256" alt="Portfolio" /> 
  </a>
  <a href="https://www.linkedin.com/in/pedro-menc%C3%ADas-68223336b/">
    <img align="center" src="https://i.postimg.cc/9MjsxpmM/LI-Logo.png" width="256" alt="LinkedIn" /> 
  </a>
  <a href="https://www.buymeacoffee.com/beyonddigiv">
    <img align="center" src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" width="256" alt="beyonddigiv" /> 
  </a>
</p>

<br/>

<div align="center">

<br />

<p align="center">
  <a href="https://pedromencias.netlify.app/">
    <img align="center" src="https://i.postimg.cc/GmX5PCN5/PF-ok2.png" width="256" alt="Portfolio" />
  </a>
  <a href="https://www.linkedin.com/in/pedro-menc%C3%ADas-68223336b/">
    <img align="center" src="https://i.postimg.cc/9MjsxpmM/LI-Logo.png" width="256" alt="LinkedIn" />
  </a>
  <a href="https://www.buymeacoffee.com/beyonddigiv">
    <img align="center" src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" width="256" alt="beyonddigiv" />
  </a>
</p>

<br/>

---

## 📄 Licencia

GNU General Public License v3.0

---

**Llar** - El fuego que nunca se apaga. 🔥
</div>