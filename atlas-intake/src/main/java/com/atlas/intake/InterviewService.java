package com.atlas.intake;

import com.atlas.llm.LlmGateway;
import com.atlas.services.Service;
import com.atlas.services.ServiceRelationshipsRepository;
import com.atlas.services.ServiceRepository;
import com.atlas.services.ServiceStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import static com.atlas.intake.InterviewStage.AWAITING_ANOTHER_API;
import static com.atlas.intake.InterviewStage.AWAITING_ANOTHER_API_CONSUMER;
import static com.atlas.intake.InterviewStage.AWAITING_ANOTHER_DATABASE;
import static com.atlas.intake.InterviewStage.AWAITING_ANOTHER_DOWNSTREAM;
import static com.atlas.intake.InterviewStage.AWAITING_ANOTHER_EXTERNAL_DEP;
import static com.atlas.intake.InterviewStage.AWAITING_ANOTHER_UPSTREAM;
import static com.atlas.intake.InterviewStage.AWAITING_API_AUTH;
import static com.atlas.intake.InterviewStage.AWAITING_API_CONSUMER_DESCRIPTION;
import static com.atlas.intake.InterviewStage.AWAITING_API_CONSUMER_NAME;
import static com.atlas.intake.InterviewStage.AWAITING_API_DESCRIPTION;
import static com.atlas.intake.InterviewStage.AWAITING_API_METHOD;
import static com.atlas.intake.InterviewStage.AWAITING_API_PATH;
import static com.atlas.intake.InterviewStage.AWAITING_DATABASE_ENGINE;
import static com.atlas.intake.InterviewStage.AWAITING_DATABASE_IS_OWNER;
import static com.atlas.intake.InterviewStage.AWAITING_DATABASE_LINK_DESCRIPTION;
import static com.atlas.intake.InterviewStage.AWAITING_DATABASE_NAME;
import static com.atlas.intake.InterviewStage.AWAITING_DEPLOYMENT;
import static com.atlas.intake.InterviewStage.AWAITING_DESCRIPTION;
import static com.atlas.intake.InterviewStage.AWAITING_DESCRIPTION_CLARIFICATION;
import static com.atlas.intake.InterviewStage.AWAITING_DOWNSTREAM_DESCRIPTION;
import static com.atlas.intake.InterviewStage.AWAITING_DOWNSTREAM_NAME;
import static com.atlas.intake.InterviewStage.AWAITING_EXTERNAL_DEP_LINK_DESCRIPTION;
import static com.atlas.intake.InterviewStage.AWAITING_EXTERNAL_DEP_NAME;
import static com.atlas.intake.InterviewStage.AWAITING_EXTERNAL_DEP_URL;
import static com.atlas.intake.InterviewStage.AWAITING_FRAMEWORK;
import static com.atlas.intake.InterviewStage.AWAITING_HAS_API_CONSUMERS;
import static com.atlas.intake.InterviewStage.AWAITING_HAS_APIS;
import static com.atlas.intake.InterviewStage.AWAITING_HAS_DATABASES;
import static com.atlas.intake.InterviewStage.AWAITING_HAS_DOWNSTREAM_DEPS;
import static com.atlas.intake.InterviewStage.AWAITING_HAS_EXTERNAL_DEPS;
import static com.atlas.intake.InterviewStage.AWAITING_HAS_UPSTREAM_DEPS;
import static com.atlas.intake.InterviewStage.AWAITING_LANGUAGE;
import static com.atlas.intake.InterviewStage.AWAITING_NAME;
import static com.atlas.intake.InterviewStage.AWAITING_NOTES;
import static com.atlas.intake.InterviewStage.AWAITING_OWNER_TEAM;
import static com.atlas.intake.InterviewStage.AWAITING_REPO_URL;
import static com.atlas.intake.InterviewStage.AWAITING_SLA;
import static com.atlas.intake.InterviewStage.AWAITING_STATUS;
import static com.atlas.intake.InterviewStage.AWAITING_SUPPORT_CONTACT;
import static com.atlas.intake.InterviewStage.AWAITING_UPSTREAM_DESCRIPTION;
import static com.atlas.intake.InterviewStage.AWAITING_UPSTREAM_NAME;

