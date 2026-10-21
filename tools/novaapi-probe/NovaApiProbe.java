import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NovaAPI 数据源验证探针（JDK 17+ 单文件运行，无第三方依赖）。
 *
 * 用途：一次性验证接入前必须确认的 5 件事，结果落盘为原始 JSON 供后续分析。
 * 运行：
 *   java NovaApiProbe.java selftest
 *   NOVA_API_KEY=xxx NOVA_PRIVATE_KEY_B64=yyy java NovaApiProbe.java all
 */
public class NovaApiProbe {

    private static final String BASE_URL = "https://novaapi.tsgnkj.com";

    /** Ed25519 PKCS8 最小前缀（RFC 8410），用于把裸 32 字节私钥补成合法 PKCS8。 */
    private static final byte[] ED25519_MINIMAL_HEADER = new byte[]{
            0x30, 0x2e, 0x02, 0x01, 0x00,
            0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70,
            0x04, 0x22, 0x04, 0x20
    };

    private static final HttpClient HTTP = buildClient();

    /** 若环境变量配置了代理（企业网络/沙箱常见），让请求走代理，否则直连。 */
    private static HttpClient buildClient() {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        String raw = System.getenv("HTTPS_PROXY");
        if (raw == null) {
            raw = System.getenv("https_proxy");
        }
        if (raw == null) {
            raw = System.getenv("HTTP_PROXY");
        }
        if (raw == null) {
            raw = System.getenv("http_proxy");
        }
        if (raw != null && !raw.isBlank()) {
            try {
                java.net.URI u = java.net.URI.create(raw.contains("://") ? raw : "http://" + raw);
                if (u.getHost() != null && u.getPort() > 0) {
                    b.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(u.getHost(), u.getPort())));
                    System.err.println("[proxy] " + u.getHost() + ":" + u.getPort());
                }
            } catch (RuntimeException ignored) {
                // 代理串不可解析则退回直连
            }
        }
        return b.build();
    }

    private static String apiKey;
    private static String privateKeyB64;
    private static Path outDir;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        outDir = Path.of("novaapi-probe-out", DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(java.time.LocalDateTime.now()));
        Files.createDirectories(outDir);

        if ("selftest".equals(mode)) {
            selftest();
            return;
        }

        apiKey = envOrArg(args, "--api-key", "NOVA_API_KEY");
        privateKeyB64 = envOrArg(args, "--private-key", "NOVA_PRIVATE_KEY_B64");
        String keyFile = envOrArg(args, "--private-key-file", "NOVA_PRIVATE_KEY_FILE");
        if (privateKeyB64 == null && keyFile != null) {
            privateKeyB64 = Files.readString(Path.of(keyFile)).trim();
        }
        boolean fakeMode = "auth".equals(mode) && apiKey == null;
        if (apiKey == null || privateKeyB64 == null) {
            if ("auth".equals(mode)) {
                // 用临时密钥对发一次假请求，只为确认鉴权链路可达与错误格式
                KeyPair kp = generateKeyPair();
                apiKey = "probe-fake-key";
                privateKeyB64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
                fakeMode = true;
                System.out.println("[模式] 无真实凭证，使用临时密钥对做鉴权链路探测\n");
            } else {
                System.out.println("缺少凭证。请设置环境变量 NOVA_API_KEY / NOVA_PRIVATE_KEY_B64，"
                        + "或使用 --api-key= / --private-key-file= 传参。");
                System.out.println("提示：先跑 `java NovaApiProbe.java selftest` 可验证签名与网络，无需凭证。");
                return;
            }
        }

        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        String d5 = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(5).toString();
        String d1 = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();

        // 通用探测：raw <path> [k=v,k=v]
        if ("raw".equals(mode)) {
            String path = args.length > 1 ? args[1] : "/nova/sports-lottery/list";
            String body = call("raw-" + path.replaceAll("[^a-zA-Z0-9]", "_"),
                    path, args.length > 2 ? csvToMap(args[2]) : map());
            System.out.println("   → " + abbreviate(body, 300));
            return;
        }

        Map<String, String> r = new LinkedHashMap<>();

        // 1) 鉴权 + 竞彩赛程（拿到真实 matchId 才能继续）
        //    已实测：/sports-lottery/list 不接受 date 参数，传了反而签名校验失败；带日期要用 all-list
        String listBody = call("01-sports-lottery-all-list", "/nova/sports-lottery/all-list", map("date", today));
        r.put("赛程", listBody);
        List<String> matchIds = extract(listBody, "\"matchId\"\\s*:\\s*(\\d+)");
        System.out.println("   → 解析到 matchId 数：" + matchIds.size());

        if (!matchIds.isEmpty() && !fakeMode) {
            String mid = matchIds.get(0);

            // 2) 机构×玩法是否一次全返（决定额度模型）
            String oddsAll = call("02-tf-odds-all", "/nova/tf/odds", map("matchId", mid));
            r.put("赔率全部", oddsAll);
            reportOdds(oddsAll, "省略 providerId/marketId");

            // 3) 带过滤再查一次，确认参数生效
            String oddsFiltered = call("03-tf-odds-filtered", "/nova/tf/odds",
                    map("matchId", mid, "providerId", "4", "marketId", "4"));
            reportOdds(oddsFiltered, "providerId=4 + marketId=4（平博·亚盘让球）");

            // 4) 亚盘盘口线是否含 .25/.75 四分之一盘（决定拆盘结算逻辑）
            String hist = call("04-tf-odds-history", "/nova/tf/odds-history",
                    map("matchId", mid, "providerId", "4", "marketId", "4"));
            r.put("赔率历史", hist);
            int points = extract(hist, "\"odd\"\\s*:").size();
            System.out.println("   → 赔率历史数据点：" + points + "（>0 说明 CLV 可用）");

            // 5) 静态字典
            call("05-tf-bookmakers", "/nova/tf/bookmakers", map());
            call("06-tf-bet-types", "/nova/tf/bet-types", map());
        }

        // 6) 赛果回溯深度：单次查询上限 30 天，因此按 <=30 天窗口分段探测
        ZoneId sh = ZoneId.of("Asia/Shanghai");
        String[][] wins = {
                {"07-results-d0-5", LocalDate.now(sh).minusDays(5).toString(), d1},
                {"08-results-d6-30", LocalDate.now(sh).minusDays(30).toString(), LocalDate.now(sh).minusDays(6).toString()},
                {"09-results-d31-60", LocalDate.now(sh).minusDays(60).toString(), LocalDate.now(sh).minusDays(31).toString()},
                {"14-results-d91-120", LocalDate.now(sh).minusDays(120).toString(), LocalDate.now(sh).minusDays(91).toString()},
        };
        for (String[] w : wins) {
            String body = call(w[0], "/nova/text/match-results", map("startDate", w[1], "endDate", w[2]));
            int n = extract(body, "\"matchId\"\\s*:").size();
            System.out.println("   → 赛果条数 " + w[1] + " ~ " + w[2] + "：" + n);
            r.put(w[0], body);
        }
        String d30 = LocalDate.now(sh).minusDays(30).toString();

        // 7) 回测可行性：拿 30 天前的已结束赛事反查历史赔率，这是回测的真正门槛
        if (!fakeMode) {
            List<String> oldIds = extract(r.getOrDefault("08-results-d6-30", ""), "\"matchId\"\\s*:\\s*(\\d+)");
            String oldId = oldIds.isEmpty() ? null : oldIds.get(0);
            System.out.println();
            System.out.println("===== 回测可行性（30 天前赛事 id=" + oldId + "）=====");
            if (oldId == null) {
                System.out.println("   → 30 天前无赛果，无法验证历史赔率，需换更近的窗口");
            } else {
                String oldOdds = call("10-old-tf-odds", "/nova/tf/odds", map("matchId", oldId));
                System.out.println("   → 历史赛事赛前赔率快照：" + extract(oldOdds, "\"option\"\\s*:").size() + " 个选项");

                String oldHist = call("11-old-tf-odds-history", "/nova/tf/odds-history", map("matchId", oldId));
                int pts = extract(oldHist, "\"odd\"\\s*:").size();
                System.out.println("   → 历史赛事赔率变动 points：" + pts + (pts > 0 ? " ✓ 可回测" : " ✗ 无历史赔率，回测不可行"));

                // 赛事列表是否支持历史日期（回测需要按天枚举历史赛事）
                String ev = call("12-soccer-events-d30", "/nova/tsd/soccer-events", map("date", d30));
                System.out.println("   → 历史日期赛事列表条数：" + extract(ev, "\"matchId\"\\s*:").size());
                reportScoreScopes(ev);

                // 事件流能否还原 90 分钟比分（settlement 二次校验用）；该接口按 idEvent 查
                List<String> evIds = extract(oldOdds, "\"idEvent\"\\s*:\\s*\"?(\\d+)");
                String eid = evIds.isEmpty() ? oldId : evIds.get(0);
                String fd = call("13-fixture-detail", "/nova/tf/fixture-detail", map("idEvent", eid));
                System.out.println("   → 比赛事件条数：" + extract(fd, "\"type\"\\s*:").size()
                        + "（Goal 累计可核对 90 分钟比分）");
            }
        }

        // 汇总
        System.out.println("\n===== 关键判定 =====");
        TreeSet<String> lines = new TreeSet<>();
        for (String body : r.values()) {
            lines.addAll(extract(body, "\"line\"\\s*:\\s*\"([-+]?[0-9.]*)\""));
        }
        boolean hasQuarter = lines.stream().anyMatch(l -> l.endsWith(".25") || l.endsWith(".75"));
        System.out.println("盘口线取值（去空后）：" + lines.stream().filter(s -> !s.isEmpty()).toList());
        System.out.println("含 .25/.75 四分之一盘：" + (hasQuarter ? "是 ✓" : "否 ✗（拆盘结算需调整）"));
        System.out.println("原始响应目录：" + outDir.toAbsolutePath());
    }

    /** 自检：用临时密钥对跑完整签名流程，并验证签名字节可被公钥验签。 */
    private static void selftest() throws Exception {
        KeyPair kp = generateKeyPair();
        String b64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        System.out.println("1) 生成 Ed25519 密钥对，私钥 PKCS8 base64 长度：" + b64.length());

        PrivateKey loaded = loadPrivateKey(b64);
        String ts = String.valueOf(System.currentTimeMillis());
        String toSign = buildStringToSign("demo-key", map("date", "2026-10-21", "matchId", "123456"), ts);
        System.out.println("2) 待签串：" + toSign);

        String sig = sign(toSign, loaded);
        System.out.println("3) 签名 base64 长度：" + sig.length());

        Signature v = Signature.getInstance("Ed25519");
        v.initVerify(kp.getPublic());
        v.update(toSign.getBytes(StandardCharsets.UTF_8));
        System.out.println("4) 公钥验签：" + (v.verify(Base64.getDecoder().decode(sig)) ? "通过 ✓" : "失败 ✗"));

        // 用假 key 打一次真实请求，确认域名可达、鉴权链路存在且返回结构化错误
        String fakeBody = call("00-auth-probe-fake", "/nova/sports-lottery/list",
                map("date", LocalDate.now().toString()), "probe-fake-key", b64);
        System.out.println("5) 假凭证响应片段：" + abbreviate(fakeBody, 300));
        System.out.println("\n自检完成。签名与网络链路正常，填入真实凭证即可验证数据。");
    }

    private static String call(String name, String path, Map<String, String> params) throws Exception {
        return call(name, path, params, apiKey, privateKeyB64);
    }

    /** 签名并发起 GET，打印状态/耗时/体积，原始响应落盘。 */
    private static String call(String name, String path, Map<String, String> params,
                               String key, String privB64) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String toSign = buildStringToSign(key, params, ts);
        String sig = sign(toSign, loadPrivateKey(privB64));

        StringBuilder url = new StringBuilder(BASE_URL).append(path);
        if (!params.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!first) {
                    url.append('&');
                }
                url.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(30))
                .header("X-API-Key", key)
                .header("X-Timestamp", ts)
                .header("X-Signature", sig)
                .header("Accept", "application/json")
                .GET()
                .build();

        long t0 = System.currentTimeMillis();
        if (System.getenv("NOVA_PROBE_DEBUG") != null) {
            System.err.println("[req] " + url);
        }
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long ms = System.currentTimeMillis() - t0;

        String body = resp.body() == null ? "" : resp.body();
        Files.writeString(outDir.resolve(name + ".json"), body);
        StringBuilder hb = new StringBuilder();
        resp.headers().map().forEach((k, v) -> hb.append(k).append(": ").append(String.join(" | ", v)).append('\n'));
        Files.writeString(outDir.resolve(name + ".headers.txt"), hb.toString());

        String rate = resp.headers().firstValue("x-ratelimit-remaining")
                .or(() -> resp.headers().firstValue("X-RateLimit-Remaining")).orElse("-");
        System.out.printf("[%s] HTTP=%d  %dms  %dB  rateLimitRemaining=%s%n",
                name, resp.statusCode(), ms, body.getBytes(StandardCharsets.UTF_8).length, rate);
        if (resp.statusCode() != 200) {
            System.out.println("    body: " + abbreviate(body, 400));
        }
        return body;
    }

    /** 统计比分口径字段，确认 90 分钟 / 含加时 / 点球是否分开返回。 */
    private static void reportScoreScopes(String body) {
        java.util.Map<String, Integer> status = new java.util.TreeMap<>();
        for (String v : extract(body, "\"tfStatusShort\"\\s*:\\s*\"([A-Za-z]+)\"")) {
            status.merge(v, 1, Integer::sum);
        }
        int ft = extract(body, "\"ftHomeScore\"\\s*:").size();
        int et = extract(body, "\"etHomeScore\"\\s*:").size();
        int pen = extract(body, "\"penHomeScore\"\\s*:").size();
        System.out.println("   → 比分字段出现次数：ft(90分钟)=" + ft + " et(含加时)=" + et + " pen(点球)=" + pen
                + "，tfStatusShort=" + status);
        System.out.println("   → 90 分钟口径可独立取用：" + (ft > 0 ? "是 ✓" : "否 ✗")
                + (et == 0 && pen == 0 ? "（该日无加时/点球场次，无法验证分离度）" : ""));
    }

    /** 打印一场比赛的机构/玩法/选项规模，用于判断额度模型。 */
    private static void reportOdds(String body, String label) {
        int providers = extract(body, "\"providerId\"\\s*:").size();
        int markets = extract(body, "\"marketId\"\\s*:").size();
        int options = extract(body, "\"option\"\\s*:").size();
        System.out.printf("   → %s：providerId 出现 %d 次，marketId %d 次，option %d 个%n",
                label, providers, markets, options);
    }

    private static PrivateKey loadPrivateKey(String base64) throws Exception {
        byte[] keyBytes = normalizePkcs8(decode(base64));
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    private static String sign(String data, PrivateKey key) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    /** 签名串规则：apiKey + timestamp 在前，其余参数按 key 升序追加。 */
    private static String buildStringToSign(String key, Map<String, String> params, String ts) {
        StringBuilder sb = new StringBuilder("apiKey=").append(key).append("&timestamp=").append(ts);
        params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append('&').append(e.getKey()).append('=').append(e.getValue()));
        return sb.toString();
    }

    private static byte[] decode(String base64) {
        String clean = base64.replaceAll("\\s", "");
        int mod = clean.length() % 4;
        if (mod != 0) {
            clean += "====".substring(mod);
        }
        return Base64.getDecoder().decode(clean);
    }

    /** 服务商可能下发裸 32 字节私钥，需补齐 PKCS8 结构后再交给 KeyFactory。 */
    private static byte[] normalizePkcs8(byte[] keyBytes) {
        if (keyBytes.length == 48) {
            return keyBytes;
        }
        for (int i = 0; i <= keyBytes.length - 34; i++) {
            if (keyBytes[i] == 0x04 && keyBytes[i + 1] == 0x22
                    && keyBytes[i + 2] == 0x04 && keyBytes[i + 3] == 0x20) {
                byte[] minimal = new byte[48];
                System.arraycopy(ED25519_MINIMAL_HEADER, 0, minimal, 0, 16);
                System.arraycopy(keyBytes, i + 4, minimal, 16, 32);
                return minimal;
            }
        }
        return keyBytes;
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
        return g.generateKeyPair();
    }

    private static List<String> extract(String text, String regex) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) {
            out.add(m.groupCount() >= 1 ? m.group(1) : m.group());
        }
        return out;
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 解析 "k=v,k=v" 形式的参数串，供 raw 模式使用。 */
    private static Map<String, String> csvToMap(String csv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : csv.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                m.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return m;
    }

    private static String envOrArg(String[] args, String argPrefix, String envName) {
        for (String a : args) {
            if (a.startsWith(argPrefix + "=")) {
                return a.substring(argPrefix.length() + 1);
            }
        }
        return System.getenv(envName);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
