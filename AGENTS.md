# AirMouse 项目协作规则

## 一、改动流程（强制，不可跳过）

任何功能或界面的改动，必须严格按这个顺序：

```
1. 先改原型（prototype/index.html）
2. 等用户确认原型
3. 再改 App 代码
4. 自查：代码逻辑是否真的对应本次要改的问题
5. 再等用户确认是否推送
6. 用户明确说「推送」之后，才能 git commit + git push
```

**绝对禁止：**
- 跳过原型直接改 Kotlin
- 未经用户明确同意就 `git commit` 或 `git push`
- 交付前不做自查就声称「改好了」

## 二、交付前必做的自查

在请用户确认之前，逐条核对：

1. 这次要修的问题，每一处都对应到具体代码/原型位置了吗？
2. 有没有可能重现用户上次反馈过的同类问题？
3. 原型和 App 的界面、文案、交互是否**完全一致**？
4. App 端改动是否已经在原型里同步体现？
5. 编译是否通过（本地 kotlinc + android.jar）？
6. 原型渲染是否验证过（headless 截图 + 尺寸测量）？

## 三、本地验证手段

- Kotlin 编译：
  `D:\Projects\Android\_toolchain` 下的 JDK 17 + kotlinc 1.9.24 + `platform34\android-34\android.jar`
  （`R` 类需用工具链目录下的 stub，不能放进仓库）
- 原型渲染：
  `C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe --headless=new --screenshot`
  测量时 CSS 必须加 `*,*::before,*,::after{transition:none!important;animation:none!important}`

## 三之二、禁止在未验证的系统机制上冒险（血泪教训）

**不要在没查清副作用的情况下，使用会改变「应用自身身份」的系统 API。**

已经踩过的坑：

| 尝试 | 后果 |
|---|---|
| `activity-alias` 动态换桌面图标（v3.3/v3.4） | 华为桌面上图标重复出现、切换后消失，**全部 alias 被禁用后应用彻底没有入口，连重装都找不到图标** |

规则：
1. 涉及 `PackageManager.setComponentEnabledSetting`、`activity-alias`、
   多入口组件、修改自身 manifest 组件的功能，**默认不做**。
2. 如果确实要做，必须先说明风险和回退方案，由用户决定是否承担。
3. 一旦这类改动导致"应用不可用"，**立刻整体回退**，不要在坏掉的方案上继续打补丁。

## 四、版本号

- 小修：`3.3.1`
- 功能迭代：`3.4`
- 架构重写才动主版本
- 原型标题与 `versionName` 保持一致

## 五、已安装设备

- 签名已固定（`keystore/debug.jks`），3.3 起可覆盖升级
- 3.2 及更早的包签名不固定，升级需先卸载
