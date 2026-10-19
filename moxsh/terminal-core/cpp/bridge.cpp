// moxsh C++ JNI 桥接层（D9：C/C++ 仅做与 Android 的桥接，最小存在）。
// 本文件只把 JVM 类型翻译成 Rust 的 C-ABI（moxsh_*），不承载任何业务逻辑。
#include <jni.h>
#include <cstdint>
#include <string>

extern "C" {
    void* moxsh_open_pty(const char* cmd, int cols, int rows);
    long  moxsh_pump(void* sess, uint8_t* buf, size_t len);
    long  moxsh_write(void* sess, const uint8_t* buf, size_t len);
    int   moxsh_resize(void* sess, int cols, int rows);
    void  moxsh_close(void* sess);
    int   moxsh_session_exit_status(void* sess);
    int   moxsh_screen_rows(void* sess);
    int   moxsh_screen_cols(void* sess);
    int   moxsh_total_rows(void* sess);
    int   moxsh_copy_cells(void* sess, int start_row, int count, uint8_t* buf, size_t buflen);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_moxsh_core_TerminalCore_nativeOpenPty(JNIEnv* env, jobject,
                                                jstring cmd, jint cols, jint rows) {
    const char* c = env->GetStringUTFChars(cmd, nullptr);
    void* s = moxsh_open_pty(c, cols, rows);
    env->ReleaseStringUTFChars(cmd, c);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_TerminalCore_nativePump(JNIEnv* env, jobject,
                                            jlong sess, jbyteArray buf) {
    auto* s = reinterpret_cast<void*>(sess);
    jsize len = env->GetArrayLength(buf);
    jbyte* p = env->GetByteArrayElements(buf, nullptr);
    long n = moxsh_pump(s, reinterpret_cast<uint8_t*>(p), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(buf, p, JNI_ABORT);
    return static_cast<jint>(n);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_TerminalCore_nativeWrite(JNIEnv* env, jobject,
                                             jlong sess, jbyteArray buf) {
    auto* s = reinterpret_cast<void*>(sess);
    jsize len = env->GetArrayLength(buf);
    jbyte* p = env->GetByteArrayElements(buf, nullptr);
    long n = moxsh_write(s, reinterpret_cast<uint8_t*>(p), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(buf, p, JNI_ABORT);
    return static_cast<jint>(n);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_TerminalCore_nativeResize(JNIEnv*, jobject,
                                              jlong sess, jint cols, jint rows) {
    return moxsh_resize(reinterpret_cast<void*>(sess), cols, rows);
}

extern "C" JNIEXPORT void JNICALL
Java_com_moxsh_core_TerminalCore_nativeClose(JNIEnv*, jobject, jlong sess) {
    moxsh_close(reinterpret_cast<void*>(sess));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_TerminalCore_nativeSessionExitStatus(JNIEnv*, jobject, jlong sess) {
    return moxsh_session_exit_status(reinterpret_cast<void*>(sess));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_TerminalCore_nativeScreenRows(JNIEnv*, jobject, jlong sess) {
    return moxsh_screen_rows(reinterpret_cast<void*>(sess));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_TerminalCore_nativeScreenCols(JNIEnv*, jobject, jlong sess) {
    return moxsh_screen_cols(reinterpret_cast<void*>(sess));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_TerminalCore_nativeTotalRows(JNIEnv*, jobject, jlong sess) {
    return moxsh_total_rows(reinterpret_cast<void*>(sess));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_TerminalCore_nativeCopyCells(JNIEnv* env, jobject,
                                                 jlong sess, jint startRow,
                                                 jint count, jbyteArray buf) {
    auto* s = reinterpret_cast<void*>(sess);
    jsize len = env->GetArrayLength(buf);
    jbyte* p = env->GetByteArrayElements(buf, nullptr);
    int n = moxsh_copy_cells(s, startRow, count, reinterpret_cast<uint8_t*>(p),
                             static_cast<size_t>(len));
    env->ReleaseByteArrayElements(buf, p, 0); // 提交写入的数据
    return n;
}
