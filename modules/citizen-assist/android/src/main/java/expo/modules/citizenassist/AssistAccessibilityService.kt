package expo.modules.citizenassist

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Coração do assistente. Todo o ciclo de orientação roda aqui, no Android,
 * sem depender da tela React Native estar visível:
 * apagar destaque -> esperar a página estabilizar -> ler -> chamar API/mock -> validar -> destacar e falar.
 *
 * Fora de uma sessão, o serviço fica inativo: não lê nada e ignora eventos.
 */
class AssistAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {

  companion object {
    @Volatile
    var instance: AssistAccessibilityService? = null
      private set

    private const val STABLE_MS = 500L
    private const val STABILIZE_MAX_MS = 3_000L
    private const val POLL_MS = 150L
    private const val CLIENT_TIMEOUT_MS = 25_000L
    private const val HIGHLIGHT_CHECK_MS = 250L
    private const val MAX_HISTORY = 3
    private const val MAX_STEPS_RECORDED = 100
    private const val EMPTY_FINGERPRINT = Int.MIN_VALUE
  }

  private class ActiveSession(
    val sessionId: String,
    val goal: String,
    val mode: String,
    val baseUrl: String
  ) {
    val startedAt = System.currentTimeMillis()
    @Volatile var generation = 0
    @Volatile var transport: GuidanceTransport? = null
    var busy = false
    val history = ArrayList<HistoryItem>()
    var pendingUserMessage: String? = null
    var awaitingAnswer = false
    var highlightNode: AccessibilityNodeInfo? = null
    var highlightRect: Rect? = null
    var highlightRawRect: Rect? = null
    var highlightKeyboard = false
    var lastAction = ""
    var status = "waiting"
    var message = ""
    var completed = false
    val steps = JSONArray()
  }

  private val main = Handler(Looper.getMainLooper())
  private val worker: ExecutorService = Executors.newSingleThreadExecutor()

  @Volatile private var session: ActiveSession? = null
  private var overlay: OverlayController? = null
  private var reader: ScreenReader? = null
  private var tts: TextToSpeech? = null
  private var ttsReady = false
  private var ttsFailed = false
  private var pendingSpeech: String? = null
  private var screenOffReceiver: BroadcastReceiver? = null
  private var highlightCheckScheduled = false

