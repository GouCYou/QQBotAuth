# QQBotAuth

QQBotAuth 是 CCTStudio 登录服使用的 Paper 插件。腾讯 QQ 官方 Bot、验证码、账号绑定、AuthMe 联动和转服控制都在插件内完成，不需要也不会启动 Spring Boot 或独立 Web 服务。

项目只使用腾讯官方 QQ Bot API，不支持 OneBot、NapCat、go-cqhttp、Mirai 或任何 QQ 模拟登录协议。

## 运行环境

- Java 21
- Paper 1.21.11+
- AuthMe 6.0.0-Paper
- Velocity 3.5+（强烈建议，用于代理层阻止 `/server` 绕过）
- Minecraft 客户端 1.21.6+ 支持 Dialog；本项目按当前 Paper 1.21.11 Dialog API 构建

QQ Bot 始终只在 Paper 登录服中运行。同一个 jar 内还带有一个不连接 QQ、不读取 QQ 密钥的 Velocity 门禁入口，用来在代理真正执行转服之前校验本次在线状态。

## 架构

```text
QQ 官方 Gateway / OpenAPI
          │
          ▼
QQBotClient → QQEventDispatcher → CommandManager
                                      │
                              BindingService
                                      │
AuthMe LoginEvent → PlayerVerificationManager → SQLite / MySQL
                                      │
                         Paper Dialog / Title / 消息
                                      │
                         Velocity 在线转服门禁 → lobby
```

- `QQBotClient`：异步获取 Gateway、处理 Hello、Identify/Resume、心跳、READY、断线重连和主动关闭。
- `QQApiClient`：获取及缓存 AccessToken，提前 60 秒刷新，并统一调用群聊/单聊 HTTP API。
- `QQEventDispatcher`：解析官方群消息事件并做消息去重；群成员加入/退出类型仅保留为 TODO 扩展点，不解析官方尚未定义的普通 QQ 群成员事件。
- `QQGroupRegistry`：发现事件中的 `group_openid`，并在执行指令前应用群来源白名单。
- `CommandManager`：解析 `@机器人 绑定 ABC123`，分发到独立命令对象。
- `VerificationService`：生成单次、限时验证码。
- `BindingRepository`：隔离存储实现，默认 SQLite，可切换 MySQL。
- `AuthMeHook`：只在 AuthMe 登录成功或会话恢复后启动 QQ 验证。
- `PlayerVerificationManager`：限制未验证玩家、展示 Dialog、提示和倒计时。
- `ServerTransferService`：通过代理插件消息发布状态并转移到大厅。

网络请求、WebSocket 回调和 JDBC 操作均不在 Minecraft 主线程执行。所有 Player、Dialog、Title、插件消息和 Bukkit API 操作都会切回 Paper 主线程。

## 创建腾讯 QQ 官方机器人

