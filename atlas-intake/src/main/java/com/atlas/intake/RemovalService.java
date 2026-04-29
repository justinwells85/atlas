package com.atlas.intake;

import com.atlas.services.Service;
import com.atlas.services.ServiceRelationshipsRepository;
import com.atlas.services.ServiceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service-removal interview: a small two-stage flow that looks a service up
 * by name, asks the operator to confirm the resolved name + owner team, and
 * on confirmation soft-deletes the row (per ADR-014). Audit row recorded
 * with {@code changed_by = "intake-removal"}.
 *
 * Separate from {@link InterviewService} because removal and registration
 * are distinct UX surfaces — a future web UI is more likely to expose them
 * as two buttons than one merged flow. Sharing a class would mix the two
 * state machines unnecessarily.
 */
@Component
public class RemovalService {

    private static final Set<String> YES_TOKENS = Set.of("y", "yes");
    private static final Set<String> NO_TOKENS = Set.of("n", "no", "cancel");

    private final ServiceRepository repository;
    private final ServiceRelationshipsRepository relationships;

    public RemovalService(ServiceRepository repository,
                          ServiceRelationshipsRepository relationships) {
        this.repository = repository;
        this.relationships = relationships;
    }

    @Transactional
    public RemovalResult next(RemovalState state, String userInput) {
        RemovalState s = (state == null) ? RemovalState.empty() : state;

        if (s.stage() == null) {
            return ask(s.atStage(RemovalStage.AWAITING_REMOVAL_NAME),
                    "What's the name of the service you want to remove?");
        }

        String trimmed = userInput == null ? "" : userInput.trim();

        return switch (s.stage()) {
            case AWAITING_REMOVAL_NAME -> handleNameLookup(s, trimmed);
            case AWAITING_REMOVAL_CONFIRM -> handleConfirmation(s, trimmed);
        };
    }

    private RemovalResult handleNameLookup(RemovalState s, String name) {
        if (name.isEmpty()) {
            return ask(s.withError("Service name cannot be blank."),
                    "What's the name of the service you want to remove?");
        }
        // findByName respects @SQLRestriction — soft-deleted rows are invisible,
        // so a previously-removed service is treated the same as an unknown one.
        Optional<Service> match = repository.findByName(name);
        if (match.isEmpty()) {
            return ask(s.withError("No active service named '" + name + "' was found."),
                    "Try another name, or check for typos.");
        }
        Service svc = match.get();
        RemovalState next = s.withCandidate(svc.getId(), svc.getName(), svc.getOwnerTeam())
                .atStage(RemovalStage.AWAITING_REMOVAL_CONFIRM)
                .clearError();
        return ask(next,
                "About to remove '" + svc.getName() + "' (owned by " + svc.getOwnerTeam()
                        + "). Confirm? (yes/no)");
    }

    private RemovalResult handleConfirmation(RemovalState s, String input) {
        String lower = input.toLowerCase();
        if (YES_TOKENS.contains(lower)) {
            // Re-load so we can call delete() on the managed entity. Hibernate's
            // @SQLDelete rewrites the call into UPDATE deleted_at = CURRENT_TIMESTAMP.
            Service svc = repository.findById(s.candidateServiceId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Candidate service " + s.candidateServiceId() + " disappeared between turns"));
            repository.delete(svc);
            relationships.insertServiceChange(s.candidateServiceId(), "intake-removal", "deleted",
                    "Service '" + s.candidateServiceName() + "' soft-deleted via intake.");
            return new RemovalResult(s.clearError(), null, true, s.candidateServiceId());
        }
        if (NO_TOKENS.contains(lower)) {
            return new RemovalResult(s.clearError(), null, true, null);
        }
        return ask(s.withError("Please answer yes or no."),
                "About to remove '" + s.candidateServiceName() + "' (owned by " + s.candidateOwnerTeam()
                        + "). Confirm? (yes/no)");
    }

    private RemovalResult ask(RemovalState s, String question) {
        String full = s.lastError() == null ? question : (s.lastError() + " " + question);
        return new RemovalResult(s.clearError(), full, false, null);
    }

    /**
     * @param state            echoed back to the caller, ride-along like the
     *                         registration flow.
     * @param question         next prompt, or null when {@code complete}.
     * @param complete         true on yes/no terminal turns.
     * @param removedServiceId UUID if a soft-delete happened, null on cancellation.
     */
    public record RemovalResult(RemovalState state, String question,
                                boolean complete, UUID removedServiceId) {}
}
