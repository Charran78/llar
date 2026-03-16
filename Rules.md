# 📜 Rules.md — Reglas del Cerebro (Llar v2)

Este documento describe las reglas reales implementadas en `Cerebro.kt`. Las reglas se evalúan en orden por el operador `when`.

> Las reglas de YAML descritas en la versión inicial eran una **arquitectura futura**. Lo que hay implementado está aquí documentado.

---

## Flujo general

```
Voz del usuario
  → Oido detecta texto → publica "voz.comando"
  → Cerebro evalúa reglas (este documento)
    → Regla directa: responde y/o actúa
    → Sin regla: pide historial → envía al LLM → LLM responde
      → Cerebro responde en voz + cambia emoción del Avatar
```

---

## Reglas implementadas

### 1. Wake Word
```
CUANDO: "wakeword.detectada"
SI: modo_descanso == true
  ENTONCES: desactivar_descanso, hablar("¡Ya estoy despierta!"), avatar(alegre)
SI: modo_descanso == false
  ENTONCES: hablar(frase_aleatoria["¿Sí?", "Dime.", "Te escucho.", "Estoy aquí."])
SIEMPRE: activar_modo_escucha(7 segundos)
```

### 2. Modo Descanso
```
CUANDO: "voz.comando"
  texto_contiene: "descansa" | "duerme"
ENTONCES:
  modo_descanso = true
  hablar("Entendido, voy a descansar.")
  avatar(dormir)
```

### 3. Despertar
```
CUANDO: "voz.comando"
  texto_contiene: "despierta"
ENTONCES:
  modo_descanso = false
  hablar("¡Hola de nuevo!")
  avatar(alegre)
```

### 4. Modo Lista de Compra
```
CUANDO: "voz.comando"
  texto_contiene: "añade a la lista" | "apunta"
ENTONCES:
  modo_apuntar_compra = true
  hablar("Claro, ¿qué quieres que apunte?")

MIENTRAS: modo_apuntar_compra == true
  CUANDO: texto_contiene "fin lista" | "terminar"
    ENTONCES: modo_apuntar_compra = false, hablar("Hecho, lo he apuntado.")
  SI NO:
    ENTONCES: lista.añadir(texto), hablar("Apuntado.")
```

### 5. Consultar Lista
```
CUANDO: "voz.comando"
  texto_contiene: "qué tengo en la lista" | "ver la lista"
ENTONCES:
  publicar("lista.consultar")
  [Al recibir "lista.compra_actualizada"]:
    SI vacía: hablar("La lista está vacía.")
    SI NO: hablar("En la lista tienes: {items}.")
```

### 6. Vaciar Lista
```
CUANDO: "voz.comando"
  texto_contiene: "vacía la lista" | "borra la lista"
ENTONCES:
  publicar("lista.limpiar")
  [Memoria responde con voz automáticamente]
```

### 7. Regla de Petición al LLM General (Delegación y Memoria Semántica)
Si el comando no es para la lista de la compra ni de utilidades:
1. **Petición Semántica**: El `Cerebro` emite `memoria.buscar_contexto` pasando el texto.
2. **Espera de Vectores**: Al recibir `memoria.recuerdos_recuperados`, los almacena temporalmente.
3. **Petición del Historial**: Emite `memoria.obtener_historial`.
4. **Construcción y Envío**: Cuando llega `memoria.historial_recuperado` (con los N mensajes recientes de la sesión actual):
   - Inyecta los recuerdos semánticos (si los hay) como un mensaje especial de sistema: `"RECUERDO: He encontrado información relevante..."`.
   - Agrega el historial reciente (pares role/content).
   - Agrega la petición actual del usuario.
   - Envía el array estructurado a `LLMRemoto` vía `llm.solicitar`.

