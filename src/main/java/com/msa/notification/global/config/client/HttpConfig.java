package com.msa.notification.global.config.client;

import org.springframework.context.annotation.*;
import org.springframework.core.env.*;

import java.net.*;
import java.net.http.*;
import java.time.*;

/** 내부 HTTP도 연결/요청 시간을 제한하며 redirect로 인증 정보가 유출되지 않게 합니다. */
@Configuration
public class HttpConfig {
    @Bean
    public HttpClient internalHttp(ClientProperties properties, Environment environment) {
        boolean local =
                environment.acceptsProfiles(Profiles.of("local | test"))
                        && !environment.acceptsProfiles(Profiles.of("prod"));
        for (String address :
                new String[] {properties.account(), properties.ai(), properties.productQuery()}) {
            URI uri = URI.create(address);
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getQuery() != null
                    || (!"https".equals(uri.getScheme())
                            && !(local && "http".equals(uri.getScheme()))))
                throw new IllegalArgumentException("내부 서비스 주소의 전송 설정이 올바르지 않습니다.");
        }
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
