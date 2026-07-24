package com.jingcaicompass.match.support;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 名称规范化工具：产出用于匹配的 normalizedKey，不改写展示名。
 */
public final class NameNormalizationSupport {

    private static final List<String> COMMON_SUFFIXES = List.of(
            "足球俱乐部",
            "足球队",
            "俱乐部",
            "afc",
            "fc",
            "cf",
            "club"
    );

    private static final Pattern MULTI_WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern MEANINGLESS_PUNCT = Pattern.compile("[.\\u00B7\\u30FB\\-_/'\"，。、]+");

    private NameNormalizationSupport() {
    }

    /**
     * 将原始显示名转为匹配用 key：trim、压缩空白、全角转半角、英文小写、去无意义标点与常见后缀。
     */
    public static String normalizedKey(String displayName) {
        if (displayName == null) {
            return "";
        }
        String value = displayName.trim();
        if (value.isEmpty()) {
            return "";
        }
        value = fullWidthToHalfWidth(value);
        value = MULTI_WHITESPACE.matcher(value).replaceAll(" ").trim();
        value = value.toLowerCase(Locale.ROOT);
        value = MEANINGLESS_PUNCT.matcher(value).replaceAll("");
        value = MULTI_WHITESPACE.matcher(value).replaceAll(" ").trim();
        value = stripCommonSuffixes(value);
        return value.replace(" ", "");
    }

    private static String stripCommonSuffixes(String input) {
        String value = input;
        boolean changed = true;
        while (changed && !value.isEmpty()) {
            changed = false;
            for (String suffix : COMMON_SUFFIXES) {
                if (value.equals(suffix)) {
                    return "";
                }
                if (value.endsWith(" " + suffix)) {
                    value = value.substring(0, value.length() - suffix.length() - 1).trim();
                    changed = true;
                    break;
                }
                if (value.endsWith(suffix) && value.length() > suffix.length()) {
                    value = value.substring(0, value.length() - suffix.length()).trim();
                    changed = true;
                    break;
                }
            }
        }
        return value;
    }

    private static String fullWidthToHalfWidth(String input) {
        StringBuilder builder = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '\u3000') {
                builder.append(' ');
            } else if (ch >= '\uFF01' && ch <= '\uFF5E') {
                builder.append((char) (ch - 0xFEE0));
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }
}
