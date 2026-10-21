# 公开预测快照 Manifest v1

## 1. 用途与边界

公开预测快照用于把一个竞彩业务日的当前公开预测固定为可重复生成的 UTF-8 JSON
字节，并把这些字节的 SHA-256 记录到 `prediction_snapshots`。

本地文件与数据库哈希一致只能证明实现内部的内容完整性。单库哈希不构成独立的
防篡改证明；对象版本、外部公开下载位置和可信时间证据由 T604/T605 补齐。

## 2. 选择规则

- `snapshotDate` 是 `Asia/Shanghai` 口径的竞彩业务日，对应 `matches.lottery_date`。
- 只选择状态为 `PUBLISHED` 或 `LOCKED` 的预测。
- 同一 `matchId + modelVersion` 只保留最高 `predictionVersion`；同版本以最高
  `predictionId` 作为确定性兜底。
- `DRAFT` 和已被更高公开版本替代的历史版本不进入当前快照。
- 空业务日生成合法的空 `predictions` 数组。

## 3. JSON Schema

manifest 根对象字段顺序固定为：

```json
{
  "schemaVersion": 1,
  "snapshotDate": "2026-07-26",
  "predictionCount": 1,
  "predictions": []
}
```

`predictions` 每项字段顺序固定为：

```json
{
  "predictionHashSchemaVersion": 1,
  "predictionId": 303001,
  "matchId": 303101,
  "modelVersion": "model-v1",
  "featureVersion": "feature-v1",
  "generationBatchId": "batch-1",
  "generationBatchHash": "64位小写SHA-256",
  "predictionVersion": 1,
  "homeWinProb": 0.450000,
  "drawProb": 0.300000,
  "awayWinProb": 0.250000,
  "handicapPick": "HOME_WIN",
  "expectedTotalGoals": 2.50,
  "confidenceLevel": "HIGH",
  "analysisSummary": "公开分析摘要",
  "generatedAt": "2026-07-26T07:30:00.654321Z",
  "publishTime": "2026-07-26T08:00:00.123456Z",
  "lockTime": "2026-07-26T10:00:00.000000Z",
  "predictionHash": "64位小写SHA-256"
}
```

## 4. 规范化与复算

- JSON 使用 UTF-8、无 BOM、无缩进、无额外空白，不输出 `null` 字段。
- 概率固定 6 位小数，预期总进球固定 2 位小数，使用普通十进制表示法。
- 时间统一转换为 UTC，固定 6 位微秒并以 `Z` 结尾。
- 数组按 `matchId`、`modelVersion`、`predictionVersion`、`predictionId` 升序排列；
  字符串采用 Java `String.compareTo` 的固定码元顺序，不依赖数据库 collation。
- 生成 manifest 前必须复算每条 `predictionHash` 并与数据库比较；任一不一致则拒绝发布。
  复算按该行自己的结构版本进行（见下表），存量行不会因新增字段而失效。
- `snapshotHash = lowercaseHex(SHA-256(manifestBytes))`。
- manifest 不包含快照生成时间、数据库审计时间、操作者或存储位置，因此相同事实
  必须生成完全相同的字节和哈希。

### 4.1 结构版本矩阵

`schemaVersion` 取本批条目 `predictionHashSchemaVersion` 的最大值，绝不降级标注——
按错的版本解读字段集合，复算必然失败。

| schemaVersion | predictionHashSchemaVersion | 新增内容 | 适用预测 |
| --- | --- | --- | --- |
| 1 | 1 | 竞彩三向概率、竞彩让球、预期总进球、整场置信度 | 早期公开预测 |
| 2 | 2 | `predictionType`、`asianOddsSnapshotId`、亚盘让球/大小球方向 | 竞彩预测首次携带亚盘方向 |
| 3 | 3 | 两个市场各自的置信度（`asianHandicapConfidenceLevel`、`totalGoalsConfidenceLevel`） | 亚盘专用预测（`predictionType=ASIAN`） |
| 4 | 4 | 亚盘十列模型概率（`lambdaHome/Away`、`model*/market*Prob`、`edge*`、两个 `*Dispersion`） | V24 之后落库的亚盘预测 |

各版本永久保留：已发布行的 `prediction_hash` 被数据库触发器冻结，改动任一版字段顺序、
精度或字段集合都会让存量行再也复算不出自己的哈希。选择版本只看事实——
十列全非空走 v4，全空走 v3，部分为空直接拒绝（那种行哪个版本都对不上）。
