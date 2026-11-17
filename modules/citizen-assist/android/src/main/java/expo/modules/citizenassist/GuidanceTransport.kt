package expo.modules.citizenassist

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.text.Normalizer

data class HttpResult(val status: Int, val body: String?)

/** code: NETWORK_ERROR, CLIENT_TIMEOUT ou CANCELLED. */
class TransportException(val code: String, cause: Throwable? = null) : Exception(code, cause)

/**
 * Caminho único de execução: o ciclo nativo monta a mesma requisição JSON e
 * a entrega a um transporte. O modo real usa HTTP; o mock responde localmente
 * no mesmo formato v1, e a resposta passa pela mesma validação.
 */
interface GuidanceTransport {
  /** Bloqueia a thread atual. Chamar fora da thread principal. */
  fun post(json: String): HttpResult

  /** reason: CLIENT_TIMEOUT ou CANCELLED. */
  fun cancel(reason: String)
}

class HttpGuidanceTransport(baseUrl: String) : GuidanceTransport {
  private val url = baseUrl.trim().trimEnd('/') + "/api/v1/guidance"

  @Volatile private var connection: HttpURLConnection? = null
  @Volatile private var cancelReason: String? = null

  override fun post(json: String): HttpResult {
    val bytes = json.toByteArray(Charsets.UTF_8)
    val conn = try {
      URL(url).openConnection() as HttpURLConnection
    } catch (e: IOException) {
      throw TransportException("NETWORK_ERROR", e)
    }
    connection = conn
    try {
      cancelReason?.let { throw TransportException(it) }
      conn.requestMethod = "POST"
      conn.connectTimeout = 10_000
      conn.readTimeout = 25_000
      conn.doOutput = true
      conn.useCaches = false
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
      conn.setRequestProperty("Accept", "application/json")
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }

      val status = conn.responseCode
      val stream = if (status >= 400) conn.errorStream else conn.inputStream
      val body = stream?.bufferedReader(Charsets.UTF_8)?.use { readLimited(it) }
      cancelReason?.let { throw TransportException(it) }
      return HttpResult(status, body)
    } catch (e: TransportException) {
      throw e
    } catch (e: SocketTimeoutException) {
      throw TransportException(cancelReason ?: "CLIENT_TIMEOUT", e)
    } catch (e: IOException) {
      // Inclui DNS, TLS, conexão recusada e desconexão.
      throw TransportException(cancelReason ?: "NETWORK_ERROR", e)
    } finally {
      conn.disconnect()
      connection = null
    }
  }

  override fun cancel(reason: String) {
    if (cancelReason == null) cancelReason = reason
    connection?.disconnect()
  }

  private fun readLimited(reader: Reader, max: Int = 256 * 1024): String {
    val sb = StringBuilder()
    val buf = CharArray(4096)
    while (sb.length < max) {
      val n = reader.read(buf)
      if (n < 0) break
      sb.append(buf, 0, n)
    }
    return sb.toString()
  }
}

/**
 * Modo simulado. NÃO é IA: regras fixas para testar leitura, destaque e voz.
 * Regra mínima da especificação: se houver elemento habilitado "Agendamento", destacar o ID real dele;
 * senão, explicar que é uma resposta de teste. Também conhece as telas da página de demonstração (docs/demo).
 */
class MockGuidanceTransport : GuidanceTransport {
  @Volatile private var cancelReason: String? = null

  private class El(val id: String, val role: String, val text: String, val norm: String, val enabled: Boolean)

