package com.ruoyi.framework.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.domain.model.LoginUser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * Redis wire format with explicit application types. No JSON class name is ever
 * loaded from Redis, and legacy Fastjson AutoType values fail closed.
 */
public final class SafeRedisJsonSerializer implements RedisSerializer<Object>
{
    private static final int MAX_BYTES = 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public byte[] serialize(Object value) throws SerializationException
    {
        if (value == null)
        {
            return new byte[0];
        }
        String kind = kindOf(value);
        try
        {
            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("version", 1);
            envelope.put("kind", kind);
            envelope.set("payload", MAPPER.valueToTree(value));
            byte[] bytes = MAPPER.writeValueAsBytes(envelope);
            if (bytes.length > MAX_BYTES)
            {
                throw new SerializationException("Redis value exceeds size limit");
            }
            return bytes;
        }
        catch (IOException | IllegalArgumentException ex)
        {
            throw new SerializationException("Cannot serialize Redis value", ex);
        }
    }

    @Override
    public Object deserialize(byte[] bytes) throws SerializationException
    {
        if (bytes == null || bytes.length == 0)
        {
            return null;
        }
        if (bytes.length > MAX_BYTES)
        {
            throw new SerializationException("Redis value exceeds size limit");
        }
        try
        {
            JsonNode envelope = MAPPER.readTree(bytes);
            if (envelope == null || !envelope.isObject() || envelope.path("version").asInt(-1) != 1
                    || !envelope.path("kind").isTextual() || !envelope.has("payload"))
            {
                throw new SerializationException("Unsupported Redis value envelope");
            }
            JsonNode payload = envelope.get("payload");
            return switch (envelope.get("kind").textValue())
            {
                case "string" -> requireText(payload);
                case "integer" -> requireInteger(payload);
                case "long" -> requireLong(payload);
                case "boolean" -> requireBoolean(payload);
                case "login-user" -> MAPPER.treeToValue(payload, LoginUser.class);
                case "dict-list" -> readDictionaryList(payload);
                case "repeat-map" -> readMap(payload);
                default -> throw new SerializationException("Unsupported Redis value kind");
            };
        }
        catch (IOException | IllegalArgumentException ex)
        {
            throw new SerializationException("Cannot deserialize Redis value", ex);
        }
    }

    private static String kindOf(Object value)
    {
        if (value instanceof String) return "string";
        if (value instanceof Integer) return "integer";
        if (value instanceof Long) return "long";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof LoginUser) return "login-user";
        if (value instanceof List<?> list && list.stream().allMatch(SysDictData.class::isInstance)) return "dict-list";
        if (value instanceof Map<?, ?> map && safeMap(map)) return "repeat-map";
        throw new SerializationException("Unsupported Redis value type");
    }

    private static boolean safeMap(Map<?, ?> map)
    {
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            if (!(entry.getKey() instanceof String)) return false;
            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Integer || value instanceof Long
                    || value instanceof Boolean || value instanceof Map<?, ?> nested && safeMap(nested))) return false;
        }
        return true;
    }

    private static String requireText(JsonNode value)
    {
        if (!value.isTextual()) throw new SerializationException("Expected Redis string");
        return value.textValue();
    }

    private static Integer requireInteger(JsonNode value)
    {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new SerializationException("Expected Redis integer");
        return value.intValue();
    }

    private static Long requireLong(JsonNode value)
    {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new SerializationException("Expected Redis long");
        return value.longValue();
    }

    private static Boolean requireBoolean(JsonNode value)
    {
        if (!value.isBoolean()) throw new SerializationException("Expected Redis boolean");
        return value.booleanValue();
    }

    private static List<SysDictData> readDictionaryList(JsonNode payload) throws IOException
    {
        if (!payload.isArray()) throw new SerializationException("Expected Redis dictionary list");
        List<SysDictData> values = new ArrayList<>();
        for (JsonNode item : payload)
        {
            if (!item.isObject()) throw new SerializationException("Invalid Redis dictionary item");
            values.add(MAPPER.treeToValue(item, SysDictData.class));
        }
        return values;
    }

    private static Map<String, Object> readMap(JsonNode payload)
    {
        if (!payload.isObject()) throw new SerializationException("Expected Redis map");
        Map<String, Object> values = new LinkedHashMap<>();
        payload.fields().forEachRemaining(field -> {
            JsonNode value = field.getValue();
            Object scalar;
            if (value.isTextual()) scalar = value.textValue();
            else if (value.isIntegralNumber() && value.canConvertToLong()) scalar = value.longValue();
            else if (value.isBoolean()) scalar = value.booleanValue();
            else if (value.isObject()) scalar = readMap(value);
            else throw new SerializationException("Invalid Redis map value");
            values.put(field.getKey(), scalar);
        });
        return values;
    }
}
