package com.example.voiceagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Экран настройки: ключ API, разрешения, включение службы доступности. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var webBox: CheckBox
    private lateinit var commandField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        root.addView(label("Голосовой агент", 26f))
        status = label("", 16f)
        root.addView(status)

        root.addView(label("\n1. Ключ API Anthropic и модель", 16f))
        keyField = EditText(this).apply {
            hint = "sk-ant-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.apiKey)
        }
        root.addView(keyField)
        modelField = EditText(this).apply {
            hint = Prefs.DEFAULT_MODEL
            setText(prefs.model)
        }
        root.addView(modelField)
        webBox = CheckBox(this).apply {
            text = "Разрешить веб-поиск (серверный инструмент API)"
            isChecked = prefs.webSearch
        }
        root.addView(webBox)
        root.addView(button("Сохранить") {
            prefs.apiKey = keyField.text.toString()
            prefs.model = modelField.text.toString()
            prefs.webSearch = webBox.isChecked
            toast("Сохранено")
            refreshStatus()
        })

        root.addView(label("\n2. Микрофон", 16f))
        root.addView(button("Выдать доступ к микрофону") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })

        root.addView(label("\n3. Служба доступности", 16f))
        root.addView(
            label(
                "На Android 13+ для приложений, установленных не из магазина, сначала нужно " +
                    "открыть «Сведения о приложении», нажать ⋮ в углу и выбрать " +
                    "«Разрешить ограниченные настройки». Только потом включится переключатель.",
                14f
            )
        )
        root.addView(button("Сведения о приложении") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("Открыть настройки доступности") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(label("\n4. Проверка текстом", 16f))
        commandField = EditText(this).apply { hint = "например: открой YouTube" }
        root.addView(commandField)
        root.addView(button("Выполнить") {
            val service = AgentAccessibilityService.instance
            if (service == null) {
                toast("Служба доступности не включена")
            } else {
                service.startTask(commandField.text.toString())
            }
        })

        root.addView(
            label(
                "\nКак пользоваться: после настройки на экране появится синяя кнопка с микрофоном. " +
                    "Коснитесь, скажите команду. Повторное касание останавливает.\n\n" +
                    "Приватность: пока агент работает, текст с вашего экрана отправляется в API Anthropic. " +
                    "Поля паролей скрываются. Платежи, отправка и удаление требуют вашего подтверждения.",
                14f
            )
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val key = if (prefs.apiKey.isNotBlank()) "✅" else "❌"
        val mic = if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "✅" else "❌"
        val acc = if (isServiceEnabled()) "✅" else "❌"
        status.text = "$key Ключ API\n$mic Микрофон\n$acc Служба доступности"
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName) && enabled.contains("AgentAccessibilityService")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun label(t: String, size: Float) = TextView(this).apply {
        text = t
        textSize = size
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }
}
