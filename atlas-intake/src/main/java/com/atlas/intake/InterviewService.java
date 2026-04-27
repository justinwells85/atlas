package com.atlas.intake;

import com.atlas.anthropic.AnthropicGateway;
import com.atlas.services.Service;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.BiFunction;

import static com.atlas.intake.InterviewStage.AWAITING_DEPLOYMENT;
import static com.atlas.intake.InterviewStage.AWAITING_DESCRIPTION;
import static com.atlas.intake.InterviewStage.AWAITING_DESCRIPTION_CLARIFICATION;
import static com.atlas.intake.InterviewStage.AWAITING_FRAMEWORK;
import static com.atlas.intake.InterviewStage.AWAITING_LANGUAGE;
import static com.atlas.intake.InterviewStage.AWAITING_NAME;
import static com.atlas.intake.InterviewStage.AWAITING_NOTES;
import static com.atlas.intake.InterviewStage.AWAITING_OWNER_TEAM;
import static com.atlas.intake.InterviewStage.AWAITING_REPO_URL;
import static com.atlas.intake.InterviewStage.AWAITING_SLA;
import static com.atlas.intake.InterviewStage.AWAITING_STATUS;
import static com.atlas.intake.InterviewStage.AWAITING_SUPPORT_CONTACT;

@Component
public class InterviewService {

    private static final int BRIEF_DESCRIPTION_THRESHOLD = 20;
    private static final String SKIP_TOKEN = "skip";

    private final ServiceRepository repository;
    private final AnthropicGateway anthropic;

    public InterviewService(ServiceRepository repository, AnthropicGateway anthropic) {
        this.repository = repository;
        this.anthropic = anthropic;
    }

    public TurnResult next(InterviewState state, String userInput) {
        InterviewState s = (state == null) ? InterviewState.empty() : state;

        if (s.stage() != null && userInput != null) {
            s = applyInput(s, userInput);
        }

        ServiceDraft d = s.draft();

        if (isBlank(d.name())) {
            return askWithError(s.atStage(AWAITING_NAME),
                    "What's the name of your service? (e.g., 'order-processor')");
        }
        if (isBlank(d.description())) {
            return askWithError(s.atStage(AWAITING_DESCRIPTION),
                    "Describe what " + d.name() + " does in a sentence or two.");
        }
        if (d.description().length() < BRIEF_DESCRIPTION_THRESHOLD && !s.descriptionClarified()) {
            String prompt = "A user is registering the service '" + d.name()
                    + "'. They briefly described it as: '" + d.description() + "'. "
                    + "Ask one friendly follow-up question (15 words max) to help them describe what it does, "
                    + "who uses it, and how. Reply with only the question, no preamble.";
            String followUp = anthropic.complete(prompt);
            return askWithError(s.atStage(AWAITING_DESCRIPTION_CLARIFICATION), followUp);
        }
        if (isBlank(d.ownerTeam())) {
            return askWithError(s.atStage(AWAITING_OWNER_TEAM),
                    "Which team owns " + d.name() + "?");
        }
        if (d.status() == null) {
            return askWithError(s.atStage(AWAITING_STATUS),
                    "What's the current status of " + d.name() + " — active, deprecated, or in_dev?");
        }

        // Optional services-row fields. Each is asked once; user provides a value
        // or types 'skip' (or just empty input) to advance and leave the field null.
        if (!s.hasVisitedOptional(AWAITING_LANGUAGE)) {
            return askWithError(s.atStage(AWAITING_LANGUAGE),
                    "What language is " + d.name() + " written in? (or 'skip')");
        }
        if (!s.hasVisitedOptional(AWAITING_FRAMEWORK)) {
            return askWithError(s.atStage(AWAITING_FRAMEWORK),
                    "What framework or runtime does it use? (or 'skip')");
        }
        if (!s.hasVisitedOptional(AWAITING_REPO_URL)) {
            return askWithError(s.atStage(AWAITING_REPO_URL),
                    "Source repository URL? (or 'skip')");
        }
        if (!s.hasVisitedOptional(AWAITING_DEPLOYMENT)) {
            return askWithError(s.atStage(AWAITING_DEPLOYMENT),
                    "Where is it deployed? (e.g., 'AWS ECS prod cluster', or 'skip')");
        }
        if (!s.hasVisitedOptional(AWAITING_SUPPORT_CONTACT)) {
            return askWithError(s.atStage(AWAITING_SUPPORT_CONTACT),
                    "Support contact for " + d.name() + "? (email, oncall channel, or 'skip')");
        }
        if (!s.hasVisitedOptional(AWAITING_SLA)) {
            return askWithError(s.atStage(AWAITING_SLA),
                    "SLA for " + d.name() + "? (e.g., '99.9% uptime', or 'skip')");
        }
        if (!s.hasVisitedOptional(AWAITING_NOTES)) {
            return askWithError(s.atStage(AWAITING_NOTES),
                    "Any notes worth capturing? (or 'skip')");
        }

        return persistAndComplete(s);
    }

