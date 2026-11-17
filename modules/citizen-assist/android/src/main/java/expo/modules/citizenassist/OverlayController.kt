package expo.modules.citizenassist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Janelas do assistente sobre o Chrome, do tipo TYPE_ACCESSIBILITY_OVERLAY
 * (ligadas ao serviço; não exigem SYSTEM_ALERT_WINDOW).
 *  - painel compacto: mensagem + "Próximo passo" + "Encerrar" (+ "Responder" quando há pergunta);
 *  - destaque: janela de tela cheia que NÃO recebe toques, só desenha a moldura.
 * Todos os métodos devem ser chamados na thread principal.
 */
class OverlayController(
  private val context: Context,
  private val onNext: () -> Unit,
  private val onAnswer: () -> Unit,
  private val onEnd: () -> Unit
) {
  private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val density = context.resources.displayMetrics.density

  private val container = FrameLayout(context)
  private val messageView = TextView(context)
  private val hintView = TextView(context)
  private val nextButton = Button(context)
  private val answerButton = Button(context)
  private val endButton = Button(context)
  private var panelAdded = false
  private var highlightView: HighlightView? = null

  private val panelParams = WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT
  ).apply {
    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    title = "CitizenTech - painel"
  }

  init {
    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(14), dp(16), dp(14))
      background = GradientDrawable().apply {
        setColor(Color.parseColor("#F21B1B1F"))
        cornerRadius = dp(20).toFloat()
        setStroke(dp(2), Color.parseColor("#FFD54F"))
      }
    }

    messageView.apply {
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
      setLineSpacing(0f, 1.15f)
      maxLines = 5
      ellipsize = android.text.TextUtils.TruncateAt.END
      accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    hintView.apply {
      setTextColor(Color.parseColor("#FFE082"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      visibility = View.GONE
      setPadding(0, dp(6), 0, 0)
    }
    card.addView(messageView)
    card.addView(hintView)

    val row = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      setPadding(0, dp(12), 0, 0)
    }
    styleButton(nextButton, "Próximo passo", "#FFD54F", Color.BLACK)
    styleButton(answerButton, "Responder", "#90CAF9", Color.BLACK)
    styleButton(endButton, "Encerrar", "#C62828", Color.WHITE)
    answerButton.visibility = View.GONE

    nextButton.setOnClickListener { onNext() }
    answerButton.setOnClickListener { onAnswer() }
    endButton.setOnClickListener { onEnd() }

    row.addView(nextButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
    row.addView(answerButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { marginStart = dp(8) })
    row.addView(endButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
    card.addView(row)

    container.setPadding(dp(12), dp(12), dp(12), dp(28))
    container.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  }

  fun showPanel() {
    if (!panelAdded) {
      wm.addView(container, panelParams)
      panelAdded = true
    }
    container.visibility = View.VISIBLE
  }

  /** Esconde enquanto a caixa de resposta está aberta (não remove a janela). */
  fun hidePanel() {
    if (panelAdded) container.visibility = View.GONE
  }

  fun setMessage(text: String) {
    messageView.text = text
    messageView.contentDescription = text
  }

  fun setHint(text: String?) {
    hintView.text = text.orEmpty()
    hintView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
  }

  /** Bloqueia múltiplos toques enquanto uma orientação está em andamento. */
  fun setBusy(busy: Boolean) {
    nextButton.isEnabled = !busy
    nextButton.alpha = if (busy) 0.55f else 1f
    nextButton.text = if (busy) "Aguarde…" else "Próximo passo"
    answerButton.isEnabled = !busy
  }

  fun setAnswerVisible(visible: Boolean) {
    answerButton.visibility = if (visible) View.VISIBLE else View.GONE
  }

  fun showHighlight(screenRect: Rect) {
    clearHighlight()
    val view = HighlightView(context, Rect(screenRect))
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      title = "CitizenTech - destaque"
      if (Build.VERSION.SDK_INT >= 28) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
    }
    wm.addView(view, params)
    highlightView = view
  }

  fun clearHighlight() {
    highlightView?.let { safeRemove(it) }
    highlightView = null
  }

  /** Mantém o painel longe do elemento destacado (em cima ou embaixo da tela). */
  fun placePanelAwayFrom(rect: Rect?) {
    val screenHeight = context.resources.displayMetrics.heightPixels
    val top = rect != null && rect.centerY() > screenHeight / 2
    val gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
    if (panelParams.gravity == gravity) return
    panelParams.gravity = gravity
    container.setPadding(dp(12), if (top) dp(36) else dp(12), dp(12), if (top) dp(12) else dp(28))
    if (panelAdded) wm.updateViewLayout(container, panelParams)
  }

  fun removeAll() {
    clearHighlight()
    if (panelAdded) {
      safeRemove(container)
      panelAdded = false
    }
  }

  private fun safeRemove(view: View) {
    try {
      wm.removeView(view)
    } catch (e: IllegalArgumentException) {
      // A janela já tinha sido removida.
    }
  }

  private fun styleButton(button: Button, label: String, background: String, textColor: Int) {
    button.text = label
    button.contentDescription = label
    button.isAllCaps = false
    button.setTextColor(textColor)
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    button.typeface = Typeface.DEFAULT_BOLD
    button.minHeight = dp(56)
    button.minimumHeight = dp(56)
    button.setPadding(dp(8), dp(8), dp(8), dp(8))
    button.background = GradientDrawable().apply {
      setColor(Color.parseColor(background))
      cornerRadius = dp(14).toFloat()
    }
  }

  private fun dp(value: Int): Int = (value * density).roundToInt()

  /** Desenha a moldura convertendo coordenadas de tela para as coordenadas da própria janela. */
  private class HighlightView(context: Context, private val target: Rect) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 11 * density
      color = Color.WHITE
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 6 * density
      color = Color.parseColor("#FF6D00")
    }
    private val location = IntArray(2)
    private val box = RectF()

    init {
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
      super.onDraw(canvas)
      getLocationOnScreen(location)
      val pad = 7 * density
      box.set(
        target.left - location[0] - pad,
        target.top - location[1] - pad,
        target.right - location[0] + pad,
        target.bottom - location[1] + pad
      )
      val radius = 14 * density
      canvas.drawRoundRect(box, radius, radius, halo)
      canvas.drawRoundRect(box, radius, radius, ring)
    }
  }
}