@Component
public class InterviewService {

    private static final int BRIEF_DESCRIPTION_THRESHOLD = 20;
    private static final String SKIP_TOKEN = "skip";
    private static final Set<String> VALID_HTTP_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> YES_TOKENS = Set.of("y", "yes");
    private static final Set<String> NO_TOKENS = Set.of("n", "no", "skip");

    private final ServiceRepository repository;
    private final ServiceRelationshipsRepository relationships;
    private final LlmGateway llm;

    public InterviewService(ServiceRepository repository,
                            ServiceRelationshipsRepository relationships,
                            LlmGateway llm) {
        this.repository = repository;
        this.relationships = relationships;
        this.llm = llm;
    }

    /**
     * @Transactional so persistAndComplete's services-row + relationship-row
     * inserts run in one transaction; non-completing turns get an empty tx,
     * which is cheap.
     */
    @Transactional
    public TurnResult next(InterviewState state, String userInput) {
        InterviewState s = (state == null) ? InterviewState.empty() : state;

        if (s.stage() != null && userInput != null) {
            s = applyInput(s, userInput);
        }

        ServiceDraft d = s.draft();

        // --- Required services-row fields ---------------------------------
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
            String followUp = llm.complete(prompt);
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

        // --- Optional services-row fields (M1) ----------------------------
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

        // --- APIs section (M2) --------------------------------------------
        if (!s.hasVisitedOptional(AWAITING_HAS_APIS)) {
            return askWithError(s.atStage(AWAITING_HAS_APIS),
                    "Does " + d.name() + " expose any APIs? (yes/skip)");
        }
        if (!s.apisSectionClosed()) {
            return askInsideApisSection(s);
        }

        // --- Upstream dependencies (M2) -----------------------------------
        if (!s.hasVisitedOptional(AWAITING_HAS_UPSTREAM_DEPS)) {
            return askWithError(s.atStage(AWAITING_HAS_UPSTREAM_DEPS),
                    "Does " + d.name() + " depend on any other services? (yes/skip)");
        }
        if (!s.upstreamSectionClosed()) {
            return askInsideUpstreamSection(s);
        }

        // --- Downstream dependencies (M2) ---------------------------------
        if (!s.hasVisitedOptional(AWAITING_HAS_DOWNSTREAM_DEPS)) {
            return askWithError(s.atStage(AWAITING_HAS_DOWNSTREAM_DEPS),
                    "Do any other services depend on " + d.name() + "? (yes/skip)");
        }
        if (!s.downstreamSectionClosed()) {
            return askInsideDownstreamSection(s);
        }

        // --- Databases section (M3) ---------------------------------------
        if (!s.hasVisitedOptional(AWAITING_HAS_DATABASES)) {
            return askWithError(s.atStage(AWAITING_HAS_DATABASES),
                    "Does " + d.name() + " use any databases? (yes/skip)");
        }
        if (!s.databasesSectionClosed()) {
            return askInsideDatabasesSection(s);
        }

        // --- External dependencies section (M3) ---------------------------
        if (!s.hasVisitedOptional(AWAITING_HAS_EXTERNAL_DEPS)) {
            return askWithError(s.atStage(AWAITING_HAS_EXTERNAL_DEPS),
                    "Does " + d.name() + " use any external/third-party services? (yes/skip)");
        }
        if (!s.externalDependenciesSectionClosed()) {
            return askInsideExternalDependenciesSection(s);
        }

        return persistAndComplete(s);
    }

    // -----------------------------------------------------------------------
    // Section question dispatchers
    // -----------------------------------------------------------------------

