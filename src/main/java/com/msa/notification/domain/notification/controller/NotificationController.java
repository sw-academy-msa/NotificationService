package com.msa.notification.domain.notification.controller;

import com.msa.notification.domain.notification.dto.request.SubscriptionRequest;
import com.msa.notification.domain.notification.dto.response.NotificationResponses.*;
import com.msa.notification.domain.notification.service.NotificationService;
import com.msa.notification.global.response.*;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 사용자 ID를 요청 본문에서 받지 않고 검증된 JWT에서 결정합니다. 반환은 공통 응답과 DTO입니다. */
@RestController
@RequestMapping(
        value = "/api/v1/notifications",
        produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Messages> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.onSuccess(service.list(account(jwt), page, size), SuccessCode.OK);
    }

    @GetMapping("/unread-count")
    public ApiResponse<Count> unread(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.onSuccess(service.unread(account(jwt)), SuccessCode.OK);
    }

    @GetMapping("/{id}")
    public ApiResponse<Message> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.onSuccess(service.get(account(jwt), id), SuccessCode.OK);
    }

    @PatchMapping("/{id}/read")
    public ApiResponse<Changed> read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.onSuccess(service.read(account(jwt), id), SuccessCode.OK);
    }

    @PatchMapping("/read-all")
    public ApiResponse<Changed> readAll(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.onSuccess(service.readAll(account(jwt)), SuccessCode.OK);
    }

    @GetMapping("/push-configuration")
    public ApiResponse<Configuration> configuration() {
        return ApiResponse.onSuccess(service.configuration(), SuccessCode.OK);
    }

    @PostMapping("/push-subscriptions")
    public ApiResponse<Subscription> subscribe(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SubscriptionRequest request) {
        return ApiResponse.onSuccess(service.subscribe(account(jwt), request), SuccessCode.OK);
    }

    @GetMapping("/push-subscriptions")
    public ApiResponse<List<Subscription>> subscriptions(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.onSuccess(service.subscriptions(account(jwt)), SuccessCode.OK);
    }

    @DeleteMapping("/push-subscriptions/{id}")
    public ApiResponse<Void> unsubscribe(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.unsubscribe(account(jwt), id);
        return ApiResponse.onSuccess(null, SuccessCode.OK);
    }

    private UUID account(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
