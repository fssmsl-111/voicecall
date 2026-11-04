# VoiceCall — Minecraft 对讲机式频道语音
> **下载**：[GitHub Releases](https://github.com/fssmsl-111/voicecall/releases/latest)
> · `voicecall-1.0.0-with-denoise.jar`（带降噪，推荐）
> · `voicecall-1.0.0-no-denoise.jar`（无降噪，延迟最低）


Minecraft **Forge 1.20.1** 的**频道制 / 对讲机式**语音 mod。

> 按住 `~` 与同频道玩家不限距离通话；`M` 键自由麦附近说话；同一频道同时只能一人说话（对讲机占线）；说话时角色抬手扶耳麦，全服可见。

---

## 功能

| 功能 | 说明 |
|---|---|
| **频道语音（PTT）** | 按住 `~` 说话，同频道玩家**不限距离**都能听到 |
| **对讲机占线** | 同一频道同一时刻只能一人说话，别人按 `~` 发不出去 |
| **自由麦（近距）** | 按 `M` 开启，16 格内玩家可听，**走近变大、走远变小** |
| **抬手动画** | 说话时抬手扶耳麦（PlayerAnimator 实现），所有玩家可见 |
| **频道面板** | 按 `V` 加入/切换频道，右上角显示成员，说话者高亮 |
| **RNNoise 降噪** | 48kHz 原生采集 + RNNoise 神经网络降噪（可选无降噪版） |
| **独立 UDP 隧道** | 音频走独立 UDP 通道，延迟低于 MC 连接；隧道不通自动退回 MC 转发 |
| **低延迟** | 20ms 一帧，绝对时钟节奏播放，实测 50–100ms |

---

## 按键

| 键 | 功能 |
|---|---|
| `~` | 按住说话（频道麦） |
| `M` | 自由麦开关（近距模式） |
| `V` | 频道面板 |

键位可在「选项 → 控制 → 按键绑定 → 频道语音通话」里修改。

---

## 安装

### 客户端

1. 安装 **Minecraft 1.20.1 + Forge 47.x**（推荐 47.4.22）
2. 把以下 jar 放进 `.minecraft/mods/`：
   - `voicecall-1.0.0-with-denoise.jar`（推荐）
   - **或** `voicecall-1.0.0-no-denoise.jar`（延迟最低，底噪原样传出）
   - 两个只能选一个
3. 安装前置 mod **player-animation-lib-forge 1.0.2+**（CurseForge/Modrinth 搜索 "Player Animator"）

### 服务端

1. 服务器 `mods/` 放同一个 jar（带降噪/无降噪可混用）
2. 首次启动生成 `config/voicecall-server.txt`：

```ini
# 自由麦半径（格）
proximityRadius=16
# 是否启用独立 UDP 语音隧道
voiceTunnel=true
# UDP 语音端口（防火墙放行）
voicePort=24455
# 经 FRP / 樱花穿透时，对外暴露的 UDP 端口（0 = 与 voicePort 相同）
voicePublicPort=0
```

3. 使用 FRP、樱花穿透等工具时，**必须同时映射 UDP 端口**，否则音频会退回较慢的 MC 通道。

---

## 适用场景

- **生存服组队**：跑图、打 BOSS 随时喊话
- **建筑队**：边搭边沟通，不用切打字
- **角色扮演（RP）服**：频道当“无线电”，代入感强
- **小游戏服**：分队捉迷藏、夺旗，语音更有对抗感
- **对讲机玩法**：一条频道就是一条通讯线路，可做只用语音沟通的密室玩法

---

## 从源码构建

```bash
# 需要 JDK 17
./gradlew build
# 产物：build/libs/voicecall-1.0.0.jar
```

Windows：
```powershell
.\gradlew.bat build
```

---

## 常见问题

**听不到对方**
- 确认两人在同一频道（右上角成员列表能看到对方）
- 服务器防火墙放行 UDP `24455`
- 服务端 `voicePublicPort` 与穿透端口一致

**有回声 / 电流声**
- 使用耳机，避免外放被麦克风重复采集
- 试试无降噪版

**声音断续**
- 关闭后台高 CPU 占用程序
- 蓝牙耳机切到通话模式会明显劣化音质，建议有线

**日志位置**
- 客户端：`.minecraft/logs/latest.log`（搜索 `[VoiceCall]`）
- 服务端：`logs/latest.log`

---

## 第三方

- [RNNoise](https://github.com/xiph/rnnoise)（BSD-3-Clause）— 神经网络降噪，构建版位于 `src/main/resources/native/win-x64/rnnoise.dll`
- [PlayerAnimator](https://github.com/KosmX/minecraftPlayerAnimator) — 抬手动画前置 mod（不随本仓库分发）
- [JNA](https://github.com/java-native-access/jna)（Apache-2.0）— 调用 RNNoise

## 许可证

本项目代码以 **MIT** 协议开源，详见 [LICENSE](LICENSE)。
