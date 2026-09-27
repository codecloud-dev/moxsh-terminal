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
    int   moxsh_screen_rows(void* sess);
    int   moxsh_screen_cols(void* sess);
    int   moxsh_total_rows(void* sess);
    int   moxsh_copy_cells(void* sess, int start_row, int count, uint8_t* buf, size_t buflen);

    // M4 PRoot 容器引擎（D12）：C-ABI 转发声明。
    int moxsh_proot_install(const char* id, const char* moxsh_root, const char* cache_tar);
    int moxsh_proot_remove(const char* id, const char* moxsh_root);
    int moxsh_proot_login(const char* id, const char* moxsh_root, char* out, size_t cap);
    int moxsh_proot_list_installed(const char* moxsh_root, char* out, size_t cap);
    int moxsh_proot_backup(const char* id, const char* moxsh_root, const char* out_tar);
    int moxsh_proot_restore(const char* id, const char* moxsh_root, const char* tar_path);
    int moxsh_proot_translate_selftest(void);
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

// ================= M4 PRoot 容器引擎转发（ProotNative.kt 的 JNI 入口） =================
// 惯例沿用上文：jstring ↔ const char*，jbyteArray ↔ char* 缓冲（GetStringUTFChars/
// GetByteArrayElements + 对应 Release）。返回值统一为 Rust C-ABI 的错误码/长度。

namespace {
// 取 jstring 的 UTF-8；可选参数（null 透传 null）。
const char* jstr(JNIEnv* env, jstring s) {
    return s == nullptr ? nullptr : env->GetStringUTFChars(s, nullptr);
}
void jstrFree(JNIEnv* env, jstring s, const char* c) {
    if (s != nullptr && c != nullptr) env->ReleaseStringUTFChars(s, c);
}
// 字符串返回约定：Rust 经 GetByteArrayElements 指针直接写入（Release 传 0 提交），
// 返回值原样透传：>0 所需长度 / 0 缓冲不足 / <0 错误码。
jint stringReturn(JNIEnv*, jbyteArray, int n) {
    return static_cast<jint>(n);
}
} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_ProotNative_nativeInstall(JNIEnv* env, jobject,
                                               jstring id, jstring moxshRoot, jstring cacheTar) {
    const char* i = jstr(env, id);
    const char* r = jstr(env, moxshRoot);
    const char* t = jstr(env, cacheTar);
    int n = moxsh_proot_install(i, r, t);
    jstrFree(env, id, i); jstrFree(env, moxshRoot, r); jstrFree(env, cacheTar, t);
    return n;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_ProotNative_nativeRemove(JNIEnv* env, jobject,
                                              jstring id, jstring moxshRoot) {
    const char* i = jstr(env, id);
    const char* r = jstr(env, moxshRoot);
    int n = moxsh_proot_remove(i, r);
    jstrFree(env, id, i); jstrFree(env, moxshRoot, r);
    return n;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_ProotNative_nativeLogin(JNIEnv* env, jobject,
                                             jstring id, jstring moxshRoot, jbyteArray buf) {
    const char* i = jstr(env, id);
    const char* r = jstr(env, moxshRoot);
    jsize cap = env->GetArrayLength(buf);
    jbyte* p = env->GetByteArrayElements(buf, nullptr);
    int n = moxsh_proot_login(i, r, reinterpret_cast<char*>(p), static_cast<size_t>(cap));
    env->ReleaseByteArrayElements(buf, p, 0);
    jstrFree(env, id, i); jstrFree(env, moxshRoot, r);
    return stringReturn(env, buf, n);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_ProotNative_nativeListInstalled(JNIEnv* env, jobject,
                                                     jstring moxshRoot, jbyteArray buf) {
    const char* r = jstr(env, moxshRoot);
    jsize cap = env->GetArrayLength(buf);
    jbyte* p = env->GetByteArrayElements(buf, nullptr);
    int n = moxsh_proot_list_installed(r, reinterpret_cast<char*>(p), static_cast<size_t>(cap));
    env->ReleaseByteArrayElements(buf, p, 0);
    jstrFree(env, moxshRoot, r);
    return stringReturn(env, buf, n);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_ProotNative_nativeBackup(JNIEnv* env, jobject,
                                              jstring id, jstring moxshRoot, jstring outTar) {
    const char* i = jstr(env, id);
    const char* r = jstr(env, moxshRoot);
    const char* o = jstr(env, outTar);
    int n = moxsh_proot_backup(i, r, o);
    jstrFree(env, id, i); jstrFree(env, moxshRoot, r); jstrFree(env, outTar, o);
    return n;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_ProotNative_nativeRestore(JNIEnv* env, jobject,
                                               jstring id, jstring moxshRoot, jstring tarPath) {
    const char* i = jstr(env, id);
    const char* r = jstr(env, moxshRoot);
    const char* t = jstr(env, tarPath);
    int n = moxsh_proot_restore(i, r, t);
    jstrFree(env, id, i); jstrFree(env, moxshRoot, r); jstrFree(env, tarPath, t);
    return n;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moxsh_core_ProotNative_nativeTranslateSelfTest(JNIEnv*, jobject) {
    return moxsh_proot_translate_selftest();
}
