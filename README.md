# 🎙️ Offline AI Voice Assistant (Android)

Một trợ lý ảo thông minh chạy **hoàn toàn trên thiết bị Android** (On-device AI), không cần kết nối internet cho các tác vụ cơ bản, đảm bảo quyền riêng tư và tốc độ phản hồi cực nhanh.

Dự án kết hợp sức mạnh của **Hybrid AI Architecture**:
*   ✅ **Local Knowledge Base:** Tìm kiếm câu trả lời tức thì (10-50ms) từ dữ liệu nội bộ.
*   ✅ **Local LLM (Qwen2.5):** Xử lý ngôn ngữ tự nhiên phức tạp ngay trên điện thoại.
*   ✅ **Streaming Response:** Phản hồi từng từ theo thời gian thực (Real-time).

---

## 📌 Tính năng chính

-   **Speech-to-Text (STT):** Sử dụng `Android SpeechRecognizer` (hỗ trợ tiếng Việt tốt nhất, nhẹ nhất).
-   **Hybrid Search Engine:**
    -   **FastText + BM25:** Tìm kiếm câu hỏi tương đồng trong tích tắc.
    -   **Local LLM (LiteRT):** Fallback xử lý các câu hỏi mở, sáng tạo (Qwen2.5-1.5B 4-bit).
-   **Smart TTS Pipeline:**
    -   Tự động chuẩn hóa số liệu ("123" -> "một trăm hai ba").
    -   **Fast Start:** Phát âm thanh ngay từ câu đầu tiên, không chờ toàn bộ text.
    -   **Sentence Queue:** Đảm bảo thứ tự phát dù xử lý song song.
-   **Performance Metrics:** Hiển thị trực quan độ trễ (TTFT, TTFA, Query Latency) ngay trên màn hình.

---

