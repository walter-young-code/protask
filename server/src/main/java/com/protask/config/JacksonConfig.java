package com.protask.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * Jackson 配置：LocalDateTime 统一序列化为 yyyy-MM-dd HH:mm:ss，
 * 反序列化同时兼容 yyyy-MM-dd HH:mm:ss 与 ISO（yyyy-MM-ddTHH:mm:ss）两种格式。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter OUTPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 兼容 "2026-08-14 00:00:00" 与 "2026-08-14T00:00:00"（秒可省略） */
    private static final DateTimeFormatter LENIENT_INPUT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral('T').optionalEnd()
            .optionalStart().appendLiteral(' ').optionalEnd()
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .optionalEnd()
            .toFormatter();

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer lenientLocalDateTimeCustomizer() {
        return builder -> builder
                .serializers(new LocalDateTimeSerializer(OUTPUT))
                .deserializers(new LocalDateTimeDeserializer(LENIENT_INPUT));
    }
}
