# AirMouse — 手机当蓝牙鼠标（空鼠）

把安卓手机（华为 P60 / HarmonyOS 4.x）变成一只**标准蓝牙鼠标**，用来控制平板
（MatePad Pro 10.8 2021）。**平板端不需要安装任何东西**，系统会把它当成一个普通蓝牙鼠标。

- 转动手机 → 平板上指针跟着动
- App 界面里点「左键 / 右键」→ 对应按键
- 中间竖条上下拖 → 滚轮
- 不积分、不漂移：手机一停指针立刻停

---

## 一、怎么编译出 APK（不需要装 Android Studio）

仓库已经带好 GitHub Actions 工作流，**云端编译**。

1. 在 GitHub 上新建仓库，名字随意（例如 `airmouse`），**Public 或 Private 都可以**
2. 把本目录里的**全部文件**上传上去（保留目录结构，`.github/workflows/build.yml` 必须在）
3. 打开仓库的 **Actions** 标签页 → 左侧选 `Build APK` → 若提示授权，点
   `I understand my workflows, go ahead and enable them`
4. 等 3~6 分钟，出现绿色 ✓ 即编译成功
5. **在手机上**打开仓库的 **Releases** 页面（地址形如
   `https://github.com/<你的用户名>/airmouse/releases`），点开 `latest`，
   直接点 `AirMouse.apk` 下载安装

> 如果 Release 步骤报错但编译步骤是绿的，也可以从 Actions 那次运行的
> **Artifacts → AirMouse-apk** 下载（是个 zip，手机上需要解压）。

安装时会提示「未知来源」，允许即可。Debug 签名，无安全风险。

---

## 二、第一次使用

1. 手机打开蓝牙
2. 打开 AirMouse，全部允许权限（**BLUETOOTH_CONNECT / SCAN / ADVERTISE 三个都要**）
3. 等状态栏显示 **「已注册为蓝牙鼠标 ✓」**
4. 点 **「让平板发现我」**（保持 5 分钟可被发现）
5. 在平板上：设置 → 蓝牙 → 搜索新设备 → 找到 **AirMouse** → 配对
6. 连上后手机端状态变成 **「已连接：xxx ✓」**，平板上出现鼠标指针

如果第 5 步平板上完全搜不到 AirMouse，或手机端一直显示「正在注册…」不动，
说明这台手机的蓝牙栈没有开放 HID Device 角色（见下面「Plan B」）。

---

## 三、操作方式

| 动作 | 效果 |
|---|---|
| 手腕左右转（像转方向盘） | 指针左右移动 |
| 手腕上下点头 | 指针上下移动 |
| 点住「左键」不放 + 移动手机 | 拖动（选文字、拖窗口） |
| 点「右键」 | 右键单击 |
| 中间竖条上下拖 | 滚轮 |

**方向反了怎么办**：界面下方有「水平反向 / 垂直反向」两个开关，点一下即可。
第一次用大概率需要调其中一个。

**灵敏度**：滑块越小越稳（适合点小图标），越大越快（适合跨屏）。

**拿手机的姿势**：手肘搭在桌上，只做**小幅度手腕转动**，不要举着整条手臂甩，
否则五分钟就酸。手机竖着拿最准。

---

## 四、调参建议（觉得手感不对时改这里）

全部集中在 `AirMouseEngine.kt`：

| 参数 | 默认值 | 作用 |
|---|---|---|
| `deadzone` | 0.035 rad/s | 死区。指针自己慢慢爬 → 调大；轻转没反应 → 调小 |
| `sensitivity` | 600 | 灵敏度（界面上有滑块，不用改代码） |
| `maxRate` | 3.0 rad/s | 增益曲线的参考点。觉得甩起来还不够快 → 调小 |
| `shapedGain()` 里的 `0.55` | — | 低速增益。点小图标费劲 → 调大 |
| `OneEuroFilter(minCutoff, beta)` | 1.2 / 0.02 | 抖动大 → `minCutoff` 调小；觉得拖影 → 调大 |

按钮按下时会冻结 120ms 输入（`engine.suppress(120)`），这是为了避免拇指压屏幕
导致机身微动、指针在点击瞬间窜出去。这个值太小会让点击偏移，太大手感发滞。

---

## 五、Plan B：如果 HID Device 角色被禁用

症状：`registerApp` 返回 false、`getProfileProxy` 返回 null、或注册后平板侧永远搜不到。

那条路是：平板装一个接收端（例如 AutoX.js 脚本或无障app），手机通过 WiFi 发
`dx/dy/按键`，接收端用无障碍服务 `dispatchGesture` 注入。**能跑通，但不是真鼠标**
（系统的右键菜单、跨应用拖拽、文本选词都会别扭），延迟 30~60ms。

---

## 六、原理速览

```
SensorManager(TYPE_GYROSCOPE)  50Hz
        ↓  角速度 → 零偏校准 → 死区 → One Euro 滤波 → 非线性增益
  像素增量 = 增益 × 角速度 × dt
        ↓  亚像素累加器（保留小数，避免慢速被取整成 0）
  HandlerThread 60Hz flush
        ↓  超过 ±127 拆帧
BluetoothHidDevice.sendReport(device, 0, byte[4])
        ↓
平板蓝牙栈认为是标准鼠标 → 系统光标移动
```

关键点：**速率控制**而不是位置积分。位置积分（陀螺仪积出角度再映射坐标）
十几秒就会漂到屏幕角落，这是绝大多数自制约物失败的原因。

---

## 七、技术依赖

**零第三方依赖。** 不用 AndroidX、不用 AppCompat、不用 Compose。
全部是平台自带 API：`BluetoothHidDevice` (API 28+)、`SensorManager`、`Canvas`。

- minSdk 28（Android 9）
- targetSdk 34
- AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.7 / JDK 17
