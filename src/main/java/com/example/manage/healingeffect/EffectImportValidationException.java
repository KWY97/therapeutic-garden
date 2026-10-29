package com.example.manage.healingeffect;

import java.util.List;

public class EffectImportValidationException extends IllegalArgumentException {
    private final List<String> errors;

    public EffectImportValidationException(List<String> errors) {
        super(String.join(System.lineSeparator(), errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
