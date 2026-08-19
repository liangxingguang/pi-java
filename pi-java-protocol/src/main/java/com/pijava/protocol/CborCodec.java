package com.pijava.protocol;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

/**
 * CBOR 编解码（Jackson CBOR）。多态 sealed 层次经 @JsonTypeInfo 注解支持。
 */
public final class CborCodec {

    private static final ObjectMapper CBOR =
        new ObjectMapper(new CBORFactory());

    /** 编码对象为 CBOR 字节。 */
    public byte[] encode(Object value) {
        try {
            return CBOR.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode " + value.getClass(), e);
        }
    }

    /** 解码 CBOR 字节为指定类型。 */
    public <T> T decode(byte[] data, Class<T> type) {
        try {
            return CBOR.readValue(data, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot decode " + type.getSimpleName(), e);
        }
    }
}
