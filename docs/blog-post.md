# Velocity 升级后 MultiLogin 挂了？这是一份修好的构建（3.5.x / 4.3.x）

> **TL;DR**：CaaMoe 的 MultiLogin 已经停止维护，在 Velocity 3.5.0 之后无法接管登录（插件初始化直接抛
> `NoSuchMethodException`，最坏情况会把整个代理关掉）。我把它修好了，并在真实生产环境跑通，
> 这里提供两个已适配的构建：**Velocity 3.5.x 版**和 **Velocity 4.x 版**。

---

## 谁需要它

- 你的代理是 **Velocity**，正在跑 **3.5.x** 或准备升 **4.x**
- 你在用 MultiLogin 做「正版 + 外置登录（LittleSkin / BlessingSkin / 自建 Yggdrasil）共存」
- 升级 Velocity 后，日志里出现 `NoSuchMethodException: AuthSessionHandler.<init>(...)`，或者干脆没人能进服

## 谁不需要它

- 还在 Velocity **3.4.x 及更早**：用官方原版就行，不用换
- 用的是 **BungeeCord / Bukkit**：这个构建只支持 Velocity
- 只需要**单一**外置登录源：`authlib-injector` 更简单，不需要这个插件

---

## 到底坏在哪

Velocity 3.5.0 给 `AuthSessionHandler` 的构造器尾部加了一个参数：

```java
// 3.4.x
AuthSessionHandler(VelocityServer, LoginInboundConnection, GameProfile, boolean)
// 3.5.0+ / 4.x
AuthSessionHandler(VelocityServer, LoginInboundConnection, GameProfile, boolean, String serverIdHash)
```

MultiLogin 是靠反射构造这个类来接管登录会话的，写死了四参数签名，于是：

```
java.lang.NoSuchMethodException:
  com.velocitypowered.proxy.connection.client.AuthSessionHandler.<init>(..., boolean)
    at MultiInitialLoginSessionHandler.init(...)
    at VelocityInjector.inject(...)
```

更糟的是原代码在 `catch` 之后调用了 `server.shutdown()` —— **一个构造器没对上，整个代理被打包带走**，
所有大厅全员掉线，而日志里只有一行看不懂的反射异常。

---

## 修了什么

| 改动 | 说明 |
|---|---|
| **反射锚点** | `AuthSessionHandler` 构造器改为按「前四参固定 + 尾部按类型兜底」匹配，兼容 3.4 / 3.5 / 4.x；`String` 位填真实的 `serverId`，保持 `LoginEvent.serverIdHash` 语义正确 |
| **不再关服** | 初始化失败改为**降级模式**：打醒目错误、插件自我停用，但**代理继续运行**，不会因为一个插件把整个群组带走 |
| **可以重新编译** | 上游依赖的 `com.velocitypowered:velocity-proxy` 已从 PaperMC 仓库下架，原仓库在当前环境下根本编不出来。改为按目标版本拉官方 Velocity 发行包做编译基线 |
| **诊断日志** | hasJoined 的分组顺序、解析结果、异常全部进 DEBUG，排查问题不用再靠猜 |

---

## 下载

> ⚠️ **先确认你的 Velocity 版本再下载**。`/velocity` 启动日志第一行就写着，例如
> `Booting up Velocity 3.5.0-SNAPSHOT (git-a7581821-b605)`。

