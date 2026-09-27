package com.moxsh.plugin.store

import android.content.Context
import com.moxsh.shared.MoxPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * .mox 商店签名密钥（D15/D17）。
 *
 * 占位常量：与 Rust 侧测试及内置包构建脚本的 secret 一致。发布版改为从
 * Android Keystore 派生（M6 落地），硬编码仅用于开发期本地包验签。
 */
object StoreSecrets {
    const val OFFICIAL: String = "moxsh-official-store-v1"

    /**
     * P1 修复：插件 id 白名单。id 来自包内 manifest（作者可控）/云端清单，
     * 直接拼安装/缓存路径会路径逃逸。规则：仅字母数字与 `._-`，不以 `.` 开头。
     */
    fun validId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 && !id.startsWith(".") &&
            id.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
}

/**
 * 商店条目（目录卡片的渲染数据）。字段与 .mox manifest（terminal-core/src/
 * moxpkg.rs 规范头）对齐，外加 [sizeBytes]（卡片显示"约 x MB"）。
 */
data class StoreEntry(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    /** "plugin" | "skill" | "theme" | "rootfs"。 */
    val type: String,
    val minAppVersion: String,
    val permissions: List<String>,
    /** D17 预留：0=免费；>0 卡片右上角"¥x 预留"徽章，无支付流程。 */
    val price: Double,
    /** D17 预留：已购标记（支付接入后由商店回写）。 */
    val purchased: Boolean,
    val sizeBytes: Long,
)

/** 商店数据源统一契约（D16：目录浏览 + 包下载）。 */
interface StoreApi {
    /** 拉取目录（浏览页数据源）。失败抛异常，由上层 runCatching 兜底。 */
    suspend fun catalog(): List<StoreEntry>

    /** 按 id 下载（或定位）.mox 包文件，返回本地可读文件。 */
    suspend fun download(id: String): File
}

/**
 * 本地源：扫 `/sdcard/Download/moxsh-store/` 目录下的 .mox 包（用户手动放的包，离线安装）。
 *
 * Android 10+ 无法直接读 Download 子目录，生产实现经 MediaStore.Downloads 或
 * SAF 列目录；骨架先按直路径扫描，真机不可读时返回空目录（不抛错）。
 */
class LocalStoreApi(
    private val dir: File = File("/sdcard/Download/moxsh-store"),
) : StoreApi {

    override suspend fun catalog(): List<StoreEntry> = withContext(Dispatchers.IO) {
        val files = runCatching { dir.listFiles { f -> f.name.endsWith(".mox") } }
            .getOrNull() ?: return@withContext emptyList()
        files.orEmpty().mapNotNull { f ->
            // 用 native 读 manifest（顺带确认是合法 .mox；坏包直接跳过不展示）。
            MoxPackage.readManifest(f.absolutePath)?.let { m ->
                StoreEntry(
                    id = m.id, name = m.name, version = m.version,
                    author = m.author, description = m.description,
                    type = m.type, minAppVersion = m.minAppVersion,
                    permissions = m.permissions, price = m.price,
                    purchased = m.purchased, sizeBytes = f.length(),
                )
            }
        }
    }

    override suspend fun download(id: String): File = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.endsWith(".mox") }
            .orEmpty()
            .firstOrNull { f -> MoxPackage.readManifest(f.absolutePath)?.id == id }
            ?: throw IllegalArgumentException("本地源没有 id=$id 的 .mox 包")
    }
}

/**
 * 云端源骨架（D16）：
 *  - GET `<base>/catalog.json`（数组，元素即 .mox manifest + sizeBytes）
 *  - GET `<base>/pkg/<id>.mox`
 *
 * **服务器后建**（roadmap M6 后）：URL 可配（[baseUrl] 参数 / 远程配置下发），
 * 目录拉取失败由 [StoreRepository.loadCatalog] 静默回退到内置目录。
 * 下载用 HttpURLConnection（零依赖），超时 10s，落 cacheDir 临时文件。
 */
class CloudStoreApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val cacheDir: File,
) : StoreApi {

    companion object {
        /** 云源地址：可配项（设置页/远程配置），上线前由服务器提供。 */
        const val DEFAULT_BASE_URL = "https://store.moxsh.local/v1"
        private const val TIMEOUT_MS = 10_000
    }

    override suspend fun catalog(): List<StoreEntry> = withContext(Dispatchers.IO) {
        val json = httpGet("$baseUrl/catalog.json")
        val arr = JSONObject(json).optJSONArray("entries") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            StoreEntry(
                id = o.getString("id"), name = o.getString("name"),
                version = o.getString("version"), author = o.optString("author"),
                description = o.optString("description"), type = o.getString("type"),
                minAppVersion = o.optString("minAppVersion"),
                permissions = o.optJSONArray("permissions")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList(),
                price = o.optDouble("price", 0.0),
                purchased = o.optBoolean("purchased", false),
                sizeBytes = o.optLong("sizeBytes", 0L),
            )
        }
    }

    override suspend fun download(id: String): File = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val out = File(cacheDir, "store-$id.mox")
        val url = URL("$baseUrl/pkg/$id.mox")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("云端源下载失败：HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { input -> out.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            conn.disconnect()
        }
        out
    }

    /** 极简 GET 文本（骨架用；大文件走 [download] 的流式拷贝）。 */
    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("云端源目录拉取失败：HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * 内置目录（D16 兜底）：4 个示例条目，覆盖 plugin/skill/theme 三种 type，
 * 标注 type 与 permissions；云端/本地源都不可用时商店仍有内容可浏览。
 *
 * 这些条目的 .mox 包由 CI 构建脚本产出并放进本地源/云源；包未就绪时
 * 点"一键安装"会在进度卡提示"包源未配置"（见 StoreScreen 注释）。
 */
object BuiltinCatalog {

    fun entries(): List<StoreEntry> = listOf(
        StoreEntry(
            id = "float.terminal", name = "浮动终端", version = "1.2.0",
            author = "moxsh", description = "液态玻璃悬浮窗终端，可拖拽/多窗。",
            type = "plugin", minAppVersion = "0.1.0",
            permissions = listOf("run_command"), price = 0.0, purchased = false,
            sizeBytes = 1_048_576L,
        ),
        StoreEntry(
            id = "theme.aurora", name = "极光主题包", version = "0.9.1",
            author = "moxsh", description = "极光青配色玻璃主题 + 终端色板。",
            type = "theme", minAppVersion = "0.1.0",
            permissions = emptyList(), price = 6.0, purchased = false,
            sizeBytes = 262_144L,
        ),
        StoreEntry(
            id = "skill.python", name = "Python 技能", version = "0.3.0",
            author = "moxsh", description = "AI 技能包：装 Python 环境并教写脚本。",
            type = "skill", minAppVersion = "0.1.0",
            permissions = listOf("install_distro", "install_pkg", "run_command"),
            price = 0.0, purchased = false,
            sizeBytes = 524_288L,
        ),
        StoreEntry(
            id = "sys.monitor", name = "系统监控", version = "1.0.0",
            author = "moxsh", description = "CPU/内存/磁盘实时曲线玻璃面板。",
            type = "plugin", minAppVersion = "0.1.0",
            permissions = listOf("run_command"), price = 0.0, purchased = false,
            sizeBytes = 393_216L,
        ),
    )
}

/**
 * 商店目录聚合（D16）：本地源 + 云源 + 内置目录合并去重（id 相同时
 * 优先本地 > 云端 > 内置）。任一源失败静默跳过——商店必须永远能打开。
 */
object StoreRepository {

    /** 默认云源（URL 可配，服务器后建；失败回退内置目录）。 */
    var cloudBaseUrl: String = CloudStoreApi.DEFAULT_BASE_URL

    suspend fun loadCatalog(cacheDir: File): List<StoreEntry> {
        val builtin = BuiltinCatalog.entries()
        val local = runCatching { LocalStoreApi().catalog() }.getOrDefault(emptyList())
        val cloud = runCatching { CloudStoreApi(cloudBaseUrl, cacheDir).catalog() }
            .getOrDefault(emptyList())
        return (local + cloud + builtin).distinctBy { it.id }
    }
}

/**
 * 安装落位与启停管理（D16）。
 *
 * 安装目录约定：`/data/data/com.moxsh/store/installed/<id>/`（应用私有区，
 * 免存储权限；经 applicationInfo.dataDir 拼接保证真机/多用户路径正确）。
 * 启停状态存 SharedPreferences（"moxsh_store"），卸载即删目录 + 清标记。
 */
object StoreInstallManager {

    private const val PREFS = "moxsh_store"
    private const val KEY_ENABLED = "enabled_ids"

    /** 安装根目录：`<dataDir>/store/installed`。 */
    fun installedRoot(context: Context): File =
        File(context.applicationInfo.dataDir, "store/installed")

    fun installedDir(context: Context, id: String): File =
        File(installedRoot(context), id)

    /** 已安装包 id 列表（扫目录；目录存在且有 manifest.json 视为已装）。 */
    fun installedIds(context: Context): List<String> =
        installedRoot(context).listFiles()
            .orEmpty()
            .filter { File(it, "manifest.json").isFile }
            .map { it.name }

    fun isInstalled(context: Context, id: String): Boolean =
        File(installedDir(context, id), "manifest.json").isFile

    /** 已装版本（读落盘 manifest.json，比较更新用）；未装返回 null。 */
    fun installedVersion(context: Context, id: String): String? = runCatching {
        JSONObject(File(installedDir(context, id), "manifest.json").readText())
            .optString("version")
    }.getOrNull()

    /** 是否已启用（默认启用）。 */
    fun isEnabled(context: Context, id: String): Boolean =
        prefs(context).getStringSet(KEY_ENABLED, null)?.contains(id) ?: true

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val set = (prefs(context).getStringSet(KEY_ENABLED, null) ?: emptySet()).toMutableSet()
        if (enabled) set.add(id) else set.remove(id)
        prefs(context).edit().putStringSet(KEY_ENABLED, set).apply()
    }

    /** 卸载：删安装目录 + 清启停标记。返回是否删除成功。 */
    fun uninstall(context: Context, id: String): Boolean {
        val dir = installedDir(context, id)
        val ok = !dir.exists() || dir.deleteRecursively()
        setEnabled(context, id, true) // 复位默认启用位（目录已删，标记一并清掉）
        return ok
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 安装编排（D16 四阶段）：下载/定位 → 验签 → 解压 → 启用。
 * 进度按阶段折算到 0-100（照 plugin-distro 的安装进度卡口径）。
 */
object StoreInstaller {

    /**
     * 一键安装 [entry] 对应的 .mox 包。
     *
     * @param onProgress (percent 0-100, stage 文案) 回调，IO 线程触发。
     * @return 全阶段成功 true；验签失败/包未配置返回 false（stage 带原因）。
     */
    suspend fun install(
        context: Context,
        entry: StoreEntry,
        api: StoreApi,
        secret: String = StoreSecrets.OFFICIAL,
        onProgress: suspend (Int, String) -> Unit,
    ): Boolean {
        // ① 下载/定位（0-35%）：云源走 download；本地/内置包直接定位文件。
        onProgress(5, "下载中")
        val file = runCatching { api.download(entry.id) }.getOrElse {
            onProgress(0, "包源未配置（${it.message ?: "下载失败"}）")
            return false
        }
        onProgress(35, "下载完成（${file.length() / 1024} KB）")

        // ② 验签（35-55%）：HMAC-SHA256(secret, manifest)。
        onProgress(40, "验签中")
        if (!MoxPackage.verify(file.absolutePath, secret)) {
            onProgress(0, "验签失败：包被篡改或来源不可信")
            return false
        }
        onProgress(55, "验签通过")

        // 包内 manifest 为准取安装 id（防目录条目伪造）。
        val manifest = MoxPackage.readManifest(file.absolutePath)
            ?: run { onProgress(0, "不是合法 .mox 包"); return false }
        // 外来格式兼容点（D15/M7）：Termux/zip 插件包在此先转 tar（解 zip →
        // 生成 manifest.json → 重打 tar）再走验签；转换器落地前 zip 包在
        // 上一步 readManifest 即返回 null，提示"外来格式待转换"。
        onProgress(60, "解压中")
        val outDir = StoreInstallManager.installedDir(context, manifest.id)
        if (!MoxPackage.extract(file.absolutePath, outDir.absolutePath)) {
            onProgress(0, "解压失败（详见内核日志）")
            return false
        }
        onProgress(85, "解压完成")

        // ④ 启用（85-100%）：写启停标记，安装即启用。
        onProgress(90, "启用中")
        StoreInstallManager.setEnabled(context, manifest.id, true)
        onProgress(100, "安装完成，可以在\"已安装\"里管理")
        return true
    }
}
