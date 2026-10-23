package com.egorkurunov.shortlinks;

import com.egorkurunov.shortlinks.application.port.in.CreateLink;
import com.egorkurunov.shortlinks.application.port.in.ResolveLink;
import com.egorkurunov.shortlinks.application.port.in.UpdateLink;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "cache.enabled=false",
            "app.public-base-url=https://sho.rt"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class NoCacheApplicationIT extends ApplicationIT {
    NoCacheApplicationIT(CreateLink create, ResolveLink resolve, UpdateLink update) {
        super(create, resolve, update);
    }
}
