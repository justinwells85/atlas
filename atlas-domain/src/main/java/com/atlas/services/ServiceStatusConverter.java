package com.atlas.services;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ServiceStatusConverter implements AttributeConverter<ServiceStatus, String> {

    @Override
    public String convertToDatabaseColumn(ServiceStatus status) {
        return status == null ? null : status.name().toLowerCase();
    }

    @Override
    public ServiceStatus convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : ServiceStatus.valueOf(dbValue.toUpperCase());
    }
}
