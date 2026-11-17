package expo.modules.citizenassist

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.RecognizerIntent
import expo.modules.kotlin.Promise
import expo.modules.kotlin.Queues
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.util.UUID

class ServiceNotRunningException :
  CodedException("SERVICE_NOT_RUNNING", "Ative o serviço de acessibilidade do CitizenTech Assistente.", null)

class InvalidConfigException(message: String) : CodedException("INVALID_CONFIG", message, null)

/**
 * Ponte React Native <-> Android. A interface só inicia/encerra a ajuda e espelha o estado;
 * o ciclo de orientação inteiro roda no AssistAccessibilityService.
 */
class CitizenAssistModule : Module() {
  private var speechPromise: Promise? = null

  private val context: Context
    get() = appContext.reactContext ?: throw IllegalStateException("Contexto Android indisponível")

  override fun definition() = ModuleDefinition {
    Name("CitizenAssist")

    Events("onStateChange", "onSessionEnded")

    OnCreate {
      SessionBus.eventSink = { name, body -> sendEvent(name, body) }
    }

    OnDestroy {
      SessionBus.eventSink = null
    }

    Function("getStatus") {
      mapOf(
        "serviceEnabled" to isServiceEnabled(),
        "serviceRunning" to (AssistAccessibilityService.instance != null),
        "speechRecognitionAvailable" to isSpeechAvailable(),
        "state" to SessionBus.lastState
      )
    }

    Function("openAccessibilitySettings") {
      context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copia a configuração (objetivo, modo e baseUrl) para o lado nativo e inicia a sessão. */
    Function("startSession") { goal: String, mode: String, baseUrl: String ->
      val cleanGoal = goal.trim()
      if (cleanGoal.isEmpty() || cleanGoal.length > 500) {
        throw InvalidConfigException("O objetivo precisa ter de 1 a 500 caracteres.")
      }
      if (mode != "mock" && mode != "real") throw InvalidConfigException("Modo inválido: use mock ou real.")
      val url = baseUrl.trim()
      if (mode == "real" && !(url.startsWith("https://") || url.startsWith("http://"))) {
        throw InvalidConfigException("Configure EXPO_PUBLIC_API_BASE_URL para usar o modo real.")
      }
      val service = AssistAccessibilityService.instance ?: throw ServiceNotRunningException()
      val sessionId = UUID.randomUUID().toString()
      service.startSession(sessionId, cleanGoal, mode, url)
      sessionId
    }

    Function("stopSession") {
      AssistAccessibilityService.instance?.endSessionFromApp()
      Unit
    }

    /** Devolve (e esvazia) os resumos de sessões encerradas, em JSON, para o app salvar no Firestore. */
    Function("consumeFinishedSessions") {
      SessionBus.drainFinished()
    }

    /** Fala -> texto usando o reconhecedor do sistema (Google). O app não pede permissão de microfone. */
    AsyncFunction("recognizeSpeech") { prompt: String, promise: Promise ->
      val activity = appContext.currentActivity
      if (activity == null) {
        promise.reject("NO_ACTIVITY", "Abra o aplicativo para usar a fala.", null)
      } else {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
          putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
        speechPromise?.resolve(null)
        speechPromise = promise
        try {
          @Suppress("DEPRECATION")
          activity.startActivityForResult(intent, SPEECH_REQUEST)
        } catch (e: ActivityNotFoundException) {
          speechPromise = null
          promise.reject("SPEECH_UNAVAILABLE", "Reconhecimento de voz indisponível neste aparelho.", e)
        }
      }
    }.runOnQueue(Queues.MAIN)

    OnActivityResult { _, payload ->
      if (payload.requestCode == SPEECH_REQUEST) {
        val promise = speechPromise
        speechPromise = null
        if (payload.resultCode == Activity.RESULT_OK) {
          val text = payload.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
          promise?.resolve(text?.take(500))
        } else {
          promise?.resolve(null)
        }
      }
    }
  }

  private fun isServiceEnabled(): Boolean {
    val expected = ComponentName(context, AssistAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
      ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
  }

  private fun isSpeechAvailable(): Boolean = try {
    context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
  } catch (e: RuntimeException) {
    false
  }

  companion object {
    private const val SPEECH_REQUEST = 7311
  }
}
