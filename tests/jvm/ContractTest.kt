import expo.modules.citizenassist.*
import org.json.JSONObject

var failures = 0
fun check(name: String, cond: Boolean) { println((if (cond) "OK   " else "FAIL ") + name); if (!cond) failures++ }

fun req(vararg els: ScreenElement, history: List<HistoryItem> = emptyList()) = GuidanceRequest(
  requestId = "a1111111-1111-4111-8111-111111111111", sessionId = "b2222222-2222-4222-8222-222222222222",
  goal = "Quero agendar a renovação do meu RG", userMessage = null, history = history,
  snapshotId = "c3333333-3333-4333-8333-333333333333", packageName = "com.android.chrome", elements = els.toList())

fun mock(r: GuidanceRequest): GuidanceOutcome {
  val res = MockGuidanceTransport().post(GuidanceContract.toJson(r))
  return GuidanceContract.parseResponse(res.status, res.body, r)
}
fun ok(o: GuidanceOutcome) = (o as? GuidanceOutcome.Success)?.guidance

fun main() {
  // ---- requisição segue o exemplo da seção 7
  val r1 = req(ScreenElement("e1","button","Agendamento",true), ScreenElement("e2","link","Consultar solicitação",true), ScreenElement("e3","input","Município",true))
  val j = JSONObject(GuidanceContract.toJson(r1))
  check("request: contractVersion/locale", j.opt("contractVersion") == "1" && j.opt("locale") == "pt-BR")
  check("request: userMessage null explícito", j.has("userMessage") && j.isNull("userMessage"))
  check("request: history vazio []", j.getJSONArray("history").length() == 0)
  check("request: 3 elementos sem coordenadas", j.getJSONObject("screen").getJSONArray("elements").length() == 3 &&
     !GuidanceContract.toJson(r1).contains("left") && !GuidanceContract.toJson(r1).contains("bounds"))

  // ---- mock: jornada da página de demonstração
  ok(mock(r1)).let { check("mock tela 1 -> highlight e1 (Agendamento)", it?.action == "highlight" && it.targetElementId == "e1") }
  ok(mock(req(ScreenElement("e1","text","Escolha o serviço",true), ScreenElement("e2","button","Renovação de RG",true), ScreenElement("e3","button","Segunda via de documento",true))))
    .let { check("mock tela 2 -> Renovação de RG (e2)", it?.targetElementId == "e2") }
  ok(mock(req(ScreenElement("e1","text","Município",true), ScreenElement("e2","input","",true), ScreenElement("e3","button","Continuar",false), ScreenElement("e4","button","Voltar",true))))
    .let { check("mock tela 3 -> campo após rótulo Município (e2)", it?.targetElementId == "e2") }
  ok(mock(req(ScreenElement("e1","text","Município",true), ScreenElement("e2","button","Selecione",true), ScreenElement("e3","button","Continuar",false), ScreenElement("e4","button","Voltar",true))))
    .let { check("mock tela 3 (select como botão) -> e2", it?.targetElementId == "e2") }
  ok(mock(req(ScreenElement("e1","input","Município",true), ScreenElement("e2","button","Continuar",true))))
    .let { check("mock tela 3 com Continuar habilitado -> e2", it?.targetElementId == "e2") }
  ok(mock(req(ScreenElement("e1","button","Amanhã, 09:00",true), ScreenElement("e2","button","Confirmar agendamento",false))))
    .let { check("mock tela 4 sem horário -> horário e1", it?.targetElementId == "e1") }
  ok(mock(req(ScreenElement("e1","button","Amanhã, 09:00",true), ScreenElement("e2","button","Confirmar agendamento",true))))
    .let { check("mock tela 4 com horário -> Confirmar e2", it?.targetElementId == "e2") }
  ok(mock(req(ScreenElement("e1","text","Agendamento simulado confirmado",true), ScreenElement("e2","button","Recomeçar demonstração",true))))
    .let { check("mock tela 5 -> complete com target null", it?.action == "complete" && it.targetElementId == null) }
  ok(mock(req(ScreenElement("e7","text","Outro site qualquer",true))))
    .let { check("mock sem Agendamento -> explain", it?.action == "explain" && it.targetElementId == null && it.instruction.startsWith("Esta é uma resposta de teste")) }
  ok(mock(req(ScreenElement("e1","button","Agendamento",false), ScreenElement("e2","text","x",true))))
    .let { check("mock não escolhe Agendamento desabilitado", it?.action == "explain") }
  ok(mock(req(ScreenElement("e9","text","Bem-vindo",true), ScreenElement("e12","button","Agendamento",true))))
    .let { check("mock usa o ID real, não e1 fixo", it?.targetElementId == "e12") }

  // ---- validação de respostas
  fun resp(action: String, target: Any?, instruction: String = "Toque no botão.", rid: String = r1.requestId, ver: Any = "1") =
    JSONObject().put("contractVersion", ver).put("requestId", rid).put("sessionId", r1.sessionId).put("snapshotId", r1.snapshotId)
      .put("action", action).put("instruction", instruction).put("targetElementId", target ?: JSONObject.NULL).toString()
  fun parse(body: String?, status: Int = 200) = GuidanceContract.parseResponse(status, body, r1)
  fun failCode(o: GuidanceOutcome) = (o as? GuidanceOutcome.Failure)?.errorCode

  check("válida highlight e1", ok(parse(resp("highlight","e1")))?.targetElementId == "e1")
  check("highlight com ID inexistente -> inválida", failCode(parse(resp("highlight","e99"))) == "INVALID_RESPONSE")
  check("highlight sem alvo -> inválida", failCode(parse(resp("highlight", null))) == "INVALID_RESPONSE")
  check("explain com alvo -> inválida", failCode(parse(resp("explain","e1"))) == "INVALID_RESPONSE")
  check("ação desconhecida (click) -> inválida", failCode(parse(resp("click","e1"))) == "INVALID_RESPONSE")
  check("requestId diferente -> inválida", failCode(parse(resp("highlight","e1", rid = "x"))) == "INVALID_RESPONSE")
  check("versão 2 -> inválida", failCode(parse(resp("highlight","e1", ver = "2"))) == "INVALID_RESPONSE")
  check("versão numérica 1 -> inválida", failCode(parse(resp("highlight","e1", ver = 1))) == "INVALID_RESPONSE")
  check("instrução vazia -> inválida", failCode(parse(resp("explain", null, "  "))) == "INVALID_RESPONSE")
  check("instrução > 250 -> inválida", failCode(parse(resp("explain", null, "a".repeat(251)))) == "INVALID_RESPONSE")
  check("sem campo targetElementId -> inválida", failCode(parse(JSONObject(resp("explain", null)).also { it.map.remove("targetElementId") }.toString())) == "INVALID_RESPONSE")
  val rDisabled = req(ScreenElement("e1","button","Agendamento",false))
  check("alvo desabilitado -> inválida", failCode(GuidanceContract.parseResponse(200, resp("highlight","e1"), rDisabled)) == "INVALID_RESPONSE")
  check("HTML com 200 -> inválida", failCode(parse("<html>oi</html>")) == "INVALID_RESPONSE")
  check("corpo nulo -> inválida", failCode(parse(null)) == "INVALID_RESPONSE")

  // ---- erros HTTP
  fun err(code: String) = """{"contractVersion":"1","requestId":"${r1.requestId}","error":{"code":"$code","message":"x"}}"""
  val p503 = parse(err("PERSISTENCE_UNAVAILABLE"), 503) as GuidanceOutcome.Failure
  check("503 PERSISTENCE_UNAVAILABLE -> mensagem de registro", p503.errorCode == "PERSISTENCE_UNAVAILABLE" && p503.message == "Não consegui registrar esta orientação. Tente novamente.")
  val p409 = parse(err("REQUEST_ALREADY_RECORDED"), 409) as GuidanceOutcome.Failure
  check("409 -> mensagem da especificação", p409.message == "Esta solicitação já foi registrada. Peça uma nova orientação.")
  check("429 sem JSON -> RATE_LIMITED", failCode(parse("Too many", 429)) == "RATE_LIMITED")
  check("502 HTML do proxy -> HTTP_502", failCode(parse("<html>Bad gateway</html>", 502)) == "HTTP_502")
  check("503 do Azure sem JSON NÃO vira falha de banco", failCode(parse("Service Unavailable", 503)) == "HTTP_503")
  check("código desconhecido -> HTTP_500", failCode(parse(err("WHATEVER"), 500)) == "HTTP_500")

  // ---- cancelamento do mock
  val t = MockGuidanceTransport(); t.cancel("CANCELLED")
  val code = try { t.post(GuidanceContract.toJson(r1)); "none" } catch (e: TransportException) { e.code }
  check("mock cancelado lança CANCELLED", code == "CANCELLED")

  // ---- normalização
  check("normalize remove acentos", MockGuidanceTransport.normalize(" Renovação de RG ") == "renovacao de rg")

  println(if (failures == 0) "\nTODOS OS TESTES PASSARAM" else "\n$failures FALHA(S)")
  if (failures > 0) kotlin.system.exitProcess(1)
}