1. 打开[腾讯 QQ 机器人开放平台](https://q.qq.com/)，按平台流程注册开发者并创建机器人。
2. 在机器人管理端配置 QQ 群开发场景和沙箱群，先将测试机器人添加到沙箱群。
3. 在“开发设置/基础设置”查看 AppID 和 AppSecret。
4. 在“功能配置”中为群聊添加需要展示的指令，例如“绑定”“查询”“解绑”“帮助”。平台上的指令配置只负责 QQ 客户端入口，实际逻辑由本插件执行。
5. 为生产环境配置服务器公网出口 IP 白名单。腾讯当前文档要求启用白名单的机器人只能从白名单 IP 连接 WebSocket 和调用 OpenAPI。
6. 确认机器人具有群消息场景权限。插件订阅 `GROUP_AND_C2C_EVENT (1<<25)`，主要处理 `GROUP_AT_MESSAGE_CREATE`。

官方资料：

- [QQ 机器人官方文档](https://bot.q.qq.com/wiki/)
- [获取 AccessToken](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/access-token.html)
- [WebSocket 接入](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/websocket.html)
- [群 @ 机器人消息事件](https://bot.q.qq.com/wiki/develop/api-v2/autogen/event/group_at_message_create.html)
- [发送群聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages.post.html)

### 关于 Token

腾讯当前文档已明确废弃旧 Token 鉴权，QQBotAuth 不提供旧 Token 配置。插件使用 AppID 和 AppSecret 请求 AccessToken，并以 `Authorization: QQBot {AccessToken}` 调用 OpenAPI 和鉴权 Gateway。

AppSecret 和 AccessToken 不会写入日志。不要把生产 `config.yml`、环境变量或数据库密码提交到版本库。

## 构建

```bash
./gradlew clean test shadowJar
```

输出文件：

```text
build/libs/QQBotAuth-1.0.0.jar
```

该文件已经包含 Gson、SQLite JDBC 和 MySQL Connector/J。Gson、MySQL 及其依赖已重定位；SQLite JDBC 因 JNI 符号要求保留原包名，并由 Paper/Velocity 插件类加载器隔离。不要部署带 `plain` 后缀的 jar。

## 安装

1. 停止 Paper 登录服和 Velocity。
2. 将 `QQBotAuth-1.0.0.jar` 放入登录服的 `plugins/`。
3. 将同一个 `QQBotAuth-1.0.0.jar` 放入 Velocity 的 `plugins/`。这一步启用真正的代理层转服门禁；不会在代理启动 QQ Bot。
4. 确保登录服已安装 `AuthMe-6.0.0-Paper.jar`，Velocity 已安装 `AuthMe-6.0.0-Velocity.jar`。
5. 启动一次登录服以生成 `plugins/QQBotAuth/config.yml`，填写配置后重启。
6. 关闭 AuthMe 自己的登录后自动转服：
   - 登录服 `plugins/AuthMe/config.yml`：将 `Hooks.sendPlayerTo` 设为空字符串。
   - Velocity `plugins/authmevelocity/config.yml`：将 `loginServer` 设为空字符串。
7. 在登录服执行 `/qqverify status`，确认 Gateway 最终为 `READY`。

Velocity 首次启动会生成 `plugins/qqbotauthgate/config.properties`：

```properties
auth-server=login
unverified-message=请先在登录服完成 QQ 验证。
block-commands=true
hide-command-suggestions=true
allowed-commands=login,l,register,reg,email,captcha,qqverify,qqyz,authme:login,authme:l,authme:register,authme:reg,authme:email,authme:captcha,qqbotauth:qqverify,qqbotauth:qqyz
command-blocked-message=请先完成登录和 QQ 验证，再使用其他指令。
```

`auth-server` 必须与 `velocity.toml` 中的登录服名称完全一致。
未完成 QQ 验证时，Paper 会安全取消签名聊天；Velocity 会拒绝白名单以外的代理及后端指令，并从 1.13+ 客户端命令树中移除它们。因此 `/server` 不会执行，按 Tab 也不会显示服务器列表。聊天拦截没有关闭开关。`allowed-commands` 使用英文逗号分隔；默认仅保留 AuthMe 登录、注册、邮箱、验证码以及 QQBotAuth 验证指令。

不要使用 PlugMan 一类工具热卸载包含网络线程和 JDBC 驱动的插件；生产环境应完整重启 Paper/Velocity。

## 配置

最小 QQ 配置：

```yaml
qq:
  enabled: true
  app-id: "机器人 AppID"
  app-secret: "机器人 AppSecret"
  group-number: "展示给玩家看的 QQ 群号"
  allowed-group-openids:
    - "官方群事件返回的 group_openid"
```

`group-number` 是普通 QQ 群号，仅用于 Dialog 和聊天提示。腾讯官方 Gateway 事件使用的是不透明的 `group_openid`，两者不能互相换算，也不能用普通群号代替白名单值。

首次获取 `group_openid`：

1. 保持 `allowed-group-openids: []` 并启动插件，此时 Gateway 正常连接，但群命令处于安全发现模式，不会执行绑定。
2. 在目标 QQ 群中 `@机器人 帮助` 或发送任意指令。
3. 在登录服后台执行 `qqverify groups`，复制显示的 `group_openid`。
4. 将它加入 `qq.allowed-group-openids`，再执行 `qqverify reload`。

如果机器人只服务一个验证群，白名单通常只需一项。不要把另一个群观察到的 `group_openid` 加入配置。

完整默认配置见 [`src/main/resources/config.yml`](src/main/resources/config.yml)。常用设置：

```yaml
verification:
  code-length: 6
  expire-seconds: 120

database:
  mode: sqlite
  sqlite:
    file: bindings.db

player:
  block-movement: true
  block-interaction: true
  block-server-command: true
  allowed-commands: [qqverify, qqyz, "qqbotauth:qqverify", "qqbotauth:qqyz"]
  dialog-enabled: true
  transfer:
    enabled: true
    verified-server: lobby
    delay-seconds: 5
```

验证码字符集为数字 `2-9` 和大写字母，排除了 `O/0/I/1`。一个 UUID 同时只有一个有效验证码，默认两分钟过期。绑定成功后立即失效；未在时限内完成绑定时会销毁验证码并以可配置的红色原因踢出玩家，不会自动生成新验证码。

QQ 群中的帮助、绑定、查询、解绑、未知指令和错误回复全部位于 `qq-messages` 配置段。`<minecraft>` 会在需要时替换为玩家名；这些回复是 QQ 纯文本，不使用 MiniMessage 标签。

### 环境变量覆盖

以下环境变量可覆盖敏感配置：

```text
QQBOTAUTH_QQ_ENABLED
QQBOTAUTH_QQ_APP_ID
QQBOTAUTH_QQ_APP_SECRET
QQBOTAUTH_QQ_ALLOWED_GROUP_OPENIDS
QQBOTAUTH_DATABASE_MODE
QQBOTAUTH_MYSQL_HOST
QQBOTAUTH_MYSQL_PORT
QQBOTAUTH_MYSQL_DATABASE
QQBOTAUTH_MYSQL_USERNAME
QQBOTAUTH_MYSQL_PASSWORD
QQBOTAUTH_MYSQL_USE_SSL
QQBOTAUTH_MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL
```

推荐在 systemd 或容器编排的秘密管理中提供 AppSecret 和 MySQL 密码。
`QQBOTAUTH_QQ_ALLOWED_GROUP_OPENIDS` 使用英文逗号分隔多个值。

## SQLite 与 MySQL

默认数据库位于：

```text
plugins/QQBotAuth/bindings.db
```

切换 MySQL：

1. 在 `database.mysql` 中填写完整连接信息，但先保持 `database.mode: sqlite`。
2. 在后台执行：

   ```text
   qqverify migrate mysql
   ```

3. 命令会读取 SQLite 并写入 MySQL，不删除 SQLite 文件，可安全重试。
4. 核对迁移数量后将 `database.mode` 改为 `mysql`。
5. 完整重启登录服。

数据库模式和连接信息不支持热重载，避免运行中切换 Repository 导致写入分叉。

## 玩家流程

1. 玩家进入登录服并通过 AuthMe `/login` 或 `/register`。
2. QQBotAuth 收到 AuthMe 成功事件后异步查询绑定。
3. 未绑定玩家会看到 Paper Dialog 与聊天提示，并获得默认两分钟有效的验证码。
4. 玩家在指定 QQ 群发送：

   ```text
   @CCTBot 绑定 A7K9PX
   ```

5. QQ 官方 Gateway 推送群 @ 消息，插件验证 `group_openid`、`member_openid` 和验证码。
6. 数据库写入成功后，游戏主线程显示绿色成功 Title 和倒计时。
7. 倒计时结束后，插件先向 Velocity 发布在线验证状态，再将玩家转移到配置的大厅。

若验证码到期时仍未成功写入绑定，插件会立即销毁验证码并在 Paper 主线程踢出玩家。重新进入服务器后才会获得新验证码。

未验证时聊天始终由 Paper 的当前签名聊天事件阻止。移动和交互限制还会覆盖背包点击、拖动、物品拾取、丢弃、切换、食用、书本编辑、攻击、钓鱼、桶和盔甲架操作；玩家同时不会受到伤害或掉饥饿值。Paper 仅放行 `player.allowed-commands`，Velocity 也会拦截其余代理指令并隐藏命令树。跨服请求还会在 `ServerPreConnectEvent` 再检查一次，因此直接输入或补全 `/server lobby` 都无法绕过。

## 指令

### Minecraft

- `/qqverify` 或 `/qqverify dialog`：打开当前验证码 Dialog。
- `/qqverify code`：废弃旧验证码并生成新验证码。
- `/qqverify status`：玩家查看状态；管理员查看 Gateway、Session 摘要和绑定数量。
- `/qqverify groups`：管理员查看本次运行期间观察到的 `group_openid` 及其白名单状态。
- `/qqverify reload`：重载消息、验证、玩家限制和 QQ 连接配置。数据库配置变更需要重启。
- `/qqverify migrate mysql`：后台迁移 SQLite 数据到 MySQL。

权限：

- `qqbotauth.use`：默认所有玩家。
- `qqbotauth.admin`：默认 OP。

### QQ 群

- `绑定 <验证码>`
- `查询`
- `解绑`
- `帮助`

英文别名 `bind/status/unbind/help` 也可使用。群 @ 事件中的机器人前缀由腾讯平台自动去除，解析器同时容忍常见 mention 文本和 `/` 前缀。

## 测试

### 测试 QQ Bot

1. 先使用腾讯平台沙箱群，不要直接在生产群调试。
2. 启动 Velocity 和登录服。
3. 执行 `/qqverify status`，等待 `Gateway: READY`。
4. 在沙箱群发送 `@机器人 帮助`，确认收到指令列表。
5. 观察控制台。正常日志统一带 `[QQBot]`，日志中不应出现 AppSecret 或 AccessToken。

如果 Gateway 不是 READY，检查：

- AppID/AppSecret 是否正确；
- 机器人是否已配置群场景和沙箱群；
- 生产 IP 白名单是否包含服务器公网出口 IP；
- 服务器是否能访问 `api.bot.qq.com`；
- 机器人是否具备所订阅 Intent 的权限。

当管理员明确设置 `qq.enabled: false` 时，插件会跳过 QQ 绑定检查并继续负责登录后转移。若 `qq.enabled: true` 但 AppID/AppSecret 缺失，插件会保持玩家在登录服并提示联系管理员，避免因配置错误绕过验证。

### 测试绑定验证码

1. 使用未绑定的测试玩家登录 AuthMe。
2. 确认 Dialog 和聊天中出现同一个六位验证码。
3. 在 QQ 群发送 `@机器人 绑定 <验证码>`。
4. 确认 QQ 回复 Minecraft 玩家名。
5. 确认游戏显示绿色成功 Title、逐秒倒计时，并进入 `lobby`。
6. 再次使用同一验证码，应提示验证码无效或已使用。
7. 新建未绑定账号，在倒计时前尝试 `/server lobby`，应被 Velocity 门禁送回登录服。
8. 重启登录服后再次登录，确认 SQLite/MySQL 中的绑定仍然有效。

## 运维与安全

- 插件关闭时会取消心跳/重连任务、主动关闭 WebSocket、取消 Bukkit 任务并停止自有线程池。
- AccessToken 在内存中缓存并在到期前刷新，不写入磁盘或日志。
- Gateway 断开后使用指数退避重连；存在有效 Session/Seq 时尝试 Resume，否则重新 Identify。
- `4914`（机器人下架）和 `4915`（机器人封禁）会停止自动重连，避免无意义请求。
- QQ 消息以 `msg_id + message_scene.msg_idx` 去重，防止官方重复投递导致重复执行。
- Velocity 仅接受来自名为 `login` 的后端、且 UUID 与当前连接玩家一致的状态消息。
- SQLite 到 MySQL 迁移不会删除原数据库，切换前请额外备份 `plugins/QQBotAuth/`。

当前 Velocity 门禁默认认证服务器名是 `login`，与 CCTStudio 现有 `velocity.toml` 一致。如果未来修改代理中的登录服名称，请同步修改 `plugins/qqbotauthgate/config.properties` 并重启 Velocity。
