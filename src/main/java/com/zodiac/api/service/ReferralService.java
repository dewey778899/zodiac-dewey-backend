package com.zodiac.api.service;

import com.zodiac.api.dto.ReferralBindRequest;
import com.zodiac.api.dto.ReferralVisitRequest;
import com.zodiac.api.dto.ReferralWithdrawRequest;
import com.zodiac.api.dto.WechatPhoneBindRequest;
import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.entity.ReferralBinding;
import com.zodiac.api.entity.ReferralReward;
import com.zodiac.api.entity.ReferralUser;
import com.zodiac.api.entity.ReferralWithdrawal;
import com.zodiac.api.exception.PaymentException;
import com.zodiac.api.repository.ReferralBindingRepository;
import com.zodiac.api.repository.ReferralRewardRepository;
import com.zodiac.api.repository.ReferralUserRepository;
import com.zodiac.api.repository.ReferralWithdrawalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReferralService {

    private static final int REWARD_PERCENT = 30;
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();
    private static final String PLATFORM_WECHAT = "WECHAT";
    private static final String PLATFORM_DOUYIN = "DOUYIN";

    private final ReferralUserRepository referralUserRepository;
    private final ReferralBindingRepository referralBindingRepository;
    private final ReferralRewardRepository referralRewardRepository;
    private final ReferralWithdrawalRepository referralWithdrawalRepository;

    @Transactional
    public Map<String, Object> bindUser(ReferralBindRequest request) {
        String phone = normalizePhone(request.getPhone());
        String platform = normalizePlatform(request.getPlatform());
        String openid = trimToNull(request.getOpenid());

        ReferralUser user = referralUserRepository.findByPhone(phone).orElseGet(ReferralUser::new);
        if (user.getId() == null) {
            user.setPhone(phone);
            user.setInviteCode(generateInviteCode());
        }

        if (PLATFORM_WECHAT.equals(platform)) {
            user.setWechatOpenid(openid);
            user.setUnionid(trimToNull(request.getUnionid()));
        } else if (PLATFORM_DOUYIN.equals(platform)) {
            user.setDouyinOpenid(openid);
        }

        user.setDisplayName(trimToNull(request.getDisplayName()));
        user.setDeviceToken(trimToNull(request.getDeviceToken()));
        user.setSource(trimToNull(request.getSource()));
        referralUserRepository.save(user);

        bindInviterIfNeeded(user, trimToNull(request.getInviteCode()), trimToNull(request.getSource()));
        return toProfile(user);
    }

    @Transactional
    public Map<String, Object> bindWechatPhoneUser(WechatPhoneBindRequest request) {
        String openid = "wechat-openid-" + Math.abs(request.getLoginCode().hashCode());
        String phone = resolveWechatPhone(request);

        ReferralBindRequest bindRequest = new ReferralBindRequest();
        bindRequest.setPhone(phone);
        bindRequest.setInviteCode(trimToNull(request.getInviteCode()));
        bindRequest.setOpenid(openid);
        bindRequest.setPlatform(PLATFORM_WECHAT);
        bindRequest.setDeviceToken(trimToNull(request.getDeviceToken()));
        bindRequest.setSource(trimToNull(request.getSource()) == null ? "miniapp-wechat-phone" : request.getSource().trim());
        bindRequest.setDisplayName(trimToNull(request.getDisplayName()));
        return bindUser(bindRequest);
    }

    @Transactional
    public Map<String, Object> recordVisit(ReferralVisitRequest request) {
        return Map.of(
                "status", "ok",
                "inviteCode", request.getInviteCode(),
                "deviceToken", trimToNull(request.getDeviceToken()),
                "source", trimToNull(request.getSource())
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getProfile(String phone) {
        return toProfile(requireUser(phone));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSummary(String phone) {
        ReferralUser user = requireUser(phone);
        long inviteCount = referralBindingRepository.countByInviterUserId(user.getId());
        long rewardCount = referralRewardRepository.countByInviterUserId(user.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.putAll(toProfile(user));
        data.put("inviteCount", inviteCount);
        data.put("rewardCount", rewardCount);
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRecords(String phone) {
        ReferralUser user = requireUser(phone);
        List<Map<String, Object>> rewards = referralRewardRepository.findByInviterUserIdOrderBySettledAtDesc(user.getId())
                .stream()
                .map(this::toRewardMap)
                .toList();
        List<Map<String, Object>> bindings = referralBindingRepository.findByInviterUserIdOrderByBoundAtDesc(user.getId())
                .stream()
                .map(this::toBindingMap)
                .toList();
        List<Map<String, Object>> withdrawals = referralWithdrawalRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::toWithdrawalMap)
                .toList();
        return Map.of(
                "rewards", rewards,
                "bindings", bindings,
                "withdrawals", withdrawals
        );
    }

    @Transactional
    public Map<String, Object> applyWithdrawal(ReferralWithdrawRequest request) {
        ReferralUser user = requireUser(request.getPhone());
        int amountFen = request.getAmountFen() == null ? 0 : request.getAmountFen();
        if (amountFen <= 0) {
            throw new PaymentException("withdraw_amount_invalid", "提现金额必须大于 0", HttpStatus.BAD_REQUEST);
        }
        if (nullSafe(user.getWithdrawableFen()) < amountFen) {
            throw new PaymentException("withdraw_amount_invalid", "提现金额超过可提现余额", HttpStatus.BAD_REQUEST);
        }

        ReferralWithdrawal withdrawal = new ReferralWithdrawal();
        withdrawal.setUserId(user.getId());
        withdrawal.setAmountFen(amountFen);
        withdrawal.setStatus(ReferralWithdrawal.STATUS_APPLIED);
        withdrawal.setWithdrawPlatform(normalizePlatform(request.getWithdrawPlatform()));
        withdrawal.setPayeeAccountSnapshot(resolveWithdrawAccount(user, request.getWithdrawPlatform()));
        withdrawal.setRemark("等待人工审核后打款");
        referralWithdrawalRepository.save(withdrawal);

        user.setWithdrawableFen(nullSafe(user.getWithdrawableFen()) - amountFen);
        user.setFrozenFen(nullSafe(user.getFrozenFen()) + amountFen);
        referralUserRepository.save(user);

        int remaining = amountFen;
        for (ReferralReward reward : referralRewardRepository.findByInviterUserIdOrderBySettledAtDesc(user.getId())) {
            if (remaining <= 0) break;
            if (!ReferralReward.STATUS_AVAILABLE.equals(reward.getStatus())) continue;
            reward.setStatus(ReferralReward.STATUS_WITHDRAW_APPLIED);
            reward.setWithdrawalId(withdrawal.getId());
            referralRewardRepository.save(reward);
            remaining -= nullSafe(reward.getAmountFen());
        }

        return Map.of(
                "status", "ok",
                "withdrawalId", withdrawal.getId()
        );
    }

    @Transactional
    public void settleRewardForPaidOrder(PayOrder order) {
        if (order == null || order.getId() == null || order.getReferralUserId() == null) {
            return;
        }
        if (Boolean.TRUE.equals(order.getReferralSettled())) {
            return;
        }
        if (referralRewardRepository.findByPayOrderId(order.getId()).isPresent()) {
            order.setReferralSettled(true);
            return;
        }

        ReferralUser invitee = referralUserRepository.findById(order.getReferralUserId()).orElse(null);
        if (invitee == null) {
            return;
        }

        markUserAsEligible(invitee);

        Optional<ReferralBinding> bindingOpt = referralBindingRepository.findByInviteeUserId(invitee.getId());
        if (bindingOpt.isEmpty()) {
            order.setReferralSettled(true);
            return;
        }

        ReferralBinding binding = bindingOpt.get();
        ReferralUser inviter = referralUserRepository.findById(binding.getInviterUserId()).orElse(null);
        if (inviter == null || !Boolean.TRUE.equals(inviter.getInviterEligible())) {
            order.setReferralSettled(true);
            return;
        }

        int rewardFen = calculateRewardFen(order.getAmountFen());
        if (rewardFen <= 0) {
            order.setReferralSettled(true);
            return;
        }

        ReferralReward reward = new ReferralReward();
        reward.setPayOrderId(order.getId());
        reward.setInviterUserId(inviter.getId());
        reward.setInviteeUserId(invitee.getId());
        reward.setAmountFen(rewardFen);
        reward.setStatus(ReferralReward.STATUS_AVAILABLE);
        referralRewardRepository.save(reward);

        inviter.setBalanceFen(nullSafe(inviter.getBalanceFen()) + rewardFen);
        inviter.setWithdrawableFen(nullSafe(inviter.getWithdrawableFen()) + rewardFen);
        referralUserRepository.save(inviter);

        order.setReferralSettled(true);
    }

    @Transactional
    public void markUserAsEligible(ReferralUser user) {
        if (user == null) {
            return;
        }
        user.setPremiumPaidCount(nullSafe(user.getPremiumPaidCount()) + 1);
        user.setInviterEligible(true);
        referralUserRepository.save(user);
    }

    public Long resolveUserIdByPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        return referralUserRepository.findByPhone(normalizePhone(phone)).map(ReferralUser::getId).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<ReferralUser> listUsers() {
        return referralUserRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<ReferralBinding> listBindings() {
        return referralBindingRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<ReferralReward> listRewards() {
        return referralRewardRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<ReferralWithdrawal> listWithdrawals() {
        return referralWithdrawalRepository.findAll();
    }

    @Transactional
    public Map<String, Object> approveWithdrawal(Long withdrawalId, String remark) {
        ReferralWithdrawal withdrawal = referralWithdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new PaymentException("withdrawal_not_found", "提现单不存在", HttpStatus.NOT_FOUND));
        if (!ReferralWithdrawal.STATUS_APPLIED.equals(withdrawal.getStatus())) {
            throw new PaymentException("withdrawal_status_invalid", "当前提现单状态不可审核通过", HttpStatus.BAD_REQUEST);
        }

        ReferralUser user = referralUserRepository.findById(withdrawal.getUserId())
                .orElseThrow(() -> new PaymentException("referral_user_not_found", "返现账户不存在", HttpStatus.NOT_FOUND));
        withdrawal.setStatus(ReferralWithdrawal.STATUS_PAID);
        withdrawal.setRemark(trimToNull(remark) == null ? "后台审核通过，等待平台打款" : remark.trim());
        referralWithdrawalRepository.save(withdrawal);

        user.setFrozenFen(Math.max(0, nullSafe(user.getFrozenFen()) - nullSafe(withdrawal.getAmountFen())));
        user.setWithdrawnFen(nullSafe(user.getWithdrawnFen()) + nullSafe(withdrawal.getAmountFen()));
        referralUserRepository.save(user);

        for (ReferralReward reward : referralRewardRepository.findByWithdrawalId(withdrawal.getId())) {
            reward.setStatus(ReferralReward.STATUS_WITHDRAWN);
            referralRewardRepository.save(reward);
        }

        return Map.of("status", "ok", "withdrawalId", withdrawalId, "action", "approved");
    }

    @Transactional
    public Map<String, Object> rejectWithdrawal(Long withdrawalId, String remark) {
        ReferralWithdrawal withdrawal = referralWithdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new PaymentException("withdrawal_not_found", "提现单不存在", HttpStatus.NOT_FOUND));
        if (!ReferralWithdrawal.STATUS_APPLIED.equals(withdrawal.getStatus())) {
            throw new PaymentException("withdrawal_status_invalid", "当前提现单状态不可驳回", HttpStatus.BAD_REQUEST);
        }

        ReferralUser user = referralUserRepository.findById(withdrawal.getUserId())
                .orElseThrow(() -> new PaymentException("referral_user_not_found", "返现账户不存在", HttpStatus.NOT_FOUND));
        withdrawal.setStatus(ReferralWithdrawal.STATUS_REJECTED);
        withdrawal.setRemark(trimToNull(remark) == null ? "后台驳回，余额已退回可提现" : remark.trim());
        referralWithdrawalRepository.save(withdrawal);

        user.setFrozenFen(Math.max(0, nullSafe(user.getFrozenFen()) - nullSafe(withdrawal.getAmountFen())));
        user.setWithdrawableFen(nullSafe(user.getWithdrawableFen()) + nullSafe(withdrawal.getAmountFen()));
        referralUserRepository.save(user);

        for (ReferralReward reward : referralRewardRepository.findByWithdrawalId(withdrawal.getId())) {
            reward.setStatus(ReferralReward.STATUS_AVAILABLE);
            reward.setWithdrawalId(null);
            referralRewardRepository.save(reward);
        }

        return Map.of("status", "ok", "withdrawalId", withdrawalId, "action", "rejected");
    }

    @Transactional
    public Map<String, Object> rebindInvitee(Long inviteeUserId, Long inviterUserId, String source) {
        if (inviteeUserId == null || inviterUserId == null || inviteeUserId.equals(inviterUserId)) {
            throw new PaymentException("binding_invalid", "邀请关系参数无效", HttpStatus.BAD_REQUEST);
        }
        ReferralUser invitee = referralUserRepository.findById(inviteeUserId)
                .orElseThrow(() -> new PaymentException("invitee_not_found", "被邀请人账户不存在", HttpStatus.NOT_FOUND));
        ReferralUser inviter = referralUserRepository.findById(inviterUserId)
                .orElseThrow(() -> new PaymentException("inviter_not_found", "邀请人账户不存在", HttpStatus.NOT_FOUND));
        if (!Boolean.TRUE.equals(inviter.getInviterEligible())) {
            throw new PaymentException("inviter_not_eligible", "该邀请人账户尚未激活邀请资格", HttpStatus.BAD_REQUEST);
        }

        ReferralBinding binding = referralBindingRepository.findByInviteeUserId(inviteeUserId).orElseGet(ReferralBinding::new);
        binding.setInviterUserId(inviterUserId);
        binding.setInviteeUserId(inviteeUserId);
        binding.setInviteCode(inviter.getInviteCode());
        binding.setBindSource(trimToNull(source) == null ? "admin-rebind" : source.trim());
        referralBindingRepository.save(binding);
        return Map.of("status", "ok", "inviteeUserId", invitee.getId(), "inviterUserId", inviter.getId());
    }

    @Transactional
    public Map<String, Object> issueReward(Long payOrderId, Long inviterUserId, Integer amountFen, String remark) {
        if (payOrderId == null || inviterUserId == null) {
            throw new PaymentException("reward_params_invalid", "补发返现参数不完整", HttpStatus.BAD_REQUEST);
        }
        if (referralRewardRepository.findByPayOrderId(payOrderId).isPresent()) {
            throw new PaymentException("reward_exists", "该订单已存在返现记录", HttpStatus.BAD_REQUEST);
        }
        ReferralUser inviter = referralUserRepository.findById(inviterUserId)
                .orElseThrow(() -> new PaymentException("inviter_not_found", "邀请人账户不存在", HttpStatus.NOT_FOUND));
        int rewardFen = amountFen == null ? 0 : amountFen;
        if (rewardFen <= 0) {
            throw new PaymentException("reward_amount_invalid", "补发返现金额无效", HttpStatus.BAD_REQUEST);
        }

        ReferralReward reward = new ReferralReward();
        reward.setPayOrderId(payOrderId);
        reward.setInviterUserId(inviterUserId);
        reward.setInviteeUserId(0L);
        reward.setAmountFen(rewardFen);
        reward.setStatus(ReferralReward.STATUS_AVAILABLE);
        referralRewardRepository.save(reward);

        inviter.setBalanceFen(nullSafe(inviter.getBalanceFen()) + rewardFen);
        inviter.setWithdrawableFen(nullSafe(inviter.getWithdrawableFen()) + rewardFen);
        referralUserRepository.save(inviter);
        return Map.of("status", "ok", "payOrderId", payOrderId, "action", "issued", "remark", trimToNull(remark));
    }

    @Transactional
    public Map<String, Object> cancelReward(Long rewardId, String remark) {
        ReferralReward reward = referralRewardRepository.findById(rewardId)
                .orElseThrow(() -> new PaymentException("reward_not_found", "返现记录不存在", HttpStatus.NOT_FOUND));
        if (!ReferralReward.STATUS_AVAILABLE.equals(reward.getStatus())) {
            throw new PaymentException("reward_status_invalid", "只有可用返现才能撤销", HttpStatus.BAD_REQUEST);
        }
        ReferralUser inviter = referralUserRepository.findById(reward.getInviterUserId())
                .orElseThrow(() -> new PaymentException("inviter_not_found", "邀请人账户不存在", HttpStatus.NOT_FOUND));
        inviter.setBalanceFen(Math.max(0, nullSafe(inviter.getBalanceFen()) - nullSafe(reward.getAmountFen())));
        inviter.setWithdrawableFen(Math.max(0, nullSafe(inviter.getWithdrawableFen()) - nullSafe(reward.getAmountFen())));
        referralUserRepository.save(inviter);

        reward.setStatus(ReferralReward.STATUS_CANCELED);
        reward.setWithdrawalId(null);
        referralRewardRepository.save(reward);
        return Map.of("status", "ok", "rewardId", rewardId, "action", "canceled", "remark", trimToNull(remark));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCurrentAccount(String phone, String platform, String openid) {
        ReferralUser user = findCurrentUser(phone, platform, openid)
                .orElseThrow(() -> new PaymentException("referral_user_not_found", "当前返现账户不存在", HttpStatus.NOT_FOUND));
        return toProfile(user);
    }

    private Optional<ReferralUser> findCurrentUser(String phone, String platform, String openid) {
        String normalizedPhone = trimToNull(phone);
        if (normalizedPhone != null) {
            Optional<ReferralUser> byPhone = referralUserRepository.findByPhone(normalizePhone(normalizedPhone));
            if (byPhone.isPresent()) {
                return byPhone;
            }
        }
        String normalizedOpenid = trimToNull(openid);
        String normalizedPlatform = normalizePlatform(platform);
        if (normalizedOpenid == null) {
            return Optional.empty();
        }
        if (PLATFORM_DOUYIN.equals(normalizedPlatform)) {
            return referralUserRepository.findByDouyinOpenid(normalizedOpenid);
        }
        return referralUserRepository.findByWechatOpenid(normalizedOpenid);
    }

    private ReferralUser requireUser(String phone) {
        return referralUserRepository.findByPhone(normalizePhone(phone))
                .orElseThrow(() -> new PaymentException("referral_user_not_found", "返现账户不存在", HttpStatus.NOT_FOUND));
    }

    private void bindInviterIfNeeded(ReferralUser invitee, String inviteCode, String source) {
        if (invitee == null || invitee.getId() == null || inviteCode == null || inviteCode.isBlank()) {
            return;
        }
        if (referralBindingRepository.findByInviteeUserId(invitee.getId()).isPresent()) {
            return;
        }
        ReferralUser inviter = referralUserRepository.findByInviteCode(inviteCode).orElse(null);
        if (inviter == null || inviter.getId().equals(invitee.getId())) {
            return;
        }
        if (!Boolean.TRUE.equals(inviter.getInviterEligible())) {
            return;
        }
        ReferralBinding binding = new ReferralBinding();
        binding.setInviterUserId(inviter.getId());
        binding.setInviteeUserId(invitee.getId());
        binding.setInviteCode(inviteCode);
        binding.setBindSource(source);
        referralBindingRepository.save(binding);
    }

    private Map<String, Object> toProfile(ReferralUser user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getId());
        data.put("phone", user.getPhone());
        data.put("inviteCode", user.getInviteCode());
        data.put("displayName", user.getDisplayName());
        data.put("balanceFen", nullSafe(user.getBalanceFen()));
        data.put("withdrawableFen", nullSafe(user.getWithdrawableFen()));
        data.put("availableFen", nullSafe(user.getWithdrawableFen()));
        data.put("frozenFen", nullSafe(user.getFrozenFen()));
        data.put("withdrawnFen", nullSafe(user.getWithdrawnFen()));
        data.put("premiumPaidCount", nullSafe(user.getPremiumPaidCount()));
        data.put("inviterEligible", Boolean.TRUE.equals(user.getInviterEligible()));
        return data;
    }

    private Map<String, Object> toRewardMap(ReferralReward reward) {
        return Map.of(
                "id", reward.getId(),
                "amountFen", nullSafe(reward.getAmountFen()),
                "status", reward.getStatus(),
                "settledAt", reward.getSettledAt(),
                "withdrawalId", reward.getWithdrawalId()
        );
    }

    private Map<String, Object> toBindingMap(ReferralBinding binding) {
        return Map.of(
                "id", binding.getId(),
                "inviteCode", binding.getInviteCode(),
                "boundAt", binding.getBoundAt(),
                "bindSource", binding.getBindSource()
        );
    }

    private Map<String, Object> toWithdrawalMap(ReferralWithdrawal withdrawal) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", withdrawal.getId());
        data.put("amountFen", nullSafe(withdrawal.getAmountFen()));
        data.put("status", withdrawal.getStatus());
        data.put("remark", withdrawal.getRemark());
        data.put("createdAt", withdrawal.getCreatedAt());
        return data;
    }

    private int calculateRewardFen(Integer amountFen) {
        int amount = nullSafe(amountFen);
        return amount <= 0 ? 0 : (int) Math.floor(amount * REWARD_PERCENT / 100.0);
    }

    private String resolveWithdrawAccount(ReferralUser user, String withdrawPlatform) {
        String platform = normalizePlatform(withdrawPlatform);
        if (PLATFORM_DOUYIN.equals(platform)) {
            return "DOUYIN:" + (trimToNull(user.getDouyinOpenid()) == null ? user.getPhone() : user.getDouyinOpenid());
        }
        return "WECHAT:" + (trimToNull(user.getWechatOpenid()) == null ? user.getPhone() : user.getWechatOpenid());
    }

    private String resolveWechatPhone(WechatPhoneBindRequest request) {
        String phoneNumber = trimToNull(request.getPhoneNumber());
        if (phoneNumber != null) {
            return normalizePhone(phoneNumber);
        }
        String phoneCode = trimToNull(request.getPhoneCode());
        if (phoneCode != null) {
            return "1" + String.format("%010d", Math.abs(phoneCode.hashCode()) % 10000000000L);
        }
        throw new PaymentException("phone_required", "缺少微信手机号授权结果", HttpStatus.BAD_REQUEST);
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private String normalizePhone(String phone) {
        String value = trimToNull(phone);
        if (value == null) {
            throw new PaymentException("phone_required", "手机号不能为空", HttpStatus.BAD_REQUEST);
        }
        return value.replaceAll("\\s+", "");
    }

    private String normalizePlatform(String platform) {
        String value = trimToNull(platform);
        if (value == null) {
            return PLATFORM_WECHAT;
        }
        if ("DOUYIN".equalsIgnoreCase(value) || "TT".equalsIgnoreCase(value)) {
            return PLATFORM_DOUYIN;
        }
        return PLATFORM_WECHAT;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String generateInviteCode() {
        while (true) {
            StringBuilder sb = new StringBuilder("INV");
            for (int i = 0; i < 6; i++) {
                sb.append(CODE_CHARS.charAt(RNG.nextInt(CODE_CHARS.length())));
            }
            String code = sb.toString();
            if (referralUserRepository.findByInviteCode(code).isEmpty()) {
                return code;
            }
        }
    }
}
