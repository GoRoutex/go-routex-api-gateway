package vn.com.routex.hub.api.gateway.config;


import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.introspect.DefaultAccessorNamingStrategy;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class ApplicationConfig {

    @Bean
    public static ObjectMapper objectMapper() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .accessorNaming(new DefaultAccessorNamingStrategy.Provider().withBuilderPrefix(""))
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .build();

        SimpleModule stringNullToEmptyModule = new SimpleModule();

        objectMapper.getSerializerProvider().setNullValueSerializer(new JsonSerializer<>() {
            @Override
            public void serialize(Object value, JsonGenerator gen, SerializerProvider serializerProvider) throws IOException {
                if (value == null) {
                    gen.writeString("");
                    return;
                }
                gen.writeObject(value);
            }
        });

        objectMapper.registerModule(stringNullToEmptyModule);
        objectMapper.registerModules(new JavaTimeModule());
        return objectMapper;
    }
}