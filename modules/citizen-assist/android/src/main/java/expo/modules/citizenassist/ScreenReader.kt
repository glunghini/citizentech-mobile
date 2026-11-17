package expo.modules.citizenassist

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.text.Spanned
import android.text.style.URLSpan
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.UUID

const val CHROME_PACKAGE = "com.android.chrome"

/**
 * Uma leitura da página. IDs (e1, e2...) valem só para este snapshot.
 * rects/rawRects/nodes ficam apenas no celular: coordenadas nunca vão para a API.
 */
class Snapshot(
  val snapshotId: String,
  val packageName: String,
  val elements: List<ScreenElement>,
  /** Retângulo visível (recortado à área da página) em pixels de tela. */
  val rects: Map<String, Rect>,
  /** Retângulo original do nó, para detectar se ele se moveu. */
  val rawRects: Map<String, Rect>,
  val nodes: Map<String, AccessibilityNodeInfo>,
  val keyboardVisible: Boolean,
  val fingerprint: Int
)

sealed class ReadResult {
  class Ok(val snapshot: Snapshot) : ReadResult()
  object ChromeNotActive : ReadResult()
  object Empty : ReadResult()
}

/**
 * Lê a árvore de acessibilidade da janela de conteúdo do Chrome.
 * Regras: no máximo 80 elementos, 160 caracteres por texto, prioridade para
 * controles e rótulos, nada invisível/repetido e nunca o valor de campos.
 */
class ScreenReader(private val service: AccessibilityService) {

  private class Candidate(
    val role: String,
    val text: String,
    val enabled: Boolean,
    val rect: Rect,
    val raw: Rect,
    val node: AccessibilityNodeInfo,
    val priority: Int
  )

  private var visited = 0

  fun isKeyboardVisible(): Boolean =
    windows().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

  fun isChromeOnTop(): Boolean = chromeRoot() != null

  fun read(): ReadResult {
    val root = chromeRoot() ?: return ReadResult.ChromeNotActive
    val web = findWebContent(root)
    val content = web ?: root

    val area = Rect()
    content.getBoundsInScreen(area)
    if (area.isEmpty) root.getBoundsInScreen(area)

    visited = 0
    val found = ArrayList<Candidate>()
    walk(content, 0, null, area, found, inWeb = web != null)

    val chosen = select(found)
    if (chosen.none { it.text.isNotEmpty() }) return ReadResult.Empty

    val elements = ArrayList<ScreenElement>(chosen.size)
    val rects = HashMap<String, Rect>()
    val rawRects = HashMap<String, Rect>()
    val nodes = HashMap<String, AccessibilityNodeInfo>()
    chosen.forEachIndexed { i, c ->
      val id = "e${i + 1}"
      elements.add(ScreenElement(id, c.role, c.text, c.enabled))
      rects[id] = c.rect
      rawRects[id] = c.raw
      nodes[id] = c.node
    }

    val keyboard = isKeyboardVisible()
    val signature = chosen.joinToString("\n") { "${it.role}|${it.text}|${it.enabled}|${it.raw.flattenToString()}" }
    val fingerprint = signature.hashCode() * 31 + if (keyboard) 1 else 0

    return ReadResult.Ok(
      Snapshot(
        snapshotId = UUID.randomUUID().toString(),
        packageName = root.packageName?.toString() ?: CHROME_PACKAGE,
        elements = elements,
        rects = rects,
        rawRects = rawRects,
        nodes = nodes,
        keyboardVisible = keyboard,
        fingerprint = fingerprint
      )
    )
  }

  private fun windows(): List<AccessibilityWindowInfo> = try {
    service.windows ?: emptyList()
  } catch (e: RuntimeException) {
    emptyList()
  }

  /** Raiz da janela do Chrome, somente se o Chrome for o app em primeiro plano. Ignora os overlays do assistente. */
  private fun chromeRoot(): AccessibilityNodeInfo? {
    val top = windows()
      .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
      .maxByOrNull { it.layer } ?: return null
    val root = top.root ?: return null
    return if (root.packageName?.toString() == CHROME_PACKAGE) root else null
  }

  /** O Chrome expõe o conteúdo da página como um nó "android.webkit.WebView"; fora dele ficam barra de endereço e menus. */
  private fun findWebContent(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.addLast(root)
    var count = 0
    while (queue.isNotEmpty() && count < 500) {
      val node = queue.removeFirst()
      count++
      if (node.className?.toString() == "android.webkit.WebView") return node
      for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
    }
    return null
  }

