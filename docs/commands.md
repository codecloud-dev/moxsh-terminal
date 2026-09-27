# Termux 命令体系全景

> 基于已克隆至 `/workspace/termux-src` 的 Termux 源码盘点整理。
> 覆盖范围：`termux-api`、`termux-tools`、`termux-app` 三大组件。
> 本文聚焦「有哪些命令 / 它们做什么 / 如何被分发」，不含实现代码。

---

## 一、总体概览

Termux 的命令并非来自单一程序，而是分散在三个互补的组件中：

| 组件 | 角色 | 命令形态 |
| --- | --- | --- |
| **termux-api** | 通过 `am` 或 `termux-api` 二进制暴露的 Android 系统能力接口 | `termux-*` 独立可执行（37 个 API 类） |
| **termux-tools** | 系统初始化、维护与辅助脚本 | `termux-*.in` shell 脚本（13 个） |
| **termux-app** | 宿主 App，负责接收外部意图/套接字并执行命令 | `RunCommandService`、AmSocket、LocalServerSocket 机制 |

下文分别盘点前两类命令，并解析第三类的分发机制，最后给出一张统一总表。

---

## 二、Termux:API 提供的命令（37 个）

`termux-api/app/src/main/java/com/termux/api/apis/` 下共有 **37** 个 `*API.java` 类（类名即能力名）。每个类对应一个或多个 `termux-<name>` 子命令，由 `termux-api` 二进制负责路由。以下按类名给出中文功能说明（命令名采用官方 kebab-case 惯例）。

### 2.1 设备与硬件

| 序号 | 类名（API） | 命令名 | 功能说明 |
| --- | --- | --- | --- |
| 1 | Audio | `termux-audio` | 控制设备音频输出（切换输出设备、静音等）。 |
| 2 | Brightness | `termux-brightness` | 设置或查询屏幕亮度。 |
| 3 | Torch | `termux-torch` | 打开/关闭摄像头手电筒。 |
| 4 | Vibrate | `termux-vibrate` | 以指定时长/模式触发设备振动。 |
| 5 | Volume | `termux-volume` | 调整各音频流（通话、媒体、闹钟、通知等）的音量。 |
| 6 | Infrared | `termux-infrared-transmit` | 通过红外发射器发送红外信号（如遥控家电）。 |
| 7 | Usb | `termux-usb` | 请求访问并通过 USB 设备读写数据。 |
| 8 | CameraInfo | `termux-camera-info` | 列出设备可用摄像头及其参数。 |
| 9 | CameraPhoto | `termux-camera-photo` | 使用指定摄像头拍摄照片并保存。 |
| 10 | Wallpaper | `termux-wallpaper` | 设置主屏或锁屏壁纸。 |
| 11 | Fingerprint | `termux-fingerprint` | 请求指纹认证，用于简单生物识别校验。 |

### 2.2 传感器与定位

| 序号 | 类名（API） | 命令名 | 功能说明 |
| --- | --- | --- | --- |
| 12 | Sensor | `termux-sensor` | 启动/停止传感器并读取加速度、光线、陀螺仪等数据。 |
| 13 | Location | `termux-location` | 通过 GPS/网络获取设备地理位置。 |

### 2.3 通信与电话

| 序号 | 类名（API） | 命令名 | 功能说明 |
| --- | --- | --- | --- |
| 14 | Telephony | `termux-telephony-call` | 拨打电话或查询电话服务信息。 |
| 15 | SmsSend | `termux-sms-send` | 发送短信。 |
| 16 | SmsInbox | `termux-sms-inbox` | 读取短信收件箱内容。 |
| 17 | CallLog | `termux-call-log` | 读取通话记录。 |
| 18 | ContactList | `termux-contact-list` | 读取联系人列表。 |
| 19 | Nfc | `termux-nfc` | 读取或写入 NFC 标签。 |

### 2.4 媒体与输入

| 序号 | 类名（API） | 命令名 | 功能说明 |
| --- | --- | --- | --- |
| 20 | MediaPlayer | `termux-media-player` | 播放/暂停/停止媒体文件。 |
| 21 | MediaScanner | `termux-media-scan` | 扫描文件使之加入系统媒体库。 |
| 22 | MicRecorder | `termux-microphone-record` | 录制麦克风音频到文件。 |
| 23 | TextToSpeech | `termux-tts-speak` / `termux-tts-install-language` | 文字转语音朗读/安装语音包。 |
| 24 | SpeechToText | `termux-speech-to-text` | 通过语音识别将语音转为文字。 |