    private TurnResult askInsideApisSection(InterviewState s) {
        return switch (s.stage()) {
            case AWAITING_API_PATH -> askWithError(s, "Path of the API endpoint? (e.g., '/v1/orders')");
            case AWAITING_API_METHOD -> askWithError(s,
                    "HTTP method? (GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS)");
            case AWAITING_API_AUTH -> askWithError(s,
                    "Auth method? (e.g., 'api-key', 'oauth2', or 'skip')");
            case AWAITING_API_DESCRIPTION -> askWithError(s,
                    "Brief description of this endpoint? (or 'skip')");
            case AWAITING_HAS_API_CONSUMERS -> askWithError(s,
                    "Does any other service consume '" + s.currentApi().path() + "'? (yes/skip)");
            case AWAITING_API_CONSUMER_NAME -> askWithError(s,
                    "Name of a service that calls '" + s.currentApi().path() + "'?");
            case AWAITING_API_CONSUMER_DESCRIPTION -> askWithError(s,
                    "How does '" + s.currentApiConsumer().consumerServiceName()
                            + "' use '" + s.currentApi().path() + "'? (or 'skip')");
            case AWAITING_ANOTHER_API_CONSUMER -> askWithError(s,
                    "Any other consumers of '" + s.currentApi().path() + "'? (yes/no)");
            case AWAITING_ANOTHER_API -> askWithError(s, "Add another API endpoint? (yes/no)");
            default -> throw new IllegalStateException("Unexpected stage in APIs section: " + s.stage());
        };
    }

    private TurnResult askInsideUpstreamSection(InterviewState s) {
        return switch (s.stage()) {
            case AWAITING_UPSTREAM_NAME -> askWithError(s,
                    "Name of the service " + s.draft().name() + " depends on?");
            case AWAITING_UPSTREAM_DESCRIPTION -> askWithError(s,
                    "How does it depend on '" + s.currentUpstream().otherServiceName() + "'? (or 'skip')");
            case AWAITING_ANOTHER_UPSTREAM -> askWithError(s,
                    "Any other services " + s.draft().name() + " depends on? (yes/no)");
            default -> throw new IllegalStateException("Unexpected stage in upstream section: " + s.stage());
        };
    }

    private TurnResult askInsideDownstreamSection(InterviewState s) {
        return switch (s.stage()) {
            case AWAITING_DOWNSTREAM_NAME -> askWithError(s,
                    "Name of a service that depends on " + s.draft().name() + "?");
            case AWAITING_DOWNSTREAM_DESCRIPTION -> askWithError(s,
                    "How does '" + s.currentDownstream().otherServiceName()
                            + "' depend on " + s.draft().name() + "? (or 'skip')");
            case AWAITING_ANOTHER_DOWNSTREAM -> askWithError(s,
                    "Any other services that depend on " + s.draft().name() + "? (yes/no)");
            default -> throw new IllegalStateException("Unexpected stage in downstream section: " + s.stage());
        };
    }

    private TurnResult askInsideDatabasesSection(InterviewState s) {
        return switch (s.stage()) {
            case AWAITING_DATABASE_NAME -> askWithError(s,
                    "Name of the database? (e.g., 'orders-db')");
            case AWAITING_DATABASE_ENGINE -> askWithError(s,
                    "Engine for new database '" + s.currentDatabaseUsage().databaseName()
                            + "'? (e.g., 'postgres', 'mysql', or 'skip')");
            case AWAITING_DATABASE_IS_OWNER -> askWithError(s,
                    "Does " + s.draft().name() + " own '"
                            + s.currentDatabaseUsage().databaseName() + "'? (yes/no)");
            case AWAITING_DATABASE_LINK_DESCRIPTION -> askWithError(s,
                    "How does " + s.draft().name() + " use '"
                            + s.currentDatabaseUsage().databaseName() + "'? (or 'skip')");
            case AWAITING_ANOTHER_DATABASE -> askWithError(s,
                    "Add another database? (yes/no)");
            default -> throw new IllegalStateException("Unexpected stage in databases section: " + s.stage());
        };
    }

