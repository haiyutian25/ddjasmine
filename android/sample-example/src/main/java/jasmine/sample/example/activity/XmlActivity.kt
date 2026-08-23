package jasmine.sample.example.activity

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RatingBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import com.lhzkml.jasmine.core.plugin.component.BasePluginActivity

/** 使用传统 XML 布局的插件 Activity（findViewById，无 ViewBinding）。 */
class XmlActivity : BasePluginActivity() {

    private lateinit var ivBack: ImageButton
    private lateinit var tvFeedback: TextView
    private lateinit var editText: EditText
    private lateinit var controlSwitch: Switch
    private lateinit var checkbox: CheckBox
    private lateinit var radioGroup: RadioGroup
    private lateinit var radioButton1: RadioButton
    private lateinit var seekBar: SeekBar
    private lateinit var progressBar: ProgressBar
    private lateinit var ratingBar: RatingBar
    private lateinit var spinner: Spinner
    private lateinit var toggleButton: ToggleButton
    private lateinit var numberPicker: NumberPicker
    private lateinit var imageView: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proxy?.setContentView(jasmine.sample.example.R.layout.activity_xml)
        ivBack = proxy!!.findViewById(jasmine.sample.example.R.id.ivBack)
        tvFeedback = proxy!!.findViewById(jasmine.sample.example.R.id.tvFeedback)
        editText = proxy!!.findViewById(jasmine.sample.example.R.id.editText)
        controlSwitch = proxy!!.findViewById(jasmine.sample.example.R.id.controlSwitch)
        checkbox = proxy!!.findViewById(jasmine.sample.example.R.id.checkbox)
        radioGroup = proxy!!.findViewById(jasmine.sample.example.R.id.radioGroup)
        radioButton1 = proxy!!.findViewById(jasmine.sample.example.R.id.radioButton1)
        seekBar = proxy!!.findViewById(jasmine.sample.example.R.id.seekBar)
        progressBar = proxy!!.findViewById(jasmine.sample.example.R.id.progressBar)
        ratingBar = proxy!!.findViewById(jasmine.sample.example.R.id.ratingBar)
        spinner = proxy!!.findViewById(jasmine.sample.example.R.id.spinnerOptions)
        toggleButton = proxy!!.findViewById(jasmine.sample.example.R.id.toggleButton)
        numberPicker = proxy!!.findViewById(jasmine.sample.example.R.id.numberPicker)
        imageView = proxy!!.findViewById(jasmine.sample.example.R.id.imageView)

        ivBack.setOnClickListener { proxy?.finish() }
        val button: Button = proxy!!.findViewById(jasmine.sample.example.R.id.buttonShowToast)
        button.setOnClickListener {
            val inputText = editText.text.toString()
            val message = if (inputText.isNotBlank()) "输入内容为: $inputText" else "您没有输入任何内容"
            showToast(message)
            updateFeedbackText("点击了按钮")
        }
        controlSwitch.setOnCheckedChangeListener { _, isChecked ->
            editText.isEnabled = isChecked
            updateFeedbackText("开关: 输入框已 ${if (isChecked) "启用" else "禁用"}")
        }
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            updateFeedbackText("复选框: 状态变为 $isChecked")
        }
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedText = if (checkedId == radioButton1.id) "选项A" else "选项B"
            updateFeedbackText("单选组: 选择了 $selectedText")
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                progressBar.progress = progress
                if (fromUser) updateFeedbackText("SeekBar 拖动中: $progress%", false)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val finalProgress = seekBar?.progress ?: 0
                showToast("最终进度: $finalProgress%")
                updateFeedbackText("SeekBar 最终进度: $finalProgress%")
            }
        })
        ratingBar.setOnRatingBarChangeListener { _, rating, fromUser ->
            if (fromUser) updateFeedbackText("评分条: 选择了 $rating 星")
        }

        // Spinner 下拉选择
        val options = arrayOf("选项一", "选项二", "选项三", "选项四")
        spinner.adapter = ArrayAdapter(
            proxy!!,
            android.R.layout.simple_spinner_dropdown_item,
            options,
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFeedbackText("Spinner: 选择了 ${options[position]}")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ToggleButton 切换按钮
        toggleButton.setOnCheckedChangeListener { _, isChecked ->
            updateFeedbackText("ToggleButton: ${if (isChecked) "已开启" else "已关闭"}")
        }

        // NumberPicker 数字选择器
        numberPicker.minValue = 0
        numberPicker.maxValue = 100
        numberPicker.value = 50
        numberPicker.setOnValueChangedListener { _, _, newVal ->
            updateFeedbackText("NumberPicker: 选择了 $newVal", false)
        }

        // ImageView 图片点击
        imageView.setOnClickListener {
            showToast("点击了图片")
            updateFeedbackText("ImageView: 图片被点击")
        }
    }

    private fun updateFeedbackText(message: String, alsoToast: Boolean = false) {
        tvFeedback.text = message
        if (alsoToast) showToast(message)
    }

    private fun showToast(message: String) {
        Toast.makeText(proxy, message, Toast.LENGTH_SHORT).show()
    }
}