### 2.5 用户界面与通知

| 序号 | 类名（API） | 命令名 | 功能说明 |
| --- | --- | --- | --- |
| 25 | Toast | `termux-toast` | 在屏幕显示短时气泡提示。 |
| 26 | Dialog | `termux-dialog` | 弹出输入框、确认框、单选/多选等多种对话框收集用户输入。 |
| 27 | Notification | `termux-notification` / `termux-notification-remove` | 发送/移除系统状态栏通知。 |
| 28 | NotificationList | `termux-notification-list` | 列出当前活动的通知。 |

### 2.6 数据与存储

| 序号 | 类名（API） | 命令名 | 功能说明 |
| --- | --- | --- | --- |
| 29 | Clipboard | `termux-clipboard-get` / `termux-clipboard-set` | 读取/写入系统剪贴板。 |
| 30 | Share | `termux-share` | 通过系统分享菜单分享文本/文件。 |
| 31 | StorageGet | `termux-storage-get` | 调用文件选择器让用户挑选并返回文件。 |
| 32 | SAF | `termux-saf` | 基于存储访问框架（SAF）读写外部存储文件。 |
| 33 | Keystore | `termux-keystore` | 访问 Android Keystore 中的密钥以加解密数据。 |

### 2.7 系统与服务

| 序号 | 类名（API） | 命令名 | 功能说明 |
| --- | --- | --- | --- |
| 34 | BatteryStatus | `termux-battery-status` | 查询电池电量、温度、充电状态等。 |
| 35 | Wifi | `termux-wifi-connectioninfo` / `termux-wifi-enable` / `termux-wifi-scaninfo` / `termux-wifi-apenable` | 查询 WiFi 连接、启用/禁用 WiFi、扫描热点、开启热点。 |
| 36 | JobScheduler | `termux-job-scheduler` | 调度后台定时/条件任务（基于 Android JobScheduler）。 |
| 37 | Download | `termux-download` | 通过系统下载管理器下载指定 URL 文件。 |

> 注：部分 API 对应多个子命令（如 `termux-clipboard`、`termux-notification`、`termux-tts`、`termux-wifi`、`termux-dialog`）。若按子命令粒度计，实际可执行命令数略多于 37 个。

---

## 三、termux-tools 提供的命令（13 个）

`termux-tools/scripts/` 下的 `termux-*.in` 是模板脚本（构建时生成最终可执行脚本）。它们负责 Termux 环境的初始化、维护与便捷操作：

| 序号 | 脚本名 | 功能说明 |
| --- | --- | --- |
| 1 | `termux-setup-storage` | 建立 `~/storage` 软链接，授予并桥接外部存储访问权限。 |
| 2 | `termux-setup-package-manager` | 初始化/修复 APT 包管理器（仓库与密钥配置）。 |
| 3 | `termux-change-repo` | 交互式切换 APT 软件源镜像（加速下载）。 |
| 4 | `termux-info` | 打印系统、Termux 版本、包与环境的诊断信息。 |
| 5 | `termux-fix-shebang` | 批量修正脚本的 `#!` 解释器路径，适配 Termux 环境。 |
| 6 | `termux-reload-settings` | 重新加载 Termux 配置（如 `termux.properties`）。 |
| 7 | `termux-wake-lock` | 获取唤醒锁，防止设备休眠（保持后台运行）。 |
| 8 | `termux-wake-unlock` | 释放由 `termux-wake-lock` 持有的唤醒锁。 |
| 9 | `termux-open` | 用合适的系统应用打开指定文件（按类型关联）。 |
| 10 | `termux-open-url` | 调用默认浏览器/应用打开一个 URL。 |
| 11 | `termux-backup` | 备份 Termux 用户数据（家目录、配置等）。 |
| 12 | `termux-restore` | 从备份中恢复 Termux 用户数据。 |
| 13 | `termux-reset` | 重置 Termux 到初始状态（可选清除部分数据）。 |