    private TurnResult askInsideExternalDependenciesSection(InterviewState s) {
        return switch (s.stage()) {
            case AWAITING_EXTERNAL_DEP_NAME -> askWithError(s,
                    "Name of the external service or tool? (e.g., 'Stripe', 'SendGrid')");
            case AWAITING_EXTERNAL_DEP_URL -> askWithError(s,
                    "URL for new dependency '" + s.currentExternalDependencyUsage().name()
                            + "'? (or 'skip')");
            case AWAITING_EXTERNAL_DEP_LINK_DESCRIPTION -> askWithError(s,
                    "How does " + s.draft().name() + " use '"
                            + s.currentExternalDependencyUsage().name() + "'? (or 'skip')");
            case AWAITING_ANOTHER_EXTERNAL_DEP -> askWithError(s,
                    "Add another external dependency? (yes/no)");
            default -> throw new IllegalStateException(
                    "Unexpected stage in external dependencies section: " + s.stage());
        };
    }

    // -----------------------------------------------------------------------
    // Input handlers
    // -----------------------------------------------------------------------

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

            // APIs section
            case AWAITING_HAS_APIS -> applySectionGate(s, trimmed, AWAITING_HAS_APIS,
                    yes -> yes ? s.withCurrentApi(ApiDraft.empty()).atStage(AWAITING_API_PATH)
                               : s.closeApisSection());
            case AWAITING_API_PATH -> {
                if (trimmed.isEmpty()) yield s.withError("Path cannot be blank.");
                yield s.withCurrentApi(s.currentApi().withPath(trimmed))
                        .atStage(AWAITING_API_METHOD).clearError();
            }
            case AWAITING_API_METHOD -> {
                String method = trimmed.toUpperCase();
                if (!VALID_HTTP_METHODS.contains(method)) {
                    yield s.withError("Method must be one of: " + String.join(", ", VALID_HTTP_METHODS) + ".");
                }
                yield s.withCurrentApi(s.currentApi().withMethod(method))
                        .atStage(AWAITING_API_AUTH).clearError();
            }
            case AWAITING_API_AUTH -> {
                String value = isOptionalSkip(trimmed) ? null : trimmed;
                yield s.withCurrentApi(s.currentApi().withAuthMethod(value))
                        .atStage(AWAITING_API_DESCRIPTION).clearError();
            }
            case AWAITING_API_DESCRIPTION -> {
                // Don't commit currentApi yet — we still need to walk the
                // consumers sub-loop. commitCurrentApi() happens when the
                // consumers section closes (gate=no or another?=no).
                String value = isOptionalSkip(trimmed) ? null : trimmed;
                yield s.withCurrentApi(s.currentApi().withDescription(value))
                        .atStage(AWAITING_HAS_API_CONSUMERS).clearError();
            }
            case AWAITING_HAS_API_CONSUMERS -> {
                boolean yes = isYes(trimmed);
                boolean no = isNo(trimmed) || trimmed.isEmpty();
                if (!yes && !no) yield s.withError("Please answer yes or skip.");
                yield (yes
                        ? s.withCurrentApiConsumer(ApiConsumerDraft.empty()).atStage(AWAITING_API_CONSUMER_NAME)
                        : s.commitCurrentApi().atStage(AWAITING_ANOTHER_API))
                        .clearError();
            }
            case AWAITING_API_CONSUMER_NAME -> applyApiConsumerName(s, trimmed);
            case AWAITING_API_CONSUMER_DESCRIPTION -> {
                String value = isOptionalSkip(trimmed) ? null : trimmed;
                yield s.withCurrentApiConsumer(s.currentApiConsumer().withDescription(value))
                        .commitCurrentApiConsumer()
                        .atStage(AWAITING_ANOTHER_API_CONSUMER).clearError();
            }
            case AWAITING_ANOTHER_API_CONSUMER -> applyAnother(s, trimmed,
                    yes -> yes ? s.withCurrentApiConsumer(ApiConsumerDraft.empty()).atStage(AWAITING_API_CONSUMER_NAME)
                               : s.commitCurrentApi().atStage(AWAITING_ANOTHER_API));
            case AWAITING_ANOTHER_API -> applyAnother(s, trimmed,
                    yes -> yes ? s.withCurrentApi(ApiDraft.empty()).atStage(AWAITING_API_PATH)
                               : s.closeApisSection());

