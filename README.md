# quizgate-android

孩子端 App：连续玩/刷娱乐应用满 N 分钟 → 抢屏弹出一道学科题 → 答对才放行。
Kotlin + XML 布局（无 Compose），`applicationId = com.quizgate.spike`，minSdk 26 / targetSdk 34。

服务端：https://github.com/yaomoshen520/quizgate-server · 家长后台：https://github.com/yaomoshen520/quizgate-console

## 为什么是悬浮窗

三种抢屏方式在同一台 Android 10 上同机对比的实测：

| 方式 | 成功 |
|---|---|
| 悬浮窗覆盖（`TYPE_APPLICATION_OVERLAY`） | **5/5** |
| 后台 `startActivity` | 4/10 |
| `pressHome` + `startActivity` | 0/10 |

所以结构是**无障碍服务监测前台包 + 悬浮窗抢屏**，而不是发通知或后台拉 Activity。
答题面板 `QuizPanel` 只有一份，Activity 宿主和 overlay 宿主各套一层壳。

## 状态机里几条不能改的语义

- **force-closed 原则**：无障碍开关被清理抹掉、遮挡没铺成功时，语义是"这次直接不给玩 + 让家长知道"，
  不能静默退化成"没得管了"。
- **重启不清零**：累计时长走 `shared_prefs/spike_session.xml` 落盘 + 双时钟，
  停摆那一段被单步上限丢弃，宁可多拦不虚增。
- **抢屏失败要退避重试**（15s 起），失败后永久挂起"待答题"曾经让闸门静默 137 秒。
- 看门狗（前台服务 + 一条常驻通知）**只报不拦**：它发现的是"家长的眼睛少了"，拦人是闸门自己的事。
  通知权限刻意不算进 `GateHealth`——关掉通知不影响抢屏，混进 degraded 会让首页那个大状态说谎。

## 界面的一条产品级约束

凡是**能改变闸门行为**的开关（阈值、抢屏方式、自检、对照组、服务端地址）一律收在**长按标题才出现的「实验台」**里。
旧首页就是靠"点阈值开关 + 把抢屏方式改成 OVERLAY 却不给悬浮窗权限"两步被孩子自己拆掉的。
界面上只留"去开启无障碍/去开启悬浮窗"两个按钮，因为打开系统设置本身拆不掉闸门；权限已开启时把按钮隐藏，只留绿色状态。

答对之后确认层要**停 1.2 秒、不透明、铺满整屏（含状态栏那一条）**再收场：直接 finish 的话孩子只看到屏幕闪一下，
学不到"答对了才放行"这条因果。

## 与后端的联动

`net/ReportChannel.kt`：60 秒一次心跳（带无障碍真实绑定状态 + 累计时长 + 已知规则版本），
闸门事件从 `files/gate_events.txt` 批量上报，成功后才标记已冲刷。
**后端不可达时全部退回本地值**，首页会老实写着连不上；家长侧改的阈值只有在实验台里勾了"跟服务端走"才生效。

协议不在这个仓库单独维护：唯一事实源是后端那份 `proto/quizgate.proto`。

## 构建

仓库里**没有 gradlew**（开发机离线，wrapper 分发拉不下来），用本地 Gradle 8.14.5 发行版：

```
tools/gen-proto.sh          # 需要 ../anti-addiction/proto/quizgate.proto 与 ~/.m2 里的 protoc
gradle --offline :app:assembleDebug :app:testDebugUnitTest
```

`app/src/main/java-gen/`（protoc 产物）和 `app/libs/protobuf-javalite-4.36.2.jar` **都要入库**：
gradle 缓存里没有 protobuf 插件，而 wire 兼容性靠两端 jar 版本逐位对齐——
版本错了不会编译失败，会在运行时对不上还没人报错。

装到真机之后要注意：`adb install -r` 会**解绑无障碍服务**（`enabled_accessibility_services` 直接清空），
必须重新 `settings put secure` 才测得了抢屏路径。

## 这台 App 结构上做不到什么

- **防卸载只有一条真路：Device Owner。** 普通 App 拦不住卸载，别给家长做做不到的承诺。
  而 AOSP 规则是"机上已经有一个第三方登录账号就永久禁止设 DO"，所以形态必须二选一：
  **治理型**（只支持新机/恢复出厂后首次开机即绑 DO）或 **普通 App**（承诺降级为"降低概率"）。
- 能关掉的开关（无障碍、悬浮窗权限、自启动）一律不能当锁，自检只负责"让家长知道闸门掉了"。
- 设备上无法上报"我被卸载了"——那一半在服务端只能表现为心跳超时。

架构与规范参考了 [curbox-app/curbox-android](https://github.com/curbox-app/curbox-android)（GPL-3.0，分支 kt-rewrite）
的分层与提交习惯，**本仓库不含它的任何源码**（GPL 会传染），抢屏动作也没有沿用它的 `pressHome + startActivity`——实测 0/10。
