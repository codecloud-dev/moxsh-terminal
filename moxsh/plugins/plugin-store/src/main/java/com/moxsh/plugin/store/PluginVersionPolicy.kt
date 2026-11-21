package com.moxsh.plugin.store

/**
 * 插件版本安全策略（禁止回滚）——纯 Kotlin，只用标准库，便于 JVM 单测直接跑。
 *
 * 这是运行时「版本比较 / revoked 拦截 / minVersion 地板 / 版本单调 / 隔离判定」
 * 的单一真源。所有安装、更新、覆盖、隔离路径都必须调用 [evaluate] /
 * [evaluateInstalled]，不得在别处另写一套比较逻辑。
 *
 * 与校验层 `moxsh-plugins/scripts/check-plugin-policy.mjs` 的参考实现逐行同构：
 * 同一组测试向量（见 `PluginVersionPolicyTest.kt`）在两边都要通过，防止「校验绿、
 * 运行时错」的假绿。
 *
 * 威胁模型：插件可以回滚到「已确认有漏洞的旧版本」。防御由四道门组成，
 * 命中即拒绝（拒绝发生在解压覆盖之前，绝不覆盖现有安装）：
 *   ① 版本串非法 → 拒绝（无法比较，保守 fail-closed）；
 *   ② 命中 revokedVersions（已撤销的漏洞版本）→ 拒绝；
 *   ③ 低于 minVersion（最低允许版本地板）→ 拒绝；
 *   ④ 已装版本存在且候选 < 已装版本 → 拒绝（版本单调，禁止降级覆盖 / 回滚）。
 * 同版本重装（候选 == 已装）放行；升级（候选 > 已装）放行。
 *
 * 注意：策略字段（minVersion / revokedVersions）来源于**可信目录**（catalog.json，
 * 签名索引），而非 .mox 包内 manifest（包由作者生成，不能自证可信）；候选版本取
 * 自**验签通过后**的包内 manifest.version，即真正要落盘的产物版本。这样即便目录
 * 标价 1.2.0、实际下发旧包 1.0.0，第②③道门也会按产物版本拦截。
 */
object PluginVersionPolicy {

    /** 判定原因码（与校验层字符串一致，便于对照输出）。 */
    enum class Reason { INVALID_VERSION, REVOKED, BELOW_MIN_VERSION, DOWNGRADE, ALLOW }

    /**
     * 判定结果。
     * @param allowed true=允许安装 / 已装版本合规；false=拒绝 / 需隔离。
     * @param detail 可直接展示给用户的中文原因（安装进度卡 / 已安装卡片用）。
     */
    data class Verdict(val allowed: Boolean, val reason: Reason, val detail: String = "")

    private data class SemVer(val major: Long, val minor: Long, val patch: Long, val pre: List<String>)

    private val ID_RE = Regex("[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*")   // 构建元数据整串
    private val IDENT_RE = Regex("[0-9A-Za-z-]+")                     // 单个标识符

