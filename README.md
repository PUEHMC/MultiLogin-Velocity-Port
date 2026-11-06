[English](https://github.com/CaaMoe/MultiLogin/blob/v6/README.en.md)
<div align="center">

# MultiLogin

_✨ 正版与多种外置登录共存 ✨_

[![GitHub license](https://img.shields.io/github/license/CaaMoe/MultiLogin?style=flat-square)](https://github.com/CaaMoe/MultiLogin/blob/master/LICENSE)
[![QQ Group](https://img.shields.io/badge/QQ%20group-832210691-yellow?style=flat-square)](https://jq.qq.com/?_wv=1027&k=WrOTGIC7)
[![Join our Discord](https://img.shields.io/discord/1225725211727499347.svg?logo=discord&label=)](https://discord.gg/9vh4kZRFCj)
[![bStats](https://img.shields.io/bstats/servers/21890?color=brightgreen&label=bStats&logo=bs&style=flat-square)](https://bstats.org/plugin/velocity/MultiLogin/21890)

</div>

> [!CAUTION]
> 🚧 上游**已停止维护** (CaaMoe/MultiLogin)，官方不再建议继续使用。
>
> **本仓库是一次内部维护性移植**：已修复上游插件在 **Velocity 3.5.x / 4.3.1** 上
> 因反射锚点失效而无法接管登录的问题，并补齐了可复现的构建链与端到端验证工具。
> 改动明细、构建方法、验证方式与后续升级注意事项见 [PORTING.md](PORTING.md)。

## 概述

MultiLogin 是一款主要为 Minecraft 代理端设计的插件，旨在实现对正版与多种外置登录共存的支持，用于连接两个或多个外置验证服务器下的玩家，使他们能够在同一个服务器上一起游戏。

## 特性

* 支持多达 128 个不同来源的 Yggdrasil 同时共存
* 鉴权代理、重试机制
* 游戏内档案管理系统
* 异步/同步皮肤修复机制
* 支持接管 Floodgate

## 安装

最低需要 `Java 21`， 不需要安装 `authlib-injector` ，没有任何前置插件，也不需要添加和更改 `JVM` 参数

~~把大象装进冰箱需要几步？~~

1. [下载](https://github.com/CaaMoe/MultiLogin/releases/latest) 插件
2. 丢进 plugins
3. 启动服务器

## 配置

详见 [Wiki](https://github.com/CaaMoe/MultiLogin/wiki)

## 构建

需要 **JDK 21+**；如果目标是 **Velocity 4.x**，必须用 **JDK 25**
（Velocity 4 本体就是 Java 25 字节码）。

```bash
# 目标 Velocity 3.5.x
./gradlew clean shadowJar -Pvelocity_version=3.5.1 -Penv=final

# 目标 Velocity 4.3.1（JDK 25）
./gradlew clean shadowJar -Pvelocity_version=4.3.1 -Penv=final
```

产物在 `velocity/build/libs/MultiLogin-Velocity-<version>.jar`。

首次构建会自动从 `fill.papermc.io` 下载目标版本的 Velocity 发行包到 `libs/`
（上游依赖的 `velocity-proxy` 已经不再发布到 maven 仓库，详见 [PORTING.md](PORTING.md)）。

## 验证

`testkit/` 提供了一套协议级端到端自测（假 Yggdrasil + 真实 Velocity + 模拟客户端登录）：

```powershell
javac -encoding UTF-8 -d testkit\classes testkit\FakeYggdrasil.java testkit\LoginProbe.java
.\testkit\run-e2e.ps1 -Jar .\dist\MultiLogin-Velocity-3.5.1.jar -ServerDir .\run\velocity-3.5.1
```

详见 [PORTING.md](PORTING.md)。

## 贡献者

<a href="https://github.com/CaaMoe/MultiLogin/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=CaaMoe/MultiLogin"  alt="作者头像"/>
</a>

[我也想为贡献者之一？](https://github.com/CaaMoe/MultiLogin/pulls)