**¿Por qué?** Permite que el LLM local combine contexto inmediato (historial) con recuerdos de largo plazo (semántica vectorial) sin ensuciar su personalidad y de manera transparente, ajustándose todo al estándar de la API `/api/chat`.
```
CUANDO: "voz.comando"
  SIN coincidencia con reglas anteriores
  modo_descanso == false
ENTONCES:
  avatar(pensar)
  solicitar_historial(sesion_id, limite=8)
  [Al recibir historial]:
    construir messages = [
      ...historial como [{role: "user"|"assistant", content: texto}],
      {role: "user", content: texto_actual}
    ]
    publicar("llm.solicitar", messages)
  [Al recibir "llm.respuesta"]:
    hablar(respuesta)
    avatar(emocion_detectada)
    guardar_en_historial(respuesta, "Llar")
```

### 8. Proactividad al iniciar
```
AL INICIAR (2s de delay):
  hora = hora_actual
  saludo = "Buenos días" | "Buenas tardes" | "Buenas noches"
  hablar("{saludo} {nombre}. Ya estoy lista.")
  consultar_lista()
  [Si la lista no está vacía]:
    hablar("Recuerda que tienes {N} cosas pendientes en la lista.")
```

### 9. Regla de Proactividad de Calendario
Si el usuario dice "¿qué tengo hoy?" o menciona explícitamente "qué" y "calendario":
1. El `Cerebro` emite `calendario.leer_eventos` con 1 día de alcance.
2. Al recibir `calendario.eventos_leidos`, si hay eventos los lee en voz alta, sino se queda en silencio.

### 10. Regla de Proactividad Bluetooth
Al conectarse un dispositivo (evento `bluetooth.conectado`):
1. El `Cerebro` analiza el nombre del dispositivo en minúsculas.
2. Si contiene "coche", "car" o "auto", activa una rutina especial: saluda, enciende el vídeo `alegre` y ofrece proactivamente leer la lista de la compra o los eventos del día.
3. Si contiene "buds", "auricular" o "headset", da un acuse de recibo breve.
4. Para otros dispositivos, se registra en log pero permanece en silencio para no ser intrusiva.

### 11. Proactividad por ubicación
```
CUANDO: "ubicacion.cambio"
  datos.lugar == "supermercado"
ENTONCES:
  hablar("Estás cerca del supermercado. ¿Quieres que te lea la lista?")
```

### 12. Compactación del historial
```
CUANDO: contadorInteracciones >= 15
  O CUANDO: "memoria.compactar" (botón UI)
ENTONCES:
  avatar(pensar)
  publicar("llm.solicitar", messages=[
    {role: "user", content: "Resume en 3-5 frases los temas importantes de nuestra conversación."}
  ], es_resumen=true)
  [LLMRemoto guarda el resumen como recuerdo vectorial]
  [publica "memoria.compactacion_finalizada"]
  avatar(neutral)
```

---

## Detección de emociones (LLMRemoto.detectarEmocion)

La emoción del avatar se detecta analizando keywords en la respuesta del LLM:

| Keywords en respuesta | Emoción | Vídeo |
|---|---|---|
| alegr, feliz, genial, perfecto, encant, maravill | `alegre` | llar_alegria |
| trist, lo siento, lástima, preocup, ánimo | `pensar`/`preocupado` | Esperando respuesta de servidor (LLM u Ollama Embeddings) |
| `alegre` | Reconocimiento (ej: Entrar al coche) |
| `sorpresa` | - |
| `enfado` | - |
| `carino` | cariño, amor, cielo | - |
| `soplar` | IDLE aleatorio | llar_surprise |
| enfad, molest, basta | `enfado` | llar_angry |
| buenas noches, descansar, dormir | `dormir` | llar_dormir |
| pienso, déjame pensar, a ver | `pensar` | llar_think |
| (sin match) | `neutral` | llar_wait |

---

## Notas

- **La personalidad de Llar está en el modelfile de Ollama**, no en el código.
- El código solo pasa el texto del usuario y el historial — sin inyectar contexto adicional.
- El nombre del usuario y la fecha se guardan como **metadatos JSON** en la tabla `conversaciones`, no en el texto del mensaje.
- `modo_descanso` bloquea todos los comandos de voz excepto "despierta".