            // Upstream deps section
            case AWAITING_HAS_UPSTREAM_DEPS -> applySectionGate(s, trimmed, AWAITING_HAS_UPSTREAM_DEPS,
                    yes -> yes ? s.withCurrentUpstream(DependencyEdgeDraft.empty()).atStage(AWAITING_UPSTREAM_NAME)
                               : s.closeUpstreamSection());
            case AWAITING_UPSTREAM_NAME -> applyDependencyName(s, trimmed,
                    s.upstreamDependencies(), s::withCurrentUpstream, AWAITING_UPSTREAM_DESCRIPTION);
            case AWAITING_UPSTREAM_DESCRIPTION -> {
                String value = isOptionalSkip(trimmed) ? null : trimmed;
                yield s.withCurrentUpstream(s.currentUpstream().withDescription(value))
                        .commitCurrentUpstream()
                        .atStage(AWAITING_ANOTHER_UPSTREAM).clearError();
            }
            case AWAITING_ANOTHER_UPSTREAM -> applyAnother(s, trimmed,
                    yes -> yes ? s.withCurrentUpstream(DependencyEdgeDraft.empty()).atStage(AWAITING_UPSTREAM_NAME)
                               : s.closeUpstreamSection());

            // Downstream deps section
            case AWAITING_HAS_DOWNSTREAM_DEPS -> applySectionGate(s, trimmed, AWAITING_HAS_DOWNSTREAM_DEPS,
                    yes -> yes ? s.withCurrentDownstream(DependencyEdgeDraft.empty()).atStage(AWAITING_DOWNSTREAM_NAME)
                               : s.closeDownstreamSection());
            case AWAITING_DOWNSTREAM_NAME -> applyDependencyName(s, trimmed,
                    s.downstreamDependencies(), s::withCurrentDownstream, AWAITING_DOWNSTREAM_DESCRIPTION);
            case AWAITING_DOWNSTREAM_DESCRIPTION -> {
                String value = isOptionalSkip(trimmed) ? null : trimmed;
                yield s.withCurrentDownstream(s.currentDownstream().withDescription(value))
                        .commitCurrentDownstream()
                        .atStage(AWAITING_ANOTHER_DOWNSTREAM).clearError();
            }
            case AWAITING_ANOTHER_DOWNSTREAM -> applyAnother(s, trimmed,
                    yes -> yes ? s.withCurrentDownstream(DependencyEdgeDraft.empty()).atStage(AWAITING_DOWNSTREAM_NAME)
                               : s.closeDownstreamSection());

