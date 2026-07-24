# 业务数据流转与表关系

- 文档版本：v0.1
- 最后更新：2026-07-24
- 作用：用图说明当前已落地表之间的关系，以及体彩 / 亚盘数据如何落到标准实体
- 权威表结构：`backend/src/main/resources/db/migration/V1`～`V4`
- 相关设计：`technical-design.md`；任务进度：`dev-tasks.md`

> 本文回答「数据怎么流、表怎么连」。预测发布、结算等尚未建表的能力只标为后续，不展开。

## 1. 一句话总览

**体彩官方场次**用 `(lottery_match_no, lottery_date)` 锚定内部 `matches`；**亚盘等外部源**不直接改比赛主键，而是通过映射落到同一个 `matches.id`；联赛/球队先标准化到 `leagues` / `teams`，再挂到比赛上。

```mermaid
flowchart LR
  sporttery[体彩 Provider]
  asian[亚盘 Provider]
  raw[raw_data_payloads]
  run[data_sync_runs]
  match[matches]
  league[leagues / teams]
  snapS[sporttery_pool_snapshots]
  snapA[asian_odds_snapshots]

  sporttery --> run
  asian --> run
  sporttery --> raw
  asian --> raw
  raw --> match
  raw --> league
  match --> snapS
  match --> snapA
  league --> match
```

## 2. 表关系总图（当前库）

```mermaid
erDiagram
  data_providers ||--o{ raw_data_payloads : provider_code
  data_providers ||--o{ data_sync_runs : provider_code

  leagues ||--o{ league_aliases : league_id
  leagues ||--o{ provider_league_mappings : league_id
  leagues ||--o{ matches : league_id

  teams ||--o{ team_aliases : team_id
  teams ||--o{ provider_team_mappings : team_id
  teams ||--o{ matches : home_team_id
  teams ||--o{ matches : away_team_id

  matches ||--o{ match_source_mappings : match_id
  matches ||--o{ sporttery_pool_snapshots : match_id
  matches ||--o{ asian_odds_snapshots : match_id

  data_providers {
    string provider_code PK
    string category
    boolean enabled
  }

  raw_data_payloads {
    string provider_code
    string data_type
    string request_key
    char payload_hash
    jsonb payload
  }

  data_sync_runs {
    string provider_code
    string data_type
    string sync_status
  }

  leagues {
    bigint id PK
    string name_zh
    string name_en
  }

  teams {
    bigint id PK
    string name_zh
    string name_en
  }

  matches {
    bigint id PK
    string lottery_match_no
    date lottery_date
    string league_name
    string home_team_name
    string away_team_name
  }

  league_aliases {
    bigint league_id FK
    string alias_normalized UK
  }

  team_aliases {
    bigint team_id FK
    string alias_normalized UK
  }

  provider_league_mappings {
    bigint league_id FK
    string provider_code
    string external_league_id
    string mapping_status
  }

  provider_team_mappings {
    bigint team_id FK
    string provider_code
    string external_team_id
    string mapping_status
  }

  match_source_mappings {
    bigint match_id FK
    string provider_code
    string external_match_id
    string mapping_status
  }

  sporttery_pool_snapshots {
    bigint match_id FK
    numeric had_home_sp
    numeric hhad_home_sp
    char raw_payload_hash
  }

  asian_odds_snapshots {
    bigint match_id FK
    string bookmaker_code
    numeric handicap_line
    numeric total_line
  }
```

### 2.1 角色分工（容易混的三张「映射」）

| 表 | 连什么 | 用来干什么 |
| --- | --- | --- |
| `provider_league_mappings` / `provider_team_mappings` | 供应商 **外部 ID** → `leagues.id` / `teams.id` | 竞彩、亚盘各自联赛/球队 ID 对齐到标准字典 |
| `league_aliases` / `team_aliases` | **人工确认名称别名** → 标准 ID | 「红魔」「Manchester Utd」等名称对齐；**不是**跨源匹配键 |
| `match_source_mappings` | 供应商 **外部比赛 ID** → `matches.id` | 亚盘等外部场次挂到内部同一场比赛（T204 主线） |

