# MultiLogin on modern Velocity — 移植与维护说明

> 本文记录把 MultiLogin v6 从 **Velocity 3.4.x** 修到 **Velocity 3.5.x / 4.3.1** 的全过程。
> 结论先说: **只改了一处真正致命的反射锚点**，其余是构建链和运维健壮性。

---

## 一、故障现象与根因

生产服 (Velocity 3.5.0-SNAPSHOT-605) 上插件能加载、能读到配置、能连云数据库，然后在注册注入器时炸掉:

```
java.lang.NoSuchMethodException:
  com.velocitypowered.proxy.connection.client.AuthSessionHandler.<init>(
      VelocityServer, LoginInboundConnection, GameProfile, boolean)
    at MultiInitialLoginSessionHandler.init(MultiInitialLoginSessionHandler.java:131)
    at VelocityInjector.inject(VelocityInjector.java:36)
```

并且 `MultiLoginVelocity.onInitialize` 里 `catch` 之后调用了 `server.shutdown()` ——
**一个构造器签名没对上，整个代理被打包带走**，所有大厅跟着断。

根因:Velocity 3.5.0 给 `AuthSessionHandler` 的构造器尾部加了一个参数:

```java
// Velocity 3.4.x
AuthSessionHandler(VelocityServer, LoginInboundConnection, GameProfile, boolean)
// Velocity 3.5.0+ / 4.x
AuthSessionHandler(VelocityServer, LoginInboundConnection, GameProfile, boolean, String serverIdHash)
```

`serverIdHash` 会原样传给 `LoginEvent(player, serverIdHash)`（3.5.0 的 commit
"Use a shared per-proxy session ID for 26.2 login metrics"）。

## 二、改动清单

### 1. 注入层:按签名前缀解析 `AuthSessionHandler` 构造器

`velocity/injector/.../MultiInitialLoginSessionHandler.java`

- 不再 `getDeclaredConstructor(4 个固定类型)`，改为遍历所有构造器，匹配前四个参数
  `(VelocityServer, LoginInboundConnection, GameProfile, boolean)`，尾部多出来的参数
  按类型兜底填充（`String` → 传真实的 `serverId`，`boolean` → `true`，数值 → `0`，其余 `null`）。
- 解析失败时抛出带完整构造器清单的 `NoSuchMethodException`，一眼就能看出这个
  Velocity 版本到底长什么样，而不是原来那句没有上下文的 `NoSuchMethodException`。
- 解析成功会打一条 DEBUG 日志:

  ```
  [DEBUG] Resolved AuthSessionHandler constructor: AuthSessionHandler(VelocityServer, LoginInboundConnection, GameProfile, boolean, String)
  ```

- 传 `serverId` 而不是 `null`，保证 `LoginEvent.serverIdHash` 的语义和原版一致
  （LuckPerms 之类按 server id 做限流的插件会用到）。

### 2. 运维健壮性:初始化失败不再关服

`velocity/src/main/java/fun/ksnb/multilogin/velocity/main/MultiLoginVelocity.java`

- 删掉了两处 `server.shutdown()`，改成进入**降级模式**:打醒目错误、保持代理存活。
- 代理活着意味着:管理员能看日志、能登面板、别的插件还能用、正版直连路径还在，
  而不是整个群组瞬间全员掉线。
- `onDisable` 加了 null 保护（降级模式下 `multiCoreAPI` 为 null 不会再刷 NPE）。

### 3. 构建链:上游的依赖已经下载不到了

原 `velocity/build.gradle` 依赖:

```groovy
compileOnly 'com.velocitypowered:velocity-api:3.4.0-SNAPSHOT'
compileOnly 'com.velocitypowered:velocity-proxy:3.4.0-SNAPSHOT'   // ← 这个 artifact 已经不存在
```

`com.velocitypowered:velocity-proxy` **已经不再发布到 PaperMC 的 maven 仓库**
（`https://repo.papermc.io/repository/maven-public/com/velocitypowered/` 里只剩
`velocity-api` / `velocity-brigadier` / `velocity-native` / `velocity-annotation-processor`）。
也就是说 **这个仓库在今天根本编译不出来**。

现在改为:

- 根 `build.gradle` 注册 `prepareVelocityDist` 任务，按 `velocity_version` 从
  `https://fill.papermc.io/v3/projects/velocity/versions/<ver>/builds/latest` 下载
  **官方发行版 jar**，落到 `libs/velocity-<ver>.jar`，并校验 sha256。
- `velocity` 与 `velocity:injector` 用 `compileOnly files(rootProject.ext.velocityDistFile)`
  编译。发行版 jar 里同时含 `com.velocitypowered.api.*`、`com.velocitypowered.proxy.*`
  以及 netty / adventure / guava，编译基线 = 运行基线，不会再出现"编得过跑不起来"。