    /**
     * 解析 semver 2.0.0；非法返回 null。
     * 核心段允许 1~3 段（缺段补 0，如 "1.0" == "1.0.0"）；禁止数字段前导零；
     * 预发布/构建元数据按规范拆分；构建元数据不参与优先级比较。
     */
    private fun parse(input: String?): SemVer? {
        if (input == null) return null
        val s = input.trim()
        if (s.isEmpty()) return null

        // 拆 +build（构建元数据不参与比较，但格式必须合法）
        var withoutBuild = s
        val plus = s.indexOf('+')
        if (plus >= 0) {
            withoutBuild = s.substring(0, plus)
            val build = s.substring(plus + 1)
            if (build.isEmpty() || !ID_RE.matches(build)) return null
        }

        // 拆 -prerelease
        var core = withoutBuild
        var preStr: String? = null
        val dash = withoutBuild.indexOf('-')
        if (dash >= 0) {
            core = withoutBuild.substring(0, dash)
            preStr = withoutBuild.substring(dash + 1)
            if (preStr!!.isEmpty()) return null
        }

        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val nums = LongArray(3)
        for (i in parts.indices) {
            val p = parts[i]
            if (p.isEmpty() || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null          // 禁止前导零
            nums[i] = p.toLongOrNull() ?: return null              // 溢出视为非法
        }

        val pre = ArrayList<String>()
        if (preStr != null) {
            for (id in preStr.split('.')) {
                if (!IDENT_RE.matches(id)) return null
                // 数字型预发布标识符同样禁止前导零
                if (id.all { it in '0'..'9' } && id.length > 1 && id[0] == '0') return null
                pre.add(id)
            }
        }
        return SemVer(nums[0], nums[1], nums[2], pre)
    }

    /** 判断标识符是否全为数字。 */
    private fun isNumeric(id: String): Boolean = id.all { it in '0'..'9' }

    /**
     * 比较两个数字型预发布标识符（已无前导零）：长度不同则长者更大，
     * 长度相同按字典序（等价于数值序）。避免超大数字溢出，且纯标准库。
     * 校验层参考实现用同样口径，保证两边逐行对应。
     */
    private fun compareNumericId(a: String, b: String): Int {
        if (a.length != b.length) return if (a.length < b.length) -1 else 1
        val c = a.compareTo(b)
        return if (c < 0) -1 else if (c > 0) 1 else 0
    }

    /** semver 优先级比较：a<b→-1，a>b→1，相等→0；任一非法→null。 */
    fun compare(a: String?, b: String?): Int? {
        val va = parse(a) ?: return null
        val vb = parse(b) ?: return null
        if (va.major != vb.major) return if (va.major < vb.major) -1 else 1
        if (va.minor != vb.minor) return if (va.minor < vb.minor) -1 else 1
        if (va.patch != vb.patch) return if (va.patch < vb.patch) -1 else 1

        val pa = va.pre
        val pb = vb.pre
        if (pa.isEmpty() && pb.isEmpty()) return 0
        if (pa.isEmpty()) return 1        // 正式版 > 预发布版
        if (pb.isEmpty()) return -1
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            if (i >= pa.size) return -1  // 标识符更少者优先级更低
            if (i >= pb.size) return 1
            val ia = pa[i]
            val ib = pb[i]
            val na = isNumeric(ia)
            val nb = isNumeric(ib)
            when {
                na && nb -> {
                    val c = compareNumericId(ia, ib)
                    if (c != 0) return c
                }
                na && !nb -> return -1   // 数字标识符 < 含字母标识符
                !na && nb -> return 1
                else -> {
                    val c = ia.compareTo(ib)
                    if (c != 0) return if (c < 0) -1 else 1
                }
            }
        }
        return 0
    }

    /**
     * 版本是否命中撤销名单。
     * 精确串相等 或 semver 优先级相等：拦住 `1.0.0+build.7`、`1.0`（省略 patch）
     * 这类「改写法绕过」——它们与 `1.0.0` 优先级相同，仍视为同一个被撤销版本。
     * 名单里若有非法项，精确串比较仍可兜住完全一致的写法，不会被静默忽略。
     */
    fun isRevoked(version: String, revoked: List<String>): Boolean = revoked.any { r ->
        version == r || compare(version, r) == 0
    }

    /** 单个版本是否满足「最低版本地板 + 撤销名单」。返回拒绝判定；合规返回 null。 */
    private fun checkAgainstPolicy(version: String, minVersion: String?, revoked: List<String>): Verdict? {
        if (parse(version) == null) {
            return Verdict(false, Reason.INVALID_VERSION, "版本 \"$version\" 非法或缺失，无法校验")
        }
        if (isRevoked(version, revoked)) {
            return Verdict(false, Reason.REVOKED, "版本 \"$version\" 已被撤销（存在已知漏洞），禁止安装或回滚")
        }
        if (!minVersion.isNullOrBlank()) {
            val c = compare(version, minVersion)
            if (c == null) {
                // 策略本身损坏：安全特性 fail-closed，拒绝而不是放行
                return Verdict(false, Reason.INVALID_VERSION, "minVersion \"$minVersion\" 非法，策略拒绝安装")
            }
            if (c < 0) {
                return Verdict(false, Reason.BELOW_MIN_VERSION, "版本 \"$version\" 低于最低允许版本 \"$minVersion\"")
            }
        }
        return null
    }

