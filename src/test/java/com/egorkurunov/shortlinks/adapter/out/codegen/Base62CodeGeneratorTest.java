package com.egorkurunov.shortlinks.adapter.out.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class Base62CodeGeneratorTest {
    @Test
    void generatedCodesMeetPublicContract() {
        var generator = new Base62CodeGenerator();
        for (int i = 0; i < 100; i++) {
            assertThat(generator.next().value()).matches("[A-Za-z0-9]{7}");
        }
    }

    @Test
    void reservedRandomWordIsResampledBeforeInsertion() {
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        String draws = "metricsAbC0123";
        AtomicInteger position = new AtomicInteger();
        SecureRandom random =
                new SecureRandom() {
                    @Override
                    public int nextInt(int bound) {
                        return alphabet.indexOf(draws.charAt(position.getAndIncrement()));
                    }
                };
        assertThat(new Base62CodeGenerator(random).next().value()).isEqualTo("AbC0123");
    }
}
