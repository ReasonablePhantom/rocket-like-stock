# Rocket Head Dash (自动上升火箭 · 头控躲避) 🚀

基于人脸头部连续姿态控制的竖版火箭闪避游戏，包含 **Android 原生端（CameraX + ML Kit）** 与 **Web 端同步试玩预览（MediaPipe FaceLandmarker）**，复用并升级自 `First Play App`。

## 核心架构模块

1. **头部姿态采集与校准层 (`OneEuroFilter.kt`, `FaceControlManager.kt`, `preview/index.html`)**
   - **Android**: CameraX `1.4.2`（`PreviewView.ImplementationMode.COMPATIBLE` + `UseCaseGroup/ViewPort`）+ Google ML Kit `face-detection:16.1.7`（离线模型、16KB 页对齐）。
   - **Web**: `getUserMedia` + `@mediapipe/tasks-vision` `FaceLandmarker`。
   - **开局 3-2-1 零点锚定**: 倒计时期间连续采样 ~9 帧平均姿态作为本次飞行的基准 `(baseYaw, baseOffsetX)`，坐姿、斜靠或躺姿均可起飞，且支持点击小窗/回中按钮随时重设零点。
   - **One-Euro 滤波 + 小死区**: 静止稳如磐石，快速转头近乎零延迟，连续映射为 `steer ∈ [-1.0, +1.0]`。

2. **火箭连续运动与物理系统 (`RocketGameView.kt`)**
   - 比例位置映射 `targetX = screenWidth * (0.5 + steer * 0.45)`，头回正火箭自然回中。
   - 机身姿态联动（`-22° ~ +22°` 横倾 Roll + 尾焰粒子偏移）。
   - 宽容主体核心碰撞盒 + 近距离 **擦边（Near-Miss）火花奖励**（`⚡ 擦边 +25`）。
   - **彩蛋大招**: **张嘴 `😮`**（或点击护盾/按空格）开启 3.8s **等离子无敌护盾 + 星币磁铁**。

3. **公平关卡与障碍生成器 (`FairWaveGenerator.kt`)**
   - 每波先规划平滑可达的安全走廊（宽度至少 `1.92x` 火箭宽度），所有陨石、巡逻卫星与预警红线流星严格生成在走廊外。
   - 大气层随高度渐变：**对流层蓝天白云 ➜ 平流层晚霞 ➜ 中间层极光 ➜ 深邃星海**。

4. **HUD 与体感反馈 UI (`MainActivity.kt`, `RocketGameView.kt`)**
   - 顶部极简实时转向刻度条 `◀ ——●—— ▶`，无需分神盯摄像头框。
   - 一键切换 **人脸头控 / 单指触屏拖动** 双轨模式。
   - 可点击一键回中、可折叠的右上角迷你前摄 PiP。

5. **无素材程序化音效 (`SoundManager.kt`)**
   - 纯 PCM 合成倒计时、星币/燃料拾取、擦边火花、护盾开启/击碎与大气层升空音效。

---

## 快速运行与验证

### 1. Web 端试玩（支持摄像头头控 + 键鼠/触屏）
> 浏览器摄像头权限需在本地 HTTP 服务下启用：
```bash
python3 -m http.server 8000
```
然后访问 `http://localhost:8000/preview/index.html` 即可开始试玩！

### 2. Android 端编译与单元测试
在项目根目录运行（或用 Android Studio 打开当前文件夹）：
```bash
./gradlew testDebugUnitTest assembleDebug
```