    /**
     * 安装/覆盖决策：把 [candidateVersion] 安装到 [installedVersion] 所在位置是否允许。
     *
     * @param candidateVersion 真正要落盘的版本（取自验签通过后的包内 manifest.version）。
     * @param minVersion       可信目录声明的最低允许版本；null/空=未声明（不设地板）。
     * @param revokedVersions  可信目录声明的已撤销版本列表。
     * @param installedVersion 本地已装版本；null/空=全新安装。
     */
    fun evaluate(
        candidateVersion: String,
        minVersion: String?,
        revokedVersions: List<String>,
        installedVersion: String?,
    ): Verdict {
        checkAgainstPolicy(candidateVersion, minVersion, revokedVersions)?.let { return it }

        if (!installedVersion.isNullOrBlank()) {
            val c = compare(candidateVersion, installedVersion)
            if (c == null) {
                // 已装版本无法解析：候选已经通过②③道门（是合规版本）；此处视为「修复式重装」
                // 放行，但明确标注跳过单调门，避免与真正校验过的升级混淆。
                // 选择放行而非 fail-closed：损坏的本地版本不应让用户无法安装合规版本。
                return Verdict(
                    true, Reason.ALLOW,
                    "已装版本 \"$installedVersion\" 无法解析，跳过单调门（候选已过策略校验）",
                )
            }
            if (c < 0) {
                return Verdict(
                    false, Reason.DOWNGRADE,
                    "版本 \"$candidateVersion\" 低于已装版本 \"$installedVersion\"，禁止回滚/降级覆盖",
                )
            }
        }
        return Verdict(true, Reason.ALLOW, "允许安装（升级 / 同版本重装 / 全新安装）")
    }

    /**
     * 隔离判定：已装版本是否违规（命中撤销 / 低于地板 / 非法）。
     * [Verdict.allowed] == true 表示合规、无需隔离；false 表示必须停用 + 强制升级。
     * 已装版本缺失/损坏也判为违规（fail-closed），交由强制升级修复。
     */
    fun evaluateInstalled(
        installedVersion: String?,
        minVersion: String?,
        revokedVersions: List<String>,
    ): Verdict {
        val v = installedVersion ?: ""
        val reject = checkAgainstPolicy(v, minVersion, revokedVersions)
        return reject ?: Verdict(true, Reason.ALLOW, "合规")
    }

    /**
     * 取两个地板中「更严格」（更高）的一个；空串/null 视为未声明。
     * 用于目录合并时保证「本地/内置源不会把可信目录的地板拉低」。
     */
    fun stricterMin(a: String?, b: String?): String? {
        if (a.isNullOrBlank()) return b?.takeIf { it.isNotBlank() }
        if (b.isNullOrBlank()) return a
        val c = compare(a, b) ?: return a  // 非法地板：保留优先级高的 a（校验层已保证合法）
        return if (c >= 0) a else b
    }
}

/**
 * 商店卡片的展示决策（纯函数，零 Android 依赖，可与策略类一起用 JVM 自测）。
 *
 * 背景：旧版内置目录给每个占位条目都写了假的 sizeBytes，"一键安装"按钮点下去
 * 才报「包源未配置」。现在只把 packageStatus=available 当作可安装，其余条目
 * 直接把原因显示在卡片上，减少无效点击。
 */
object StorePresentation {
    fun installable(packageStatus: String): Boolean = packageStatus == "available"

    fun sizeLabel(packageStatus: String, sizeBytes: Long): String = when {
        installable(packageStatus) ->
            "约 ${maxOf(1L, (sizeBytes + 524_288L) / 1_048_576L)} MB"
        packageStatus == "planned" -> "包体规划中 · 暂不可安装"
        packageStatus == "yanked" -> "已下架 · 暂不可安装"
        else -> "包源未配置 · 暂不可安装"
    }
}
