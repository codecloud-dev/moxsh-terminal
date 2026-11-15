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
 * .mox 商店验签密钥（D15/D17）的注入入口。
 *
 * 源码**不再硬编码明文密钥**：真实值由构建期注入——读取 `local.properties`
 * 的 `MOX_STORE_SECRET` 或 CI 环境变量（见 `plugin-store/build.gradle.kts`），
 * 经 `BuildConfig.MOX_STORE_SECRET` 暴露给运行时代码。Release 构建必须在 CI
 * 注入正式密钥（来自密钥库/CI Secret），开发期回退为本地测试常量。
 */
object StoreSecrets {
    /**
     * 开发期本地包验签用的占位密钥（仅 `BuildConfig` 未注入时生效）。
     * 正式签名密钥由构建注入，永远不会出现在库源码里。
     */
    const val DEV_FALLBACK_SECRET: String = "moxsh-official-store-v1"

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
    /**
     * 安全：该插件允许安装的最低版本（可信目录声明）。低于它一律拒装。
     * 空串表示「来源未声明策略」（如纯本地 .mox 包），此时仍受版本单调约束，
     * 但由安装时从可信 catalog 快照按 id 补齐（见 StoreInstaller.install 的 policyCatalog）。
     */
    val minVersion: String = "",
    /** 安全：已确认有漏洞、禁止安装/回滚的版本号列表（可信目录声明）。 */
    val revokedVersions: List<String> = emptyList(),
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
                // 禁止回滚：目录（catalog.json）是策略的可信来源，必须解析这两个字段。
                minVersion = o.optString("minVersion", ""),
                revokedVersions = o.optJSONArray("revokedVersions")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList(),
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
            minVersion = "1.2.0", revokedVersions = listOf("1.0.0", "1.1.0"),
        ),
        StoreEntry(
            id = "theme.aurora", name = "极光主题包", version = "0.9.1",
            author = "moxsh", description = "极光青配色玻璃主题 + 终端色板。",
            type = "theme", minAppVersion = "0.1.0",
            permissions = emptyList(), price = 6.0, purchased = false,
            sizeBytes = 262_144L,
            minVersion = "0.9.1", revokedVersions = listOf("0.9.0"),
        ),
        StoreEntry(
            id = "skill.python", name = "Python 技能", version = "0.3.0",
            author = "moxsh", description = "AI 技能包：装 Python 环境并教写脚本。",
            type = "skill", minAppVersion = "0.1.0",
            permissions = listOf("install_distro", "install_pkg", "run_command"),
            price = 0.0, purchased = false,
            sizeBytes = 524_288L,
            minVersion = "0.3.0", revokedVersions = listOf("0.2.0"),
        ),
        StoreEntry(
            id = "sys.monitor", name = "系统监控", version = "1.0.0",
            author = "moxsh", description = "CPU/内存/磁盘实时曲线玻璃面板。",
            type = "plugin", minAppVersion = "0.1.0",
            permissions = listOf("run_command"), price = 0.0, purchased = false,
            sizeBytes = 393_216L,
            minVersion = "1.0.0", revokedVersions = emptyList(),
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
        // 按 id 合并：展示字段保留「本地 > 云端 > 内置」优先级，但安全策略
        // （minVersion / revokedVersions）取所有来源中**最严格**的并集——
        // 绝不能因为本地 .mox 包或内置兜底覆盖了同 id 条目，就把可信目录的
        // 版本地板拉低、把撤销名单冲掉（那会重新打开回滚口子）。
        val merged = LinkedHashMap<String, StoreEntry>()
        for (src in listOf(local, cloud, builtin)) {
            for (e in src) {
                val prev = merged[e.id]
                merged[e.id] = if (prev == null) e else prev.copy(
                    minVersion = PluginVersionPolicy.stricterMin(prev.minVersion, e.minVersion) ?: "",
                    revokedVersions = (prev.revokedVersions + e.revokedVersions).distinct(),
                )
            }
        }
        return merged.values.toList()
    }
}

