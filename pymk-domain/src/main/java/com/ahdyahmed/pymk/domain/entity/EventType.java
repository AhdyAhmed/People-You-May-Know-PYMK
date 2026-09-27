package com.ahdyahmed.pymk.domain.entity;

/**
 * Implicit and explicit engagement signals captured between two members.
 * Matches PYMK_DESIGN.md section 5.1.
 *
 * <p>{@code INVITE_ACCEPTED} is the positive label used for training data
 * export on Day 18; {@code INVITE_SENT} and {@code INVITE_ACCEPTED} together
 * are the two targets the L2 heavy ranker predicts (Day 24-26).</p>
 */
public enum EventType {
    PROFILE_VIEW,
    SEARCH_APPEARANCE,
    INVITE_SENT,
    INVITE_ACCEPTED,
    INVITE_IGNORED
}
