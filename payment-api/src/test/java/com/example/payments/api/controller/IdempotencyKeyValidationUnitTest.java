package com.example.payments.api.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IDEM-01/IDEM-02: the exact rule shared by every controller on the public contract. */
class IdempotencyKeyValidationUnitTest {

    @Test
    void rejectsNull() {
        assertFalse(IdempotencyKeyValidation.isValid(null));
    }

    @Test
    void rejectsBlank() {
        assertFalse(IdempotencyKeyValidation.isValid("   "));
    }

    @Test
    void rejectsEmpty() {
        assertFalse(IdempotencyKeyValidation.isValid(""));
    }

    @Test
    void acceptsExactly128Characters() {
        assertTrue(IdempotencyKeyValidation.isValid("a".repeat(128)));
    }

    @Test
    void rejectsOver128Characters() {
        assertFalse(IdempotencyKeyValidation.isValid("a".repeat(129)));
    }

    @Test
    void acceptsAlphanumericUnderscoreAndHyphen() {
        assertTrue(IdempotencyKeyValidation.isValid("Order_123-abc"));
    }

    @Test
    void rejectsWhitespaceInsideTheKey() {
        assertFalse(IdempotencyKeyValidation.isValid("has space"));
    }

    @Test
    void rejectsPunctuationOutsideTheAllowedSet() {
        assertFalse(IdempotencyKeyValidation.isValid("key|with;bad:chars"));
    }

    @Test
    void acceptsASingleCharacterKey() {
        assertTrue(IdempotencyKeyValidation.isValid("a"));
    }
}
