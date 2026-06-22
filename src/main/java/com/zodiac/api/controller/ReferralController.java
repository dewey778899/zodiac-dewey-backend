package com.zodiac.api.controller;

import com.zodiac.api.service.ReferralService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReferralController {

    private final ReferralService referralService;

    @PostMapping("/referral/visit")
    public ResponseEntity<?> recordVisit(@RequestBody VisitRequest request) {
        return ResponseEntity.ok(referralService.recordVisit(request.getInviteCode(), request.getSource()));
    }

    @PostMapping("/referral/bind")
    public ResponseEntity<?> bind(@Valid @RequestBody BindRequest request) {
        return ResponseEntity.ok(referralService.bindUser(
                request.getPhone(),
                request.getInviteCode(),
                request.getPlatform(),
                request.getOpenid(),
                request.getDisplayName(),
                request.getSource()
        ));
    }

    @PostMapping("/referral/bind/wechat-phone")
    public ResponseEntity<?> bindWechatPhone(@Valid @RequestBody BindWechatPhoneRequest request) {
        return ResponseEntity.ok(referralService.bindWechatPhone(
                request.getPhone(),
                request.getOpenid(),
                request.getInviteCode(),
                request.getSource()
        ));
    }

    @GetMapping("/referral/me")
    public ResponseEntity<?> me(@RequestParam(required = false) String phone,
                                @RequestParam(required = false) String openid) {
        return ResponseEntity.ok(referralService.getProfile(phone, openid));
    }

    @GetMapping("/referral/summary")
    public ResponseEntity<?> summary(@RequestParam String phone) {
        return ResponseEntity.ok(referralService.getSummary(phone));
    }

    @GetMapping("/referral/records")
    public ResponseEntity<?> records(@RequestParam String phone) {
        return ResponseEntity.ok(referralService.getRecords(phone));
    }

    @PostMapping("/referral/withdrawals")
    public ResponseEntity<?> createWithdrawal(@Valid @RequestBody CreateWithdrawalRequest request) {
        return ResponseEntity.ok(referralService.createWithdrawal(
                request.getPhone(),
                request.getAmountFen(),
                request.getWithdrawPlatform(),
                request.getPayeeAccountSnapshot(),
                request.getRemark()
        ));
    }

    @Data
    public static class VisitRequest {
        private String inviteCode;
        private String source;
    }

    @Data
    public static class BindRequest {
        @NotBlank
        private String phone;
        private String inviteCode;
        private String platform;
        private String openid;
        private String displayName;
        private String source;
    }

    @Data
    public static class BindWechatPhoneRequest {
        @NotBlank
        private String phone;
        @NotBlank
        private String openid;
        private String inviteCode;
        private String source;
    }

    @Data
    public static class CreateWithdrawalRequest {
        @NotBlank
        private String phone;
        @NotNull
        private Integer amountFen;
        private String withdrawPlatform;
        @NotBlank
        private String payeeAccountSnapshot;
        private String remark;
    }
}
