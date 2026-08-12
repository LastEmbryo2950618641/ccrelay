# CC Relay Skill 集中管理与同步设计

## 1. 目标

CC Center 统一管理标准 Skill，所有 Relay 随心跳周期异步收敛到 Center 的期望目录。

- `ccrelay-cli skill install` 将标准 Skill 上传并发布到 Center。
- Center 保存 Skill 元数据和压缩制品，是集群权威来源。
- Center Relay 与普通 Relay 使用同一套同步机制。
- Relay 只有完成 SHA-256 校验并安装成功的 Skill 才能被 Claude 使用。
- Skill 目录默认位于 Relay 部署目录的 `skills/`，允许通过 YAML 配置覆盖，引导过程不询问该路径。

## 2. 非目标

- 首版不提供历史版本、回滚和灰度发布。
- 首版不建立独立的节点安装状态表。
- 不修改会话、A2A、Agent 队列、自复制和任务执行协议。
- 不在心跳请求或响应中传输 Skill 清单和制品。

## 3. 目录与标识

`skillId` 取标准 `SKILL.md` frontmatter 中的 `name`。它是稳定身份，不随内容变化。

`sha256` 是 Skill 内容一致性标识。计算规则如下：

1. 只计算安装包中的普通文件，禁止符号链接。
2. 相对路径统一使用 `/`，按 UTF-8 路径升序排列。
3. 每个文件按“路径长度、路径、内容长度、原始内容”写入摘要输入。
4. 不包含绝对路径、目录时间、压缩包时间戳和宿主操作系统信息。
5. 不转换文件换行符或文本编码。

相同文件树在 Windows 和 Linux 上必须得到相同 SHA-256。

## 4. Center 元数据

Center SQLite 新增 `wdsavs_ai_skill`：

| 字段 | 说明 |
| --- | --- |
| `id` | 数据库主键 |
| `skill_id` | Skill 稳定标识，唯一 |
| `sha256` | 当前期望内容摘要 |
| `status` | `ACTIVE` 或 `INVALID` |
| `artifact_path` | Center 压缩制品路径 |
| `artifact_size` | 压缩制品字节数 |
| `created_at` | 首次发布时间 |
| `updated_at` | 最近更新时间 |

Center 根据全部记录的 `skillId + sha256 + status` 排序后计算 `catalogSha256`。任一安装、更新或失效操作都会改变目录摘要。

## 5. Relay 本地元数据

普通 Relay 不启动 Spring/JPA，使用现有 SQLite JDBC 创建独立数据库 `relay-skills-<port>.db`。

本地表 `relay_skill`：

| 字段 | 说明 |
| --- | --- |
| `skill_id` | Skill 稳定标识，主键 |
| `center_sha256` | 最近一次完整目录中的 Center 摘要 |
| `installed_sha256` | 当前本地安装摘要 |
| `status` | `INSTALLING`、`INSTALLED`、`INVALID` |
| `install_path` | 正式安装路径 |
| `last_error` | 最近安装错误 |
| `updated_at` | 最近更新时间 |

只有正式 Skill 目录存在且本地状态为 `INSTALLED` 时才算安装完成。staging、下载文件和失效目录不得位于 Claude 可扫描的 Skill 根目录中。

## 6. Center API

