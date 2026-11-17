package expo.modules.citizenassist

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Caixa curta para responder a uma pergunta do assistente (ação "ask").
 * Esta tela NUNCA é lida como se fosse a página: o leitor só aceita a janela do Chrome.
 * Depois de enviar, a pessoa volta ao Chrome e toca em "Próximo passo".
 */
class ClarifyActivity : Activity() {

  companion object {
    const val EXTRA_QUESTION = "question"
    private const val REQ_SPEECH = 4101

    @Volatile
    private var current: WeakReference<ClarifyActivity>? = null

    fun closeIfOpen() {
      current?.get()?.let { activity -> activity.runOnUiThread { activity.finish() } }
    }
  }

  private lateinit var input: EditText
  private var waitingSpeech = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    current = WeakReference(this)
    setFinishOnTouchOutside(false)

    val question = intent.getStringExtra(EXTRA_QUESTION).orEmpty()

    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(20), dp(20), dp(20), dp(20))
      setBackgroundColor(Color.WHITE)
    }

    content.addView(TextView(this).apply {
      text = "Responda ao assistente"
      setTextColor(Color.BLACK)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
      typeface = Typeface.DEFAULT_BOLD
    })
    content.addView(TextView(this).apply {
      text = question
      setTextColor(Color.parseColor("#222222"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
      setPadding(0, dp(10), 0, dp(12))
    })

    input = EditText(this).apply {
      hint = "Escreva sua resposta (sem CPF, senhas ou outros dados pessoais)"
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
      setTextColor(Color.BLACK)
      minLines = 2
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
      filters = arrayOf<InputFilter>(InputFilter.LengthFilter(500))
    }
    content.addView(input)

    val speechAvailable = packageManager.queryIntentActivities(speechIntent(), 0).isNotEmpty()
    if (speechAvailable) {
      content.addView(button("Falar resposta", "#90CAF9", Color.BLACK) { startSpeech() })
    }
    content.addView(button("Enviar", "#0B4F8A", Color.WHITE) { send() })
    content.addView(button("Cancelar", "#E0E0E0", Color.BLACK) { finish() })

    setContentView(ScrollView(this).apply { addView(content) })
    window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
  }

  override fun onResume() {
    super.onResume()
    waitingSpeech = false
  }

  override fun onStop() {
    super.onStop()
    // Se a pessoa saiu (botão início, outro app), fecha e devolve o painel ao Chrome.
    if (!waitingSpeech && !isChangingConfigurations) finish()
  }

  override fun onDestroy() {
    if (current?.get() === this) current = null
    AssistAccessibilityService.instance?.onClarifyClosed()
    super.onDestroy()
  }

  @Deprecated("Activity.onActivityResult")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == REQ_SPEECH) {
      waitingSpeech = false
      if (resultCode == RESULT_OK) {
        val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
          input.setText(spoken.take(500))
          input.setSelection(input.text.length)
        }
      }
      return
    }
    @Suppress("DEPRECATION")
    super.onActivityResult(requestCode, resultCode, data)
  }

  private fun send() {
    val text = input.text.toString().trim()
    if (text.isEmpty()) {
      input.error = "Escreva ou fale sua resposta."
      return
    }
    AssistAccessibilityService.instance?.onClarifyAnswer(text)
    finish()
  }

  private fun speechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
    putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale sua resposta")
  }

  private fun startSpeech() {
    waitingSpeech = true
    try {
      @Suppress("DEPRECATION")
      startActivityForResult(speechIntent(), REQ_SPEECH)
    } catch (e: ActivityNotFoundException) {
      waitingSpeech = false
      input.error = "Reconhecimento de voz indisponível. Escreva a resposta."
    }
  }

  private fun button(label: String, fillColor: String, labelColor: Int, onClick: () -> Unit) =
    Button(this).apply {
      text = label
      isAllCaps = false
      setTextColor(labelColor)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
      typeface = Typeface.DEFAULT_BOLD
      minHeight = dp(56)
      background = GradientDrawable().apply {
        setColor(Color.parseColor(fillColor))
        cornerRadius = dp(14).toFloat()
      }
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(10)
      }
      setOnClickListener { onClick() }
    }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