标准实体主键（`leagues.id` / `teams.id` / `matches.id`）才是跨源汇合点；别名表只是帮名称落到标准 ID。

## 3. 接入层流转（V1）

每次同步先记运行，再幂等存原始 JSON，再解析写业务表。

```mermaid
flowchart TD
  job[Job / SyncService]
  tpl[ProviderSyncTemplate]
  fetch[Provider 拉取]
  runStart[data_sync_runs RUNNING]
  rawSave[raw_data_payloads 幂等入库]
  parse[解析写业务表]
  runEnd[更新 sync_status 计数]

  job --> tpl
  tpl --> runStart
  tpl --> fetch
  fetch --> rawSave
  rawSave -->|payload_hash 去重| parse
  parse --> runEnd
```

要点：

- `data_providers`：注册源（不含密钥）。
- `data_sync_runs`：一轮同步的状态机与额度统计。
- `raw_data_payloads`：`(provider_code, data_type, request_key, payload_hash)` 唯一，保证同内容不重复落库。

## 4. 体彩比赛池同步（已落地 T202）

内部比赛以体彩身份锚定；当前同步会写展示名，联赛/球队标准 ID 可后续由标准化服务回填。

```mermaid
flowchart TD
  raw[raw_data_payloads SPORTTERY_POOL]
  mapper[SportteryPoolPayloadMapper]
  writer[SportteryPoolMatchWriter]
  match[matches]
  snap[sporttery_pool_snapshots]

  raw --> mapper
  mapper -->|SyncItem 列表| writer
  writer -->|按 lottery_match_no + lottery_date upsert| match
  writer -->|盘口/SP/销售状态有变化才追加| snap
```

| 步骤 | 写入 | 说明 |
| --- | --- | --- |
| 1 | `matches` | 唯一键 `(lottery_match_no, lottery_date)`；必写 `league_name` / 主客队展示名 |
| 2 | `sporttery_pool_snapshots` | 只追加；与上一快照盘口内容相同则跳过 |
| 3 | （可选后续） | 调用标准化服务填 `league_id` / `home_team_id` / `away_team_id` |

`sporttery_pool_snapshots.raw_payload_hash` 回溯到当次 `raw_data_payloads`，便于对账。

## 5. 联赛 / 球队标准化（已落地 T203）

解析优先级（不做模糊相似度合并）：

```mermaid
flowchart TD
  input["入参: providerCode + externalId + displayName"]
  extId["provider_*_mappings<br/>状态 ∈ AUTO_CONFIRMED / MANUAL_CONFIRMED"]
  alias["*_aliases.alias_normalized"]
  exact["leagues/teams 规范化后精确名且唯一"]
  candidate["新建标准实体<br/>有 externalId 则写 PENDING 映射"]

  input --> extId
  extId -->|命中| done[返回标准 ID]
  extId -->|未命中| alias
  alias -->|命中| done
  alias -->|未命中| exact
  exact -->|恰好 1 条| done
  exact -->|0 或多条| candidate
```

人工确认别名：

```mermaid
flowchart LR
  rawName[alias_raw 展示名]
  key[NameNormalizationSupport → alias_normalized]
  table[league_aliases / team_aliases]
  std[leagues.id / teams.id]

  rawName --> key --> table --> std
```

硬性规则：

- 规范化 key 只用于匹配，不改展示名。
- 「曼联」与「曼城」相似也不自动合并，各自候选。
- `confirmAlias` 只写别名表，不自动改其它 `PENDING` 映射。

## 6. 竞彩 ↔ 亚盘如何对齐

比赛级自动映射服务 **T204 已落地**（`MatchMappingService`）。亚盘同步 Job 与快照写入仍待后续接入；确认后才应写 `asian_odds_snapshots`。

