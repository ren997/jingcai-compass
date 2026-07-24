package com.jingcaicompass.match.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NameNormalizationSupportTest {

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("sameKeyCases")
    void normalizesEquivalentNamesToSameKey(String left, String right, String expectedKey) {
        assertThat(NameNormalizationSupport.normalizedKey(left)).isEqualTo(expectedKey);
        assertThat(NameNormalizationSupport.normalizedKey(right)).isEqualTo(expectedKey);
    }

    static Stream<Arguments> sameKeyCases() {
        return Stream.of(
                Arguments.of("  Manchester United  ", "manchester united", "manchesterunited"),
                Arguments.of("Ｍａｎｃｈｅｓｔｅｒ", "manchester", "manchester"),
                Arguments.of("曼联·FC", "曼联 FC", "曼联"),
                Arguments.of("曼联足球俱乐部", "曼联", "曼联"),
                Arguments.of("Arsenal-FC", "Arsenal FC", "arsenal"),
                Arguments.of("A.C. Milan", "AC Milan", "acmilan")
        );
    }

    @ParameterizedTest(name = "{0} != {1}")
    @MethodSource("differentKeyCases")
    void doesNotMergeSimilarButDifferentNames(String left, String right) {
        assertThat(NameNormalizationSupport.normalizedKey(left))
                .isNotEqualTo(NameNormalizationSupport.normalizedKey(right));
    }

    static Stream<Arguments> differentKeyCases() {
        return Stream.of(
                Arguments.of("曼联", "曼城"),
                Arguments.of("Manchester United", "Manchester City"),
                Arguments.of("利物浦", "利兹联")
        );
    }
}
