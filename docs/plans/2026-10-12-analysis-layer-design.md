# 分析层改造设计（方向 A：单机纯软件）

日期：2026-10-12
分支：`feature/analysis-layer`
上游：`upstream/main`（OffGridPete/Fieldwatch），本分支只做新增文件 + 极小触点，保证后续 `merge upstream/main` 低冲突。

## 目标

三个功能（用户已选定 A 方案）：

1. **长期跟踪检测** — 突破现有"15 分钟记忆"，设备出现历史持久化，可查"过去 N 天某设备何时何地出现过"。
2. **基线告警** — 按地点 + 时段学习 RF 环境基线，偏离基线（新出现的强信号/新 OUI/新签名族）时告警。
3. **随机 MAC 归并（诚实版 A）**：
   - ① 随机化检测：识别正在轮换 MAC 的设备群并计数
   - ② 时序相关归并：RSSI 同步涨落 → "可能同一物理设备"概率提示（非身份认定）
   - ③ 半固定设备直跟：Find My 分离模式 / SmartTag 等 hold MAC ~24h 的设备直接按 MAC 长期跟踪

## 架构

新增包 `app.fieldwatch.analysis`，全部为新文件：

```
analysis/
  db/            Room 数据库（4 个 entity + DAO）
  HistoryStore.kt     持久化门面：设备表 + presence 时段表
  Fingerprint.kt      内容指纹 stableKey 计算
  MacMerge.kt         随机化检测 + 时序相关归并
  BaselineEngine.kt   地点×时段基线学习与查询
  AnomalyDetector.kt  基线偏离判定 → 告警事件
  AnalysisEngine.kt   总装：ingest 入口，协程调度
ui/history/      新 Tab「历史」三个分区
```

### 注入点（改动上游文件清单，尽量小）

| 文件 | 改动 |
|---|---|
| `ScanService.kt` | `drainInbound()` 内 `ingestBatch` 之后加 1 行 `analysis.ingest(seen, fleets)` |
| `FieldwatchApp.kt` | 持有 `AnalysisEngine` 单例（仿照现有 `app.devices` / `app.sits`） |
| `MainActivity.kt` | 底部 Tab 增加「历史」 |
| `ConfigStore.kt` | 新增 analysis 相关设置项（开关、保留天数、基线灵敏度） |
| `build.gradle.kts` | Room + ksp 依赖 |

### 数据模型（Room）

- **DeviceEntity**：`deviceKey`（逻辑ID）、kind、OUI、vendor、name、签名族快照、`rotClass`（FIXED / ROTATING / SEMI_FIXED）、`stableKey`（内容指纹）、firstSeen/lastSeen、总 hitCount、session 数
- **PresenceEntity**：deviceKey、startTs/endTs（5 分钟静默关段）、rssiAvg/Min/Max、sampleCount、geohash7、质心 lat/lon
- **BaselineEntity**：(geohash7, hourOfDay) → 常见 OUI 集合、签名族集合、设备数均值/方差、访问次数（学满 3 次才出告警）
- **AlertEntity**：ts、类型（NEW_DEVICE / NEW_OUI / ROTATING_CLUSTER / KNOWN_DEVICE_NEW_PLACE）、deviceKey、说明、地点、已读标记

写入策略：批量落库（每批观测聚合后写一次），默认保留 30 天（可配），超限按最旧 presence 裁剪。

### Fingerprint（stableKey）

`kind + OUI(若无则companyId集合) + name-glob + serviceUUID集合 + mfgData结构(companyId+长度模式+type字节) + vendorIE集合` 的哈希。
随机 MAC 轮换时 MAC 变但 stableKey 不变 → 同一 stableKey 下多个 MAC 即"旋转群"。

### MacMerge（诚实版）

1. **旋转检测**：同一 stableKey 出现 ≥2 个 MAC，且旧 MAC 消失与新 MAC 出现的时间差 < 10 分钟 → 合并为一个 ROTATING 设备群，计数展示
2. **时序相关**：同一 stableKey 的并行 MAC，RSSI 轨迹皮尔逊相关 > 0.8（近 5 分钟窗口）→ "可能同一物理设备"提示
3. **半固定直跟**：rotClass=SEMI_FIXED（如 DULT 分离模式设备）按 MAC 直接建长期档案

### BaselineEngine

- 地点：geohash-7（~150m，"楼下"粒度）；无 GPS 时退化为单一全局地点
- 时间：hourOfDay（24 桶）
- 学习：每个 (地点, 时段) 至少 3 次会话后才启用告警；学习期 UI 显示进度
- 检测：新观测的 OUI/签名族不在基线集合 **且** RSSI ≥ -70 **且** 持续 ≥2 分钟 → 告警
- 告警复用现有 Alerter 风格（通知 + 可选蜂鸣），独立通知渠道，支持静默时段

### UI：新 Tab「历史」

1. **告警时间线**：新设备/新 OUI/旋转群事件，可标记已读
2. **长期跟踪**：半固定 + 旋转群设备列表（出现天数、最近出现、地点轨迹）
3. **基线状态**：地点格列表、学习进度、各地点已知设备数

## 里程碑

- M1 骨架：Room + 注入点 + 空 Tab + 设置项（可编译可跑）
- M2 长期跟踪：HistoryStore 落库 + 历史列表 UI
- M3 归并：Fingerprint + 旋转检测 + 时序相关
- M4 基线告警：BaselineEngine + AnomalyDetector + 告警时间线

## 测试

- 纯 JVM 单测：Fingerprint 稳定性、MacMerge 归并判定、BaselineEngine 学习/检测逻辑（喂假数据）
- Room DAO：迁移测试可选
- 真机：需要 Android 10+ 真机验证功耗与告警质量（模拟器没有真实 RF 环境）

## 风险

| 风险 | 缓解 |
|---|---|
| 电池消耗（DB 写入） | 批量聚合写、可关总开关 |
| DB 膨胀 | 30 天滚动裁剪，估 ~几 MB/周（城市环境） |
| 上游 merge 冲突 | 触点仅 5 个文件，且都是追加式 |
| 构建环境 | 本机 JDK 1.8，需装 JDK 17 + Android SDK |
| 无线真机环境 | 需要真机实测调阈值 |
