package com.zodiac.api.controller;

import com.zodiac.api.dto.ReferralBindRequest;
import com.zodiac.api.dto.ReferralVisitRequest;
import com.zodiac.api.dto.ReferralWithdrawRequest;
import com.zodiac.api.dto.WechatPhoneBindRequest;
import com.zodiac.api.service.ReferralService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/referral")
@RequiredArgsConstructor
public class ReferralController {

    private final ReferralService referralService;

    @PostMapping("/bind")
    public ResponseEntity<?> bind(@Valid @RequestBody ReferralBindRequest request) {
        return ResponseEntity.ok(referralService.bindUser(request));
    }

    @PostMapping("/bind/wechat-phone")
    public ResponseEntity<?> bindWechatPhone(@Valid @RequestBody WechatPhoneBindRequest request) {
        return ResponseEntity.ok(referralService.bindWechatPhoneUser(request));
    }

    @PostMapping("/visit")
    public ResponseEntity<?> visit(@Valid @RequestBody ReferralVisitRequest request) {
        return ResponseEntity.ok(referralService.recordVisit(request));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestParam(required = false) String phone,
                                @RequestParam(required = false) String platform,
                                @RequestParam(required = false) String openid) {
        return ResponseEntity.ok(referralService.getCurrentAccount(phone, platform, openid));
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestParam String phone) {
        return ResponseEntity.ok(referralService.getSummary(phone));
    }

    @GetMapping("/records")
    public ResponseEntity<?> records(@RequestParam String phone) {
        return ResponseEntity.ok(referralService.getRecords(phone));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<?> withdraw(@Valid @RequestBody ReferralWithdrawRequest request) {
        return ResponseEntity.ok(referralService.applyWithdrawal(request));
    }
}
