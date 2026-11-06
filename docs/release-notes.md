修复 MultiLogin 在 **Velocity 3.5.0+ / 4.x** 上无法接管登录的问题。

上游 CaaMoe/MultiLogin 已停止维护，在 Velocity 3.5.0 之后启动即抛
`NoSuchMethodException: AuthSessionHandler.<init>(...)`，最坏情况会把整个代理 `shutdown()`。

## 该下哪个

| 你的 Velocity | 下载 |
|---|---|
| **3.5.x** | `MultiLogin-Velocity-3.5.1.jar` |
| **4.x（含 4.3.1）** | `MultiLogin-Velocity-4.3.1.jar` |

> Velocity 4.x 需要 **Java 25**（Velocity 4 本体就是 Java 25 字节码）；3.5.x 用 Java 21+ 即可。

## 修了什么

- **反射锚点**：`AuthSessionHandler` 构造器改为「前四参固定 + 尾部按类型兜底」匹配。
  Velocity 3.5.0 在其尾部新增了 `String serverIdHash`，这里填入真实的 `serverId`，
  保持 `LoginEvent.serverIdHash` 语义正确。
- **不再关服**：初始化失败改为降级模式，只停用插件并打印醒目错误，**代理继续运行**。
- **构建链**：上游依赖的 `com.velocitypowered:velocity-proxy` 已从 PaperMC 仓库下架，
  原仓库现在无法重新编译。改为按目标版本拉官方发行包做编译基线。
- **诊断日志**：hasJoined 的分组顺序、解析结果、异常全部进 DEBUG。

## 安装

1. **备份** `plugins/MultiLogin/` 整个目录
2. 删除 `plugins/` 里**旧的** MultiLogin jar
3. 放入对应版本的 jar
4. 重启服务器

配置与数据库结构均不变，`services/*.yml` 原样沿用。

## 验证情况

协议级端到端验证（假 Yggdrasil + 真实 Velocity + 手写 Minecraft 客户端完成完整登录握手）：

| 场景 | 结果 |
|---|---|
| Velocity 3.5.1 | ✅ |
| Velocity 4.3.1 | ✅ |
| Velocity 4.3.2-SNAPSHOT | ✅ |
| 双服务（CUSTOM 返回 204 / OFFICIAL 命中） | ✅ |
| 二次登录（走已有档案） | ✅ |
| 白名单拒绝 | ✅ |
| 生产环境：Velocity 3.5.0-SNAPSHOT-605 + LittleSkin + 正版 + Floodgate + MySQL | ✅ |

## 已知限制

- 首次出现**新的客户端版本**时，第一个进服的玩家可能被 `§c请重新链接服务器` 踢一次，
  这是聊天会话包 ID 探测，每个客户端版本只发生一次，重连即正常（上游原有设计）。
- 仍依赖 Velocity 内部实现，后续上游重构可能再次失效；失效时不会再把代理带走。
- 非官方补丁，原作者不提供任何支持。

## 许可

GPL-3.0，基于 [CaaMoe/MultiLogin](https://github.com/CaaMoe/MultiLogin) v6 修改。
对应源码：https://github.com/PUEHMC/MultiLogin-Velocity-Port

## SHA-256

```
3.5.1: 4f846b983612960c1b6ab4f4378dad24c49143289292412433701a97ee42c236
4.3.1: ac0323cad9fd91c81e4a80b47fefe00ec92428c00d07ef58d83bc2a571e609fa
```