  private fun walk(
    node: AccessibilityNodeInfo,
    depth: Int,
    coveredText: String?,
    area: Rect,
    out: MutableList<Candidate>,
    inWeb: Boolean
  ) {
    if (visited++ > MAX_VISITED || depth > MAX_DEPTH || out.size >= MAX_CANDIDATES) return
    if (!node.isVisibleToUser) return
    if (!inWeb && isBrowserUi(node)) return

    val raw = Rect()
    node.getBoundsInScreen(raw)
    val visible = Rect(raw)
    if (!visible.intersect(area)) return

    var childCovered = coveredText
    val role = roleOf(node)
    if (role != null && visible.width() >= MIN_SIZE_PX && visible.height() >= MIN_SIZE_PX) {
      val text = clean(textFor(node, role))
      when (role) {
        "text" -> {
          val repeated = coveredText != null && coveredText.contains(text)
          if (text.isNotEmpty() && !repeated) {
            out.add(Candidate(role, text, node.isEnabled, visible, raw, node, PRIORITY_TEXT))
          }
        }
        "scroll" -> out.add(Candidate(role, text, node.isEnabled, visible, raw, node, PRIORITY_SCROLL))
        else -> {
          val priority = if (text.isNotEmpty()) PRIORITY_LABELED else PRIORITY_UNLABELED
          out.add(Candidate(role, text, node.isEnabled, visible, raw, node, priority))
          if (text.isNotEmpty()) childCovered = text
        }
      }
    }

    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      walk(child, depth + 1, childCovered, area, out, inWeb)
    }
  }

  /** Remove repetidos, prioriza controles com rótulo e devolve em ordem de leitura (de cima para baixo). */
  private fun select(found: List<Candidate>): List<Candidate> {
    val seen = HashSet<String>()
    val seenText = HashSet<String>()
    val unique = found.filter { c ->
      val key = "${c.role}|${c.text}|${c.raw.flattenToString()}"
      when {
        !seen.add(key) -> false
        c.role == "text" && !seenText.add(c.text) -> false
        else -> true
      }
    }
    return unique
      .sortedBy { it.priority } // ordenação estável: mantém a ordem do documento dentro de cada prioridade
      .take(MAX_ELEMENTS)
      .sortedWith(compareBy<Candidate>({ it.rect.top }, { it.rect.left }))
  }

  private fun roleOf(n: AccessibilityNodeInfo): String? {
    val cls = n.className?.toString().orEmpty()
    val desc = roleDescription(n)
    return when {
      n.isPassword || n.isEditable || cls.endsWith("EditText") -> "input"
      cls.endsWith("Spinner") || isChoice(cls) -> "input"
      desc == "link" || hasUrl(n) -> "link"
      cls.endsWith("Button") || desc == "button" || desc == "botão" || desc == "botao" -> "button"
      n.isClickable -> "other"
      n.isScrollable -> "scroll"
      !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank() -> "text"
      else -> null
    }
  }

  private fun textFor(n: AccessibilityNodeInfo, role: String): String = when (role) {
    "input" -> inputLabel(n)
    "scroll" -> n.contentDescription?.toString().orEmpty()
    else -> (n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString()).orEmpty()
  }

  /**
   * Para campos, somente rótulo: nunca o texto digitado ou a opção escolhida.
   * Se não der para separar rótulo de valor, devolve vazio.
   */
  private fun inputLabel(n: AccessibilityNodeInfo): String {
    val cls = n.className?.toString().orEmpty()
    if (isChoice(cls) && !n.isPassword) {
      // Caixa de seleção / opção: o texto é o rótulo; o valor é apenas marcado/desmarcado.
      return (n.text ?: n.contentDescription)?.toString().orEmpty()
    }
    val value = n.text?.toString()?.trim().orEmpty()
    val labeledBy = try {
      n.labeledBy?.let { (it.text ?: it.contentDescription)?.toString() }
    } catch (e: RuntimeException) {
      null
    }
    val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null
    return listOfNotNull(labeledBy, hint, n.contentDescription?.toString())
      .map { it.trim() }
      .firstOrNull { it.isNotEmpty() && it != value }
      .orEmpty()
  }

  private fun isChoice(cls: String) =
    cls.endsWith("CheckBox") || cls.endsWith("RadioButton") || cls.endsWith("Switch")

  private fun roleDescription(n: AccessibilityNodeInfo): String? = try {
    n.extras?.getCharSequence("AccessibilityNodeInfo.roleDescription")?.toString()?.trim()?.lowercase()
  } catch (e: RuntimeException) {
    null
  }

  private fun hasUrl(n: AccessibilityNodeInfo): Boolean {
    val text = n.text as? Spanned ?: return false
    return text.getSpans(0, text.length, URLSpan::class.java).isNotEmpty()
  }

  /** Quando a página não é encontrada como WebView, pula a interface do navegador. */
  private fun isBrowserUi(n: AccessibilityNodeInfo): Boolean {
    val id = n.viewIdResourceName ?: return false
    if (!id.startsWith("$CHROME_PACKAGE:id/")) return false
    return BROWSER_UI_IDS.any { id.contains(it) }
  }

  private fun clean(s: String): String {
    var t = WHITESPACE.replace(s, " ").trim()
    if (t.length > MAX_TEXT) {
      t = t.substring(0, MAX_TEXT)
      if (t.isNotEmpty() && Character.isHighSurrogate(t.last())) t = t.dropLast(1)
      t = t.trimEnd()
    }
    return t
  }

  companion object {
    const val MAX_ELEMENTS = 80
    const val MAX_TEXT = 160
    private const val MAX_CANDIDATES = 400
    private const val MAX_VISITED = 4000
    private const val MAX_DEPTH = 80
    private const val MIN_SIZE_PX = 8

    private const val PRIORITY_LABELED = 0
    private const val PRIORITY_TEXT = 1
    private const val PRIORITY_UNLABELED = 2
    private const val PRIORITY_SCROLL = 3

    private val WHITESPACE = Regex("\\s+")
    private val BROWSER_UI_IDS = listOf("toolbar", "url_bar", "location_bar", "tab_switcher", "menu_button", "home_button", "bottom_container")
  }
}