- `settings.gradle` 加了 `pluginManagement` 阿里云镜像（`plugins.gradle.org` 在国内基本拉不动）。
- 顺手删掉了没人用的 `org.jetbrains.kotlin.jvm` 插件声明（项目里没有一行 Kotlin，
  它只是每次构建都去拖一份 kotlin-gradle-plugin）。
- `-Penv=final` 现在可靠生效（原来只认 `-Denv`，而 `-D` 不一定传给 Gradle daemon）。

### 4. 诊断日志

- `YggdrasilAuthenticationFlows` / `YggdrasilAuthenticationService` 增加了 hasJoined
  结果与分组信号的 DEBUG 输出。排查"为什么这个玩家连不上"时，把 `config.yml` 的
  `debug` 打开就能看到是**服务端 200 但档案解析失败**、还是**网络异常**、还是**全部验证失败**。

## 三、构建

环境: **JDK 21+**（构建 Velocity 4.x 目标时必须 **JDK 25**，因为 Velocity 4 本体就是
Java 25 字节码，class file 版本 69）。

```powershell
# 目标 Velocity 3.5.x（生产当前版本）
.\gradlew.bat clean shadowJar -Pvelocity_version=3.5.1 -Penv=final

# 目标 Velocity 4.3.1（必须用 JDK 25）
$env:JAVA_HOME = 'D:\JDK25'
.\gradlew.bat clean shadowJar -Pvelocity_version=4.3.1 -Penv=final
```

产物: `velocity/build/libs/MultiLogin-Velocity-<version>.jar`

> `velocity_version` 也可以直接改 `gradle.properties`。
> 首次构建会自动下载对应的 Velocity 发行版到 `libs/`（约 19MB / 42MB）。

## 四、验证

仓库里带了一套自测工具 `testkit/`，它做的是**真协议级**验证，不是"能启动就算过":

| 文件 | 作用 |
|---|---|
| `FakeYggdrasil.java` | 本地假 Yggdrasil，`/session/minecraft/hasJoined` 返回固定档案；支持按路径前缀返回 204（模拟"查无此人"），用于验证多服务回退和 OFFICIAL 分支 |
| `LoginProbe.java` | 手写 Minecraft 客户端: Handshake → LoginStart → RSA/AES 握手 → SetCompression → LoginSuccess |
| `run-e2e.ps1` | Windows:起假 Yggdrasil + 起真实 Velocity + 跑 LoginProbe + 断言 UUID/名字 |
| `run-e2e.sh` | Linux/macOS:同上（等价实现，适合在真实服务器上跑） |

```powershell
# Windows
javac -encoding UTF-8 -d testkit\classes testkit\FakeYggdrasil.java testkit\LoginProbe.java
.\testkit\run-e2e.ps1 -Jar .\dist\MultiLogin-Velocity-3.5.1.jar `
    -ServerDir .\run\velocity-3.5.1 -ProxyPort 25588 -JavaHome D:\JDK25
```

```bash
# Linux / macOS
javac -encoding UTF-8 -d testkit/classes testkit/FakeYggdrasil.java testkit/LoginProbe.java
./testkit/run-e2e.sh --jar dist/MultiLogin-Velocity-3.5.1.jar --server-dir run/velocity-3.5.1
```

验证通过时输出:

```
[LoginProbe] LOGIN SUCCESS uuid=11111111-2222-3333-4444-555555555555 name=TestPlayer
[LoginProbe] RESULT=OK exit=0
==> E2E RESULT: PASS
```

已实际跑通的场景（每一次都是完整握手走完、拿到 `LoginSuccess`）:

| 场景 | 结果 |
|---|---|
| Velocity **3.5.1** + CUSTOM_YGGDRASIL 登录 | PASS |
| Velocity **4.3.1** + CUSTOM_YGGDRASIL 登录 | PASS |
| Velocity **4.3.2-SNAPSHOT**（最新快照）+ CUSTOM_YGGDRASIL 登录 | PASS |
| Velocity **4.3.1** + 双服务（CUSTOM 返回 204 / **OFFICIAL 命中**） | PASS，日志确认走的是 `Official(sid: 2)` |
| 同一玩家**二次登录**（走数据库已有档案分支） | PASS |
| 服务端开启 `whitelist: true` | 正确以"没有白名单权限"踢出 |

> 日志里出现的 `java.net.SocketException: Connection reset` 是探针拿到 `LoginSuccess`
> 之后主动断开导致的，不是故障。

## 五、在真实服务器上验收（强烈建议）

本地测试环境是干净的，覆盖不到 Floodgate / LuckPerms / SkinsRestorer / MySQL 的组合。
最终验收请在真实机器上开一台**临时 Velocity 实例**——独立目录、独立端口，不要碰生产。

```bash
# 1) 拉一份官方 Velocity（示例 3.5.1；4.x 同理，但必须 Java 25）
mkdir -p /opt/mltest && cd /opt/mltest
curl -sL -o velocity.jar "$(curl -s 'https://fill.papermc.io/v3/projects/velocity/versions/3.5.1/builds/latest' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["downloads"]["server:default"]["url"])')"

