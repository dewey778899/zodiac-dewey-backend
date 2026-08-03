package com.zodiac.api.service;

import com.zodiac.api.entity.PayOrder;
import com.zodiac.api.entity.ReferralBinding;
import com.zodiac.api.entity.ReferralReward;
import com.zodiac.api.entity.ReferralUser;
import com.zodiac.api.entity.ReferralWithdrawal;
import com.zodiac.api.repository.PayOrderRepository;
import com.zodiac.api.repository.ReferralBindingRepository;
import com.zodiac.api.repository.ReferralRewardRepository;
import com.zodiac.api.repository.ReferralUserRepository;
import com.zodiac.api.repository.ReferralWithdrawalRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReferralService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String INVITE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int DEFAULT_AUTO_REWARD_FEN = 100;
    private static final int MIN_WITHDRAW_FEN = 100;
    private static final int MAX_WITHDRAWALS_PER_DAY = 1;
    private static final String AUTO_REWARD_REMARK = "auto-payment-settlement";

    private final ReferralUserRepository referralUserRepository;
    private final ReferralBindingRepository referralBindingRepository;
    private final ReferralRewardRepository referralRewardRepository;
    private final ReferralWithdrawalRepository referralWithdrawalRepository;
    private final PayOrderRepository payOrderRepository;

    @Transactional
    public Map<String, Object> recordVisit(String inviteCode, String source) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("inviteCode", normalizeInviteCode(inviteCode));
        resp.put("source", trim(source, 64));
        return resp;
    }

    @Transactional
    public Map<String, Object> bindUser(String phone,
                                        String inviteCode,
                                        String platform,
                                        String openid,
                                        String displayName,
                                        String source) {
        String normalizedPhone = normalizePhone(phone);
        ReferralUser user = referralUserRepository.findByPhone(normalizedPhone)
                .orElseGet(() -> {
                    ReferralUser created = new ReferralUser();
                    created.setPhone(normalizedPhone);
                    created.setInviteCode(generateInviteCode());
                    return created;
                });

        user.setDisplayName(trim(displayName, 64));
        user.setLastBindSource(trim(source, 64));
        if ("WECHAT".equalsIgnoreCase(platform) && openid != null && !openid.isBlank()) {
            user.setWechatOpenid(trim(openid, 128));
        }
        user = referralUserRepository.save(user);

        if (inviteCode != null && !inviteCode.isBlank()) {
            Optional<ReferralUser> inviter = referralUserRepository.findByInviteCode(normalizeInviteCode(inviteCode));
            if (inviter.isPresent() && !inviter.get().getId().equals(user.getId())) {
                ReferralBinding binding = referralBindingRepository.findByInviteeUserId(user.getId())
                        .orElseGet(ReferralBinding::new);
                binding.setInviteeUserId(user.getId());
                binding.setInviterUserId(inviter.get().getId());
                binding.setInviteCode(inviter.get().getInviteCode());
                binding.setBindSource(trim(source, 64));
                referralBindingRepository.save(binding);
            }
        }

        return toReferralProfile(user);
    }

    @Transactional
    public Map<String, Object> bindWechatPhone(String phone, String openid, String inviteCode, String source) {
        return bindUser(phone, inviteCode, "WECHAT", openid, phone, source);
    }

    public Map<String, Object> getProfile(String phone, String openid) {
        return toReferralProfile(findUser(phone, openid));
    }

    public Map<String, Object> getSummary(String phone) {
        ReferralUser user = referralUserRepository.findByPhone(normalizePhone(phone))
                .orElseThrow(() -> new IllegalArgumentException("referral user not found"));
        Map<String, Object> summary = toReferralProfile(user);
        summary.put("inviteCount", referralBindingRepository.countByInviterUserId(user.getId()));
        summary.put("rewardCount", referralRewardRepository.countByInviterUserId(user.getId()));
        return summary;
    }

    public Map<String, Object> getRecords(String phone) {
        ReferralUser user = referralUserRepository.findByPhone(normalizePhone(phone))
                .orElseThrow(() -> new IllegalArgumentException("referral user not found"));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("rewards", mapRewards(referralRewardRepository.findByInviterUserIdOrderBySettledAtDesc(user.getId())));
        resp.put("bindings", mapBindings(referralBindingRepository.findAllByOrderByBoundAtDesc(), user.getId()));
        resp.put("withdrawals", mapWithdrawals(referralWithdrawalRepository.findByUserIdOrderByCreatedAtDesc(user.getId())));
        return resp;
    }

    @Transactional
    public Map<String, Object> createWithdrawal(String phone,
                                                Integer amountFen,
                                                String withdrawPlatform,
                                                String payeeAccountSnapshot,
                                                String remark) {
        ReferralUser user = referralUserRepository.findByPhone(normalizePhone(phone))
                .orElseThrow(() -> new IllegalArgumentException("referral user not found"));
        if (amountFen == null || amountFen < MIN_WITHDRAW_FEN) {
            throw new IllegalArgumentException("单笔提现金额最低为 1 元");
        }
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        if (referralWithdrawalRepository.countByUserIdAndCreatedAtGreaterThanEqual(user.getId(), dayStart)
                >= MAX_WITHDRAWALS_PER_DAY) {
            throw new IllegalArgumentException("每日最多提交 1 笔提现申请，请次日再试");
        }
        if (user.getWithdrawableFen() == null || user.getWithdrawableFen() < amountFen) {
            throw new IllegalArgumentException("insufficient withdrawable balance");
        }

        user.setWithdrawableFen(user.getWithdrawableFen() - amountFen);
        user.setFrozenFen(defaultZero(user.getFrozenFen()) + amountFen);
        referralUserRepository.save(user);

        ReferralWithdrawal withdrawal = new ReferralWithdrawal();
        withdrawal.setUserId(user.getId());
        withdrawal.setAmountFen(amountFen);
        withdrawal.setWithdrawPlatform(normalizePlatform(withdrawPlatform));
        withdrawal.setPayeeAccountSnapshot(trim(payeeAccountSnapshot, 255));
        withdrawal.setRemark(trim(remark, 255));
        referralWithdrawalRepository.save(withdrawal);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("withdrawalId", withdrawal.getId());
        resp.put("withdrawalStatus", withdrawal.getStatus());
        resp.put("reviewMode", "MANUAL_REVIEW");
        resp.put("payoutMode", "MANUAL_PAYOUT");
        resp.put("withdrawPlatform", withdrawal.getWithdrawPlatform());
        return resp;
    }

    public Map<String, Object> getOverview() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("users", referralUserRepository.count());
        resp.put("bindings", referralBindingRepository.count());
        resp.put("withdrawals", referralWithdrawalRepository.countByStatus(ReferralWithdrawal.STATUS_APPLIED));
        return resp;
    }

    public List<Map<String, Object>> getUsers() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ReferralUser user : referralUserRepository.findAllByOrderByCreatedAtDesc()) {
            list.add(toReferralProfile(user));
        }
        return list;
    }

    public List<Map<String, Object>> getBindings() {
        return mapBindings(referralBindingRepository.findAllByOrderByBoundAtDesc(), null);
    }

    public List<Map<String, Object>> getRewards() {
        return mapRewards(referralRewardRepository.findAllByOrderBySettledAtDesc());
    }

    public List<Map<String, Object>> getWithdrawals() {
        return mapWithdrawals(referralWithdrawalRepository.findAllByOrderByCreatedAtDesc());
    }

    @Transactional
    public void rebind(Long inviteeUserId, Long inviterUserId, String source) {
        if (inviteeUserId == null || inviterUserId == null) {
            throw new IllegalArgumentException("inviteeUserId and inviterUserId are required");
        }
        ReferralUser inviter = referralUserRepository.findById(inviterUserId)
                .orElseThrow(() -> new IllegalArgumentException("inviter not found"));
        referralUserRepository.findById(inviteeUserId)
                .orElseThrow(() -> new IllegalArgumentException("invitee not found"));

        ReferralBinding binding = referralBindingRepository.findByInviteeUserId(inviteeUserId)
                .orElseGet(ReferralBinding::new);
        binding.setInviteeUserId(inviteeUserId);
        binding.setInviterUserId(inviter.getId());
        binding.setInviteCode(inviter.getInviteCode());
        binding.setBindSource(trim(source, 64));
        referralBindingRepository.save(binding);
    }

    @Transactional
    public void issueReward(Long payOrderId, Long inviterUserId, Long inviteeUserId, Integer amountFen, String remark) {
        if (payOrderId == null || inviterUserId == null) {
            throw new IllegalArgumentException("payOrderId and inviterUserId are required");
        }
        if (amountFen == null || amountFen <= 0) {
            throw new IllegalArgumentException("reward amount must be greater than 0");
        }

        referralUserRepository.findById(inviterUserId)
                .orElseThrow(() -> new IllegalArgumentException("inviter not found"));
        PayOrder payOrder = payOrderRepository.findById(payOrderId)
                .orElseThrow(() -> new IllegalArgumentException("pay order not found"));
        if (referralRewardRepository.existsByPayOrderId(payOrder.getId())) {
            throw new IllegalArgumentException("reward already exists for this pay order");
        }

        ReferralReward reward = new ReferralReward();
        reward.setPayOrderId(payOrder.getId());
        reward.setInviterUserId(inviterUserId);
        Long resolvedInviteeUserId = inviteeUserId;
        if (resolvedInviteeUserId == null && payOrder.getPhone() != null && !payOrder.getPhone().isBlank()) {
            resolvedInviteeUserId = referralUserRepository.findByPhone(normalizePhone(payOrder.getPhone()))
                    .map(ReferralUser::getId)
                    .orElse(null);
        }
        if (resolvedInviteeUserId == null) {
            throw new IllegalArgumentException("invitee user is required for manual reward issue");
        }
        referralUserRepository.findById(resolvedInviteeUserId)
                .orElseThrow(() -> new IllegalArgumentException("invitee not found"));
        reward.setInviteeUserId(resolvedInviteeUserId);
        reward.setAmountFen(amountFen);
        reward.setRemark(trim(remark, 255));
        referralRewardRepository.save(reward);
        applyRewardToUser(inviterUserId, amountFen, true);
    }

    @Transactional
    public void cancelReward(Long rewardId, String remark) {
        ReferralReward reward = referralRewardRepository.findById(rewardId)
                .orElseThrow(() -> new IllegalArgumentException("reward not found"));
        if (!ReferralReward.STATUS_AVAILABLE.equals(reward.getStatus())) {
            throw new IllegalArgumentException("reward cannot be canceled in current status");
        }
        reward.setStatus(ReferralReward.STATUS_CANCELED);
        reward.setRemark(trim(remark, 255));
        referralRewardRepository.save(reward);
        applyRewardToUser(reward.getInviterUserId(), reward.getAmountFen(), false);
    }

    @Transactional
    public void approveWithdrawal(Long withdrawalId, String remark) {
        ReferralWithdrawal withdrawal = referralWithdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal not found"));
        if (!ReferralWithdrawal.STATUS_APPLIED.equals(withdrawal.getStatus())) {
            throw new IllegalArgumentException("withdrawal is not in applied status");
        }

        ReferralUser user = referralUserRepository.findById(withdrawal.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("referral user not found"));
        int amountFen = defaultZero(withdrawal.getAmountFen());
        user.setFrozenFen(Math.max(0, defaultZero(user.getFrozenFen()) - amountFen));
        user.setWithdrawnFen(defaultZero(user.getWithdrawnFen()) + amountFen);
        user.setBalanceFen(Math.max(0, defaultZero(user.getBalanceFen()) - amountFen));
        referralUserRepository.save(user);

        withdrawal.setStatus(ReferralWithdrawal.STATUS_SUCCESS);
        withdrawal.setRemark(trim(remark, 255));
        referralWithdrawalRepository.save(withdrawal);

        List<ReferralReward> rewards = referralRewardRepository.findByInviterUserIdOrderBySettledAtDesc(user.getId());
        int remaining = amountFen;
        for (ReferralReward reward : rewards) {
            if (remaining <= 0) {
                break;
            }
            if (!ReferralReward.STATUS_AVAILABLE.equals(reward.getStatus())) {
                continue;
            }
            reward.setStatus(ReferralReward.STATUS_WITHDRAWN);
            reward.setWithdrawalId(withdrawal.getId());
            referralRewardRepository.save(reward);
            remaining -= defaultZero(reward.getAmountFen());
        }
    }

    @Transactional
    public void rejectWithdrawal(Long withdrawalId, String remark) {
        ReferralWithdrawal withdrawal = referralWithdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal not found"));
        if (!ReferralWithdrawal.STATUS_APPLIED.equals(withdrawal.getStatus())) {
            throw new IllegalArgumentException("withdrawal is not in applied status");
        }

        ReferralUser user = referralUserRepository.findById(withdrawal.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("referral user not found"));
        int amountFen = defaultZero(withdrawal.getAmountFen());
        user.setFrozenFen(Math.max(0, defaultZero(user.getFrozenFen()) - amountFen));
        user.setWithdrawableFen(defaultZero(user.getWithdrawableFen()) + amountFen);
        referralUserRepository.save(user);

        withdrawal.setStatus(ReferralWithdrawal.STATUS_REJECTED);
        withdrawal.setRemark(trim(remark, 255));
        referralWithdrawalRepository.save(withdrawal);
    }

    @Transactional
    public void recordPremiumPayment(PayOrder payOrder) {
        if (payOrder == null || payOrder.getPhone() == null || payOrder.getPhone().isBlank()) {
            return;
        }

        ReferralUser invitee = referralUserRepository.findByPhone(normalizePhone(payOrder.getPhone())).orElse(null);
        if (invitee == null) {
            return;
        }

        invitee.setPremiumPaidCount(defaultZero(invitee.getPremiumPaidCount()) + 1);
        invitee.setInviterEligible(Boolean.TRUE);
        referralUserRepository.save(invitee);

        if (payOrder.getId() == null || referralRewardRepository.existsByPayOrderId(payOrder.getId())) {
            return;
        }

        ReferralBinding binding = referralBindingRepository.findByInviteeUserId(invitee.getId()).orElse(null);
        if (binding == null || binding.getInviterUserId() == null) {
            return;
        }
        if (referralUserRepository.findById(binding.getInviterUserId()).isEmpty()) {
            return;
        }

        int rewardAmountFen = resolveAutoRewardAmountFen(payOrder);
        if (rewardAmountFen <= 0) {
            return;
        }

        ReferralReward reward = new ReferralReward();
        reward.setPayOrderId(payOrder.getId());
        reward.setInviterUserId(binding.getInviterUserId());
        reward.setInviteeUserId(invitee.getId());
        reward.setAmountFen(rewardAmountFen);
        reward.setRemark(AUTO_REWARD_REMARK);
        referralRewardRepository.save(reward);
        applyRewardToUser(binding.getInviterUserId(), rewardAmountFen, true);
    }

    public Map<String, Object> getOrderReferralSnapshot(PayOrder payOrder) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("referralUserId", null);
        snapshot.put("referralPhone", payOrder == null ? null : payOrder.getPhone());
        snapshot.put("referralSettled", false);
        if (payOrder == null || payOrder.getPhone() == null || payOrder.getPhone().isBlank()) {
            if (payOrder != null && payOrder.getId() != null) {
                referralRewardRepository.findFirstByPayOrderId(payOrder.getId()).ifPresent((reward) -> {
                    snapshot.put("referralUserId", reward.getInviteeUserId());
                    snapshot.put("referralSettled", true);
                });
            }
            return snapshot;
        }

        referralUserRepository.findByPhone(normalizePhone(payOrder.getPhone())).ifPresent((user) -> {
            snapshot.put("referralUserId", user.getId());
        });
        if (payOrder.getId() != null) {
            snapshot.put("referralSettled", referralRewardRepository.existsByPayOrderId(payOrder.getId()));
        }
        return snapshot;
    }

    private ReferralUser findUser(String phone, String openid) {
        if (phone != null && !phone.isBlank()) {
            return referralUserRepository.findByPhone(normalizePhone(phone))
                    .orElseThrow(() -> new IllegalArgumentException("referral user not found"));
        }
        if (openid != null && !openid.isBlank()) {
            return referralUserRepository.findByWechatOpenid(trim(openid, 128))
                    .orElseThrow(() -> new IllegalArgumentException("referral user not found"));
        }
        throw new IllegalArgumentException("phone or openid is required");
    }

    private void applyRewardToUser(Long inviterUserId, Integer amountFen, boolean add) {
        ReferralUser user = referralUserRepository.findById(inviterUserId)
                .orElseThrow(() -> new IllegalArgumentException("inviter not found"));
        int amount = defaultZero(amountFen);
        int delta = add ? amount : -amount;
        user.setBalanceFen(Math.max(0, defaultZero(user.getBalanceFen()) + delta));
        user.setWithdrawableFen(Math.max(0, defaultZero(user.getWithdrawableFen()) + delta));
        if (add) {
            user.setInviterEligible(Boolean.TRUE);
        }
        referralUserRepository.save(user);
    }

    private Map<String, Object> toReferralProfile(ReferralUser user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("userId", user.getId());
        map.put("phone", user.getPhone());
        map.put("inviteCode", user.getInviteCode());
        map.put("displayName", user.getDisplayName());
        map.put("balanceFen", defaultZero(user.getBalanceFen()));
        map.put("availableFen", defaultZero(user.getWithdrawableFen()));
        map.put("withdrawableFen", defaultZero(user.getWithdrawableFen()));
        map.put("frozenFen", defaultZero(user.getFrozenFen()));
        map.put("withdrawnFen", defaultZero(user.getWithdrawnFen()));
        map.put("premiumPaidCount", defaultZero(user.getPremiumPaidCount()));
        map.put("inviterEligible", Boolean.TRUE.equals(user.getInviterEligible()));
        map.put("wechatOpenid", user.getWechatOpenid());
        map.put("douyinOpenid", user.getDouyinOpenid());
        map.put("unionid", user.getUnionid());
        map.put("createdAt", user.getCreatedAt());
        return map;
    }

    private List<Map<String, Object>> mapBindings(List<ReferralBinding> bindings, Long onlyUserId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ReferralBinding binding : bindings) {
            if (onlyUserId != null && !onlyUserId.equals(binding.getInviterUserId())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", binding.getId());
            item.put("inviterUserId", binding.getInviterUserId());
            item.put("inviteeUserId", binding.getInviteeUserId());
            item.put("inviteCode", binding.getInviteCode());
            item.put("bindSource", binding.getBindSource());
            item.put("boundAt", binding.getBoundAt());
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> mapRewards(List<ReferralReward> rewards) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ReferralReward reward : rewards) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", reward.getId());
            item.put("payOrderId", reward.getPayOrderId());
            item.put("inviterUserId", reward.getInviterUserId());
            item.put("inviteeUserId", reward.getInviteeUserId());
            item.put("amountFen", reward.getAmountFen());
            item.put("status", reward.getStatus());
            item.put("withdrawalId", reward.getWithdrawalId());
            item.put("remark", reward.getRemark());
            item.put("settledAt", reward.getSettledAt());

            referralUserRepository.findById(reward.getInviterUserId()).ifPresent((user) -> {
                item.put("inviterPhone", user.getPhone());
                item.put("inviterDisplayName", user.getDisplayName());
            });
            if (reward.getInviteeUserId() != null) {
                referralUserRepository.findById(reward.getInviteeUserId()).ifPresent((user) -> {
                    item.put("inviteePhone", user.getPhone());
                    item.put("inviteeDisplayName", user.getDisplayName());
                });
            }
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> mapWithdrawals(List<ReferralWithdrawal> withdrawals) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ReferralWithdrawal withdrawal : withdrawals) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", withdrawal.getId());
            item.put("userId", withdrawal.getUserId());
            item.put("amountFen", withdrawal.getAmountFen());
            item.put("status", withdrawal.getStatus());
            item.put("withdrawPlatform", withdrawal.getWithdrawPlatform());
            item.put("payeeAccountSnapshot", withdrawal.getPayeeAccountSnapshot());
            item.put("remark", withdrawal.getRemark());
            item.put("createdAt", withdrawal.getCreatedAt());
            item.put("updatedAt", withdrawal.getUpdatedAt());
            item.put("reviewMode", "MANUAL_REVIEW");
            item.put("payoutMode", "MANUAL_PAYOUT");
            referralUserRepository.findById(withdrawal.getUserId()).ifPresent((user) -> {
                item.put("userPhone", user.getPhone());
                item.put("userDisplayName", user.getDisplayName());
            });
            list.add(item);
        }
        return list;
    }

    private int resolveAutoRewardAmountFen(PayOrder payOrder) {
        int orderAmountFen = defaultZero(payOrder.getTotalFee());
        if (orderAmountFen <= 0) {
            return 0;
        }
        return Math.min(orderAmountFen, DEFAULT_AUTO_REWARD_FEN);
    }

    private String generateInviteCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                sb.append(INVITE_CHARS.charAt(RNG.nextInt(INVITE_CHARS.length())));
            }
            code = sb.toString();
        } while (referralUserRepository.findByInviteCode(code).isPresent());
        return code;
    }

    private String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("phone is required");
        }
        return phone.replaceAll("\\s+", "").trim();
    }

    private String normalizeInviteCode(String inviteCode) {
        return inviteCode == null ? "" : inviteCode.trim().toUpperCase();
    }

    private String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "WECHAT";
        }
        String value = platform.trim().toUpperCase();
        return "ALIPAY".equals(value) ? "ALIPAY" : "WECHAT";
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private int defaultZero(Integer value) {
        return value == null ? 0 : value;
    }
}
