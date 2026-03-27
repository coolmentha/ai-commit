# AI Commit

AI Commit 是一个 IntelliJ Platform 插件，用来在 Commit 工具窗口中分析当前勾选的改动，并生成可直接提交的 `commit message`。

开源仓库：<https://github.com/coolmentha/ai-commit>  
如果这个项目对你有帮助，欢迎点个 Star。

> Codex 风险提示：`Codex OAuth` 模式会复用本机 Codex 登录态，并将待提交 diff 发送到远端服务。启用前请确认账号权限、网络策略与代码外发风险。

## 免责声明

本插件仅提供与 Codex 相关能力的接入、调用或辅助功能，不参与 Codex 及其所依赖平台的内容审核、风控判定或滥用检测机制。

用户输入的提示词、描述内容或相关操作，可能因触发 Codex 或平台侧的滥用检测、使用限制、风控策略或其他治理规则，而导致拒绝响应、限流、功能受限、输出异常、账号限制等结果。上述判定均由 Codex 或其服务平台独立作出，本插件无法干预、规避、修改或保证其结果。

因前述平台机制触发而产生的任何直接或间接后果，包括但不限于调用失败、响应中断、结果不符合预期、访问受限或其他损失，均不属于本插件的责任范围。

它面向两类接入方式：

- OpenAI 兼容接口的 `API Key` 模式
- ChatGPT Codex Responses 的 `Codex OAuth` 模式

## 功能概览

- 在 Commit 工具窗口提供 `AI 生成提交信息` 按钮
- 自动收集当前勾选的变更和未跟踪文件内容作为上下文
- 支持自定义 `Prompt` 模板
- 支持自定义提交格式模板，并对 AI 返回结果做严格校验
- 支持为不同认证模式分别维护 `API Base URL`
- 支持插件内网页登录 Codex OAuth，也可复用本机已有的 Codex 登录态

## 适用环境

- IntelliJ Platform 2025.1 系列
- 当前构建目标：`IC 2025.1.4.1`
- 最低兼容版本：`sinceBuild 251`

如果你是开发者或需要本地打包，还需要：

- JDK `21`
- 可用的 Gradle Wrapper 环境

## 安装方式

优先从 GitHub Releases 下载已经打好的插件 ZIP：

- Release 页面：<https://github.com/coolmentha/ai-commit/releases>

如果你需要自行构建，也可以使用仓库内的 Gradle Wrapper 本地打包。

### 1. 从 Release 安装

1. 打开 Release 页面并下载对应版本的 ZIP 文件
2. 在 IDE 中打开 `Settings` / `Plugins`
3. 点击右上角齿轮
4. 选择 `Install Plugin from Disk...`
5. 选择下载好的 ZIP 文件
6. 重启 IDE

### 2. 本地打包安装

```bash
./gradlew buildPlugin
```

打包完成后，插件 ZIP 默认位于 `build/distributions/`。

然后在 IDE 中安装本地构建出的 ZIP：

1. 打开 `Settings` / `Plugins`
2. 点击右上角齿轮
3. 选择 `Install Plugin from Disk...`
4. 选择 `build/distributions/` 下生成的 ZIP 文件
5. 重启 IDE

## 快速开始

### 1. 打开插件设置

在 IDE 的 `Settings` 中搜索 `AI Commit`。

### 2. 配置认证方式

#### API Key 模式

- 默认接口地址：`https://api.openai.com/v1`
- 请求接口：`/chat/completions`
- 需要填写：`API Key`、`模型`
- 可选填写：自定义 `API Base URL`

#### Codex OAuth 模式

- 默认接口地址：`https://chatgpt.com/backend-api/codex/responses`
- 请求接口：`/responses`
- 需要填写：`模型`
- 可选填写：自定义 `API Base URL`
- 支持点击设置页中的 `网页登录`
- 如果本机已经有 Codex 登录态，插件会优先复用已有凭证

### 3. 生成提交信息

1. 打开 Commit 工具窗口
2. 勾选本次要提交的变更
3. 点击 `AI 生成提交信息`
4. 等待插件生成结果并自动回填到提交信息输入框
5. 人工确认后再执行提交

## 配置说明

### Prompt 模板

Prompt 模板必须包含：

- `${diff}`

可选变量：

- `${branch}`：当前 Git 分支名
- `${format_instruction}`：根据提交格式模板自动生成的补充约束

默认模板会要求 AI：

- 只返回最终的 `commit message`
- 优先概括主要改动和业务意图
- 差异较多时优先总结核心改动

### 提交格式模板

提交格式模板使用 `{{字段名}}` 语法，例如：

```text
{{type}}({{scope}}): {{subject}}
```

开启后，插件会：

- 在 Prompt 中自动告诉 AI 必须匹配该模板
- 在收到响应后再次校验格式
- 如果格式不匹配，则拒绝写回提交信息

### API Base URL

插件会为 `API Key` 和 `Codex OAuth` 两种模式分别保存各自的 `API Base URL`。切换认证模式时，界面会自动切回该模式上次保存的地址。

## Codex OAuth 行为说明

- 插件支持直接在设置页发起网页授权
- 默认本地回调端口为 `1455`
- 自动回调等待时间为 `60` 秒
- 如果浏览器没有自动回调，插件会提示手工粘贴回调 URL
- 凭证会从 `CODEX_HOME` 或默认的 `~/.codex/auth.json` 中读取
- 在 macOS 上，插件也支持读取和更新系统 Keychain 中的 Codex 凭证

## 插件如何收集上下文

插件只会分析当前 Commit 工具窗口中已勾选的内容。

- 已跟踪文件：使用 IDE 生成统一 diff
- 未跟踪文件：按“新文件”形式拼接文本预览
- 当前分支：通过 Git 命令读取

## 限制与边界

- 超过 `16000` 个字符的 diff 会被截断
- 单个未跟踪文件超过 `12000` 字节时会省略内容
- 非 UTF-8 文本文件不会读取正文
- 没有可分析差异时，插件不会生成提交信息
- 模板校验失败时，插件会直接报错而不是写入不合法结果

## 本地存储

- 插件设置由 IDE 持久化到 `ai-commit.xml`
- `API Key` 配置属于 IDE 本地配置，不会自动写入仓库
- Codex OAuth 凭证由本机 Codex 登录目录或系统 Keychain 提供

## 开发与测试

### 运行测试

```bash
./gradlew test
```

### 本地打包

```bash
./gradlew buildPlugin
```

## 常见问题

### 点击按钮后没有生成结果

优先检查以下几点：

- Commit 工具窗口里是否真的勾选了文件
- `模型` 是否为空
- `API Key` 模式下是否填写了有效密钥
- `Codex OAuth` 模式下是否已经完成登录

### Prompt 模板保存失败

自定义 Prompt 模板必须包含 `${diff}`，否则插件无法分析待提交内容。

### Codex OAuth 登录卡住

如果浏览器已经完成登录但插件没有自动继续，可以把浏览器地址栏里的完整回调 URL 粘贴回插件弹窗。

## 仓库结构

```text
src/main/kotlin/org/coolmentha/aicommit/
├── action/      # Commit 工具窗口动作入口
├── ai/          # OpenAI 兼容接口与 Codex OAuth 客户端
├── settings/    # 设置页与本地配置持久化
├── template/    # Prompt 模板与提交格式模板处理
└── vcs/         # 待提交 diff 收集
```
