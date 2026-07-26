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
- `schemaVersion` 和 `predictionHashSchemaVersion` 当前均固定为 `1`。
- 概率固定 6 位小数，预期总进球固定 2 位小数，使用普通十进制表示法。
- 时间统一转换为 UTC，固定 6 位微秒并以 `Z` 结尾。
- 数组按 `matchId`、`modelVersion`、`predictionVersion`、`predictionId` 升序排列；
  字符串采用 Java `String.compareTo` 的固定码元顺序，不依赖数据库 collation。
- 生成 manifest 前，必须按 T303 `predictionHashSchemaVersion=1` 重新计算每条
  `predictionHash` 并与数据库比较；任一不一致则拒绝发布。
- `snapshotHash = lowercaseHex(SHA-256(manifestBytes))`。
- manifest 不包含快照生成时间、数据库审计时间、操作者或存储位置，因此相同事实
  必须生成完全相同的字节和哈希。