  // ---------------------------------------------------------------- ciclo de vida do serviço

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    reader = ScreenReader(this)
    publishState()
  }

  override fun onUnbind(intent: Intent?): Boolean {
    endSession("service_stopped")
    instance = null
    publishState()
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    endSession("service_stopped")
    instance = null
    worker.shutdownNow()
    publishState()
    super.onDestroy()
  }

  override fun onInterrupt() {
    tts?.stop()
  }

  // ---------------------------------------------------------------- API usada pelo módulo/Activity (qualquer thread)

  fun startSession(sessionId: String, goal: String, mode: String, baseUrl: String) {
    main.post { startOnMain(sessionId, goal, mode, baseUrl) }
  }

  fun endSessionFromApp() {
    main.post { endSession("app") }
  }

  internal fun onClarifyAnswer(text: String) {
    main.post {
      val s = session ?: return@post
      val answer = text.trim().take(500)
      if (answer.isEmpty()) return@post
      s.pendingUserMessage = answer
      s.awaitingAnswer = false
      overlay?.setAnswerVisible(false)
      updateUi(s, "waiting", "Resposta guardada. Volte ao Chrome e toque em Próximo passo.", speak = true)
    }
  }

  internal fun onClarifyClosed() {
    main.post { if (session != null) overlay?.showPanel() }
  }

  // ---------------------------------------------------------------- sessão

  private fun startOnMain(sessionId: String, goal: String, mode: String, baseUrl: String) {
    endSession("restarted")
    val s = ActiveSession(sessionId, goal, mode, baseUrl)
    session = s
    overlay = OverlayController(
      this,
      onNext = { requestNextStep() },
      onAnswer = { openClarify() },
      onEnd = { endSession("user") }
    ).also {
      it.showPanel()
      if (mode == "mock") it.setHint("Modo de teste: respostas simuladas, sem IA.")
    }
    registerScreenOff()
    initTts()
    updateUi(s, "waiting", "Ajuda iniciada. Abra a página no Chrome e toque em Próximo passo.", speak = true)
  }

  /** Encerrar: cancela a chamada, para a voz, remove overlays e descarta o contexto da sessão. */
  private fun endSession(reason: String) {
    val s = session ?: return
    session = null
    s.generation++
    s.transport?.cancel("CANCELLED")
    main.removeCallbacks(highlightCheck)
    highlightCheckScheduled = false
    overlay?.removeAll()
    overlay = null
    tts?.stop()
    tts?.shutdown()
    tts = null
    ttsReady = false
    pendingSpeech = null
    unregisterScreenOff()
    ClarifyActivity.closeIfOpen()

    SessionBus.addFinished(buildRecord(s, reason))
    publishState()
    SessionBus.emit("onSessionEnded", mapOf("sessionId" to s.sessionId, "reason" to reason))
  }

  private fun isCurrent(s: ActiveSession, generation: Int) = session === s && s.generation == generation

  // ---------------------------------------------------------------- ciclo de orientação

  /** Um toque aceito em "Próximo passo" = no máximo uma chamada. */
  private fun requestNextStep() {
    val s = session ?: return
    val r = reader ?: return
    if (s.busy) return
    s.busy = true
    val generation = ++s.generation
    clearHighlight(s)
    tts?.stop()
    overlay?.setHint(null)
    val history = s.history.toList()
    val userMessage = s.pendingUserMessage
    updateUi(s, "reading", "Lendo a página…", speak = false)
    worker.execute { runCycle(s, generation, r, history, userMessage) }
  }

  /** Roda na thread de trabalho. */
  private fun runCycle(
    s: ActiveSession,
    generation: Int,
    r: ScreenReader,
    history: List<HistoryItem>,
    userMessage: String?
  ) {
    // 1. Esperar ~500 ms sem mudança relevante (máximo 3 s).
    val start = SystemClock.elapsedRealtime()
    var lastFingerprint: Int? = null
    var since = start
    var lastWasEmpty = false
    var stable: Snapshot? = null
    while (stable == null) {
      if (!isCurrent(s, generation)) return
      val now = SystemClock.elapsedRealtime()
      when (val result = r.read()) {
        is ReadResult.Ok -> {
          lastWasEmpty = false
          if (result.snapshot.fingerprint == lastFingerprint) {
            if (now - since >= STABLE_MS) stable = result.snapshot
          } else {
            lastFingerprint = result.snapshot.fingerprint
            since = now
          }
        }
        is ReadResult.Empty -> {
          lastWasEmpty = true
          if (lastFingerprint != EMPTY_FINGERPRINT) {
            lastFingerprint = EMPTY_FINGERPRINT
            since = now
          } else if (now - since >= STABLE_MS) {
            failLocally(s, generation, "SCREEN_UNREADABLE", record = true)
            return
          }
        }
        is ReadResult.ChromeNotActive -> {
          failLocally(s, generation, "CHROME_NOT_ACTIVE", record = false)
          return
        }
      }
      if (stable != null) break
      if (now - start >= STABILIZE_MAX_MS) {
        failLocally(s, generation, if (lastWasEmpty) "SCREEN_UNREADABLE" else "SCREEN_UNSTABLE", record = true)
        return
      }
      try {
        Thread.sleep(POLL_MS)
      } catch (e: InterruptedException) {
        return
      }
    }
    val snapshot = stable ?: return

    // 2. Montar a requisição v1.
    val request = GuidanceRequest(
      requestId = UUID.randomUUID().toString(),
      sessionId = s.sessionId,
      goal = s.goal,
      userMessage = userMessage,
      history = history,
      snapshotId = snapshot.snapshotId,
      packageName = snapshot.packageName,
      elements = snapshot.elements
    )
    val json = GuidanceContract.toJson(request)
    if (json.toByteArray(Charsets.UTF_8).size > GuidanceContract.MAX_BODY_BYTES) {
      failLocally(s, generation, "REQUEST_TOO_LARGE", record = true)
      return
    }

    val transport: GuidanceTransport =
      if (s.mode == "real") HttpGuidanceTransport(s.baseUrl) else MockGuidanceTransport()
    s.transport = transport
    if (!isCurrent(s, generation)) {
      transport.cancel("CANCELLED")
      return
    }
    main.post {
      if (isCurrent(s, generation)) {
        val label = if (s.mode == "mock") "Pensando na orientação (modo de teste)…" else "Pensando na orientação…"
        updateUi(s, "requesting", label, speak = false)
      }
    }

    // 3. Chamar API ou mock, com limite total de 25 s.
    val timeout = Runnable { transport.cancel("CLIENT_TIMEOUT") }
    main.postDelayed(timeout, CLIENT_TIMEOUT_MS)
    val outcome: GuidanceOutcome = try {
      val response = transport.post(json)
      GuidanceContract.parseResponse(response.status, response.body, request)
    } catch (e: TransportException) {
      if (e.code == "CANCELLED") return
      GuidanceOutcome.Failure(e.code, GuidanceContract.messageFor(e.code))
    } catch (e: RuntimeException) {
      GuidanceOutcome.Failure("CLIENT_ERROR", GuidanceContract.messageFor("CLIENT_ERROR"))
    } finally {
      main.removeCallbacks(timeout)
    }

    // 4. A página mudou desde a leitura? Então a orientação não vale mais.
    var finalOutcome = outcome
    if (outcome is GuidanceOutcome.Success) {
      val after = r.read()
      val changed = after !is ReadResult.Ok || after.snapshot.fingerprint != snapshot.fingerprint
      if (changed) finalOutcome = GuidanceOutcome.Discarded(outcome.guidance)
    }

    main.post { applyOutcome(s, generation, request, snapshot, finalOutcome) }
  }

  private fun failLocally(s: ActiveSession, generation: Int, code: String, record: Boolean) {
    main.post {
      if (!isCurrent(s, generation)) return@post
      s.busy = false
      if (record) recordStep(s, null, null, null, "failure", code)
      updateUi(s, "error", GuidanceContract.messageFor(code), speak = true)
    }
  }

  /** Thread principal. */
  private fun applyOutcome(
    s: ActiveSession,
    generation: Int,
    request: GuidanceRequest,
    snapshot: Snapshot,
    outcome: GuidanceOutcome
  ) {
    if (!isCurrent(s, generation)) return // encerrada ou substituída: resposta atrasada é ignorada
    s.busy = false
    s.transport = null

    when (outcome) {
      is GuidanceOutcome.Failure -> {
        // Erros não entram no history e não deixam destaque nem orientação antiga.
        recordStep(s, request.requestId, null, null, "failure", outcome.errorCode)
        updateUi(s, "error", outcome.message, speak = true)
      }

      is GuidanceOutcome.Discarded -> {
        recordStep(s, request.requestId, outcome.guidance.action, outcome.guidance.instruction, "discarded", "SCREEN_CHANGED")
        updateUi(s, "waiting", "A página mudou enquanto eu pensava. Toque em Próximo passo de novo.", speak = true)
      }

      is GuidanceOutcome.Success -> {
        val g = outcome.guidance
        s.pendingUserMessage = null
        s.history.add(HistoryItem(g.action, g.instruction))
        while (s.history.size > MAX_HISTORY) s.history.removeAt(0)
        s.lastAction = g.action
        recordStep(s, request.requestId, g.action, g.instruction, "success", null)

        when (g.action) {
          "highlight" -> {
            val id = g.targetElementId
            val rect = if (id != null) snapshot.rects[id] else null
            if (id != null && rect != null) {
              s.highlightRect = Rect(rect)
              s.highlightRawRect = snapshot.rawRects[id]?.let { Rect(it) }
              s.highlightNode = snapshot.nodes[id]
              s.highlightKeyboard = snapshot.keyboardVisible
              overlay?.showHighlight(rect)
              overlay?.placePanelAwayFrom(rect)
            }
          }
          "ask" -> {
            s.awaitingAnswer = true
            overlay?.setAnswerVisible(true)
          }
          "complete" -> s.completed = true
        }
        // O MESMO texto vai para o painel e para a voz.
        updateUi(s, "showing", g.instruction, speak = true)
      }
    }
  }

  // ---------------------------------------------------------------- destaque e mudanças na página

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val s = session ?: return // sem sessão: não coleta nada
    if (event == null || s.highlightRect == null) return
    val fromChrome = event.packageName?.toString() == CHROME_PACKAGE
    when (event.eventType) {
      AccessibilityEvent.TYPE_VIEW_SCROLLED,
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (fromChrome) invalidateHighlight(s)
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (fromChrome) scheduleHighlightCheck()
      // Inclui teclado abrindo/fechando. Mudanças dos nossos próprios overlays caem aqui também,
      // por isso não apagamos direto: conferimos se o alvo realmente mudou.
      AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scheduleHighlightCheck()
    }
  }

  private fun scheduleHighlightCheck() {
    if (highlightCheckScheduled) return
    highlightCheckScheduled = true
    main.postDelayed(highlightCheck, HIGHLIGHT_CHECK_MS)
  }

  private val highlightCheck = Runnable {
    highlightCheckScheduled = false
    val s = session ?: return@Runnable
    val expected = s.highlightRawRect ?: s.highlightRect ?: return@Runnable
    val node = s.highlightNode
    val r = reader
    val stillValid = try {
      val now = Rect()
      node != null && r != null &&
        node.refresh() && node.isVisibleToUser &&
        now.also { node.getBoundsInScreen(it) } == expected &&
        r.isKeyboardVisible() == s.highlightKeyboard &&
        r.isChromeOnTop()
    } catch (e: RuntimeException) {
      false
    }
    if (!stillValid) invalidateHighlight(s)
  }

  private fun invalidateHighlight(s: ActiveSession) {
    if (s.highlightRect == null) return
    clearHighlight(s)
    if (s.status == "showing") {
      overlay?.setHint("A página mudou. Quando estiver pronto, toque em Próximo passo.")
    }
  }

  private fun clearHighlight(s: ActiveSession) {
    s.highlightRect = null
    s.highlightRawRect = null
    s.highlightNode = null
    main.removeCallbacks(highlightCheck)
    highlightCheckScheduled = false
    overlay?.clearHighlight()
    overlay?.placePanelAwayFrom(null)
  }

  // ---------------------------------------------------------------- resposta a perguntas (ação "ask")

  private fun openClarify() {
    val s = session ?: return
    if (s.busy) return
    tts?.stop()
    overlay?.hidePanel()
    val intent = Intent(this, ClarifyActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      .putExtra(ClarifyActivity.EXTRA_QUESTION, s.message)
    try {
      startActivity(intent)
    } catch (e: RuntimeException) {
      overlay?.showPanel()
    }
  }

  // ---------------------------------------------------------------- voz

  private fun initTts() {
    ttsReady = false
    ttsFailed = false
    pendingSpeech = null
    tts = TextToSpeech(this, this)
  }

  override fun onInit(status: Int) {
    main.post {
      val engine = tts ?: return@post
      if (status == TextToSpeech.SUCCESS) {
        val result = engine.setLanguage(Locale.forLanguageTag("pt-BR"))
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
      }
      ttsFailed = !ttsReady
      if (ttsReady) {
        pendingSpeech?.let { engine.speak(it, TextToSpeech.QUEUE_FLUSH, null, "citizen-assist") }
      } else if (session != null) {
        // Falha de voz não impede a orientação visual.
        overlay?.setHint("Voz indisponível neste aparelho. Leia as orientações no painel.")
      }
      pendingSpeech = null
      publishState()
    }
  }

  private fun speak(text: String) {
    val engine = tts ?: return
    if (ttsReady) {
      engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "citizen-assist")
    } else if (!ttsFailed) {
      pendingSpeech = text
    }
  }

  // ---------------------------------------------------------------- tela bloqueada

  private fun registerScreenOff() {
    unregisterScreenOff()
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_SCREEN_OFF) main.post { endSession("screen_off") }
      }
    }
    val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
    if (Build.VERSION.SDK_INT >= 33) {
      registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      registerReceiver(receiver, filter)
    }
    screenOffReceiver = receiver
  }

  private fun unregisterScreenOff() {
    screenOffReceiver?.let {
      try {
        unregisterReceiver(it)
      } catch (e: IllegalArgumentException) {
        // já removido
      }
    }
    screenOffReceiver = null
  }

  // ---------------------------------------------------------------- estado e registro

  private fun updateUi(s: ActiveSession, status: String, message: String, speak: Boolean) {
    s.status = status
    s.message = message
    overlay?.setMessage(message)
    overlay?.setBusy(status == "reading" || status == "requesting")
    if (speak) speak(message)
    publishState()
  }

  private fun publishState() {
    val s = session
    val state: Map<String, Any?> = if (s == null) {
      SessionBus.idleState(serviceRunning = instance != null)
    } else {
      mapOf(
        "active" to true,
        "serviceRunning" to true,
        "sessionId" to s.sessionId,
        "mode" to s.mode,
        "status" to s.status,
        "message" to s.message,
        "lastAction" to s.lastAction,
        "awaitingAnswer" to s.awaitingAnswer,
        "voiceAvailable" to ttsReady,
        "completed" to s.completed,
        "stepCount" to s.steps.length()
      )
    }
    SessionBus.publishState(state)
  }

  private fun recordStep(
    s: ActiveSession,
    requestId: String?,
    action: String?,
    instruction: String?,
    result: String,
    errorCode: String?
  ) {
    if (s.steps.length() >= MAX_STEPS_RECORDED) return
    s.steps.put(
      JSONObject()
        .put("requestId", requestId ?: JSONObject.NULL)
        .put("at", System.currentTimeMillis())
        .put("action", action ?: JSONObject.NULL)
        .put("instruction", instruction ?: JSONObject.NULL)
        .put("result", result)
        .put("errorCode", errorCode ?: JSONObject.NULL)
    )
  }

  /** Resumo da sessão entregue ao JavaScript para salvar no Firestore. Não contém textos da página. */
  private fun buildRecord(s: ActiveSession, reason: String): String =
    JSONObject()
      .put("sessionId", s.sessionId)
      .put("goal", s.goal)
      .put("mode", s.mode)
      .put("startedAt", s.startedAt)
      .put("endedAt", System.currentTimeMillis())
      .put("endReason", reason)
      .put("completed", s.completed)
      .put("steps", s.steps)
      .toString()
}
