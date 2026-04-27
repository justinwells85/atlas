package com.atlas.intake;

/**
 * In-progress representation of one API endpoint being captured during the
 * interview. Maps 1:1 onto a row in the {@code apis} table at persist time.
 */
public record ApiDraft(String path, String method, String authMethod, String description) {

    public static ApiDraft empty() {
        return new ApiDraft(null, null, null, null);
    }

    public ApiDraft withPath(String v) { return new ApiDraft(v, method, authMethod, description); }
    public ApiDraft withMethod(String v) { return new ApiDraft(path, v, authMethod, description); }
    public ApiDraft withAuthMethod(String v) { return new ApiDraft(path, method, v, description); }
    public ApiDraft withDescription(String v) { return new ApiDraft(path, method, authMethod, v); }
}