/**
 * 安装落位、启停与「禁止回滚」隔离管理（D16 + 安全硬需求）。
 *
 * 安装目录约定：`/data/data/com.moxsh/store/installed/<id>/`（应用私有区，
 * 免存储权限；经 applicationInfo.dataDir 拼接保证真机/多用户路径正确）。
 * 状态存 SharedPreferences（"moxsh_store"）。
 *
 * 启停位用「禁用名单」(denylist) 而非「启用名单」：默认全部启用，只有被显式
 * 禁用的 id 才入表。修掉原实现「禁用一个插件会写空集合、导致其余插件全被误判
 * 为禁用」的隐患——隔离/停用逻辑必须先建在这个正确的语义上。
 *
 * 隔离（quarantine）：已装版本命中撤销名单 / 低于地板 / 无法校验时，写入隔离集，
 * [isEnabled] 直接返回 false，[setEnabled] 也拒绝把隔离中的插件重新启用，
 * 直到通过一次合规升级（[onInstallSucceeded]）才解除。这就是「强制升级/隔离停用」
 * 的真实逻辑，而不是一句提示。
 */
object StoreInstallManager {

    private const val PREFS = "moxsh_store"
    /** 被显式禁用的 id 集合（denylist）；不在集合内即启用。 */
    private const val KEY_DISABLED = "disabled_ids"
    /** 被隔离（漏洞/过期版本）的 id 集合；隔离优先于一切启用操作。 */
    private const val KEY_QUARANTINED = "quarantined_ids"

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

