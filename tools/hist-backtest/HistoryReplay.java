import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.odds.client.AsianModelProperties;
import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.odds.service.AsianMarketConsensusAggregator;
import com.jingcaicompass.odds.service.AsianMarketMath;
import com.jingcaicompass.odds.service.AsianPublishGate;
import com.jingcaicompass.odds.service.ImpliedPoissonModel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 用 NovaAPI {@code /nova/tf/odds-history} 的时间序列，在任意历史时点复现亚盘预测。
 *
 * <p>这是回测「特征构造」环节的原型：把每个 option 的 {@code points[]} 按「取 ≤T 最后一条」
 * 切成截面，再把截面伪装成一次同步写入的结果，交给<b>生产同一批纯函数</b>聚合、反解 λ、
 * 选冻结行、判置信度。因此结果与「那一刻真跑一遍同步 + 生成」等价，而不是手写公式的近似。</p>
 *
 * <p><b>编译与运行</b>（Git Bash / Windows，仓库根目录执行）：</p>
 *
 * <pre>{@code
 * # 依赖只有 Jackson 三件套，且本目录的 lib/ 与 out/ 不入库（.gitignore 忽略 *.jar / *.class）
 * mkdir -p tools/hist-backtest/lib && cp ~/.m2/repository/com/fasterxml/jackson/core/\
 *   jackson-{databind,core,annotations}/2.18.2/*.jar tools/hist-backtest/lib/
 *
 * javac -encoding UTF-8 -cp "backend/target/classes;tools/hist-backtest/lib/*" \
 *   -d tools/hist-backtest/out tools/hist-backtest/HistoryReplay.java
 *
 * java -cp "tools/hist-backtest/out;tools/hist-backtest/lib/*;backend/target/classes" \
 *   HistoryReplay <odds-history.json> "2026-10-23 17:00:00" "2026-10-22 17:00:00,2026-10-23 14:00:00"
 * }</pre>
 *
 * <p><b>两条实测踩过的坑</b>：① Git Bash 下把 m2 长路径直接写进 {@code -cp} 不生效（Jackson 报
 * {@code NoClassDefFoundError: JsonView}），必须先复制到 {@code lib/} 再用通配符；② 时刻字符串按
 * <b>Asia/Shanghai</b> 解释——源侧 {@code updateTime} 是北京时间，而生产 mapper 按 UTC 解析，
 * 照搬会让「赛前 105 分钟」变成「赛前 585 分钟」。</p>
 *
 * <p>用法：{@code java HistoryReplay <odds-history.json> <kickoff "yyyy-MM-dd HH:mm:ss"> [T1,T2,...]}</p>
 */
public class HistoryReplay {

    private static final int HANDICAP_MARKET_ID = 4;
    private static final int TOTALS_MARKET_ID = 5;
    private static final int TOTALS_LINES_KEPT = 4;

    private static final BigDecimal FIFTY = new BigDecimal("0.5");

    /** 与 BaselinePredictionGenerationServiceImpl 的常量一致。 */
    private static final BigDecimal HIGH_DIRECTION_SIGNAL = new BigDecimal("0.0750");
    private static final BigDecimal MEDIUM_DIRECTION_SIGNAL = new BigDecimal("0.0250");
    private static final BigDecimal HIGH_CONFIDENCE_MARGIN = new BigDecimal("0.1000");
    private static final BigDecimal MEDIUM_CONFIDENCE_MARGIN = new BigDecimal("0.1600");

    private static final Map<String, String> BOOKMAKER_CODES = Map.of(
            "平博", "pinnacle",
            "必发", "betfair",
            "利记", "liji",
            "威廉希尔", "williamhill",
            "Bet365", "bet365",
            "1xBet", "1xbet",
            "韦德", "betvictor");

    private static final String PROVIDER_CODE = "NOVA_API";

    private final Map<String, Map<Integer, List<Option>>> bookmakers;
    private final AsianMarketConsensusAggregator aggregator;
    private final ImpliedPoissonModel model;
    private final AsianPublishGate gate;
    private final Set<String> whitelist;

    record Point(String time, BigDecimal odd) {
    }

    record Option(String side, BigDecimal line, List<Point> points) {
    }

    record Row(
            String bookmaker,
            BigDecimal handicapLine,
            BigDecimal home,
            BigDecimal away,
            BigDecimal totalLine,
            BigDecimal over,
            BigDecimal under,
            String capturedAt
    ) {
    }

    HistoryReplay(Map<String, Map<Integer, List<Option>>> bookmakers) {
        this.bookmakers = bookmakers;
        AsianOddsAggregationProperties aggregation = AsianOddsAggregationProperties.defaults();
        AsianModelProperties modelProperties = AsianModelProperties.defaults();
        this.aggregator = new AsianMarketConsensusAggregator(aggregation);
        this.model = new ImpliedPoissonModel(modelProperties);
        this.gate = new AsianPublishGate(modelProperties);
        this.whitelist = aggregation.normalizedWhitelist();
    }

    public static void main(String[] args) throws Exception {
        Path json = Path.of(args[0]);
        String kickoff = args[1];
        List<String> cuts = args.length > 2
                ? List.of(args[2].split(","))
                : List.of(kickoff);

        JsonNode data = new ObjectMapper().readTree(Files.readString(json, StandardCharsets.UTF_8)).path("data");
        HistoryReplay replay = new HistoryReplay(load(data));

        System.out.println("matchId=" + data.path("matchId").asText()
                + "  idEvent=" + data.path("idEvent").asText()
                + "  kickoff=" + kickoff
                + "  数据源最后变动=" + data.path("updateTime").asText());
        for (String cut : cuts) {
            replay.report(cut, kickoff);
        }
    }

    private static Map<String, Map<Integer, List<Option>>> load(JsonNode data) {
        Map<String, Map<Integer, List<Option>>> result = new LinkedHashMap<>();
        for (JsonNode provider : data.path("providers")) {
            String name = provider.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String code = BOOKMAKER_CODES.getOrDefault(name.trim(), name.trim().toLowerCase(Locale.ROOT));
            Map<Integer, List<Option>> markets = result.computeIfAbsent(code, key -> new LinkedHashMap<>());
            for (JsonNode market : provider.path("markets")) {
                int marketId = market.path("marketId").asInt(-1);
                if (marketId != HANDICAP_MARKET_ID && marketId != TOTALS_MARKET_ID) {
                    continue;
                }
                List<Option> options = markets.computeIfAbsent(marketId, key -> new ArrayList<>());
                for (JsonNode option : market.path("options")) {
                    List<Point> points = new ArrayList<>();
                    for (JsonNode point : option.path("points")) {
                        points.add(new Point(point.path("updateTime").asText(), point.path("odd").decimalValue()));
                    }
                    points.sort(Comparator.comparing(Point::time));
                    options.add(new Option(
                            option.path("option").asText(""),
                            option.path("line").isMissingNode() || option.path("line").isNull()
                                    ? null : new BigDecimal(option.path("line").asText().trim()),
                            points));
                }
            }
        }
        return result;
    }

    /** 截面取价：某个 option 在 T 时刻的价格 = 该 option 在 ≤T 的最后一条变动。 */
    private static BigDecimal oddAt(Option option, String cut) {
        BigDecimal last = null;
        for (Point point : option.points()) {
            if (point.time().compareTo(cut) <= 0) {
                last = point.odd();
            } else {
                break;
            }
        }
        return last;
    }

    /** 把某家庄某市场在 T 时刻的报价配成「档 → 两侧」；两侧缺一则该档不成立。 */
    private Map<BigDecimal, BigDecimal[]> pairs(String bookmaker, int marketId, String cut, String firstSide) {
        Map<BigDecimal, BigDecimal[]> result = new LinkedHashMap<>();
        for (Option option : bookmakers.getOrDefault(bookmaker, Map.of()).getOrDefault(marketId, List.of())) {
            if (option.line() == null) {
                continue;
            }
            BigDecimal price = oddAt(option, cut);
            if (!AsianMarketMath.isUsablePrice(price)) {
                continue;
            }
            BigDecimal line = AsianMarketMath.normalizeLine(option.line());
            BigDecimal[] pair = result.computeIfAbsent(line, key -> new BigDecimal[2]);
            if (firstSide.equalsIgnoreCase(option.side())) {
                pair[0] = price;
            } else {
                pair[1] = price;
            }
        }
        result.entrySet().removeIf(entry -> entry.getValue()[0] == null || entry.getValue()[1] == null);
        return result;
    }

    /** 复刻 NovaApiOddsPayloadMapper.mostBalancedTotalsLines：按跨庄去水概率的平衡度取前 4 档。 */
    private Set<BigDecimal> keptTotalsLines(String cut) {
        Map<BigDecimal, List<BigDecimal>> probabilities = new LinkedHashMap<>();
        Map<BigDecimal, Integer> counts = new LinkedHashMap<>();
        for (String bookmaker : bookmakers.keySet()) {
            pairs(bookmaker, TOTALS_MARKET_ID, cut, "Over").forEach((line, pair) -> {
                probabilities.computeIfAbsent(line, key -> new ArrayList<>())
                        .add(AsianMarketMath.devigFirst(pair[0], pair[1]));
                counts.merge(line, 1, Integer::sum);
            });
        }
        record Candidate(BigDecimal line, BigDecimal distance) {
        }
        Set<BigDecimal> kept = new LinkedHashSet<>();
        probabilities.entrySet().stream()
                .map(entry -> new Candidate(entry.getKey(),
                        AsianMarketMath.median(entry.getValue()).subtract(FIFTY).abs()))
                .sorted(Comparator.comparing(Candidate::distance)
                        .thenComparing(Comparator.comparingInt((Candidate c) -> -counts.getOrDefault(c.line(), 0)))
                        .thenComparing(Candidate::line))
                .limit(TOTALS_LINES_KEPT)
                .forEach(candidate -> kept.add(candidate.line()));
        return kept;
    }

    /** 交叉积落库形态：每家 × 让球档 × 保留的大小球档。 */
    private List<Row> snapshot(String cut) {
        Set<BigDecimal> keptTotals = keptTotalsLines(cut);
        List<Row> rows = new ArrayList<>();
        for (String bookmaker : bookmakers.keySet()) {
            Map<BigDecimal, BigDecimal[]> handicaps = pairs(bookmaker, HANDICAP_MARKET_ID, cut, "Home");
            Map<BigDecimal, BigDecimal[]> totals = pairs(bookmaker, TOTALS_MARKET_ID, cut, "Over");
            List<BigDecimal> totalsLines = totals.keySet().stream()
                    .filter(keptTotals::contains)
                    .sorted()
                    .toList();
            for (Map.Entry<BigDecimal, BigDecimal[]> handicap : handicaps.entrySet()) {
                if (totalsLines.isEmpty()) {
                    rows.add(new Row(bookmaker, handicap.getKey(), handicap.getValue()[0], handicap.getValue()[1],
                            null, null, null, cut));
                    continue;
                }
                for (BigDecimal totalsLine : totalsLines) {
                    BigDecimal[] total = totals.get(totalsLine);
                    rows.add(new Row(bookmaker, handicap.getKey(), handicap.getValue()[0], handicap.getValue()[1],
                            totalsLine, total[0], total[1], cut));
                }
            }
        }
        return rows;
    }

    private void report(String cut, String kickoff) {
        System.out.println();
        System.out.println("================ 截面 T = " + cut + "（距开赛 " + minutesBefore(cut, kickoff) + " 分钟）================");
        List<Row> rows = snapshot(cut);
        if (rows.isEmpty()) {
            System.out.println("  该时刻无任何报价（数据源尚未开出或已停盘）");
            return;
        }

        List<AsianMarketConsensusAggregator.Quote> handicapQuotes = rows.stream()
                .map(row -> new AsianMarketConsensusAggregator.Quote(PROVIDER_CODE, row.bookmaker(),
                        row.handicapLine(), row.home(), row.away(), java.time.Instant.EPOCH, null))
                .toList();
        Map<String, AsianMarketConsensusAggregator.Quote> totalsByKey = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row.totalLine() == null) {
                continue;
            }
            totalsByKey.putIfAbsent(row.bookmaker() + "|" + row.totalLine(),
                    new AsianMarketConsensusAggregator.Quote(PROVIDER_CODE, row.bookmaker(), row.totalLine(),
                            row.over(), row.under(), java.time.Instant.EPOCH, null));
        }

        AsianMarketConsensusAggregator.MarketAggregate handicapAggregate = aggregator.aggregate(handicapQuotes);
        AsianMarketConsensusAggregator.MarketAggregate totalsAggregate =
                aggregator.aggregate(new ArrayList<>(totalsByKey.values()));
        Integer handicapBookmakers = distinctBookmakers(rows, true);
        Integer totalsBookmakers = distinctBookmakers(rows, false);
        System.out.println("  截面家数：让球 " + handicapBookmakers + " 家（" + handicapAggregate.ranked().size()
                + " 档）/ 大小球 " + totalsBookmakers + " 家（" + totalsAggregate.ranked().size() + " 档）");

        if (handicapAggregate.isEmpty()) {
            System.out.println("  让球聚合无合格线（家数不足 2）→ 无法生成");
            return;
        }
        AsianMarketConsensusAggregator.LineConsensus handicap = handicapAggregate.best().orElseThrow();
        Optional<AsianMarketConsensusAggregator.LineConsensus> totalsBest = totalsAggregate.best();
        System.out.println("  让球主盘 " + handicap.line().toPlainString()
                + "  共识 主 " + handicap.firstProbability() + " / 客 " + handicap.secondProbability()
                + "  σ " + handicap.dispersion().toPlainString()
                + "  " + handicap.sampleSource());
        if (totalsBest.isEmpty()) {
            System.out.println("  大小球无反解约束 → INSUFFICIENT_ASIAN_CONSTRAINTS，整场不生成");
            return;
        }
        AsianMarketConsensusAggregator.LineConsensus totalsMain = totalsBest.get();
        System.out.println("  大小球主盘 " + totalsMain.line().toPlainString()
                + "  共识 大 " + totalsMain.firstProbability() + " / 小 " + totalsMain.secondProbability()
                + "  σ " + totalsMain.dispersion().toPlainString()
                + "  " + totalsMain.sampleSource());

        ImpliedPoissonModel.Solution solution = model.solve(
                handicap.line(), handicap.firstProbability().doubleValue(),
                totalsMain.line(), totalsMain.firstProbability().doubleValue());
        if (!solution.feasible()) {
            System.out.println("  λ 反解失败：" + solution.infeasibility() + " → INCONSISTENT_ASIAN_MARKETS");
            return;
        }
        ImpliedPoissonModel.NeighborCheck neighbor = model.check(
                solution.lambdaHome(), solution.lambdaAway(),
                handicapAggregate.ranked(), handicap.line(),
                totalsAggregate.ranked(), totalsMain.line());
        System.out.printf("  λ 主 %.4f / 客 %.4f（残差 %.1e / %.1e，邻线最大偏差 %.4f%%（%d 档））%n",
                solution.lambdaHome(), solution.lambdaAway(),
                solution.coverResidual(), solution.overResidual(),
                neighbor.maxDeviation() * 100, neighbor.lineCount());

        Optional<Row> frozenRow = freeze(rows, handicap, totalsMain);
        if (frozenRow.isEmpty()) {
            System.out.println("  共识线上没有「四个水位齐全」的快照行 → NO_ASIAN_SNAPSHOT_ON_CONSENSUS_LINE，整场不生成");
            return;
        }
        Row frozen = frozenRow.get();
        if (frozen.totalLine().compareTo(totalsMain.line()) != 0) {
            System.out.println("  冻结行大小球线 " + frozen.totalLine().toPlainString()
                    + " ≠ 主盘线 " + totalsMain.line().toPlainString() + " → 整场跳过");
            return;
        }
        AsianMarketConsensusAggregator.LineConsensus totalsAtLine =
                totalsAggregate.atLine(frozen.totalLine()).orElse(null);
        if (totalsAtLine == null) {
            System.out.println("  判定线没有聚合共识 → 整场跳过");
            return;
        }

        double cover = model.project(solution.lambdaHome(), solution.lambdaAway(),
                frozen.handicapLine(), ImpliedPoissonModel.Market.HANDICAP, ImpliedPoissonModel.Side.FIRST);
        double over = model.project(solution.lambdaHome(), solution.lambdaAway(),
                frozen.totalLine(), ImpliedPoissonModel.Market.TOTALS, ImpliedPoissonModel.Side.FIRST);

        String handicapConfidence = confidence(frozen.home(), frozen.away());
        boolean handicapDispersion = gate.exceedsDispersion(handicap.dispersion());
        String handicapPick = handicapDispersion || "LOW".equals(handicapConfidence)
                ? null
                : (favorsFirst(cover, frozen.home(), frozen.away()) ? "主队赢盘" : "客队赢盘");

        String totalsConfidence = confidence(frozen.over(), frozen.under());
        boolean totalsDispersion = gate.exceedsDispersion(totalsAtLine.dispersion());
        String totalsPick = totalsDispersion || "LOW".equals(totalsConfidence)
                ? null
                : (favorsFirst(over, frozen.over(), frozen.under()) ? "大球" : "小球");

        System.out.printf("  冻结行：%s（%s）让球 %s 主 @%s / 客 @%s ；大小球 %s 大 @%s / 小 @%s%n",
                frozen.bookmaker(),
                whitelist.contains(frozen.bookmaker()) ? "白名单" : "非白名单",
                frozen.handicapLine().toPlainString(), frozen.home().toPlainString(), frozen.away().toPlainString(),
                frozen.totalLine().toPlainString(), frozen.over().toPlainString(), frozen.under().toPlainString());

        reportMarket("让球", handicapPick, handicapConfidence, cover, handicap.firstProbability(),
                frozen.home(), frozen.away(), handicapDispersion);
        reportMarket("大小球", totalsPick, totalsConfidence, over, totalsAtLine.firstProbability(),
                frozen.over(), frozen.under(), totalsDispersion);

        if (handicapPick == null && totalsPick == null) {
            System.out.println("  ⇒ 整场跳过：" + (handicapDispersion && totalsDispersion
                    ? "MARKET_DISPERSION_TOO_HIGH" : "LOW_CONFIDENCE_ASIAN_MARKETS"));
        } else {
            System.out.println("  ⇒ 会发布：" + (handicapPick == null ? "" : "让球 " + handicapPick + "；")
                    + (totalsPick == null ? "" : "大小球 " + totalsPick));
        }
    }

    private void reportMarket(String label, String pick, String confidence, double modelProbability,
                              BigDecimal marketProbability, BigDecimal firstPrice, BigDecimal secondPrice,
                              boolean dispersionExceeded) {
        BigDecimal breakeven = BigDecimal.ONE.divide(firstPrice, 6, RoundingMode.HALF_UP);
        String side = "客队/小球".equals(pick) ? "次边" : "首边";
        BigDecimal modelPick = BigDecimal.valueOf(modelProbability).setScale(6, RoundingMode.HALF_UP);
        BigDecimal marketPick = marketProbability.setScale(6, RoundingMode.HALF_UP);
        BigDecimal price = firstPrice;
        if (pick != null && (pick.equals("客队赢盘") || pick.equals("小球"))) {
            modelPick = BigDecimal.ONE.subtract(modelPick);
            marketPick = BigDecimal.ONE.subtract(marketPick);
            price = secondPrice;
            breakeven = BigDecimal.ONE.divide(price, 6, RoundingMode.HALF_UP);
        }
        System.out.printf("    %s：%s %s ｜ 模型 %s / 市场 %s / edge %s ｜ 概率 %.6f ｜ 抽水%s ｜ 盈亏平衡 %s%n",
                label,
                pick == null ? "不发布" : pick,
                confidence == null ? "-" : confidence,
                modelPick, marketPick, modelPick.subtract(marketPick),
                modelProbability,
                dispersionExceeded ? "超限(σ)" : "正常",
                breakeven);
        if (pick == null) {
            System.out.println("      （置信度 LOW 的原因是冻结报价抽水高或两侧信号弱，见上两行）");
        }
        if (side.isEmpty()) {
            return;
        }
    }

    /** 复刻 AsianFreezeSnapshotSelector 的 7 键排序（去掉 id 这一键，截面内无快照 id）。 */
    private Optional<Row> freeze(List<Row> rows,
                                 AsianMarketConsensusAggregator.LineConsensus handicap,
                                 AsianMarketConsensusAggregator.LineConsensus totals) {
        return rows.stream()
                .filter(row -> row.handicapLine().compareTo(handicap.line()) == 0)
                .filter(row -> AsianMarketMath.isUsablePrice(row.home())
                        && AsianMarketMath.isUsablePrice(row.away())
                        && row.totalLine() != null
                        && AsianMarketMath.isUsablePrice(row.over())
                        && AsianMarketMath.isUsablePrice(row.under()))
                .min(Comparator
                        .comparing((Row row) -> row.totalLine().compareTo(totals.line()) == 0,
                                Comparator.reverseOrder())
                        .thenComparing(row -> whitelist.contains(row.bookmaker().toLowerCase(Locale.ROOT)),
                                Comparator.reverseOrder())
                        .thenComparing(row -> agreesOnSide(row, handicap), Comparator.reverseOrder())
                        .thenComparing(row -> priceGap(row, handicap))
                        .thenComparing(Row::capturedAt, Comparator.nullsLast(Comparator.reverseOrder())));
    }

    private boolean agreesOnSide(Row row, AsianMarketConsensusAggregator.LineConsensus consensus) {
        BigDecimal rowProbability = AsianMarketMath.devigFirst(row.home(), row.away());
        if (rowProbability.compareTo(FIFTY) == 0 || consensus.firstProbability().compareTo(FIFTY) == 0) {
            return true;
        }
        return rowProbability.compareTo(FIFTY) > 0 == consensus.firstProbability().compareTo(FIFTY) > 0;
    }

    private BigDecimal priceGap(Row row, AsianMarketConsensusAggregator.LineConsensus consensus) {
        return row.home().subtract(consensus.fairFirstPrice()).abs()
                .add(row.away().subtract(consensus.fairSecondPrice()).abs());
    }

    private Integer distinctBookmakers(List<Row> rows, boolean handicap) {
        return (int) rows.stream()
                .filter(row -> handicap || row.totalLine() != null)
                .map(Row::bookmaker)
                .distinct()
                .count();
    }

    private static String confidence(BigDecimal firstPrice, BigDecimal secondPrice) {
        BigDecimal primary = AsianMarketMath.inverse(firstPrice);
        BigDecimal secondary = AsianMarketMath.inverse(secondPrice);
        BigDecimal total = primary.add(secondary);
        BigDecimal margin = total.subtract(BigDecimal.ONE);
        BigDecimal signal = primary.subtract(secondary).abs().divide(total, 6, RoundingMode.HALF_UP);
        if (margin.compareTo(HIGH_CONFIDENCE_MARGIN) <= 0 && signal.compareTo(HIGH_DIRECTION_SIGNAL) >= 0) {
            return "HIGH";
        }
        if (margin.compareTo(MEDIUM_CONFIDENCE_MARGIN) <= 0 && signal.compareTo(MEDIUM_DIRECTION_SIGNAL) >= 0) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static boolean favorsFirst(double modelProbability, BigDecimal firstPrice, BigDecimal secondPrice) {
        double comparison = modelProbability - 0.5d;
        if (comparison > 0) {
            return true;
        }
        if (comparison < 0) {
            return false;
        }
        return firstPrice.compareTo(secondPrice) <= 0;
    }

    private static long minutesBefore(String cut, String kickoff) {
        return java.time.Duration.between(
                java.time.LocalDateTime.parse(cut.replace(' ', 'T')),
                java.time.LocalDateTime.parse(kickoff.replace(' ', 'T'))).toMinutes();
    }
}
