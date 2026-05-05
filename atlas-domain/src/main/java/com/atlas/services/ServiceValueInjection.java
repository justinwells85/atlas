package com.atlas.services;

import java.util.UUID;

/**
 * One {@code @Value("${...}")} injection-site observation belonging to a
 * service (Phase 5.9 M2 — configuration extraction). Append-only model: live
 * view returns latest observation per
 * {@code (service_id, module_path, enclosing_class, member_name, member_kind)}
 * where {@code presence='present'}.
 *
 * <p>{@code memberKind} is one of {@code 'field'}, {@code 'constructor-parameter'},
 * {@code 'method-parameter'}, {@code 'setter-parameter'} — disambiguates the
 * rare case of a class with both a constructor parameter and a setter
 * parameter sharing a name.
 *
 * <p>{@code rawSpel} carries the verbatim {@code ${...}} expression; {@code keyPath}
 * is the resolved key after stripping the optional {@code :default} suffix;
 * {@code defaultValue} is the {@code :default}-after-colon, {@code null} if absent.
 */
public record ServiceValueInjection(
        UUID id,
        UUID serviceId,
        String modulePath,
        String enclosingClass,
        String memberName,
        String memberKind,
        String rawSpel,
        String keyPath,
        String defaultValue,
        String source) {
}
