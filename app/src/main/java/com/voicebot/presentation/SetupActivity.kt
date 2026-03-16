package com.voicebot.presentation

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.voicebot.databinding.ActivitySetupBinding
import com.voicebot.domain.model.LlmType
import com.voicebot.domain.model.RagType
import com.voicebot.domain.model.SttType
import com.voicebot.domain.model.TtsType
import java.io.File

/**
 * Màn hình cấu hình — chạy trước khi load bất kỳ model nào.
 *
 * Luồng:
 *   1. User chọn LLM type → tự động scan /sdcard/Download/ tìm model phù hợp
 *   2. User chọn model cụ thể trong danh sách tìm được
 *   3. Chọn STT / RAG / TTS
 *   4. Nhấn "Khởi động" → startActivity(MainActivity) + finish()
 *
 * Quay lại từ MainActivity:
 *   MainActivity gọi goBackToSetup() → release tài nguyên → startActivity(SetupActivity)
 *   SetupActivity được tạo mới hoàn toàn → không giữ state cũ.
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LLM_TYPE           = "extra_llm_type"
        const val EXTRA_LLM_MODEL_NAME     = "extra_llm_model_name"   // filename hoặc folder name
        const val EXTRA_GEMINI_KEY         = "extra_gemini_key"
        const val EXTRA_STT_TYPE           = "extra_stt_type"
        const val EXTRA_RAG_TYPE           = "extra_rag_type"
        const val EXTRA_TTS_TYPE           = "extra_tts_type"

        // /sdcard/Download/ — nơi user đặt tất cả model file
        private val DOWNLOAD_DIRS = listOf(
            "/sdcard/Download",
            "/storage/emulated/0/Download"
        )
    }

    private lateinit var binding: ActivitySetupBinding

    /** Model đang được chọn trong rgModels (filename hoặc folder name) */
    private var selectedModelName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLlmTypeListener()
        // Kích hoạt scan ngay cho lựa chọn mặc định (LiteRT)
        onLlmTypeChanged(binding.rgLlm.checkedRadioButtonId)

        binding.btnStart.setOnClickListener { launchMain() }
    }

    // ── LLM type selection ────────────────────────────────────────────────

    private fun setupLlmTypeListener() {
        binding.rgLlm.setOnCheckedChangeListener { _, checkedId ->
            onLlmTypeChanged(checkedId)
        }
    }

    private fun onLlmTypeChanged(checkedId: Int) {
        binding.layoutGeminiKey.visibility = View.GONE
        when (checkedId) {
            binding.rbLlmGemini.id -> {
                binding.layoutGeminiKey.visibility = View.VISIBLE
                hideModelPicker()
            }
            binding.rbLlmRagOnly.id -> {
                // Không cần model file — dùng assets QA + vector
                hideModelPicker()
                binding.tvModelLabel.visibility = View.VISIBLE
                binding.tvModelLabel.text       = "✅ Dùng assets/qa_database.txt + vi_fasttext_pruned.vec"
            }
            binding.rbLlmLiteRt.id     -> scanAndShowModels(ext = "litertlm")
            binding.rbLlmNative.id     -> scanAndShowModels(ext = "gguf")
            binding.rbLlmExecuTorch.id -> scanAndShowExecuTorchFolders()
        }
    }

    // ── Model scanning ────────────────────────────────────────────────────

    /**
     * Tìm tất cả file *.{ext} trong /sdcard/Download/ và hiển thị dưới dạng RadioButton.
     * Dùng cho LiteRT (.litertlm) và NativeCpp (.gguf).
     */
    private fun scanAndShowModels(ext: String) {
        val files = DOWNLOAD_DIRS
            .flatMap { dir ->
                File(dir).listFiles { f -> f.isFile && f.name.endsWith(".$ext") }?.toList()
                    ?: emptyList()
            }
            .distinctBy { it.name }
            .sortedBy { it.name }

        showModelList(files.map { it.name }, hint = "*.${ext}")
    }

    /**
     * Tìm tất cả subfolder trong /sdcard/Download/ chứa ít nhất 1 file .pte.
     * Dùng cho ExecuTorch (mỗi model nằm trong 1 thư mục riêng).
     */
    private fun scanAndShowExecuTorchFolders() {
        val folders = DOWNLOAD_DIRS
            .flatMap { dir ->
                File(dir).listFiles { f -> f.isDirectory }?.filter { folder ->
                    // Dùng FilenameFilter tường minh để tránh ambiguity với FileFilter
                    folder.list { _, name -> name.endsWith(".pte") }?.isNotEmpty() == true
                } ?: emptyList()
            }
            .distinctBy { it.name }
            .sortedBy { it.name }

        showModelList(folders.map { it.name }, hint = "thư mục chứa .pte")
    }

    /**
     * Render danh sách model thành RadioButton trong rgModels.
     * @param names danh sách filename hoặc foldername
     * @param hint  chuỗi mô tả hiện khi không tìm thấy gì
     */
    private fun showModelList(names: List<String>, hint: String) {
        // ① Xóa listener CŨ TRƯỚC khi removeAllViews để tránh listener bắn với checkedId=-1
        //    và vô tình reset selectedModelName thành ""
        binding.rgModels.setOnCheckedChangeListener(null)
        binding.rgModels.removeAllViews()
        selectedModelName = ""

        if (names.isEmpty()) {
            binding.tvModelLabel.visibility = View.GONE
            binding.tvNoModel.visibility    = View.VISIBLE
            binding.tvNoModel.text = "⚠️  Không tìm thấy $hint nào trong /sdcard/Download/\nHãy copy file model vào thiết bị trước."
            binding.btnStart.isEnabled = false
            return
        }

        binding.tvNoModel.visibility    = View.GONE
        binding.tvModelLabel.visibility = View.VISIBLE
        binding.btnStart.isEnabled      = true

        // ② Tạo và add tất cả RadioButton CHƯA check
        val radioButtons = names.map { name ->
            RadioButton(this).apply {
                text           = name
                textSize       = 13f
                setTextColor(0xFFE5E7EB.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF10B981.toInt())
                setPadding(24, 8, 8, 8)
                id             = View.generateViewId()
            }.also { binding.rgModels.addView(it) }
        }

        // ③ Gắn listener trước khi check
        binding.rgModels.setOnCheckedChangeListener { group, checkedId ->
            selectedModelName = group.findViewById<RadioButton>(checkedId)?.text?.toString() ?: ""
        }

        // ④ Check item đầu tiên — lúc này listener đã sẵn sàng nhận event
        radioButtons.firstOrNull()?.isChecked = true
        // selectedModelName sẽ được set bởi listener ở bước ③
    }

    private fun hideModelPicker() {
        // Xóa listener trước để tránh reset selectedModelName
        binding.rgModels.setOnCheckedChangeListener(null)
        binding.rgModels.removeAllViews()
        binding.tvModelLabel.visibility = View.GONE
        binding.tvNoModel.visibility    = View.GONE
        binding.btnStart.isEnabled      = true
        selectedModelName               = ""  // Gemini không cần model name
    }

    // ── Launch ────────────────────────────────────────────────────────────

    private fun launchMain() {
        val llmType = when (binding.rgLlm.checkedRadioButtonId) {
            binding.rbLlmGemini.id      -> LlmType.GEMINI_API
            binding.rbLlmExecuTorch.id  -> LlmType.EXECUTORCH
            binding.rbLlmNative.id      -> LlmType.NATIVE_CPP
            binding.rbLlmRagOnly.id     -> LlmType.RAG_ONLY
            else                        -> LlmType.LITE_RT
        }

        // RAG_ONLY không cần model file
        if (llmType != LlmType.GEMINI_API && llmType != LlmType.RAG_ONLY && selectedModelName.isBlank()) {
            Toast.makeText(this, "Vui lòng chọn một model", Toast.LENGTH_SHORT).show()
            return
        }

        val geminiKey = binding.etGeminiKey.text?.toString()?.trim() ?: ""
        if (llmType == LlmType.GEMINI_API && geminiKey.isBlank()) {
            Toast.makeText(this, "Vui lòng nhập Gemini API Key", Toast.LENGTH_SHORT).show()
            return
        }

        val sttType = when (binding.rgStt.checkedRadioButtonId) {
            binding.rbSttSherpa.id -> SttType.SHERPA_ONNX
            else                   -> SttType.ANDROID
        }
        val ragType = when (binding.rgRag.checkedRadioButtonId) {
            binding.rbRagNone.id      -> RagType.NONE
            binding.rbRagEmbedding.id -> RagType.EMBEDDING
            else                      -> RagType.FASTTEXT
        }
        val ttsType = when (binding.rgTts.checkedRadioButtonId) {
            binding.rbTtsPiper.id -> TtsType.PIPER
            else                  -> TtsType.ANDROID
        }

        startActivity(
            Intent(this, SecondActivity::class.java).apply {
                putExtra(EXTRA_LLM_TYPE,       llmType.name)
                putExtra(EXTRA_LLM_MODEL_NAME, selectedModelName)
                putExtra(EXTRA_GEMINI_KEY,     geminiKey)
                putExtra(EXTRA_STT_TYPE,       sttType.name)
                putExtra(EXTRA_RAG_TYPE,       ragType.name)
                putExtra(EXTRA_TTS_TYPE,       ttsType.name)
            }
        )
        finish()
    }
}