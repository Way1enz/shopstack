package com.ecommerce.user.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// Rules: 8+ chars, upper, lower, digit, special char, no whitespace. Reports every failing rule at once.
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    private static final Pattern HAS_UPPER = Pattern.compile("[A-Z]");
    private static final Pattern HAS_LOWER = Pattern.compile("[a-z]");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");
    private static final Pattern HAS_SPECIAL = Pattern.compile("[!@#$%^&*()\\-_=+\\[\\]{}|;:'\",.<>?/`~]");
    private static final Pattern HAS_SPACE = Pattern.compile("\\s");

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null) {
            return true; // @NotBlank on the field handles null/blank separately
        }

        List<String> problems = new ArrayList<>();
        if (password.length() < 8) problems.add("at least 8 characters");
        if (!HAS_UPPER.matcher(password).find()) problems.add("an uppercase letter");
        if (!HAS_LOWER.matcher(password).find()) problems.add("a lowercase letter");
        if (!HAS_DIGIT.matcher(password).find()) problems.add("a digit");
        if (!HAS_SPECIAL.matcher(password).find()) problems.add("a special character");
        if (HAS_SPACE.matcher(password).find()) problems.add("no whitespace");

        if (problems.isEmpty()) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("must contain " + String.join(", ", problems))
                .addConstraintViolation();
        return false;
    }
}
