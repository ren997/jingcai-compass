package com.jingcaicompass.match.client;

import com.jingcaicompass.system.provider.ProviderErrorCategory;
import com.jingcaicompass.system.provider.ProviderException;
import com.jingcaicompass.system.provider.ProviderHttpExecutor;
import com.jingcaicompass.system.provider.ProviderHttpRequest;
import com.jingcaicompass.system.provider.ProviderHttpResponse;
import com.jingcaicompass.system.provider.ProviderRetryPolicy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * NovaAPI 签名客户端：Ed25519 请求签名 + 受控重试的 GET。
 *
 * <p>签名规则（与 {@code tools/novaapi-probe} 中已实测通过的实现一致）：
 * 待签串为 {@code apiKey={key}&timestamp={ts}}，随后把业务参数按 key 升序以
 * {@code &k=v} 追加；签名结果 Base64 编码后放入 {@code X-Signature}，
 * 同时发送 {@code X-API-Key} 与 {@code X-Timestamp}。</p>
 *
 * <p>待签串与 URL 使用<b>同一份</b>已编码参数值。参数值原样参与签名而 URL 再编码，
 * 是目前最容易踩的验签失败原因：日期、比赛 ID 这类纯 ASCII 值两种做法等价，
 * 但一旦出现需要转义的字符就会出现「本地能签、服务端拒签」。</p>
 */
@Component
@Conditional(NovaApiEnabledCondition.class)
public class NovaApiClient {

    /** 私钥下发的 PKCS#8 Ed25519 最小前缀（RFC 8410），用于把裸 32 字节种子补成合法结构。 */
    private static final byte[] ED25519_PKCS8_HEADER = new byte[]{
            0x30, 0x2e, 0x02, 0x01, 0x00,
            0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70,
            0x04, 0x22, 0x04, 0x20
    };

    private static final int ED25519_PKCS8_LENGTH = 48;
    private static final int ED25519_SEED_LENGTH = 32;
    private static final int ED25519_SEED_MARKER_LENGTH = 4;

    /**
     * 最小请求间隔。官方限速 30 次/分钟，这里按固定间隔匀速放行而不是攒令牌突发：
     * 亚盘赔率是一场一次调用，一轮十几场会在几秒内连发，突发桶打空后必然吃 429，
     * 而 429 被统一归为可重试，会把同步拖成成倍的重试与超时。
     */
    private static final long MIN_REQUEST_INTERVAL_NANOS = Duration.ofSeconds(2).toNanos();

    private final Object pacingLock = new Object();
    private long nextSlotNanos = 0L;

    private final RestClient restClient;
    /**
     * 历史赔率专用客户端（读超时更长）。
     *
     * <p>签名、限速与重试必须与实时共用同一份实现——历史补采同样吃 30 次/分钟的额度，
     * 而且签名规则一旦分叉就会出现「实时能签、历史 401」这种只在一侧复现的问题。
     * 因此这里只换传输层（超时），其余逻辑一律复用。</p>
     */
    private final RestClient historyRestClient;
    private final NovaApiProperties properties;
    private final ProviderHttpExecutor httpExecutor;

    public NovaApiClient(
            @Qualifier("novaApiRestClient") RestClient restClient,
            @Qualifier("novaApiHistoryRestClient") RestClient historyRestClient,
            NovaApiProperties properties,
            ProviderHttpExecutor httpExecutor
    ) {
        this.restClient = restClient;
        this.historyRestClient = historyRestClient;
        this.properties = properties;
        this.httpExecutor = httpExecutor;
    }

    /**
     * 以 GET 调用指定路径。
     *
     * @param providerCode 用于指标与重试日志的 provider 编码
     * @param path         {@code /nova/...} 形式的路径
     * @param params       业务查询参数；可为空
     */
    public ProviderHttpResponse get(String providerCode, String path, Map<String, String> params) {
        return get(restClient, providerCode, path, params);
    }

    /**
     * 以 GET 调用历史类接口，使用更长的读超时。
     *
     * <p>只给 {@code /nova/tf/odds-history} 这类「整场全史」接口用：实测老场次响应 20.8s / 388KB，
     * 而实时接口都在 1~2s 内返回。把所有请求都放宽到 60s 会让实时同步的卡死检测一起变钝。</p>
     */
    public ProviderHttpResponse getHistory(String providerCode, String path, Map<String, String> params) {
        if (historyRestClient == null) {
            // 未装配专用客户端时退化为通用超时：宁可慢一点失败，也不要在运行期抛 NPE。
            return get(restClient, providerCode, path, params);
        }
        return get(historyRestClient, providerCode, path, params);
    }

    private ProviderHttpResponse get(
            RestClient client,
            String providerCode,
            String path,
            Map<String, String> params
    ) {
        awaitPacingSlot();
        Map<String, String> encoded = encodeValues(params);
        String requestPath = buildRequestPath(path, encoded);
        Map<String, String> headers = signatureHeaders(encoded, System.currentTimeMillis());
        ProviderRetryPolicy policy = new ProviderRetryPolicy(
                properties.retry().maxAttempts(),
                properties.retry().delay()
        );
        ProviderHttpResponse response = httpExecutor.get(
                client,
                providerCode,
                ProviderHttpRequest.of(requestPath, headers),
                policy,
                properties.quotaWarningThreshold()
        );
        // NovaAPI 不回任何额度响应头（实测只有 nginx/date/content-type），
        // 通用执行器按响应头解析额度，这里若原样返回会让 quota_cost 恒为 0，
        // 面对 5000 次/日 的上限完全失去可见性。NovaAPI 的额度模型是「一次请求一个单位」，
        // 因此按 首次 1 + 每次重试 1 计。
        return new ProviderHttpResponse(
                response.status(),
                response.body(),
                response.retryCount(),
                response.retryCount() + 1,
                response.headers()
        );
    }