# 2) 先空跑一次，让它生成 velocity.toml / forwarding.secret，然后 Ctrl-C
java -jar velocity.jar
```

改 `velocity.toml` 三处（其余保持默认即可）:

```toml
bind = "0.0.0.0:25599"                        # 别和生产的 25565 撞
online-mode = true
player-info-forwarding-mode = "modern"

[servers]
lobby = "127.0.0.1:25566"                     # 随便填，登录流程用不到
```

```bash
# 3) 放插件；把生产的 services/*.yml 复制过来
#    但 config.yml 里的 sql.backend 改成 H2，避免往生产库写测试数据
mkdir -p plugins/MultiLogin/services
cp MultiLogin-Velocity-3.5.1.jar plugins/
cp /path/to/prod/plugins/MultiLogin/services/*.yml plugins/MultiLogin/services/

# 4) 起服
java -jar velocity.jar
```

**验收判据（日志级，不需要客户端）:**

```
[multilogin]: Loaded, using MultiLogin v0.6.13 on Velocity - 3.5.1
[multilogin]: [DEBUG] Resolved AuthSessionHandler constructor: AuthSessionHandler(VelocityServer, LoginInboundConnection, GameProfile, boolean, String)
[multilogin]: Added N authentication services.
```

第一行说明插件加载，第二行（需要 `debug: true`）说明**注入绑定成功**，第三行说明配置读到了。
只要没有 `MultiLogin failed to initialize and is now DISABLED`，注入层就是好的。

**验收判据（真实客户端）:** 用真实账号连 `25599`，日志应出现

```
[multilogin]: <name>(uuid: ...) from authentication service <服务名>(sid: N) has been authenticated, profile redirected to ...
```

> 临时实例可以不接后端子服:登录成功后代理连不上 lobby 会把玩家踢掉，但 `LoginSuccess`
> 已经发出来了，上面这行日志一定会有。想完整走一遍就用测试工具:
> `./testkit/run-e2e.sh --jar dist/MultiLogin-Velocity-3.5.1.jar --server-dir /opt/mltest`

## 六、已知限制 / 下次升级 Velocity 时要看什么

1. **唯一的结构性风险点仍然是 `MultiInitialLoginSessionHandler.init()`**。
   它反射的东西包括:
   - `InitialLoginSessionHandler$LoginState` 四个枚举常量
   - `InitialLoginSessionHandler` 的 `login` / `verify` / `currentState` 字段
     （按**类型**定位，不认名字）
   - `StateRegistry.PacketRegistry.versions`、
     `ProtocolRegistry.packetIdToSupplier` / `packetClassToId`
   - `AuthSessionHandler` 构造器（已做模糊匹配）
   - `MinecraftConnection.enableEncryption` / `setActiveSessionHandler`

   **升级 Velocity 后第一件事**: 打开 debug 起一次服，确认这行还在:

   ```
   [DEBUG] Resolved AuthSessionHandler constructor: AuthSessionHandler(...)
   ```

   如果哪天真炸了，`failFast` 会把这几个类逐个打出来，对着 `javap -p` 补一下即可。

2. **补丁停留在插件侧，没有魔改 Velocity。** 这一点是刻意的:
   魔改服务端意味着每次上游安全更新都要重新 rebase 一份私有构建，运维成本远高于
   几十行反射适配；而且用户拿到的仍然是"标准 Velocity + 一个 jar"。

3. **Java 25 是 Velocity 4.x 的硬门槛。** 生产机已经是 JDK 25，无需额外动作。
   但如果哪天回退到 JDK 21，就必须同时回退到 Velocity 3.5.x。

4. `mapper.yml` 里 Chat Session 包 ID 是逐协议版本手工维护的；遇到未收录的协议，
   插件会**把玩家踢一次并自动记录 ID**（`NewChatSessionPacketIDEvent`），第二次进就正常了。
   这属于上游原有行为，本次未改动。

5. Floodgate 接管逻辑未改动，依赖服务器上真实的 Floodgate 2.2.x。
   本次测试环境没有装 Floodgate，因此该路径只做了编译期验证；生产日志里
   `Floodgate detected, service registered.` 那行走到了就算正常。