## 🧱 Kiến trúc hệ thống
![OneAI_Image1 (1)](https://github.com/user-attachments/assets/954486f8-5c86-4665-83c3-f2cae2aba9e0)

1.  **Audio Input:** `SpeechRecognizer` lắng nghe và chuyển đổi giọng nói.
2.  **Logic:**
    *   **QA Engine:** Kiểm tra `qa_database.txt` bằng embedding similarity (FastText) + Từ khóa (BM25).
    *   **LiteRT LLM:** Nếu không tìm thấy, gọi mô hình ngôn ngữ lớn chạy offline (Qwen2.5-1.5B-instruct-8bit).
3.  **TTS Pipeline:**
    *   Cắt luồng text thành các chunk ngắn theo dấu câu.
    *   Ưu tiên phát ngay cụm 2 từ đầu tiên.
    *   Đẩy vào hàng đợi `AndroidTTSManager`.

---

## ✅ Yêu cầu hệ thống

*   **Android OS:** Android 8.0 (API 26) trở lên.
*   **RAM:** Tối thiểu 4GB (Khuyến nghị 6GB+ để chạy mượt LLM).
*   **Dung lượng trống:** ~2GB (Chứa Model LLM và Vector).
*   **Kiến trúc CPU:** `arm64-v8a` (Bắt buộc cho LiteRT/TensorFlow Lite).

---

## 🚀 Quickstart (Cài đặt & Chạy)

### 1. Chuẩn bị Môi trường
*   Cài đặt **Android Studio**.
*   JDK 17.

### 2. Tải Model & Dữ liệu
Dự án cần các file dữ liệu lớn (không commit lên Git). Hãy tải và copy vào điện thoại:

| File Name | Mô tả | Vị trí đặt trên điện thoại |
| :--- | :--- | :--- |
| `qa_database.txt` | Dữ liệu câu hỏi - trả lời mẫu | `/sdcard/Download/` hoặc Assets |
| `vi_fasttext_pruned.vec` | Vector từ điển tiếng Việt rút gọn | `/sdcard/Download/` hoặc Assets |
| `https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct.litertlm` | Model LLM lượng tử hóa (1.5GB) | `/sdcard/Download/` |

*(Lưu ý: Bạn có thể đặt file trong `app/src/main/assets` để build cùng app, nhưng sẽ làm tăng kích thước APK đáng kể).*

### 3. Build & Run
1.  Clone repo:
    ```bash
    git clone https://github.com/your-username/offline-voice-bot.git
    ```
2.  Mở bằng Android Studio.
3.  Sync Gradle.
4.  Kết nối thiết bị Android thật (Emulator sẽ rất chậm với LLM).
5.  Run App (`Shift + F10`).
6.  Cấp quyền **Microphone** và **Storage** khi được hỏi.

---

## 📦 Quản lý Model

*   **LLM Model:** [Qwen2.5-1.5B-Instruct-LiteRT]((https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct)) (Format: `.litertlm`).
*   **QA Database:** File text đơn giản, định dạng:
    ```text
    Câu hỏi 1 | Câu trả lời 1
    Câu hỏi 2 | Câu trả lời 2
    ```
*   **FastText Vector:** Bản rút gọn 30k từ phổ biến nhất của [CC.vi.300.vec](https://fasttext.cc/docs/en/crawl-vectors.html).

---

## 🧪 Cách sử dụng

1.  **Bắt đầu:** Nhấn nút **"🎙️ Bắt đầu"**.
2.  **Nói chuyện:** Đặt câu hỏi (ví dụ: *"Bạn tên là gì?", "Mấy giờ rồi?", "Viết một bài thơ về mùa thu"*).
3.  **Quan sát:**
    *   **User:** Câu nói của bạn hiện lên màu xanh.
    *   **Bot:** Câu trả lời hiện ra từng từ (Streaming).
    *   **Metrics:** Xem thanh trạng thái để biết tốc độ phản hồi.
4.  **Dừng/Xóa:** Nhấn **"🛑 Dừng"** để ngắt, **"Xóa"** để reset toàn bộ hội thoại.

---

## 📊 Hiệu năng (Performance Metrics)

Các chỉ số được hiển thị real-time trên ứng dụng:

*   **Query Latency:** Thời gian tìm kiếm trong database nội bộ. (~10-50ms)
*   **TTFT (Time To First Token):** Thời gian từ lúc dứt lời đến lúc chữ đầu tiên hiện ra.
    *   Local QA: ~50ms.
    *   LiteRT LLM: ~500ms - 1.5s (tùy chip điện thoại).
*   **TTFA (Time To First Audio):** Thời gian từ lúc dứt lời đến lúc nghe tiếng nói.

---

## 🔐 Quyền riêng tư (Privacy)

*   **Offline First:** Toàn bộ dữ liệu giọng nói và văn bản được xử lý nội bộ.
*   **Permissions:**
    *   `RECORD_AUDIO`: Để thu âm.
    *   `READ_EXTERNAL_STORAGE`: Để đọc file Model.
    *   `INTERNET`: (Optional) Cho SpeechRecognizer của Google.

---

## 🛠 Troubleshooting (Lỗi thường gặp)

1.  **Lỗi: "Model file not found"**
    *   -> Kiểm tra xem bạn đã copy file vào đúng thư mục `/sdcard/Download/` chưa.
    *   -> Kiểm tra quyền truy cập bộ nhớ (Settings -> App -> Permissions).

2.  **Lỗi: Crash ngay khi mở (Native Library Error)**
    *   -> Đảm bảo bạn đang chạy trên thiết bị ARM64 (`arm64-v8a`). Emulator x86 sẽ không chạy được thư viện LiteRT.

---

## 📄 License & Credits

*   **Source Code:** MIT License.
*   **Sherpa-Onnx:** Apache 2.0.
*   **Google AI Edge (LiteRT):** Apache 2.0.
*   **Model Qwen2.5:** Alibaba Cloud (Tongyi Qianwen License).

---
