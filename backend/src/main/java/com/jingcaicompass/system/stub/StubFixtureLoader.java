package com.jingcaicompass.system.stub;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 从 classpath 加载 Stub JSON fixtures。
 */
public final class StubFixtureLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

    private StubFixtureLoader() {
    }

    public static <T> List<T> readList(String classpathLocation, Class<T> elementType) {
        try (InputStream inputStream = open(classpathLocation)) {
            CollectionType listType = MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, elementType);
            return MAPPER.readValue(inputStream, listType);
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载 Stub fixture：" + classpathLocation, exception);
        }
    }

    public static String readText(String classpathLocation) {
        try (InputStream inputStream = open(classpathLocation)) {
            return new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载 Stub fixture：" + classpathLocation, exception);
        }
    }

    private static InputStream open(String classpathLocation) {
        String normalized = classpathLocation.startsWith("/")
                ? classpathLocation.substring(1)
                : classpathLocation;
        InputStream inputStream = StubFixtureLoader.class.getClassLoader().getResourceAsStream(normalized);
        if (inputStream == null) {
            throw new IllegalStateException("Stub fixture 不存在：" + classpathLocation);
        }
        return inputStream;
    }
}