            // Databases section (M3)
            case AWAITING_HAS_DATABASES -> applySectionGate(s, trimmed, AWAITING_HAS_DATABASES,
                    yes -> yes ? s.withCurrentDatabaseUsage(DatabaseUsageDraft.empty()).atStage(AWAITING_DATABASE_NAME)
                               : s.closeDatabasesSection());
            case AWAITING_DATABASE_NAME -> applyDatabaseName(s, trimmed);
            case AWAITING_DATABASE_ENGINE -> {
                String value = isOptionalSkip(trimmed) ? null : trimmed;
                yield s.withCurrentDatabaseUsage(s.currentDatabaseUsage().withEngine(value))
                        .atStage(AWAITING_DATABASE_IS_OWNER).clearError();
            }
            case AWAITING_DATABASE_IS_OWNER -> {
                if (!isYes(trimmed) && !isNo(trimmed)) {
                    yield s.withError("Please answer yes or no.");
                }
                yield s.withCurrentDatabaseUsage(s.currentDatabaseUsage().withIsOwner(isYes(trimmed)))
                        .atStage(AWAITING_DATABASE_LINK_DESCRIPTION).clearError();
            }
            case AWAITING_DATABASE_LINK_DESCRIPTION -> {
                String value = isOptionalSkip(trimmed) ? null : trimmed;
                yield s.withCurrentDatabaseUsage(s.currentDatabaseUsage().withDescription(value))
                        .commitCurrentDatabaseUsage()
                        .atStage(AWAITING_ANOTHER_DATABASE).clearError();
            }
            case AWAITING_ANOTHER_DATABASE -> applyAnother(s, trimmed,
                    yes -> yes ? s.withCurrentDatabaseUsage(DatabaseUsageDraft.empty()).atStage(AWAITING_DATABASE_NAME)
                               : s.closeDatabasesSection());