---

## 四、termux-app 的命令/意图分发机制

宿主 App（`termux-app`）并不只运行终端，它还充当「命令执行中枢」，允许第三方应用、插件、甚至自身通过多种通道请求执行命令。核心机制有三层。

### 4.1 RunCommandService —— 基于 Intent 的官方分发入口

- 类位置：`termux-app/app/src/main/java/com/termux/app/RunCommandService.java`
- 本质：一个 Android `Service`，接收 action 为 `RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND` 的 `Intent`。
- 工作流程：
  1. 第三方应用/插件发送一个 `am startservice` 或显式 Intent，携带 `EXTRA_COMMAND_PATH`（可执行路径）、`EXTRA_ARGUMENTS`、`EXTRA_STDIN`、`EXTRA_WORKDIR`、`EXTRA_RUNNER` 等附加数据。
  2. `RunCommandService.onStartCommand()` 校验 action，将 Intent 的 extras 组装成 `ExecutionCommand` 对象。
  3. 对参数中的逗号占位符做还原（因 `am` 命令会把逗号当作参数分隔符，故支持 `EXTRA_REPLACE_COMMA_ALTERNATIVE_CHARS_IN_ARGUMENTS`）。
  4. 根据 `EXTRA_RUNNER` 决定执行方式：`TERMINAL_SESSION`（前台终端会话）或 `APP_SHELL`（后台执行），最终转发给 `TermuxService` 真正运行。
- 意义：这是**插件与第三方 App 调用的标准接口**（见 Wiki 的 `RUN_COMMAND Intent`），无需 root。

### 4.2 AmSocketServer —— 通过 localhost socket 执行 `am` 命令

- 类位置：`termux-app/termux-shared/.../shell/am/AmSocketServer.java`
- 本质：一个基于 `LocalServerSocket`（AF_UNIX / `SOCK_STREAM` 本地套接字）的服务器，由 `LocalSocketManager` 托管。
- 工作流程：
  1. App 启动时创建本地套接字（建议用**文件系统套接字**而非抽象命名空间套接字，出于安全考虑），默认仅允许本应用所属用户及 root 进程连接。
  2. 客户端（`termux-am-socket` 的 C 客户端或 `termux-am-library`）在输出流发送一条 **不含首参数 `am`** 的 am 命令字符串。
  3. 服务端读取命令字符串，经 `parseAmCommand()` 解析为参数列表，调用 `termux-am-library` 的 `Am` 执行。
  4. 结果按 `exit_code\0stdout\0stderr\0`（以 null 字符分隔）格式回写客户端。
- 意义：把 Android 的 `am`（Activity Manager）能力以**低延迟 socket 协议**暴露给本地进程，比反复 fork `am` 二进制更高效，是 `termux-api` 等内部调用 `am` 的底层通道。

### 4.3 LocalServerSocket —— 插件与宿主 App 的通信总线

- 类位置：`termux-app/termux-shared/.../net/socket/local/LocalServerSocket.java`
- 本质：`LocalSocketManager` 的服务端套接字实现，是上述两种机制的**通用基础设施**。
- 关键设计：
  - 通过 `LocalSocketRunConfig` 配置路径、是否为抽象命名空间、权限等；要求服务器 socket 父目录具备 `rwx` 权限。
  - 启动一个 `ClientSocketListener` 线程监听新的 `LocalClientSocket` 连接。
  - 配合 `ILocalSocketManager` 回调接口，在客户端接入（`onClientAccepted`）、收到数据、出错时通知宿主。
- 意义：插件（如终端配色、附加功能模块）通过**连接到宿主 App 监听的 localhost 套接字**与之通信，既能请求宿主代为执行命令/意图，也能回传状态。RunCommandService 与 AmSocketServer 都是建立在这套本地套接字框架之上的具体应用。
- 安全模型：连接受 peer credential（UID）校验约束，仅同源/root 进程可通信，避免被任意应用滥用。

### 4.4 三层机制的关系

```
第三方App / 插件
   │
   ├─(Intent)────────────► RunCommandService ──► TermuxService 执行命令
   │
   └─(localhost socket)──► LocalServerSocket (LocalSocketManager)
                              │
                              ├─ AmSocketServer：接收 am 命令字符串并执行（termux-am-library）
                              └─ 插件通道：与宿主 App 双向通信、请求执行
```

