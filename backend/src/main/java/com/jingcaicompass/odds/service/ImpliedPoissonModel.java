package com.jingcaicompass.odds.service;

import com.jingcaicompass.odds.client.AsianModelProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * 由两个市场的主盘概率联合反解期望进球参数（λ_home / λ_away），并把这对参数投影回任意盘口线。
 *
 * <p>只做计算，不读写数据库，也不依赖 Spring，使用方直接 {@code new}。数学推导见
 * {@code docs/asian-model-design.md} §4.2；这里只记录三条实现上的关键事实：</p>
 *
 * <ol>
 *   <li><b>概率口径统一</b>：一条盘口线先按线型拆子盘（整数线与 x.5 为单盘，x.25/x.75 拆成
 *       两个相邻半盘），每个子盘判赢/走/输，再合并为赢/半赢/走/半输/输五类。公平概率取
 *       {@code (W + 0.5·HW) / (W + 0.5·HW + 0.5·HL + L)}，这是零 EV 定义下的解，半盘、整盘
 *       （走盘被条件化剔除）与四分之一盘（半赢半输按半权）共用一条公式，两侧之和恒为 1。</li>
 *   <li><b>不建二维网格</b>：独立泊松之和仍是泊松，因此大小球投影只依赖 λ 总和，与两翼怎么分
 *       无关。求解于是降为两段一维二分：先由大球概率定总和，再由赢盘概率定 λ_home。两段都严格
 *       单调（随机占优），根唯一，不会像二维网格那样停在平台上，精度也不受网格步长限制。</li>
 *   <li><b>主盘残差不是自洽性检验</b>：两方程两未知数是恰定方程组，只要目标点落在模型的像里，
 *       残差必然趋近 0。判断两个市场是否打架，要看解出的 λ 能否复现主盘以外的邻线，见
 *       {@link #check}。</li>
 * </ol>
 */
public class ImpliedPoissonModel {

    /** 让球或大小球。 */
    public enum Market {
        HANDICAP,
        TOTALS
    }

    /** 首边（主队 / 大球）或次边（客队 / 小球）。 */
    public enum Side {
        FIRST,
        SECOND
    }

    /** 无解的成因；{@link Infeasibility#NONE} 表示正常解出。 */
    public enum Infeasibility {
        NONE,
        INVALID_INPUT,
        TOTALS_PROBABILITY_OUT_OF_RANGE,
        HANDICAP_PROBABILITY_OUT_OF_RANGE,
        LAMBDA_SPLIT_IMPOSSIBLE
    }

    /** 单边 λ 搜索下界。 */
    public static final double MIN_LAMBDA = 0.20d;

    /** 单边 λ 搜索上界。 */
    public static final double MAX_LAMBDA = 4.00d;

    /** 邻线校验允许的最少报价家数；单家庄的线噪声过大，参与校验会误杀。 */
    public static final int MIN_NEIGHBOR_BOOKMAKERS = 2;

    /**
     * 二分次数。80 次在 [0.4, 8.0] 上把区间压到 1e-23，远超 double 有效位，实际受浮点极限约束；
     * 实测回投残差 1e-7，验收线 1e-4 有充分余量。
     */
    private static final int BISECTION_ITERATIONS = 80;

    /**
     * 五类结果的编号。<b>顺序是承重的</b>：次边与首边互为镜像，镜像恰为 {@code 4 - code}，
     * 因此不要调整这几个常量的相对次序。
     */
    private static final int WIN = 0;
    private static final int HALF_WIN = 1;
    private static final int PUSH = 2;
    private static final int HALF_LOSS = 3;
    private static final int LOSS = 4;

    /** 分子系数：赢计满权、半赢计半权、半输与输不贡献、走盘不计。 */
    private static final double[] NUMERATOR = {1.0d, 0.5d, 0.0d, 0.0d, 0.0d};

    /** 分母系数：赢与输计满权、半赢与半输计半权、走盘从分母剔除（条件于不 push）。 */
    private static final double[] DENOMINATOR = {1.0d, 0.5d, 0.0d, 0.5d, 1.0d};

    private static final double QUARTER = 0.25d;

    private final int maxGoals;

    public ImpliedPoissonModel(AsianModelProperties properties) {
        AsianModelProperties effective = properties == null ? AsianModelProperties.defaults() : properties;
        this.maxGoals = effective.maxGoals();
    }

    public ImpliedPoissonModel() {
        this(null);
    }

    /**
     * 单点投影：给定 λ 与一条盘口线，返回该边在零 EV 口径下的公平概率。
     *
     * <p>两侧之和恒为 1，因此调用方只需取首边；保留 {@link Side} 参数是为了让「两侧互补」这条
     * 性质本身可测，而不是靠恒等式掩盖错误。</p>
     */
    public double project(double lambdaHome, double lambdaAway, BigDecimal line, Market market, Side side) {
        if (line == null || market == null) {
            throw new IllegalArgumentException("line and market are required");
        }
        Side effectiveSide = side == null ? Side.FIRST : side;
        if (market == Market.TOTALS) {
            return projectTotals(lambdaHome + lambdaAway, totalsClasses(line), effectiveSide);
        }
        return projectHandicap(lambdaHome, lambdaAway, handicapClasses(line), effectiveSide);
    }

    /**
     * 反解 λ。两个约束取各自市场的主盘线（聚合器已保证该线报价家数达标）。
     *
     * @param handicapLine      让球主盘线
     * @param coverProbability  该线去水后的主队赢盘概率
     * @param totalsLine        大小球主盘线
     * @param overProbability   该线去水后的大球概率
     */
    public Solution solve(
            BigDecimal handicapLine,
            double coverProbability,
            BigDecimal totalsLine,
            double overProbability) {
        if (handicapLine == null || totalsLine == null
                || !usableProbability(coverProbability) || !usableProbability(overProbability)) {
            return infeasible(Infeasibility.INVALID_INPUT);
        }

        // 第一步：由大球概率定 λ 总和。大小球概率关于总和严格递增，二分唯一。
        int[] totalsClasses = totalsClasses(totalsLine);
        double lowSum = 2 * MIN_LAMBDA;
        double highSum = 2 * MAX_LAMBDA;
        double overAtLow = projectTotals(lowSum, totalsClasses, Side.FIRST);
        double overAtHigh = projectTotals(highSum, totalsClasses, Side.FIRST);
        // 区间取闭：目标恰好落在端点说明真实 λ 就在搜索域边界上，是可表示的，应当接受；
        // 落在区间外才代表域不足（例如需要 λ 总和 > 8），此时无论怎么解都是被截断的错解。
        if (overProbability < overAtLow || overProbability > overAtHigh) {
            return infeasible(Infeasibility.TOTALS_PROBABILITY_OUT_OF_RANGE);
        }
        double low = lowSum;
        double high = highSum;
        for (int i = 0; i < BISECTION_ITERATIONS; i++) {
            double middle = (low + high) / 2;
            if (projectTotals(middle, totalsClasses, Side.FIRST) < overProbability) {
                low = middle;
            } else {
                high = middle;
            }
        }
        double totalLambda = (low + high) / 2;

        // 第二步：总和固定后，赢盘概率关于 λ_home 严格递增（质量逐步移向主队），二分唯一。
        int[] handicapClasses = handicapClasses(handicapLine);
        double minHome = MIN_LAMBDA;
        double maxHome = Math.min(MAX_LAMBDA, totalLambda - MIN_LAMBDA);
        if (maxHome < minHome) {
            return infeasible(Infeasibility.LAMBDA_SPLIT_IMPOSSIBLE);
        }
        double coverAtMin = projectHandicap(minHome, totalLambda - minHome, handicapClasses, Side.FIRST);
        double coverAtMax = projectHandicap(maxHome, totalLambda - maxHome, handicapClasses, Side.FIRST);
        if (coverProbability < coverAtMin || coverProbability > coverAtMax) {
            return infeasible(Infeasibility.HANDICAP_PROBABILITY_OUT_OF_RANGE);
        }
        low = minHome;
        high = maxHome;
        for (int i = 0; i < BISECTION_ITERATIONS; i++) {
            double middle = (low + high) / 2;
            if (projectHandicap(middle, totalLambda - middle, handicapClasses, Side.FIRST) < coverProbability) {
                low = middle;
            } else {
                high = middle;
            }
        }
        double lambdaHome = (low + high) / 2;
        double lambdaAway = totalLambda - lambdaHome;

        double coverResidual = Math.abs(
                projectHandicap(lambdaHome, lambdaAway, handicapClasses, Side.FIRST) - coverProbability);
        double overResidual = Math.abs(
                projectTotals(totalLambda, totalsClasses, Side.FIRST) - overProbability);
        return new Solution(lambdaHome, lambdaAway, coverResidual, overResidual, Infeasibility.NONE);
    }

    /**
     * 邻线校验：把解出的 λ 投到主盘以外的合格档位，与这些档位的共识概率比对，取最大绝对偏差。
     *
     * <p>这才是「两个市场是否自洽」的真实检验：主盘两方程是恰定的，残差天然趋近 0；而邻线是
     * 过定的，模型若只拟合了主盘而整体分布不对，邻线偏差会立刻显形。只统计报价家数达标的线，
     * 单家庄的线自身含噪，纳入会误杀。</p>
     */
    public NeighborCheck check(
            double lambdaHome,
            double lambdaAway,
            List<AsianMarketConsensusAggregator.LineConsensus> handicapRanked,
            BigDecimal handicapMainLine,
            List<AsianMarketConsensusAggregator.LineConsensus> totalsRanked,
            BigDecimal totalsMainLine) {
        double maxDeviation = 0.0d;
        int lineCount = 0;
        Market worstMarket = null;
        BigDecimal worstLine = null;

        if (handicapRanked != null) {
            for (AsianMarketConsensusAggregator.LineConsensus consensus : handicapRanked) {
                if (!eligible(consensus, handicapMainLine)) {
                    continue;
                }
                double model = projectHandicap(lambdaHome, lambdaAway, handicapClasses(consensus.line()), Side.FIRST);
                double deviation = Math.abs(model - consensus.firstProbability().doubleValue());
                lineCount++;
                if (deviation > maxDeviation) {
                    maxDeviation = deviation;
                    worstMarket = Market.HANDICAP;
                    worstLine = consensus.line();
                }
            }
        }
        if (totalsRanked != null) {
            double totalLambda = lambdaHome + lambdaAway;
            for (AsianMarketConsensusAggregator.LineConsensus consensus : totalsRanked) {
                if (!eligible(consensus, totalsMainLine)) {
                    continue;
                }
                double model = projectTotals(totalLambda, totalsClasses(consensus.line()), Side.FIRST);
                double deviation = Math.abs(model - consensus.firstProbability().doubleValue());
                lineCount++;
                if (deviation > maxDeviation) {
                    maxDeviation = deviation;
                    worstMarket = Market.TOTALS;
                    worstLine = consensus.line();
                }
            }
        }
        return new NeighborCheck(maxDeviation, lineCount, worstMarket, worstLine);
    }

    private boolean eligible(AsianMarketConsensusAggregator.LineConsensus consensus, BigDecimal mainLine) {
        return consensus != null
                && consensus.sampleCount() >= MIN_NEIGHBOR_BOOKMAKERS
                && consensus.firstProbability() != null
                && !sameLine(consensus.line(), mainLine);
    }

    private boolean sameLine(BigDecimal candidate, BigDecimal mainLine) {
        return candidate != null && mainLine != null && candidate.compareTo(mainLine) == 0;
    }

    private double projectHandicap(double lambdaHome, double lambdaAway, int[] classes, Side side) {
        double[] homePmf = poissonPmf(lambdaHome, maxGoals);
        double[] awayPmf = poissonPmf(lambdaAway, maxGoals);
        double numerator = 0.0d;
        double denominator = 0.0d;
        int index = 0;
        for (int home = 0; home <= maxGoals; home++) {
            double homeMass = homePmf[home];
            for (int away = 0; away <= maxGoals; away++, index++) {
                double joint = homeMass * awayPmf[away];
                if (joint <= 0.0d) {
                    continue;
                }
                int code = classes[index];
                if (side == Side.SECOND) {
                    code = mirror(code);
                }
                numerator += NUMERATOR[code] * joint;
                denominator += DENOMINATOR[code] * joint;
            }
        }
        return denominator > 0.0d ? numerator / denominator : 0.0d;
    }

    private double projectTotals(double totalLambda, int[] classes, Side side) {
        double[] totalPmf = poissonPmf(totalLambda, 2 * maxGoals);
        double numerator = 0.0d;
        double denominator = 0.0d;
        for (int total = 0; total < totalPmf.length; total++) {
            double mass = totalPmf[total];
            if (mass <= 0.0d) {
                continue;
            }
            int code = classes[total];
            if (side == Side.SECOND) {
                code = mirror(code);
            }
            numerator += NUMERATOR[code] * mass;
            denominator += DENOMINATOR[code] * mass;
        }
        return denominator > 0.0d ? numerator / denominator : 0.0d;
    }

    /** 每个比分格或总进球值的结果类别；系数表在构造时算一次，投影时只剩乘加。 */
    private int[] handicapClasses(BigDecimal line) {
        double value = line.doubleValue();
        boolean split = isQuarterLine(line);
        double lower = split ? value - QUARTER : value;
        double upper = split ? value + QUARTER : value;
        int[] classes = new int[(maxGoals + 1) * (maxGoals + 1)];
        int index = 0;
        for (int home = 0; home <= maxGoals; home++) {
            for (int away = 0; away <= maxGoals; away++, index++) {
                int difference = home - away;
                classes[index] = classify(difference + lower, difference + upper, split);
            }
        }
        return classes;
    }

    private int[] totalsClasses(BigDecimal line) {
        double value = line.doubleValue();
        boolean split = isQuarterLine(line);
        double lower = split ? value - QUARTER : value;
        double upper = split ? value + QUARTER : value;
        int[] classes = new int[2 * maxGoals + 1];
        for (int total = 0; total < classes.length; total++) {
            classes[total] = classify(total - lower, total - upper, split);
        }
        return classes;
    }

    /**
     * 判断线型是否要拆成两个半盘。
     *
     * <p>复用 {@link AsianMarketMath#lineKindRank}，它内部已对余数取绝对值——{@code BigDecimal}
     * 的 {@code remainder} 保留被除数符号，{@code -0.25} 的余数是 {@code -0.25} 而非
     * {@code 0.75}，直接比较会把最常见的 -0.25 主盘误判成半盘。</p>
     */
    private boolean isQuarterLine(BigDecimal line) {
        return AsianMarketMath.lineKindRank(line) == 1;
    }

    /**
     * 一条（或拆开后两半）盘口的赢/走/输判定。
     *
     * <p>不做 epsilon 容差：盘口线只可能是 0.25 的整数倍，比分差与总进球都是整数，四者相加在
     * double 下可精确表示，{@code margin == 0} 严格成立。加容差反而会把恰好走盘的格子算成赢。</p>
     */
    private int classify(double lowerMargin, double upperMargin, boolean split) {
        int lowerSign = sign(lowerMargin);
        if (!split) {
            return lowerSign > 0 ? WIN : (lowerSign < 0 ? LOSS : PUSH);
        }
        int combined = lowerSign + sign(upperMargin);
        if (combined == 2) {
            return WIN;
        }
        if (combined == -2) {
            return LOSS;
        }
        if (combined == 0) {
            // 拆盘后两个子盘差 0.5，一赢一输与两半同时走盘都不可能，此分支实际不可达；
            // 保留为走盘而不是抛异常，避免一个纯分类函数因为输入越界中断整批生成。
            return PUSH;
        }
        return combined > 0 ? HALF_WIN : HALF_LOSS;
    }

    private int sign(double margin) {
        if (margin > 0.0d) {
            return 1;
        }
        return margin < 0.0d ? -1 : 0;
    }

    /** 首边与次边互为镜像：赢变输、半赢变半输、走盘不变。 */
    private int mirror(int code) {
        return LOSS - code;
    }

    /**
     * 泊松概率质量，截断到 {@code max} 后按行归一化。
     *
     * <p>截断上限与精度直接相关：λ 总和最大可达 8.0，此时上限 16 的截断尾部质量是 3.7e-3，
     * 足以让「往返误差 &lt; 1e-4」的验收失效；上限 24 才降到 1.2e-6。归一化把剩余尾部按比例
     * 分摊，因此校验依据是「归一后的和恒为 1」而不是「未截断的尾部可忽略」。</p>
     */
    double[] poissonPmf(double lambda, int max) {
        double[] pmf = new double[max + 1];
        double term = Math.exp(-lambda);
        double sum = 0.0d;
        for (int k = 0; k <= max; k++) {
            if (k > 0) {
                term = term * lambda / k;
            }
            pmf[k] = term;
            sum += term;
        }
        if (sum > 0.0d) {
            for (int k = 0; k <= max; k++) {
                pmf[k] /= sum;
            }
        }
        return pmf;
    }

    private boolean usableProbability(double probability) {
        return !Double.isNaN(probability)
                && !Double.isInfinite(probability)
                && probability > 0.0d
                && probability < 1.0d;
    }

    private Solution infeasible(Infeasibility reason) {
        // 残差给正无穷，任何「残差超阈值」的判定都会命中，避免调用方漏检 infeasibility 时放行错解。
        return new Solution(0.0d, 0.0d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, reason);
    }

    /** 反解结果；{@code infeasibility} 非 {@code NONE} 时 λ 与残差均不可用。 */
    public record Solution(
            double lambdaHome,
            double lambdaAway,
            double coverResidual,
            double overResidual,
            Infeasibility infeasibility
    ) {

        public boolean feasible() {
            return infeasibility == Infeasibility.NONE;
        }

        public double maxResidual() {
            return Math.max(coverResidual, overResidual);
        }
    }

    /** 邻线校验结果；{@code lineCount == 0} 表示没有合格的邻线，校验未实际执行。 */
    public record NeighborCheck(
            double maxDeviation,
            int lineCount,
            Market worstMarket,
            BigDecimal worstLine
    ) {

        public boolean validated() {
            return lineCount > 0;
        }

        public boolean within(BigDecimal tolerance) {
            return lineCount == 0 || maxDeviation <= tolerance.doubleValue();
        }
    }
}