| 你的 Velocity | 下载 | SHA-256 |
|---|---|---|
| **3.5.x** | [MultiLogin-Velocity-3.5.1.jar](https://pan.pueh.cn/f/LpXFN/MultiLogin-Velocity-3.5.1.jar) | `4f846b983612960c1b6ab4f4378dad24c49143289292412433701a97ee42c236` |
| **4.x（含 4.3.1）** | [MultiLogin-Velocity-4.3.1.jar](https://pan.pueh.cn/f/LpXFN/MultiLogin-Velocity-4.3.1.jar) | `ac0323cad9fd91c81e4a80b47fefe00ec92428c00d07ef58d83bc2a571e609fa` |

下载后建议核对一下，防止传输损坏：

```bash
sha256sum MultiLogin-Velocity-4.3.1.jar
```

**版本要求**

- Velocity 3.5.x 构建：**Java 21+**
- Velocity 4.x 构建：**必须 Java 25**（Velocity 4 本体就是 Java 25 字节码，跑不了就是 JDK 太老）

---

## 安装

1. **备份** `plugins/MultiLogin/` 整个目录（里面有 `config.yml`、`services/`、以及数据库文件）
2. 删掉 plugins 里**旧的 MultiLogin jar**（留着会加载两个，行为不可预期）
3. 把对应版本的 jar 丢进 `plugins/`
4. 启动服务器

**配置一行都不用改。** 数据库结构没变，`services/*.yml` 原样沿用，Floodgate 接管方式也没变。

> 首次启动会自动从 Maven 下载依赖到 `plugins/MultiLogin/libraries/`，**需要服务器能访问外网**。
> 如果机器在国内、访问 Maven Central 慢，建议保留默认的阿里云镜像配置（插件内置）。

---

## 怎么确认真的成功了

起服后，日志里应该出现这些（按顺序）：

```
[multilogin]: Floodgate detected, service registered.          ← 装了 Floodgate 才有
[multilogin]: Add a authentication service with id 1 and name LittleSkin.
[multilogin]: Added 2 authentication services.
[multilogin]: Loaded, using MultiLogin v0.6.13 on Velocity - 3.5.0-SNAPSHOT (git-a7581821-b605)
```

**关键：`Loaded, using ...` 之后不能有异常，也不能有 `MultiLogin failed to initialize and is now DISABLED`。**
有那一行说明注入没绑上，代理虽然还活着（这是本次的改进），但外置登录不工作。

然后让一个真实账号进服，日志会出现：

```
[multilogin]: xxx(uuid: ...) from authentication service LittleSkin(sid: 1) has been authenticated,
               profile redirected to xxx(uuid: ...)
[connected player] xxx has connected
[server connection] xxx -> lobby has connected
```

**这三行出来 = 整条链路通了**（鉴权 → 档案重定向 → 建连 → 转发到子服）。

---

## 已知限制（请务必看完）

1. **首次有新客户端版本进服，可能被 `§c请重新链接服务器` 踢一次。**
   这是插件在探测该 Minecraft 版本的聊天会话包 ID，**每个客户端版本只发生一次**，重连即正常。
   属于上游原有设计，不是本次改动引入的。

2. **它仍然依赖 Velocity 的内部实现。**
   这次修的是 3.5.x / 4.3.x；以后 Velocity 再重构登录流程，还可能再坏。
   坏掉时的表现是启动日志里 `failFast` 打印的详细清单 —— 不会再把代理带走。

3. **上游已经归档，这个仓库是社区维护的补丁，不是官方版本。**
   原作者不再处理任何相关 issue。

4. **首次启动需要联网**下载插件依赖；另外插件会访问 bStats（统计）、
   `api.github.com`（更新检查，可在 `config.yml` 里用 `checkUpdate: false` 关闭）、
   以及可选的 `api.mineskin.org`（皮肤修复功能，默认关闭）。

5. **不要混用 jar**：3.5.x 的构建别丢进 4.x，反之亦然。

---

## 常见问题

**Q：会不会把我的玩家档案搞丢？**
A：不会。数据库表结构（`*_user_data_v3` / `*_in_game_profile_v3`）和上游一致，不做迁移。
但升级前**请照例备份数据库**。

**Q：支持 1.21.x 吗？**
A：支持 1.19.3 以上的登录流程；聊天会话包 ID 通过 `mapper.yml` 逐版本记录，
新版本第一次会自动探测（对应上面「被踢一次」）。

**Q：和 SkinsRestorer 冲突吗？**
A：不冲突。生产环境实测两者共存正常（SkinsRestorer 15.10.0）。

**Q：和 Floodgate 一起用？**
A：可以，这也是上游特性之一。插件会接管 Floodgate 的握手入口来管理基岩版玩家档案。
注意：该功能会让 Floodgate 自带的账户连接（含全球连接）失效，这是设计如此。

**Q：为什么不用 authlib-injector？**
A：那需要改 JVM 参数、且一个客户端只能指向一个验证服务器。MultiLogin 是为了
「**多个**外置登录源 + 正版共存」而存在的。

**Q：能自己编译吗？**
A：可以，源码见下。用 `-Pvelocity_version=` 指定目标版本即可：

```bash
# Velocity 3.5.x（JDK 21+）
./gradlew clean shadowJar -Pvelocity_version=3.5.1 -Penv=final

# Velocity 4.x（必须 JDK 25）
./gradlew clean shadowJar -Pvelocity_version=4.3.1 -Penv=final
```

---

## 验证过程（为什么我敢发）

不是「能启动就算过」，而是**真协议级**验证：本地起假 Yggdrasil + 真实 Velocity，
用一个手写的 Minecraft 客户端走完 Handshake → RSA/AES 握手 → 压缩 → LoginSuccess 全流程。

| 场景 | 结果 |
|---|---|
| Velocity 3.5.1 + 外置 Yggdrasil | ✅ |
| Velocity 4.3.1 + 外置 Yggdrasil | ✅ |
| Velocity 4.3.2-SNAPSHOT（最新快照） | ✅ |
| Velocity 4.3.1 + 双服务（一个返回 204、一个命中 Official） | ✅ |
| 同一玩家二次登录（走已有档案） | ✅ |
| 服务端开 `whitelist: true` | ✅ 正确拒绝 |
| **真实生产环境**：Velocity 3.5.0-SNAPSHOT-605 + LittleSkin + 正版 + Floodgate + MySQL | ✅ 两个来源的玩家都正常进服 |

---

## 源码与许可（重要）

本项目基于 [CaaMoe/MultiLogin](https://github.com/CaaMoe/MultiLogin) v6 分支修改，
原项目采用 **GNU GPL v3** 许可，本构建同样遵循 GPL v3。

按 GPL v3 要求，**修改版必须提供对应源代码**。任选一种即可：

- **方式 A（推荐）**：源码仓库
  <https://github.com/gerhu/MultiLogin-Velocity-Port>
- **方式 B**：直接提供源码包下载（与 jar 放在同一个网盘目录）
  [MultiLogin-velocity-port-src-0.6.13.zip](https://pan.pueh.cn/f/LpXFN/MultiLogin-velocity-port-src-0.6.13.zip)
  `sha256: d3eb4a0ec16946cb99695be100d7289b7745057d8ede08b9d72cb394ed41e55f`

- 基线版本：CaaMoe/MultiLogin v6（`v6` 分支）
- 修改内容：见本文「修了什么」一节，或源码包中的 `PORTING.md`

如果你只提供二进制而不提供源码，是不符合 GPL v3 的，请务必补上。

原始版权归 CaaMoe 及 [贡献者列表](https://github.com/CaaMoe/MultiLogin/graphs/contributors) 所有。

---

## 免责声明

- 这是**非官方**补丁，与原作者无关，原作者不提供任何支持
- 涉及登录鉴权，请务必先在小号/测试服验证再上生产
- 使用前**必须备份** `plugins/MultiLogin/` 与数据库
- 因使用本构建造成的任何损失，作者不承担责任

如果这个构建帮你省下了半天时间，欢迎在评论区留个脚印，或者把遇到的坑补充出来。

---

*最后更新：2026-11-06 ｜ 适配 Velocity 3.5.x / 4.3.x ｜ 基于 MultiLogin v0.6.13*
