package expo.modules.citizenassist

/**
 * Ponte entre o serviço de acessibilidade (que vive enquanto o serviço estiver ativo)
 * e o módulo Expo (que vive junto com o React Native). O React Native apenas espelha o estado.
 */
object SessionBus {
  @Volatile
  var eventSink: ((String, Map<String, Any?>) -> Unit)? = null

  @Volatile
  var lastState: Map<String, Any?> = idleState(serviceRunning = false)
    private set

  private val finished = ArrayList<String>()

  fun idleState(serviceRunning: Boolean): Map<String, Any?> = mapOf(
    "active" to false,
    "serviceRunning" to serviceRunning,
    "sessionId" to "",
    "mode" to "",
    "status" to "idle",
    "message" to "",
    "lastAction" to "",
    "awaitingAnswer" to false,
    "voiceAvailable" to false,
    "completed" to false,
    "stepCount" to 0
  )

  fun publishState(state: Map<String, Any?>) {
    lastState = state
    emit("onStateChange", state)
  }

  fun emit(name: String, body: Map<String, Any?>) {
    try {
      eventSink?.invoke(name, body)
    } catch (e: RuntimeException) {
      // React Native recarregando: o estado continua disponível em lastState.
    }
  }

  /** Sessões encerradas aguardando o JavaScript salvar no banco. */
  @Synchronized
  fun addFinished(recordJson: String) {
    finished.add(recordJson)
    while (finished.size > 20) finished.removeAt(0)
  }

  @Synchronized
  fun drainFinished(): List<String> {
    val copy = finished.toList()
    finished.clear()
    return copy
  }
}
