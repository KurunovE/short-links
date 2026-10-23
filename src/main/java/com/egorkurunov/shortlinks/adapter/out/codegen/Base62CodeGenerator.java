package com.egorkurunov.shortlinks.adapter.out.codegen;

import com.egorkurunov.shortlinks.application.port.out.CodeGenerator;
import com.egorkurunov.shortlinks.domain.error.InvalidInputException;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public final class Base62CodeGenerator implements CodeGenerator {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int GENERATED_CODE_LENGTH = 7;
    private final SecureRandom random;

    public Base62CodeGenerator() {
        this(new SecureRandom());
    }

    Base62CodeGenerator(SecureRandom random) {
        this.random = random;
    }

    @Override
    public ShortCode next() {
        while (true) {
            var code = new StringBuilder(GENERATED_CODE_LENGTH);
            for (int i = 0; i < GENERATED_CODE_LENGTH; i++) {
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            try {
                return new ShortCode(code.toString());
            } catch (InvalidInputException reservedWord) {

            }
        }
    }
}