            // External dependencies section (M3)
            case AWAITING_HAS_EXTERNAL_DEPS -> applySectionGate(s, trimmed, AWAITING_HAS_EXTERNAL_DEPS,
                    yes -> yes ? s.withCurrentExternalDependencyUsage(ExternalDependencyUsageDraft.empty())
                                    .atStage(AWAITING_EXTERNAL_DEP_NAME)
                               : s.closeExternalDependenciesSection());
            case AWAITING_EXTERNAL_DEP_NAME -> applyExternalDependencyName(s, trimmed);
            case AWAITING_EXTERNAL_DEP_URL -> {
                String value = isOptionalSkip(trimmed) ? null : trimmed;
                yield s.withCurrentExternalDependencyUsage(s.currentExternalDependencyUsage().withUrl(value))
                        .atStage(AWAITING_EXTERNAL_DEP_LINK_DESCRIPTION).clearError();
            }
            case AWAITING_EXTERNAL_DEP_LINK_DESCRIPTION -> {
                String value = isOptionalSkip(trimmed) ? null : trimmed;
                yield s.withCurrentExternalDependencyUsage(s.currentExternalDependencyUsage().withDescription(value))
                        .commitCurrentExternalDependencyUsage()
                        .atStage(AWAITING_ANOTHER_EXTERNAL_DEP).clearError();
            }
            case AWAITING_ANOTHER_EXTERNAL_DEP -> applyAnother(s, trimmed,
                    yes -> yes ? s.withCurrentExternalDependencyUsage(ExternalDependencyUsageDraft.empty())
                                    .atStage(AWAITING_EXTERNAL_DEP_NAME)
                               : s.closeExternalDependenciesSection());
        };
    }

    /**
     * Optional services-row field input. Empty input or literal "skip" leaves
     * the field null and marks the stage visited; any other input sets it.
     */
    private InterviewState applyOptional(
            InterviewState s, String trimmed, InterviewStage stage,
            BiFunction<ServiceDraft, String, ServiceDraft> setter) {
        boolean skipped = isOptionalSkip(trimmed);
        ServiceDraft nextDraft = skipped ? s.draft() : setter.apply(s.draft(), trimmed);
        return s.withDraft(nextDraft).markOptionalVisited(stage).clearError();
    }

    /**
     * Section-gate input ("does this service have X?"). Yes enters the section,
     * skip/no closes it. The gate stage is marked visited either way so the
     * walk advances past it.
     */
    private InterviewState applySectionGate(
            InterviewState s, String trimmed, InterviewStage gate,
            java.util.function.Function<Boolean, InterviewState> branch) {
        boolean yes = isYes(trimmed);
        boolean no = isNo(trimmed) || trimmed.isEmpty();
        if (!yes && !no) {
            return s.withError("Please answer yes or skip.");
        }
        return branch.apply(yes).markOptionalVisited(gate).clearError();
    }

    /**
     * "Add another?" loop input. Yes returns to the first sub-stage of the
     * section; no closes the section. No state mutation if the input is
     * unrecognised.
     */
    private InterviewState applyAnother(
            InterviewState s, String trimmed,
            java.util.function.Function<Boolean, InterviewState> branch) {
        boolean yes = isYes(trimmed);
        boolean no = isNo(trimmed);
        if (!yes && !no) {
            return s.withError("Please answer yes or no.");
        }
        return branch.apply(yes).clearError();
    }

    /**
     * API-consumer name input. Looks up the named service; rejects unknown,
     * self-as-consumer, and duplicates within the current API's consumers list.
     * On success, sets the current consumer's id+name and advances to its
     * description sub-stage.
     */
    private InterviewState applyApiConsumerName(InterviewState s, String name) {
        if (name.isEmpty()) {
            return s.withError("Service name cannot be blank.");
        }
        if (name.equals(s.draft().name())) {
            return s.withError("A service can't consume its own API.");
        }
        if (s.currentApi().consumers().stream().anyMatch(c -> name.equals(c.consumerServiceName()))) {
            return s.withError("'" + name + "' is already a listed consumer of this API.");
        }
        Optional<Service> other = repository.findByName(name);
        if (other.isEmpty()) {
            return s.withError("No service named '" + name + "' exists.");
        }
        return s.withCurrentApiConsumer(s.currentApiConsumer().withConsumer(other.get().getId(), name))
                .atStage(AWAITING_API_CONSUMER_DESCRIPTION).clearError();
    }

    /**
     * Database-name input (lookup-or-create). Rejects blank and duplicates;
     * on lookup hit, jumps past the engine stage straight to is_owner; on
     * miss, leaves databaseId null and advances to engine.
     */
    private InterviewState applyDatabaseName(InterviewState s, String name) {
        if (name.isEmpty()) {
            return s.withError("Database name cannot be blank.");
        }
        if (s.databaseUsages().stream().anyMatch(d -> name.equals(d.databaseName()))) {
            return s.withError("'" + name + "' is already in this list.");
        }
        Optional<UUID> existing = relationships.findDatabaseIdByName(name);
        DatabaseUsageDraft current = s.currentDatabaseUsage();
        if (existing.isPresent()) {
            return s.withCurrentDatabaseUsage(current.withExistingDatabase(existing.get(), name))
                    .atStage(AWAITING_DATABASE_IS_OWNER).clearError();
        }
        return s.withCurrentDatabaseUsage(current.withNewDatabase(name))
                .atStage(AWAITING_DATABASE_ENGINE).clearError();
    }

    /**
     * External-dependency-name input (lookup-or-create). Same shape as
     * {@link #applyDatabaseName}: hit jumps to link-description; miss goes
     * through the URL stage.
     */
    private InterviewState applyExternalDependencyName(InterviewState s, String name) {
        if (name.isEmpty()) {
            return s.withError("Dependency name cannot be blank.");
        }
        if (s.externalDependencyUsages().stream().anyMatch(d -> name.equals(d.name()))) {
            return s.withError("'" + name + "' is already in this list.");
        }
        Optional<UUID> existing = relationships.findExternalDependencyIdByName(name);
        ExternalDependencyUsageDraft current = s.currentExternalDependencyUsage();
        if (existing.isPresent()) {
            return s.withCurrentExternalDependencyUsage(current.withExistingDependency(existing.get(), name))
                    .atStage(AWAITING_EXTERNAL_DEP_LINK_DESCRIPTION).clearError();
        }
        return s.withCurrentExternalDependencyUsage(current.withNewDependency(name))
                .atStage(AWAITING_EXTERNAL_DEP_URL).clearError();
    }

    /**
     * Dependency-name input: looks up the named service, rejects unknown
     * names, self-references, and duplicates within the current section's list.
     * On success, sets the current edge's other-service id+name and advances
     * to the description sub-stage.
     */
    private InterviewState applyDependencyName(
            InterviewState s, String name,
            java.util.List<DependencyEdgeDraft> currentSectionList,
            java.util.function.Function<DependencyEdgeDraft, InterviewState> setCurrent,
            InterviewStage descriptionStage) {
        if (name.isEmpty()) {
            return s.withError("Service name cannot be blank.");
        }
        if (name.equals(s.draft().name())) {
            return s.withError("A service can't depend on itself.");
        }
        if (currentSectionList.stream().anyMatch(d -> name.equals(d.otherServiceName()))) {
            return s.withError("'" + name + "' is already in this list.");
        }
        Optional<Service> other = repository.findByName(name);
        if (other.isEmpty()) {
            return s.withError("No service named '" + name + "' exists.");
        }
        // currentEdge is the in-progress draft (set when section was entered or
        // a previous "add another?" answered yes).
        DependencyEdgeDraft current = (descriptionStage == AWAITING_UPSTREAM_DESCRIPTION)
                ? s.currentUpstream() : s.currentDownstream();
        return setCurrent.apply(current.withOther(other.get().getId(), name))
                .atStage(descriptionStage).clearError();
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

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
        // saveAndFlush so the services row is visible to the JdbcTemplate
        // inserts below — JPA's persistence context wouldn't otherwise flush
        // until transaction commit, and the relationship inserts would hit
        // a foreign-key violation.
        Service saved = repository.saveAndFlush(entity);
        UUID serviceId = saved.getId();

        for (ApiDraft api : s.apis()) {
            UUID apiId = relationships.insertApi(serviceId, api.path(), api.method(),
                    api.authMethod(), api.description());
            for (ApiConsumerDraft consumer : api.consumers()) {
                relationships.insertApiConsumer(apiId, consumer.consumerServiceId(), consumer.description());
            }
        }
        for (DependencyEdgeDraft edge : s.upstreamDependencies()) {
            // Captured as "this depends on other" — other is upstream, this is downstream.
            relationships.insertServiceDependency(edge.otherServiceId(), serviceId, edge.description());
        }
        for (DependencyEdgeDraft edge : s.downstreamDependencies()) {
            // Captured as "other depends on this" — this is upstream, other is downstream.
            relationships.insertServiceDependency(serviceId, edge.otherServiceId(), edge.description());
        }
        for (DatabaseUsageDraft usage : s.databaseUsages()) {
            UUID databaseId = usage.isNewDatabase()
                    ? relationships.insertDatabase(usage.databaseName(), usage.engine())
                    : usage.databaseId();
            relationships.insertServiceDatabaseLink(serviceId, databaseId,
                    Boolean.TRUE.equals(usage.isOwner()), usage.description());
        }
        for (ExternalDependencyUsageDraft usage : s.externalDependencyUsages()) {
            UUID externalDepId = usage.isNewDependency()
                    ? relationships.insertExternalDependency(usage.name(), usage.url())
                    : usage.externalDependencyId();
            relationships.insertServiceExternalDepLink(serviceId, externalDepId, usage.description());
        }

        relationships.insertServiceChange(serviceId, "intake-agent", "created",
                "Service registered via intake interview.");

        return new TurnResult(s.clearError(), null, true, serviceId);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private TurnResult askWithError(InterviewState s, String question) {
        String fullQuestion = (s.lastError() != null) ? s.lastError() + " " + question : question;
        return new TurnResult(s.clearError(), fullQuestion, false, null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isOptionalSkip(String trimmed) {
        return trimmed.isEmpty() || SKIP_TOKEN.equalsIgnoreCase(trimmed);
    }

    private static boolean isYes(String trimmed) {
        return YES_TOKENS.contains(trimmed.toLowerCase());
    }

    private static boolean isNo(String trimmed) {
        return NO_TOKENS.contains(trimmed.toLowerCase());
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