```mermaid
flowchart TD
  subgraph sportterySide [体彩侧]
    sRaw[raw SPORTTERY_POOL]
    sMatch[matches<br/>lottery 锚定]
    sSnap[sporttery_pool_snapshots]
    sRaw --> sMatch --> sSnap
  end

  subgraph normalize [标准化层]
    L[leagues]
    T[teams]
    PL[provider_league_mappings]
    PT[provider_team_mappings]
    AL[league_aliases / team_aliases]
  end

  subgraph asianSide [亚盘侧]
    aRaw[raw ASIAN_ODDS]
    msm[match_source_mappings]
    aSnap[asian_odds_snapshots]
    mapSvc[MatchMappingService]
    aRaw --> mapSvc --> msm
    msm -->|AUTO或人工确认| aSnap
  end

  sMatch --> L
  sMatch --> T
  PL --> L
  PT --> T
  AL --> L
  AL --> T
  aRaw --> PL
  aRaw --> PT
  msm --> sMatch
```

### 6.1 MatchMappingService 规则摘要

1. 已确认 `(provider_code, external_match_id)` → 直接复用。
2. 在开赛时间 ±180 分钟内对 `matches` 打分（主客队 ID/名、联赛、时间）。
3. 主客反转、联赛 ID 冲突、时间差 &gt; 60 分钟 → 不得自动确认，写 `PENDING`。
4. 最高分 ≥ 0.85 且与第二名分差 ≥ 0.10 → `AUTO_CONFIRMED`；否则 `PENDING`。
5. 无候选不插入（`match_id` 非空约束）；`listPending` 查待复核队列。
6. V5 增加 `mapping_explanation` / `mapping_candidates` 保存解释与候选。

读法：

1. 两边联赛/球队先各自解析到**同一个** `leagues.id` / `teams.id`。
2. 再在比赛层生成 `match_source_mappings`；确认后亚盘快照才写到该 `matches.id`。
3. 跨源对账主键是 **`matches.id`**。

## 7. `matches` 字段怎么理解

| 字段 | 含义 |
| --- | --- |
| `id` | 内部比赛主键；快照与来源映射都挂这里 |
| `lottery_match_no` + `lottery_date` | 体彩官方身份，产品侧「一场竞彩」的自然键 |
| `league_name` / `home_team_name` / `away_team_name` | 同步时的展示名（标准化前也可有值） |
| `league_id` / `home_team_id` / `away_team_id` | 标准字典外键；可空，标准化后回填 |
| `match_status` | 内部状态机（`SCHEDULED` / `LOCKED` / …） |
| `home_score` / `away_score` | 赛果同步后写入（后续任务） |

## 8. 映射状态共用语义

`provider_*_mappings` 与 `match_source_mappings` 共用：

| status | 含义 |
| --- | --- |
| `PENDING` | 候选，待复核 |
| `AUTO_CONFIRMED` | 自动确认，可参与正式解析 |
| `MANUAL_CONFIRMED` | 人工确认 |
| `REJECTED` | 拒绝，不再自动命中 |

标准化解析**只认** `AUTO_CONFIRMED` / `MANUAL_CONFIRMED` 的外部 ID 映射。

## 9. 与代码的对应关系（便于跳转）

| 流转 | 主要类 |
| --- | --- |
| 同步模板 | `data.service.ProviderSyncTemplate` |
| 体彩池同步 | `SportteryPoolSyncServiceImpl` → `SportteryPoolPayloadMapper` → `SportteryPoolMatchWriter` |
| 联赛/球队标准化 | `LeagueNormalizationService` / `TeamNormalizationService` |
| 名称规范化 | `NameNormalizationSupport` |
| 比赛级映射 | `MatchMappingService` / `MatchMappingScoreSupport` |

## 10. 尚未建表 / 未串通的部分

- 预测、快照、结算相关表：见 `technical-design.md` M3/M4，本文不画。
- 体彩同步写库后**尚未强制**调用标准化回填 `league_id` 等（服务已就绪，接入在后续任务）。
- 亚盘同步 Job 接入 `MatchMappingService` 与仅对已确认映射写 `asian_odds_snapshots`：后续任务（T206）。
- 映射人工确认/拒绝 HTTP API：T205 已落地（`/api/admin/provider/mappings/*`）；生产鉴权仍待 T601，当前 Security 对 admin 路径 denyAll。

## 11. 变更记录

| 日期 | 说明 |
| --- | --- |
| 2026-07-24 | 初版：基于 V1～V4 与 T202/T203 落地情况整理表关系与主链路图 |
| 2026-07-24 | 补充 T204：MatchMappingService 打分/待复核与 V5 解释候选列 |
