package io.qingledger.paymentsimulator

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import android.text.InputType
import java.math.BigDecimal

/** Separate, offline fixture app. It does not imitate WeChat's package or perform payments. */
class MainActivity : Activity() {
    private lateinit var body: LinearLayout
    private var amount = "18.88"
    private var scenario = "HOME"
    private var refresh = 0
    private var paymentMethod = "微信零钱"
    private var outcome = "EXPENSE"
    private val green = Color.rgb(7, 193, 96)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        amount = savedInstanceState?.getString("amount") ?: amount
        scenario = savedInstanceState?.getString("scenario") ?: scenario
        paymentMethod = savedInstanceState?.getString("method") ?: paymentMethod
        outcome = savedInstanceState?.getString("outcome") ?: outcome
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("amount", amount); outState.putString("scenario", scenario)
        outState.putString("method", paymentMethod)
        outState.putString("outcome", outcome)
        super.onSaveInstanceState(outState)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun render() {
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        scroll.addView(body)
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom); insets
        }
        scroll.requestApplyInsets()
        label("微信风格 · 测试页面", 20)
        label("仅模拟，无资金变动", 15, Color.DKGRAY)
        if (scenario == "HOME") {
            label("金额（元）", 16)
            val input = EditText(this).apply {
                setText(amount)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                contentDescription = "测试金额"
                gravity = Gravity.CENTER
            }
            body.addView(input, LinearLayout.LayoutParams(-1, dp(56)))
            listOf("EXPENSE" to "模拟付款（先选择）", "INCOME" to "模拟收款成功", "FAILURE" to "模拟失败流程", "MULTIPLE" to "模拟多金额结果", "DIRECT" to "直接显示成功（不经过确认）").forEach { (key, text) ->
                button(text) {
                    val parsed = runCatching { BigDecimal(input.text.toString()).setScale(2, java.math.RoundingMode.UNNECESSARY) }.getOrNull()
                    if (parsed == null || parsed.signum() <= 0 || parsed > BigDecimal("9999999999.99")) input.error = "请输入有效金额，最多两位小数"
                    else {
                        amount = parsed.toPlainString(); outcome = key; refresh = 0
                        scenario = when (key) { "INCOME" -> "INCOME"; "DIRECT" -> "EXPENSE"; else -> "CONFIRM" }
                        render()
                    }
                }
            }
            label("这是轻账无障碍回归用的独立软件，不是微信。", 14, Color.GRAY)
        } else if (scenario == "CONFIRM") {
            label("确认付款", 24)
            label("¥$amount", 42)
            label("选择付款方式", 18)
            val methods = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            listOf("微信零钱", "测试银行储蓄卡（1234）", "测试银行储蓄卡（5678）", "未提供付款方式").forEach { name ->
                val option = RadioButton(this).apply {
                    id = View.generateViewId(); text = name; textSize = 16f
                    isChecked = name == paymentMethod
                    setOnClickListener { paymentMethod = name }
                }
                methods.addView(option, RadioGroup.LayoutParams(-1, dp(48)))
            }
            body.addView(methods, LinearLayout.LayoutParams(-1, -2))
            button("确认支付") { scenario = outcome; render() }
            button("模拟失败") { scenario = "FAILURE"; render() }
            button("取消本次") { scenario = "CANCELLED"; render() }
        } else {
            label(if (scenario in setOf("FAILURE", "CANCELLED")) "×" else "✓", 64, green)
            label(when (scenario) { "INCOME" -> "收款成功"; "FAILURE" -> "付款失败"; "CANCELLED" -> "已取消"; else -> "支付成功" }, 24, green)
            label("¥$amount", 42)
            label("商户：测试便利店", 17)
            label("场景：$scenario", 14, Color.GRAY)
            if (scenario == "MULTIPLE") label("余额：99.99元", 17)
            val status = label("页面刷新次数：$refresh", 14, Color.GRAY)
            button("重复刷新页面（同笔）") { refresh++; status.text = "页面刷新次数：$refresh" }
            button("返回测试首页") { scenario = "HOME"; render() }
        }
    }
    private fun label(text: String, size: Int, color: Int = Color.BLACK): TextView = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER
        body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    }
    private fun button(text: String, action: () -> Unit) {
        body.addView(Button(this).apply { this.text = text; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(18) })
    }
}
