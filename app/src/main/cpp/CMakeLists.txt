cmake_minimum_required(VERSION 3.22.1)
project(ai_core LANGUAGES C CXX)

if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    # Thêm "-mfpu=neon-fp16" để hỗ trợ vld1q_f16
    # Thêm "-mfloat-abi=softfp" để tương thích tốt hơn với các thư viện cũ
    add_compile_options(-mfpu=neon-fp16 -mfloat-abi=softfp)
endif()

add_definitions(-DLLAMA_MMAP=0)
add_definitions(-DGGML_USE_MMAP=0)

# 1. Cấu hình chuẩn C++
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 2. ĐỊNH NGHĨA ĐƯỜNG DẪN LLAMA.CPP (SỬA ĐOẠN NÀY)
# Cũ: set(LLAMACPP_DIR /home/home/dev/AI/llama.cpp)
# Mới: Sử dụng biến CMAKE_CURRENT_SOURCE_DIR để lấy đường dẫn hiện tại
set(LLAMACPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# Kiểm tra xem folder có tồn tại không
if(NOT EXISTS "${LLAMACPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Could not find llama.cpp in ${LLAMACPP_DIR}")
endif()

# Tắt tính năng MMAP để tránh lỗi posix_madvise trên Android cũ
set(LLAMA_MMAP OFF CACHE BOOL "" FORCE) 
# 3. Add subdirectory
add_subdirectory(${LLAMACPP_DIR} llama-build)

# 4. Các file source của AI-Core (Đã lọc bỏ phần Image/Embed thừa)
set(SRC_FILES
        src/ai_core.cpp
        # src/ai_core_embed.cpp  <-- ĐÃ XÓA (theo yêu cầu của bạn)
        # src/ai_core_mtmd.cpp   <-- ĐÃ XÓA (theo yêu cầu của bạn)
        # src/ai_core_state.cpp  <-- ĐÃ XÓA (nếu không dùng state management)
        src/state/global_state.cpp
        src/state/model_state.cpp
        src/utils/jni_utils.cpp
        src/utils/utf8_utils.cpp
        src/chat/chat_template.cpp
        src/tool_calling/tool_call_state.cpp
        src/cpu/cpu_helper.cpp
)

# 5. Include directories
include_directories(${LLAMACPP_DIR})
include_directories(${LLAMACPP_DIR}/include)
include_directories(${LLAMACPP_DIR}/src) # Thêm dòng này để chắc chắn nhận header

# 6. Tạo thư viện ai_core
add_library(ai_core SHARED ${SRC_FILES})

# 7. Android CPU features
set(NDK_CPUFEATURES_DIR ${ANDROID_NDK}/sources/android/cpufeatures)
add_library(cpufeatures STATIC ${NDK_CPUFEATURES_DIR}/cpu-features.c)
target_include_directories(cpufeatures PUBLIC ${NDK_CPUFEATURES_DIR})

# 8. Link thư viện (SỬA ĐOẠN NÀY: XÓA mtmd)
target_link_libraries(ai_core
        PRIVATE llama
        PRIVATE ggml
        PRIVATE ggml-cpu 
        PRIVATE ggml-base 
        PRIVATE cpufeatures
        PRIVATE android
        PRIVATE log
)