  override fun post(json: String): HttpResult {
    // Espera ~500 ms como uma rede rápida, mas atende cancelamento.
    var waited = 0
    while (waited < 500) {
      cancelReason?.let { throw TransportException(it) }
      try {
        Thread.sleep(50)
      } catch (e: InterruptedException) {
        throw TransportException("CANCELLED", e)
      }
      waited += 50
    }
    cancelReason?.let { throw TransportException(it) }

    val req = JSONObject(json)
    val screen = req.getJSONObject("screen")
    val list = screen.getJSONArray("elements")
    val elements = (0 until list.length()).map {
      val o = list.getJSONObject(it)
      val text = o.optString("text")
      El(o.getString("id"), o.getString("role"), text, normalize(text), o.optBoolean("enabled"))
    }
    val history = req.optJSONArray("history") ?: JSONArray()
    val (action, instruction, target) = decide(elements, history)

    val response = JSONObject()
      .put("contractVersion", GuidanceContract.VERSION)
      .put("requestId", req.getString("requestId"))
      .put("sessionId", req.getString("sessionId"))
      .put("snapshotId", screen.getString("snapshotId"))
      .put("action", action)
      .put("instruction", instruction)
      .put("targetElementId", target ?: JSONObject.NULL)
    return HttpResult(200, response.toString())
  }

  override fun cancel(reason: String) {
    if (cancelReason == null) cancelReason = reason
  }

  private fun decide(elements: List<El>, history: JSONArray): Triple<String, String, String?> {
    val clickable = setOf("button", "link", "other")
    fun findClickable(enabledOnly: Boolean = true, predicate: (String) -> Boolean) =
      elements.firstOrNull { it.role in clickable && (!enabledOnly || it.enabled) && predicate(it.norm) }

    // 5. Confirmação final na página de demonstração.
    if (elements.any { it.norm.contains("agendamento simulado confirmado") }) {
      return Triple(
        "complete",
        "Pronto! O agendamento de demonstração foi concluído. Nenhum agendamento real foi feito. Você pode tocar em Encerrar.",
        null
      )
    }

    // 4. Escolher horário e confirmar.
    val confirm = findClickable(enabledOnly = false) { it.contains("confirmar agendamento") }
    if (confirm != null) {
      if (confirm.enabled) return Triple("highlight", "Toque em Confirmar agendamento, no botão destacado.", confirm.id)
      val slot = findClickable { TIME.containsMatchIn(it) }
      if (slot != null) return Triple("highlight", "Escolha um horário. Toque no horário destacado.", slot.id)
    }

    // 1. Regra mínima da especificação: botão/link "Agendamento" habilitado.
    val schedule = findClickable { it == "agendamento" }
    if (schedule != null) return Triple("highlight", "Toque em Agendamento, no botão destacado.", schedule.id)

    // 3. Formulário com Município e Continuar.
    val next = findClickable { it == "continuar" }
    if (next != null) return Triple("highlight", "Agora toque em Continuar, no botão destacado.", next.id)
    val city = cityField(elements)
    if (city != null) return Triple("highlight", "Toque no campo Município destacado e escolha a sua cidade.", city.id)

    // 2. Escolha do serviço.
    val service = findClickable { it.contains("renovacao de rg") }
    if (service != null) return Triple("highlight", "Toque em Renovação de RG, no botão destacado.", service.id)

    return Triple("explain", "Esta é uma resposta de teste. Abra a página de demonstração.", null)
  }

  /** Campo "Município": pelo rótulo do próprio campo ou pelo primeiro campo depois do texto "Município". */
  private fun cityField(elements: List<El>): El? {
    elements.firstOrNull { it.role == "input" && it.enabled && it.norm.contains("municipio") }?.let { return it }
    val labelIndex = elements.indexOfFirst { it.role == "text" && it.norm.startsWith("municipio") }
    if (labelIndex < 0) return null
    val after = elements.drop(labelIndex + 1).filter { it.enabled }
    // Alguns Chrome expõem o <select> como campo; outros, como botão.
    return after.firstOrNull { it.role == "input" }
      ?: after.firstOrNull { it.role in setOf("button", "other") && it.norm != "continuar" && it.norm != "voltar" }
  }

  companion object {
    private val TIME = Regex("\\b\\d{1,2}:\\d{2}\\b")
    private val MARKS = Regex("\\p{Mn}+")

    fun normalize(s: String): String =
      MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase().trim()
  }
}