    // ---- 隔离集 ----
    fun quarantinedIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_QUARANTINED, null) ?: emptySet()

    fun isQuarantined(context: Context, id: String): Boolean =
        quarantinedIds(context).contains(id)

    private fun writeQuarantined(context: Context, set: Set<String>) {
        prefs(context).edit().putStringSet(KEY_QUARANTINED, set).apply()
    }

    // ---- 启停（denylist 语义）----
    /** 是否已启用：隔离中一律 false；否则看禁用名单。 */
    fun isEnabled(context: Context, id: String): Boolean {
        if (isQuarantined(context, id)) return false
        return !(prefs(context).getStringSet(KEY_DISABLED, null)?.contains(id) ?: false)
    }

    /** 隔离中的插件不允许被重新启用——必须先升级到合规版本。 */
    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        if (enabled && isQuarantined(context, id)) return
        val set = (prefs(context).getStringSet(KEY_DISABLED, null) ?: emptySet()).toMutableSet()
        if (enabled) set.remove(id) else set.add(id)
        prefs(context).edit().putStringSet(KEY_DISABLED, set).apply()
    }

    /**
     * 合规核对（真实隔离逻辑）：用**可信目录**策略扫描所有已装插件，
     *  - 已装版本违规（撤销名单 / 低于地板 / 无法校验）→ 加入隔离集（停用）；
     *  - 已升级到合规版本 → 从隔离集移除（解除隔离，可再次启用）。
     *
     * 目录里没有该 id 的条目时跳过（无策略可依，不误隔离）。
     * 应在 App 启动与商店打开时调用，确保「用户本地已装了旧漏洞版」会被真实停用。
     *
     * @return 本次被隔离（需强制升级）的 id 列表，供 UI 显示。
     */
    fun reconcileInstalled(context: Context, catalog: List<StoreEntry>): List<String> {
        val byId = catalog.associateBy { it.id }
        val quarantined = quarantinedIds(context).toMutableSet()
        val result = ArrayList<String>()
        for (id in installedIds(context)) {
            val entry = byId[id] ?: continue
            val installed = installedVersion(context, id)
            val verdict = PluginVersionPolicy.evaluateInstalled(
                installed, entry.minVersion, entry.revokedVersions,
            )
            if (!verdict.allowed) {
                quarantined.add(id)
                result.add(id)
            } else {
                quarantined.remove(id)
            }
        }
        writeQuarantined(context, quarantined)
        return result
    }

    /**
     * 一次安装成功后调用：若新装版本已合规，则解除隔离并复位启用位。
     * 这是「隔离 → 强制升级 → 自动恢复」闭环的收尾。
     */
    fun onInstallSucceeded(
        context: Context, id: String, minVersion: String?, revokedVersions: List<String>,
    ) {
        val installed = installedVersion(context, id)
        val compliant = PluginVersionPolicy.evaluateInstalled(
            installed, minVersion, revokedVersions,
        ).allowed
        if (compliant) {
            val q = quarantinedIds(context).toMutableSet()
            if (q.remove(id)) writeQuarantined(context, q)
            setEnabled(context, id, true) // 隔离已解除，重新启用合规版本
        }
    }

    /** 卸载：删安装目录 + 清禁用位 + 清隔离标记。返回是否删除成功。 */
    fun uninstall(context: Context, id: String): Boolean {
        val dir = installedDir(context, id)
        val ok = !dir.exists() || dir.deleteRecursively()
        val q = quarantinedIds(context).toMutableSet()
        if (q.remove(id)) writeQuarantined(context, q)
        setEnabled(context, id, true) // 复位启用位（目录已删，标记一并清掉）
        return ok
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 安装编排（D16 四阶段 + 禁止回滚闸门）：
 * 下载/定位 → 验签 → **版本策略校验** → 解压 → 启用。
 * 进度按阶段折算到 0-100（照 plugin-distro 的安装进度卡口径）。
 *
 * 关键：版本策略闸门必须在「解压覆盖」之前。一旦判定回滚/撤销/低于地板，
 * 直接返回 false，绝不触碰现有安装目录（已装的好版本不会被旧包覆盖）。
 */
object StoreInstaller {

    /**
     * 一键安装 [entry] 对应的 .mox 包。
     *
     * @param policyCatalog 可信目录快照：按 id 取 minVersion / revokedVersions。
     *        调用方（商店 UI）应传入已加载的完整目录。传空则回退到 [entry] 自带策略。
     *        独立包/本地上传也走这里：即便 [entry] 本身是「从包内 manifest 临时构造、
     *        不含策略」的，也会用可信目录按 id 补齐，杜绝本地上传绕过。
     * @param onProgress (percent 0-100, stage 文案) 回调，IO 线程触发。
     * @return 全阶段成功 true；验签失败 / 版本策略拒绝 / 包未配置返回 false（stage 带原因）。
     */
    suspend fun install(
        context: Context,
        entry: StoreEntry,
        api: StoreApi,
        policyCatalog: List<StoreEntry> = emptyList(),
        secret: String = com.moxsh.plugin.store.BuildConfig.MOX_STORE_SECRET,
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

        // ③ 禁止回滚闸门（解压覆盖之前！）：可信目录策略 + 产物真实版本 + 已装版本。
        onProgress(58, "校验版本策略（禁止回滚）")
        val policy = policyCatalog.firstOrNull { it.id == manifest.id } ?: entry
        val installed = StoreInstallManager.installedVersion(context, manifest.id)
        val verdict = PluginVersionPolicy.evaluate(
            candidateVersion = manifest.version,      // 真正要落盘的产物版本
            minVersion = policy.minVersion,            // 可信目录的地板
            revokedVersions = policy.revokedVersions,  // 可信目录的撤销名单
            installedVersion = installed,              // 本地已装版本（版本单调）
        )
        if (!verdict.allowed) {
            // 拒绝即返回：不解压、不覆盖、不改启用位，现有安装保持原样。
            onProgress(0, "已拒绝安装：${verdict.detail}")
            return false
        }

        onProgress(60, "解压中")
        val outDir = StoreInstallManager.installedDir(context, manifest.id)
        if (!MoxPackage.extract(file.absolutePath, outDir.absolutePath)) {
            onProgress(0, "解压失败（详见内核日志）")
            return false
        }
        onProgress(85, "解压完成")

        // ④ 启用（85-100%）：合规版本安装成功 → 解除可能的隔离并复位启用位。
        onProgress(90, "启用中")
        StoreInstallManager.onInstallSucceeded(
            context, manifest.id, policy.minVersion, policy.revokedVersions,
        )
        onProgress(100, "安装完成，可以在\"已安装\"里管理")
        return true
    }
}