    private InterviewState applyInput(InterviewState s, String userInput) {
        String trimmed = userInput == null ? "" : userInput.trim();

        return switch (s.stage()) {
            case AWAITING_NAME -> {
                if (trimmed.isEmpty()) yield s.withError("Name cannot be blank.");
                if (repository.existsByName(trimmed)) {
                    yield s.withError("A service named '" + trimmed + "' already exists.");
                }
                yield s.withDraft(s.draft().withName(trimmed)).clearError();
            }
            case AWAITING_DESCRIPTION -> {
                if (trimmed.isEmpty()) yield s.withError("Description cannot be blank.");
                yield s.withDraft(s.draft().withDescription(trimmed)).clearError();
            }
            case AWAITING_DESCRIPTION_CLARIFICATION -> {
                String existing = s.draft().description();
                String combined = trimmed.isEmpty() ? existing : existing + ". " + trimmed;
                yield s.withDraft(s.draft().withDescription(combined))
                        .withDescriptionClarified()
                        .clearError();
            }
            case AWAITING_OWNER_TEAM -> {
                if (trimmed.isEmpty()) yield s.withError("Owner team cannot be blank.");
                yield s.withDraft(s.draft().withOwnerTeam(trimmed)).clearError();
            }
            case AWAITING_STATUS -> {
                ServiceStatus parsed = parseStatus(trimmed);
                if (parsed == null) {
                    yield s.withError("Status must be one of: active, deprecated, in_dev.");
                }
                yield s.withDraft(s.draft().withStatus(parsed)).clearError();
            }
            case AWAITING_LANGUAGE -> applyOptional(s, trimmed, AWAITING_LANGUAGE, ServiceDraft::withLanguage);
            case AWAITING_FRAMEWORK -> applyOptional(s, trimmed, AWAITING_FRAMEWORK, ServiceDraft::withFramework);
            case AWAITING_REPO_URL -> applyOptional(s, trimmed, AWAITING_REPO_URL, ServiceDraft::withRepoUrl);
            case AWAITING_DEPLOYMENT -> applyOptional(s, trimmed, AWAITING_DEPLOYMENT, ServiceDraft::withDeployment);
            case AWAITING_SUPPORT_CONTACT -> applyOptional(s, trimmed, AWAITING_SUPPORT_CONTACT, ServiceDraft::withSupportContact);
            case AWAITING_SLA -> applyOptional(s, trimmed, AWAITING_SLA, ServiceDraft::withSla);
            case AWAITING_NOTES -> applyOptional(s, trimmed, AWAITING_NOTES, ServiceDraft::withNotes);
        };
    }

    /**
     * Optional-field application: empty input or the literal "skip" leaves the
     * draft field null; anything else sets it. Either way the stage is marked
     * visited so the walk advances past it.
     */
    private InterviewState applyOptional(
            InterviewState s, String trimmed, InterviewStage stage,
            BiFunction<ServiceDraft, String, ServiceDraft> setter) {
        boolean skipped = trimmed.isEmpty() || SKIP_TOKEN.equalsIgnoreCase(trimmed);
        ServiceDraft nextDraft = skipped ? s.draft() : setter.apply(s.draft(), trimmed);
        return s.withDraft(nextDraft).markOptionalVisited(stage).clearError();
    }

    private TurnResult persistAndComplete(InterviewState s) {
        ServiceDraft d = s.draft();
        Service entity = new Service();
        entity.setName(d.name());
        entity.setDescription(d.description());
        entity.setOwnerTeam(d.ownerTeam());
        entity.setStatus(d.status());
        entity.setLanguage(d.language());
        entity.setFramework(d.framework());
        entity.setRepoUrl(d.repoUrl());
        entity.setDeployment(d.deployment());
        entity.setSupportContact(d.supportContact());
        entity.setSla(d.sla());
        entity.setNotes(d.notes());
        Service saved = repository.save(entity);
        return new TurnResult(s.clearError(), null, true, saved.getId());
    }

    private TurnResult askWithError(InterviewState s, String question) {
        String fullQuestion = (s.lastError() != null) ? s.lastError() + " " + question : question;
        return new TurnResult(s.clearError(), fullQuestion, false, null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static ServiceStatus parseStatus(String input) {
        String n = input.toLowerCase().replace(' ', '_').replace('-', '_');
        return switch (n) {
            case "active" -> ServiceStatus.ACTIVE;
            case "deprecated" -> ServiceStatus.DEPRECATED;
            case "in_dev", "indev" -> ServiceStatus.IN_DEV;
            default -> null;
        };
    }

    public record TurnResult(InterviewState state, String question, boolean complete, UUID serviceId) {}
}
