package com.opensmpsdeck.project;

import java.time.Instant;

/** Review metadata. Timestamps belong to the caller so edits remain deterministic. */
public record ReviewNote(String id, double startSeconds, Double endSeconds, String laneId, String clipId,
                         String text, String author, String status, String createdAt, Reply reply) {
    public static final String OPEN = "open";
    public static final String RESOLVED = "resolved";

    public record Reply(String text, String author, String createdAt) {}

    public boolean isOpen() { return OPEN.equals(status); }

    public ReviewNote resolve(Reply response) {
        return new ReviewNote(id, startSeconds, endSeconds, laneId, clipId, text, author, RESOLVED,
                createdAt, response == null ? reply : response);
    }

    static void validateTimestamp(String value) {
        try {
            Instant.parse(value);
        } catch (java.time.DateTimeException | NullPointerException e) {
            throw new IllegalArgumentException("Review timestamp must be an ISO-8601 instant", e);
        }
    }
}
