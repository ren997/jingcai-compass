package com.jingcaicompass.match.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * NovaAPI 的 Ed25519 签名契约。
 *
 * <p>不联网：这些断言的价值在于把「待签串怎么拼」和「私钥两种下发形态怎么归一」钉死。
 * 签名一旦拼错，表现为服务端 401，而本地任何检查都不会报错。</p>
 */
class NovaApiClientTest {

    private static final String API_KEY = "test-api-key";

    /** 待签串必须把 apiKey 与 timestamp 放在最前，业务参数按 key 升序。 */
    @Test
    void buildsStringToSignWithFixedPrefixAndSortedParams() {
        Map<String, String> params = new LinkedHashMap<>();
        // 故意乱序传入，验证排序不是靠调用方自觉。
        params.put("matchId", "2042108");
        params.put("date", "2026-10-21");

        String toSign = NovaApiClient.buildStringToSign(API_KEY, params, "1700000000000");

        assertThat(toSign).isEqualTo(
                "apiKey=test-api-key&timestamp=1700000000000&date=2026-10-21&matchId=2042108");
    }

    @Test
    void buildsStringToSignWithoutParams() {
        assertThat(NovaApiClient.buildStringToSign(API_KEY, Map.of(), "1"))
                .isEqualTo("apiKey=test-api-key&timestamp=1");
    }

    @Test
    void rejectsMissingApiKey() {
        assertThatThrownBy(() -> NovaApiClient.buildStringToSign("", Map.of(), "1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
    }

    /** PKCS#8 Base64 直接可用。 */
    @Test
    void loadsPkcs8PrivateKey() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String base64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());

        PrivateKey loaded = NovaApiClient.loadPrivateKey(base64);

        assertThat(verify("payload", NovaApiClient.sign("payload", loaded), keyPair.getPublic())).isTrue();
    }

    /**
     * 供应商可能下发裸 32 字节种子，需要补成合法 PKCS#8 才能交给 KeyFactory。
     *
     * <p>这是接入时真实踩到的形态：只支持 PKCS#8 会让线上凭据解析失败，
     * 而失败信息必须不包含私钥内容。</p>
     */
    @Test
    void loadsBareSeedPrivateKey() throws Exception {
        KeyPair keyPair = generateKeyPair();
        byte[] pkcs8 = keyPair.getPrivate().getEncoded();
        byte[] seed = Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);
        String base64 = Base64.getEncoder().encodeToString(seed);

        PrivateKey loaded = NovaApiClient.loadPrivateKey(base64);

        assertThat(verify("payload", NovaApiClient.sign("payload", loaded), keyPair.getPublic())).isTrue();
    }

    @Test
    void rejectsUnparsablePrivateKeyWithoutLeakingContent() {
        assertThatThrownBy(() -> NovaApiClient.loadPrivateKey("not-a-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private-key")
                .hasMessageNotContaining("not-a-key");
    }

    /** 签名头三个字段齐全，且签名字节能被对应公钥验证。 */
    @Test
    void producesVerifiableSignatureHeaders() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        NovaApiClient client = new NovaApiClient(
                null, null, properties(API_KEY, privateKeyBase64), null);

        Map<String, String> headers = client.signatureHeaders(Map.of("date", "2026-10-21"), 1700000000000L);

        assertThat(headers).containsOnlyKeys("X-API-Key", "X-Timestamp", "X-Signature");
        assertThat(headers.get("X-API-Key")).isEqualTo(API_KEY);
        assertThat(headers.get("X-Timestamp")).isEqualTo("1700000000000");

        String toSign = NovaApiClient.buildStringToSign(
                API_KEY, Map.of("date", "2026-10-21"), "1700000000000");
        byte[] signature = Base64.getDecoder().decode(headers.get("X-Signature"));
        assertThat(verify(toSign, signature, keyPair.getPublic())).isTrue();
    }

    private static boolean verify(String data, String signatureBase64, PublicKey publicKey) throws Exception {
        return verify(data, Base64.getDecoder().decode(signatureBase64), publicKey);
    }

    private static boolean verify(String data, byte[] signature, PublicKey publicKey) throws Exception {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(data.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(signature);
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        return generator.generateKeyPair();
    }

    private static NovaApiProperties properties(String apiKey, String privateKey) {
        return new NovaApiProperties(
                URI.create("https://novaapi.tsgnkj.com"),
                apiKey,
                privateKey,
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                Duration.ofSeconds(60),
                new NovaApiProperties.RetryProperties(2, Duration.ofMillis(500)),
                0
        );
    }
}