    /** 连接超时，供调用方拼装错误信息时引用。 */
    public Duration connectTimeout() {
        return properties.connectTimeout();
    }

    /**
     * 匀速放行：只在真的需要等待时才 sleep，首个请求与间隔足够长的请求零延迟。
     *
     * <p>锁内只做时间记账，sleep 放在锁外，避免限速把并发调用串成一条队。</p>
     */
    private void awaitPacingSlot() {
        long waitNanos;
        synchronized (pacingLock) {
            long now = System.nanoTime();
            long slot = Math.max(now, nextSlotNanos);
            nextSlotNanos = slot + MIN_REQUEST_INTERVAL_NANOS;
            waitNanos = slot - now;
        }
        if (waitNanos <= 0) {
            return;
        }
        try {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderException(
                    "NOVA_API",
                    ProviderErrorCategory.UPSTREAM_FAILURE,
                    "NovaAPI 限速等待被中断"
            );
        }
    }

    /**
     * 生成签名请求头。
     *
     * @param params 已编码的业务参数
     * @param epochMillis 当前毫秒时间戳
     */
    Map<String, String> signatureHeaders(Map<String, String> params, long epochMillis) {
        String timestamp = Long.toString(epochMillis);
        String toSign = buildStringToSign(properties.apiKey(), params, timestamp);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-API-Key", properties.apiKey());
        headers.put("X-Timestamp", timestamp);
        headers.put("X-Signature", sign(toSign, loadPrivateKey(properties.privateKey())));
        return Map.copyOf(headers);
    }

    /** 待签串：apiKey 与 timestamp 固定在最前，业务参数按 key 升序追加。 */
    static String buildStringToSign(String apiKey, Map<String, String> params, String timestamp) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("app.nova.api-key is not configured");
        }
        StringJoiner joiner = new StringJoiner("&");
        joiner.add("apiKey=" + apiKey);
        joiner.add("timestamp=" + timestamp);
        if (params != null && !params.isEmpty()) {
            new TreeMap<>(params).forEach((key, value) -> joiner.add(key + "=" + value));
        }
        return joiner.toString();
    }

    /** 解析 Base64 私钥；兼容 PKCS#8 与裸 32 字节种子两种下发形式。 */
    static PrivateKey loadPrivateKey(String base64) {
        if (!StringUtils.hasText(base64)) {
            throw new IllegalStateException("app.nova.private-key is not configured");
        }
        try {
            byte[] normalized = normalizePkcs8(decodeBase64(base64));
            return KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(normalized));
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            // 异常消息刻意不含私钥内容。
            throw new IllegalStateException("无法解析 app.nova.private-key，请确认是 Ed25519 PKCS#8 Base64");
        }
    }

    static String sign(String data, PrivateKey key) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception exception) {
            throw new IllegalStateException("Ed25519 签名失败", exception);
        }
    }

    private static Map<String, String> encodeValues(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        return params.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private static String buildRequestPath(String path, Map<String, String> encodedParams) {
        if (encodedParams.isEmpty()) {
            return path;
        }
        StringJoiner joiner = new StringJoiner("&");
        new TreeMap<>(encodedParams).forEach((key, value) -> joiner.add(key + "=" + value));
        return path + "?" + joiner;
    }

    private static byte[] decodeBase64(String base64) {
        String clean = base64.replaceAll("\\s", "");
        int remainder = clean.length() % 4;
        if (remainder != 0) {
            clean = clean + "====".substring(remainder);
        }
        try {
            return Base64.getDecoder().decode(clean);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("app.nova.private-key 不是合法 Base64");
        }
    }

    private static byte[] normalizePkcs8(byte[] keyBytes) {
        if (keyBytes.length == ED25519_PKCS8_LENGTH) {
            return keyBytes;
        }
        // 纯 32 字节种子：直接补 PKCS#8 头。这是最容易被漏掉的一种下发形态，
        // 漏掉的后果是「本地解析不了线上凭据」而不是签名算错。
        if (keyBytes.length == ED25519_SEED_LENGTH) {
            return wrapSeed(keyBytes, 0);
        }
        for (int i = 0; i <= keyBytes.length - ED25519_SEED_MARKER_LENGTH - ED25519_SEED_LENGTH; i++) {
            // 定位 DER 里的 04 22 04 20，其后即 32 字节私钥种子。
            if (keyBytes[i] == 0x04 && keyBytes[i + 1] == 0x22
                    && keyBytes[i + 2] == 0x04 && keyBytes[i + 3] == 0x20) {
                return wrapSeed(keyBytes, i + ED25519_SEED_MARKER_LENGTH);
            }
        }
        return keyBytes;
    }

    private static byte[] wrapSeed(byte[] source, int seedOffset) {
        byte[] minimal = new byte[ED25519_PKCS8_LENGTH];
        System.arraycopy(ED25519_PKCS8_HEADER, 0, minimal, 0, ED25519_PKCS8_HEADER.length);
        System.arraycopy(
                source,
                seedOffset,
                minimal,
                ED25519_PKCS8_HEADER.length,
                ED25519_SEED_LENGTH
        );
        return minimal;
    }
}