新增独立控制器，不扩展现有 Relay 和任务控制器：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/skill/catalog/install` | 上传并发布标准 Skill 压缩包 |
| `GET` | `/api/skill/catalog/digest` | 获取 `catalogSha256` |
| `GET` | `/api/skill/catalog` | 获取完整、带 `complete=true` 的目录快照 |
| `GET` | `/api/skill/catalog/{skillId}/artifact` | 下载当前有效制品 |
| `DELETE` | `/api/skill/catalog/{skillId}` | 将 Skill 置为 `INVALID` |

安装接口必须由 Center 重新校验标准结构、`skillId` 和 SHA-256，不能信任客户端声明值。

## 7. CLI

正式命令采用现有名词优先风格：

```text
ccrelay-cli skill install <skill-directory-or-zip>
ccrelay-cli skill list
ccrelay-cli skill remove <skillId>
```

CLI 负责：

- 校验输入目录包含 `SKILL.md`。
- 生成确定性 ZIP。
- 计算并展示本地 SHA-256。
- 上传到当前已解析的 CC Center。
- 不直接连接或逐台复制 Relay。

## 8. Relay 同步流程

Relay 保留原心跳协议。每次心跳成功后，仅触发一次非阻塞目录检查：

1. GET Center 目录摘要。
2. 摘要与 Relay 内存中的最近完整同步摘要相同则结束。
3. 不同则异步获取完整目录快照。
4. Center 为 `ACTIVE` 且本地不存在或 SHA-256 不同：进入 `INSTALLING`。
5. 下载到根目录之外的 staging，解压并重新计算文件树 SHA-256。
6. 校验成功后替换正式目录并写入 `INSTALLED`。
7. Center 为 `INVALID`：本地立即置为 `INVALID` 并移出正式目录。
8. 只有完整快照 `complete=true` 时，Center 不存在的本地 Skill 才能按失效处理。
9. 同步完成后记录本次 `catalogSha256`。

同步器使用单线程执行器和进程内去重标记。同一 Relay 不并发执行两个目录同步；传输、解压或校验失败时保持 `INSTALLING` 并记录 `lastError`，下个心跳周期自动重试。`INVALID` 只表示 Center 已将 Skill 失效或完整目录中已不存在该 Skill。

未配置 Center 注册或心跳端点的独立 Relay 不初始化 Skill SQLite、staging 或 Claude 目录映射，保持原有单机运行行为。

## 9. Claude Skill 可见性

当前 Relay 使用独立 `CLAUDE_CONFIG_DIR` 保存 Claude 设置和会话状态，该目录不能因 Skill 管理而迁移。

运行时应将 `$CLAUDE_CONFIG_DIR/skills` 映射到配置的 Skill 根目录：

- Linux 使用目录软链接。
- Windows 使用目录联接。
- 已存在且指向正确位置时保持不变。
- 已存在真实目录时不得直接覆盖，应先安全迁移或报告初始化失败。

这样不修改 Claude 命令行和 Session 状态路径，也不需要重启 Relay 才能让下一次 Claude 调用发现新 Skill。

## 10. 配置

```yaml
wdsavs:
  ai:
    skill:
      directory: ${CCRELAY_SKILL_DIR:./skills}
```

相对路径以 Center 或 Relay 的部署目录解析。安装脚本自动创建默认目录，不增加引导确认步骤。

## 11. 安全与恢复

- 拒绝绝对路径、`..`、符号链接和重复归档条目。
- 限制压缩包大小、文件数量、单文件大小和解压后总大小。
- 制品下载后必须重新计算文件树 SHA-256。
- 临时目录必须与正式 Skill 根目录隔离。
- Relay 启动时清理遗留 staging，并将未完成的 `INSTALLING` 记录留给下一次同步重试。
- 删除先写 `INVALID`，再移除正式目录；不能先删除 Center 元数据行。

## 12. 最小改动边界

新增 Center 目录组件、Relay 同步组件和 CLI Skill 模块。现有代码只在以下位置接入：

- `application.yml` 增加 Skill 路径配置。
- `ccrelay-cli.py` 注册 `skill` 命令组。
- `RemoteCcRelayProperties` 增加 Skill 路径和本地元数据路径。
- `RemoteCcRelayServer` 在成功心跳后触发异步同步，并在停止时关闭同步器。
- Relay 安装脚本创建 Skill 目录和 Claude 目录映射。

现有心跳 DTO、Center 心跳服务、会话上下文、Agent 执行器、A2A、任务队列、自复制和模型 Session 管理保持不变。
