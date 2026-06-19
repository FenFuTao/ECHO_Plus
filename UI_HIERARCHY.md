# ECHO+ 程序界面层级结构

```
┌──────────────────────────────────────────────────────┐
│ menuBar (左侧图标栏 48dp)                             │
│ ┌────┐                                               │
│ │ ▶  │ menuBarConnect    — 连接/断开                  │
│ │ [■]│ menuBarProtocol   — 协议与连接                 │
│ │ [■]│ menuBarCommand    — 命令                      │
│ │ [■]│ menuBarControl    — 控件                      │
│ │    │                                                │
│ │ ☰  │ menuBarSetting    — 设置                      │
│ └────┘                                               │
│                                                       │
│ 主页面 (workArea, marginStart=48dp)                   │
│ ┌────────────────────────┬──────────────┐             │
│ │ 绘图窗口 plotContainer  │ 参数列表      │             │
│ │ (上半部分)              │ paramsContainer│           │
│ │                        │              │             │
│ ├────────────────────────┤              │             │
│ │ 输出窗口 outputContainer│              │             │
│ │ (下半部分)              │              │             │
│ └────────────────────────┴──────────────┘             │
│                                                       │
│ 遮罩层 scrimOverlay (菜单展开时显示)                   │
└──────────────────────────────────────────────────────┘

菜单面板 (左侧滑出, marginStart=48dp)
├── navViewA / navViewB (双面板切换)
│   ├── navHeaderTitle (动态标题)
│   └── 菜单项列表
│
│   菜单内容 (按按钮切换):
│   ├── menuBarSetting    → R.menu.nav_drawer
│   │   ├── 显示比例 - 小 (适合手机)  [nav_scale_small]
│   │   ├── 显示比例 - 中 (默认)     [nav_scale_medium]
│   │   ├── 显示比例 - 大 (适合平板) [nav_scale_large]
│   │   ├── 测试按钮一 (nav_btn1)
│   │   ├── 测试按钮二 (nav_btn2)
│   │   ├── 测试按钮三 (nav_btn3)
│   │   └── 测试按钮四 (nav_btn4)
│   │
│   ├── menuBarProtocol   → R.menu.menu_protocol
│   │   ├── USB串口 (protocol_serial)
│   │   ├── 蓝牙 (protocol_bluetooth)
│   │   ├── TCP (protocol_tcp)
│   │   └── UDP (protocol_udp)
│   │
│   ├── menuBarCommand    → R.menu.menu_command
│   │   ├── 命令一 (cmd_btn1)
│   │   ├── 命令二 (cmd_btn2)
│   │   ├── 命令三 (cmd_btn3)
│   │   └── 命令四 (cmd_btn4)
│   │
│   ├── menuBarControl    → R.menu.menu_control
│   │   ├── 控件一 (ctrl_btn1)
│   │   ├── 控件二 (ctrl_btn2)
│   │   ├── 控件三 (ctrl_btn3)
│   │   └── 控件四 (ctrl_btn4)
│   │
│   └── menuBarConnect    → R.menu.menu_connection
│       ├── 连接设备 (conn_connect)
│       └── 断开设备 (conn_disconnect)
```

## 图层层级 (从底到顶)

| 层级 | 组件 | 说明 |
|------|------|------|
| 0 | `workArea` | 主页面（绘图、输出、参数列表） |
| 1 | `scrimOverlay` | 半透明遮罩（菜单展开时显示） |
| 2 | `navViewA` / `navViewB` | 菜单面板（双面板切换动画） |
| 3 | `menuBar` (elevation=50dp) | **图标栏，始终最顶层** |

## 显示比例功能

设置在设置菜单（menuBarSetting）中，提供三个档位：

| 选项 | 缩放比例 | 适用场景 |
|------|---------|---------|
| 小 (适合手机) | 0.8x | 小屏幕手机 |
| 中 (默认) | 1.0x | 标准屏幕 |
| 大 (适合平板) | 1.35x | 平板/大屏设备 |

缩放通过 `rootContainer.scaleX` / `scaleY` 整体缩放实现，配置保存在 `config.json` 的 `uiScale` 字段中。

## 交互流程

1. 点击 `menuBar` 任意图标 → 打开对应二级菜单页面（从左侧滑出）
2. 点击菜单项 → 执行对应操作后关闭菜单
3. 菜单已打开时点击**其他图标** → 旧菜单向左滑出，新菜单从左侧同步滑入
4. 点击**同一个已打开的图标** → 不做任何操作
5. 点击**遮罩层**或**主页面空白区** → 关闭菜单
6. 点击**菜单空白区** → 关闭菜单

## 图标栏控件列表

| ID (R.id.*) | 类型 | 功能 |
|-------------|------|------|
| `menuBarConnect` | `ImageButton` | 连接/断开 — 直接切换状态，图标显示绿/灰色三角形 |
| `menuBarProtocol` | `ImageButton` | 协议与连接 — 点击打开协议菜单 |
| `menuBarCommand` | `ImageButton` | 命令 — 点击打开命令菜单 |
| `menuBarControl` | `ImageButton` | 控件 — 点击打开控件菜单 |
| `menuBarSetting` | `ImageButton` | 设置 — 点击打开设置菜单（含显示比例） |

## 菜单 XML 资源

| 资源路径 | 对应按钮 | 监听器 |
|---------|---------|--------|
| `@menu/nav_drawer` | `menuBarSetting` | `settingsListener` |
| `@menu/menu_protocol` | `menuBarProtocol` | `protocolListener` |
| `@menu/menu_command` | `menuBarCommand` | `commandListener` |
| `@menu/menu_control` | `menuBarControl` | `controlListener` |
| `@menu/menu_connection` | `menuBarConnect` | `connectionListener` |