---

## 五、总表：命令清单与 moxsh 兼容策略

> **moxsh 兼容策略**含义（针对将 Termux 命令移植/封装到 Linux 桌面环境 moxsh 的取舍）：
> - **保留**：纯 shell / 文件 / 网络操作，在 Linux 上基本无需改动即可复用。
> - **重写**：强依赖 Android 框架（硬件、系统服务、Activity）的命令，需用 Linux 等价实现替代。
> - **增强**：可在 Linux 上以更优雅方式实现（GUI、桌面通知、XDG 机制等），建议超出原行为。

### 5.1 Termux:API 命令（37 行）

| 命令名 | 所属组件 | 一句话功能 | moxsh 兼容策略 |
| --- | --- | --- | --- |
| `termux-audio` | termux-api | 控制音频输出设备与静音 | 重写（依赖 Android AudioManager） |
| `termux-battery-status` | termux-api | 查询电池电量与充电状态 | 重写（改用 `/sys/class/power_supply` 或 UPnP） |
| `termux-brightness` | termux-api | 设置/查询屏幕亮度 | 重写（改用 XRandR/背光接口） |
| `termux-call-log` | termux-api | 读取通话记录 | 重写（无对应桌面能力，需剔除或模拟） |
| `termux-camera-info` | termux-api | 列出可用摄像头 | 重写（改用 V4L2 枚举） |
| `termux-camera-photo` | termux-api | 拍摄照片 | 重写（改用 ffmpeg/V4L2 抓帧） |
| `termux-clipboard-get/set` | termux-api | 读写系统剪贴板 | 增强（改用 xclip/wl-clipboard） |
| `termux-contact-list` | termux-api | 读取联系人 | 重写（无桌面通讯录，需剔除或模拟） |
| `termux-dialog` | termux-api | 弹出对话框收集输入 | 增强（改用 zenity/kdialog GUI） |
| `termux-download` | termux-api | 系统下载管理器下载 | 重写（改用 wget/curl 或桌面下载器） |
| `termux-fingerprint` | termux-api | 指纹认证 | 重写（改用 PAM/生物识别框架） |
| `termux-infrared-transmit` | termux-api | 发送红外信号 | 重写（无桌面红外硬件，需剔除） |
| `termux-job-scheduler` | termux-api | 调度后台定时任务 | 重写（改用 systemd timer/cron） |
| `termux-keystore` | termux-api | 访问 Android Keystore | 重写（改用 GPG/密钥环） |
| `termux-location` | termux-api | 获取地理位置 | 重写（改用 GeoClue/网络定位） |
| `termux-media-player` | termux-api | 播放媒体文件 | 重写（改用 mpv/FFmpeg） |
| `termux-media-scan` | termux-api | 扫描文件入媒体库 | 重写（桌面无媒体库概念） |
| `termux-microphone-record` | termux-api | 录制麦克风音频 | 重写（改用 PulseAudio/arecord） |
| `termux-nfc` | termux-api | 读写 NFC 标签 | 重写（无桌面 NFC，需剔除） |
| `termux-notification` / `-remove` | termux-api | 发送/移除通知 | 增强（改用桌面通知 dbus/notify-send） |
| `termux-notification-list` | termux-api | 列出活动通知 | 重写（改用桌面通知服务查询） |
| `termux-saf` | termux-api | SAF 访问外部存储 | 重写（桌面直接用文件路径） |
| `termux-sensor` | termux-api | 读取传感器数据 | 重写（改用内核传感器接口） |
| `termux-share` | termux-api | 系统分享菜单分享 | 增强（改用 XDG 共享/拖拽） |
| `termux-sms-inbox` | termux-api | 读取短信收件箱 | 重写（无桌面短信，需剔除） |
| `termux-sms-send` | termux-api | 发送短信 | 重写（无桌面短信，需剔除） |
| `termux-speech-to-text` | termux-api | 语音转文字 | 重写（改用 Whisper/在线 STT） |
| `termux-storage-get` | termux-api | 文件选择器取文件 | 增强（改用 zenity 文件对话框） |
| `termux-telephony-call` | termux-api | 拨打电话 | 重写（无桌面通话，需剔除） |
| `termux-tts-speak` | termux-api | 文字转语音 | 增强（改用 espeak/piper） |
| `termux-toast` | termux-api | 显示气泡提示 | 增强（改用桌面 notify/OSD） |
| `termux-torch` | termux-api | 开关手电筒 | 重写（无桌面摄像头灯，需剔除） |
| `termux-usb` | termux-api | 访问 USB 设备 | 重写（改用 libusb/udev） |
| `termux-vibrate` | termux-api | 触发振动 | 重写（无桌面振动，需剔除） |
| `termux-volume` | termux-api | 调整各音频流音量 | 重写（改用 PulseAudio/ALSA） |
| `termux-wallpaper` | termux-api | 设置壁纸 | 重写（改用桌面环境壁纸接口） |
| `termux-wifi-*` | termux-api | WiFi 连接/扫描/热点 | 重写（改用 NetworkManager/nmcli） |

