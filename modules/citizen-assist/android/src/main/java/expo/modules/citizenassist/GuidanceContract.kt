package expo.modules.citizenassist

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Elemento enviado à API. Nunca contém coordenadas nem valores digitados. */
data class ScreenElement(val id: String, val role: String, val text: String, val enabled: Boolean)

data class HistoryItem(val action: String, val instruction: String)

data class GuidanceRequest(
  val requestId: String,
  val sessionId: String,
  val goal: String,
  val userMessage: String?,
  val history: List<HistoryItem>,
  val snapshotId: String,
  val packageName: String,
  val elements: List<ScreenElement>
)

data class Guidance(val action: String, val instruction: String, val targetElementId: String?)

sealed class GuidanceOutcome {
  data class Success(val guidance: Guidance) : GuidanceOutcome()

  /** Resposta válida, mas a página mudou entre a leitura e a resposta: não pode ser aplicada. */
  data class Discarded(val guidance: Guidance) : GuidanceOutcome()

  data class Failure(val errorCode: String, val message: String) : GuidanceOutcome()
}

/**
 * Contrato HTTP v1 (seção 7 da especificação): montagem da requisição e validação da resposta.
 * Usado igualmente pelo modo real e pelo mock.
 */
object GuidanceContract {
  const val VERSION = "1"
  const val LOCALE = "pt-BR"
  const val MAX_BODY_BYTES = 64 * 1024
  const val MAX_INSTRUCTION = 250
  val ACTIONS = setOf("highlight", "explain", "ask", "complete")

  fun toJson(r: GuidanceRequest): String {
    val history = JSONArray()
    r.history.forEach {
      history.put(JSONObject().put("action", it.action).put("instruction", it.instruction.take(MAX_INSTRUCTION)))
    }
    val elements = JSONArray()
    r.elements.forEach {
      elements.put(
        JSONObject()
          .put("id", it.id)
          .put("role", it.role)
          .put("text", it.text)
          .put("enabled", it.enabled)
      )
    }
    val screen = JSONObject()
      .put("snapshotId", r.snapshotId)
      .put("packageName", r.packageName)
      .put("elements", elements)
    return JSONObject()
      .put("contractVersion", VERSION)
      .put("requestId", r.requestId)
      .put("sessionId", r.sessionId)
      .put("locale", LOCALE)
      .put("goal", r.goal)
      .put("userMessage", r.userMessage ?: JSONObject.NULL)
      .put("history", history)
      .put("screen", screen)
      .toString()
  }

  fun parseResponse(status: Int, body: String?, req: GuidanceRequest): GuidanceOutcome {
    if (status == 200) return parseSuccess(body, req)

    // Tenta ler o envelope de erro comum; proxies e Azure podem responder HTML ou texto.
    val serverCode = try {
      JSONObject(body ?: "").optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() }
    } catch (e: JSONException) {
      null
    }
    val code = when {
      serverCode != null && serverCode in KNOWN_SERVER_CODES -> serverCode
      status == 429 -> "RATE_LIMITED"
      else -> "HTTP_$status"
    }
    return GuidanceOutcome.Failure(code, messageFor(code))
  }

  private fun parseSuccess(body: String?, req: GuidanceRequest): GuidanceOutcome {
    val invalid = GuidanceOutcome.Failure("INVALID_RESPONSE", messageFor("INVALID_RESPONSE"))
    val o = try {
      JSONObject(body ?: return invalid)
    } catch (e: JSONException) {
      return invalid
    }

    if (o.opt("contractVersion") != VERSION) return invalid
    if (o.opt("requestId") != req.requestId) return invalid
    if (o.opt("sessionId") != req.sessionId) return invalid
    if (o.opt("snapshotId") != req.snapshotId) return invalid

    val action = o.opt("action") as? String ?: return invalid
    if (action !in ACTIONS) return invalid

    val instruction = (o.opt("instruction") as? String)?.trim() ?: return invalid
    if (instruction.isEmpty() || instruction.length > MAX_INSTRUCTION) return invalid

    if (!o.has("targetElementId")) return invalid
    val target: String? = if (o.isNull("targetElementId")) null else (o.opt("targetElementId") as? String ?: return invalid)

    if (action == "highlight") {
      val element = req.elements.firstOrNull { it.id == target } ?: return invalid
      if (!element.enabled) return invalid
    } else if (target != null) {
      return invalid
    }
    return GuidanceOutcome.Success(Guidance(action, instruction, target))
  }

  private val KNOWN_SERVER_CODES = setOf(
    "INVALID_REQUEST",
    "REQUEST_ALREADY_RECORDED",
    "REQUEST_TOO_LARGE",
    "RATE_LIMITED",
    "AI_UNAVAILABLE",
    "PERSISTENCE_UNAVAILABLE",
    "AI_TIMEOUT",
    "INTERNAL_ERROR"
  )

  /** Mensagens locais simples. Nunca exibimos texto bruto vindo do servidor. */
  fun messageFor(code: String): String = when (code) {
    "REQUEST_ALREADY_RECORDED" -> "Esta solicitação já foi registrada. Peça uma nova orientação."
    "PERSISTENCE_UNAVAILABLE" -> "Não consegui registrar esta orientação. Tente novamente."
    "RATE_LIMITED" -> "Muitos pedidos seguidos. Espere um pouco e tente novamente."
    "AI_TIMEOUT", "CLIENT_TIMEOUT" -> "O servidor demorou para responder. Tente novamente."
    "NETWORK_ERROR" -> "Sem conexão com o servidor. Verifique a internet e tente novamente."
    "INVALID_REQUEST", "REQUEST_TOO_LARGE" -> "Não consegui enviar esta página. Tente novamente."
    "INVALID_RESPONSE" -> "Recebi uma resposta inválida. Tente novamente."
    "SCREEN_UNREADABLE" -> "Não consegui ler esta página. Tente novamente."
    "SCREEN_UNSTABLE" -> "A página ainda está mudando. Espere um pouco e toque em Próximo passo."
    "CHROME_NOT_ACTIVE" -> "Abra a página no Chrome e toque em Próximo passo."
    else -> "Não consegui orientar agora. Tente novamente."
  }
}
