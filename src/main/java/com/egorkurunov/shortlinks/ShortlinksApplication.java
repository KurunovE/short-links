package com.egorkurunov.shortlinks;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;

@SpringBootApplication(
        exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
public class ShortlinksApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShortlinksApplication.class, args);
    }
}
