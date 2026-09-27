# 把 moxsh 推送到 GitHub · 中文一步步指南

> 适用对象：第一次用 GitHub、英文不太熟的同学。
> 好消息：**代码在沙箱里已经全部写好了、也提交（commit）好了**，你只需要"建仓库 + 推上去"这两步。
> 下面的操作请在**你自己的电脑**（或 CodeBuddy 里已经连上 GitHub 的终端）里执行，沙箱本身连不上 GitHub 的建仓接口。

---

## 先确认你手上有什么

- 你的 GitHub 用户名：`zyr20121219`
- 仓库名：`moxsh-terminal`
- 开源协议：**GPL-3.0**
- 仓库简介（双语一句话）：
  > 液态玻璃终端，全面兼容 Termux 生态 —— Rust 内核 / PRoot 容器 / 插件商店 / 内置 AI 助手

---

## 方法 A：用 GitHub 官方客户端 `gh`（最省事，推荐）

### 第 1 步：安装 gh
- Windows：去 https://cli.github.com 下载安装包，一路下一步。
- macOS：终端里跑 `brew install gh`（没装 brew 就先装 brew）。
- 装完在终端输入 `gh --version` 能看到版本号就成功了。

### 第 2 步：登录
终端里输入：
```
gh auth login
```
- 选 **GitHub.com**
- 选 **HTTPS**
- 选 **Login with a web browser**（用浏览器登录）
- 它会给你一串验证码，复制到浏览器里粘贴、授权即可。

### 第 3 步：一键推送
把整个 `moxsh` 项目文件夹（也就是本仓库根目录）拷到你自己电脑上，在文件夹里打开终端，运行：
```
bash push_to_github.sh
```
脚本会自动建好 `moxsh-terminal` 仓库并推上去。完事会打印 `✅ 完成：https://github.com/zyr20121219/moxsh-terminal`。

---

## 方法 B：在 GitHub 网页上手动建仓库（不用记命令）

1. 浏览器打开 https://github.com/new
2. **Repository name（仓库名）** 填：`moxsh-terminal`
3. **Description（简介）** 填：`液态玻璃终端，全面兼容 Termux 生态 —— Rust 内核 / PRoot 容器 / 插件商店 / 内置 AI 助手`
4. 选 **Public（公开）**
5. **不要**勾 "Add a README file"（我们已有）
6. **License（协议）** 选：`GPL-3.0`
7. 点 **Create repository（创建仓库）**
8. 创建好后，页面会给你一段命令。在**你自己电脑**的项目文件夹终端里执行其中的 push 部分，例如：
   ```
   git remote add origin https://github.com/zyr20121219/moxsh-terminal.git
   git push -u origin main
   ```
   （如果提示分支名是 `master`，就把 `main` 改成 `master`）

---

## 方法 C：只用 Token（不想装 gh，也不想开网页）

1. 打开 https://github.com/settings/tokens 点 **Generate new token (classic)**
2. 勾上 **repo**（整组勾上即可），有效期随便选，点最底下 **Generate token**
3. **立刻复制**那串 `ghp_...` 令牌（只显示一次！）
4. 在自己电脑的项目文件夹终端里：
   ```
   export GITHUB_TOKEN=你刚才复制的令牌
   bash push_to_github.sh
   ```

---

## 常见问题

- **推送很慢 / 卡住**：可改用国内代理前缀。把脚本里 `REMOTE_URL` 那行临时改成
  `https://ghproxy.net/https://github.com/zyr20121219/moxsh-terminal.git` 再 push。
- **提示 `repository already exists`**：说明仓库已经建好了，直接 `git push -u origin main` 即可。
- **提示没权限**：确认 token 勾了 `repo`，或用 `gh auth login` 重新登录。
- **沙箱里为什么推不了**：沙箱网络层屏蔽了 GitHub 的建仓接口（API），只能走 git 流量；所以建仓+推送必须在你本机/已连 GitHub 的终端完成。代码本身已全部就绪。

推上去之后，GitHub 的 **Actions** 会自动帮你编译 APK（见 `.github/workflows/build.yml`）。要签名出正式包，在仓库 **Settings → Secrets** 里填 `KEYSTORE_BASE64` 等四项（不填则用 debug 签名）。
