package com.msa.notification.domain.notification.client;

import com.msa.notification.global.config.client.HttpConfig;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.*;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 테스트에서 Eureka 대신 서비스 ID별 HTTP fixture 주소를 돌려주는 LoadBalancer 대역입니다. */
public class FixedLoadBalancer implements LoadBalancerClient {
    private final Map<String, URI> instances = new ConcurrentHashMap<>();

    public FixedLoadBalancer(Map<String, String> instances) {
        instances.forEach(this::register);
    }

    /** 장애 전환 검증용으로 실행 중에 선택 대상을 바꿉니다. */
    public void register(String serviceId, String address) {
        instances.put(serviceId, URI.create(address));
    }

    /** 운영의 @LoadBalanced Builder처럼 서비스 ID 주소를 이 대역의 인스턴스로 바꾸는 RestClient.Builder입니다. */
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .requestFactory(HttpConfig.requestFactory())
                .requestInterceptor(new LoadBalancerInterceptor(this));
    }

    @Override
    public ServiceInstance choose(String serviceId) {
        URI uri = serviceId == null ? null : instances.get(serviceId);
        if (uri == null) return null;
        return new DefaultServiceInstance(
                serviceId + "-fixture",
                serviceId,
                uri.getHost(),
                uri.getPort(),
                "https".equals(uri.getScheme()));
    }

    @Override
    public <T> ServiceInstance choose(String serviceId, Request<T> request) {
        return choose(serviceId);
    }

    @Override
    public <T> T execute(String serviceId, LoadBalancerRequest<T> request) throws IOException {
        var instance = choose(serviceId);
        // 실제 BlockingLoadBalancerClient와 같은 예외 형태로 인스턴스 없음 상황을 재현합니다.
        if (instance == null)
            throw new IllegalStateException("No instances available for " + serviceId);
        return execute(serviceId, instance, request);
    }

    @Override
    public <T> T execute(
            String serviceId, ServiceInstance serviceInstance, LoadBalancerRequest<T> request)
            throws IOException {
        try {
            return request.apply(serviceInstance);
        } catch (IOException | RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException(failure);
        }
    }

    @Override
    public URI reconstructURI(ServiceInstance instance, URI original) {
        return LoadBalancerUriTools.reconstructURI(instance, original);
    }
}
