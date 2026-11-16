package com.karimsaleh.ledger.fraud;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Stores List&lt;ReasonFlag&gt; as comma-separated enum names in a TEXT column. */
@Converter
public class ReasonFlagListConverter implements AttributeConverter<List<ReasonFlag>, String> {

    @Override
    public String convertToDatabaseColumn(List<ReasonFlag> flags) {
        if (flags == null) {
            return "";
        }
        return flags.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    @Override
    public List<ReasonFlag> convertToEntityAttribute(String db) {
        if (db == null || db.isBlank()) {
            return List.of();
        }
        return Arrays.stream(db.split(",")).map(String::trim).map(ReasonFlag::valueOf).toList();
    }
}