### 5.2 termux-tools 命令（13 行）

| 命令名 | 所属组件 | 一句话功能 | moxsh 兼容策略 |
| --- | --- | --- | --- |
| `termux-setup-storage` | termux-tools | 桥接外部存储/建立 storage 软链接 | 保留（改用家目录/挂载管理） |
| `termux-setup-package-manager` | termux-tools | 初始化 APT 包管理器 | 重写（改用 apt/dnf/pacman 原生） |
| `termux-change-repo` | termux-tools | 切换软件源镜像 | 保留（改写源列表配置） |
| `termux-info` | termux-tools | 打印环境与诊断信息 | 保留（改为收集系统信息） |
| `termux-fix-shebang` | termux-tools | 修正脚本解释器路径 | 保留（路径适配脚本） |
| `termux-reload-settings` | termux-tools | 重新加载配置 | 保留（发送 reload 信号） |
| `termux-wake-lock` | termux-tools | 获取唤醒锁防休眠 | 重写（改用 systemd-inhibit） |
| `termux-wake-unlock` | termux-tools | 释放唤醒锁 | 重写（配合 wake-lock） |
| `termux-open` | termux-tools | 用合适应用打开文件 | 增强（改用 xdg-open） |
| `termux-open-url` | termux-tools | 打开 URL | 增强（改用 xdg-open） |
| `termux-backup` | termux-tools | 备份用户数据 | 保留（改用 tar/rsync 备份） |
| `termux-restore` | termux-tools | 恢复备份数据 | 保留（配合 backup） |
| `termux-reset` | termux-tools | 重置到初始状态 | 保留（清理脚本） |

### 5.3 分发机制（非命令，列入总表以完整呈现体系）

| 名称 | 所属组件 | 一句话功能 | moxsh 兼容策略 |
| --- | --- | --- | --- |
| `RunCommandService` | termux-app | 接收 RUN_COMMAND Intent 并转发执行 | 重写（改用 D-Bus/IPC 或 CLI 入口） |
| `AmSocketServer` | termux-app | 通过本地 socket 执行 am 命令 | 重写（桌面无 am，改为本地 exec） |
| `LocalServerSocket` | termux-app | 插件与宿主的本地套接字通信总线 | 重写（改用 Unix domain socket/IPC） |

---

## 六、小结

- **命令规模**：Termux:API 共 **37** 个 API 类（约 38 个可执行命令，部分含子命令）；termux-tools 共 **13** 个 shell 脚本；合计 **50** 条命令级条目。
- **分发架构**：宿主 App 以 `RunCommandService`（Intent 入口）+ `LocalServerSocket`/`AmSocketServer`（本地 socket 总线）三层机制，统一承接第三方 App、插件与内部调用。
- **moxsh 移植取向**：纯文件/脚本类命令（termux-tools 多数、storage/open/info/backup 等）可**保留**；强依赖 Android 框架的 termux-api 命令普遍需**重写**为 Linux 等价实现，其中剪贴板、通知、对话框、TTS、打开文件等建议**增强**为桌面原生体验。

---

*本报告为静态源码盘点，命令名与功能依据类名、官方命名惯例及源码注释推断，未逐文件阅读实现细节。*
