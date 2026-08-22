package com.ecommerce.user.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StrongPasswordValidatorTest {

    private final StrongPasswordValidator validator = new StrongPasswordValidator();

    // Deep stubs so the buildConstraintViolationWithTemplate(...).addConstraintViolation() chain just works.
    private ConstraintValidatorContext context() {
        return mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);
    }

    @Test
    void nullPassword_isValid() {
        // @NotBlank on the field handles null/blank separately - this validator only judges content.
        assertThat(validator.isValid(null, context())).isTrue();
    }

    @Test
    void strongPassword_isValid() {
        assertThat(validator.isValid("Password123!", context())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ab1!", "Abc12!", "Abcd12!"})
    void tooShort_isInvalid(String password) {
        assertThat(validator.isValid(password, context())).isFalse();
    }

    @Test
    void missingUppercase_isInvalid() {
        assertThat(validator.isValid("password123!", context())).isFalse();
    }

    @Test
    void missingLowercase_isInvalid() {
        assertThat(validator.isValid("PASSWORD123!", context())).isFalse();
    }

    @Test
    void missingDigit_isInvalid() {
        assertThat(validator.isValid("Password!!!", context())).isFalse();
    }

    @Test
    void missingSpecialCharacter_isInvalid() {
        assertThat(validator.isValid("Password123", context())).isFalse();
    }

    @Test
    void containsWhitespace_isInvalid() {
        assertThat(validator.isValid("Password 123!", context())).isFalse();
    }

    @Test
    void invalidPassword_disablesDefaultMessageAndBuildsACustomOne() {
        ConstraintValidatorContext ctx = context();

        boolean result = validator.isValid("weak", ctx);

        assertThat(result).isFalse();
        verify(ctx).disableDefaultConstraintViolation();
        verify(ctx).buildConstraintViolationWithTemplate(org.mockito.ArgumentMatchers.contains("at least 8 characters"));
    }

    @Test
    void multipleViolations_areAllReportedInOneMessage() {
        ConstraintValidatorContext ctx = context();

        // Fails length, uppercase, digit, and special-character rules all at once.
        validator.isValid("lowercase", ctx);

        verify(ctx).buildConstraintViolationWithTemplate(
                org.mockito.ArgumentMatchers.argThat(message ->
                        message.contains("an uppercase letter")
                                && message.contains("a digit")
                                && message.contains("a special character")));
    }
}
