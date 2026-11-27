package com.campus.library.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.library.config.AppProperties;
import com.campus.library.dto.AccountSaveRequest;
import com.campus.library.dto.MajorSaveRequest;
import com.campus.library.entity.Account;
import com.campus.library.entity.AuditLog;
import com.campus.library.entity.Major;
import com.campus.library.exception.ApiException;
import com.campus.library.mapper.AccountMapper;
import com.campus.library.mapper.AuditLogMapper;
import com.campus.library.mapper.MajorMapper;
import jakarta.servlet.http.HttpSession;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DU-01 身份与账号服务（MOD-01 / INT-01）。
 *
 * 核心安全规则：
 * 1. 每次受保护请求都从数据库重读账号的 is_active 与 role，停用账号后旧会话立即失效。
 * 2. 学生角色的目标 student_id 必须等于会话本人，禁止越权读写他人记录。
 * 3. 严格按角色白名单放行：系统管理员仅维护账号、采购员仅只读采购建议、图书馆管理员可代办借还。
 */
@Service
public class AuthService {

    public static final String SESSION_USER_ID = "AUTH_USER_ID";
    private static final ZoneOffset SHANGHAI_OFFSET = ZoneOffset.ofHours(8);
    private static final Set<String> VALID_ROLES = Set.of(
            Account.ROLE_STUDENT,
            Account.ROLE_LIBRARIAN,
            Account.ROLE_BUYER,
            Account.ROLE_SYSADMIN
    );

    public enum Action {
        CATALOG_QUERY,
        BOOK_REGISTER,
        CHECKOUT,
        CHECKIN,
        RESERVE,
        VIEW_STUDENT_LOANS,
        VIEW_RANKING,
        VIEW_PURCHASE,
        VIEW_RECOMMENDATION,
        MANAGE_ACCOUNT,
        MANAGE_CLOCK
    }

    public record AuthContext(Long actorId, String loginName, String role, Long majorId, String displayCode) {}

    private static final Map<Action, Set<String>> ROLE_WHITELIST = new EnumMap<>(Action.class);

    static {
        ROLE_WHITELIST.put(Action.CATALOG_QUERY, Set.of(Account.ROLE_STUDENT, Account.ROLE_LIBRARIAN));
        ROLE_WHITELIST.put(Action.BOOK_REGISTER, Set.of(Account.ROLE_LIBRARIAN));
        ROLE_WHITELIST.put(Action.CHECKOUT, Set.of(Account.ROLE_STUDENT, Account.ROLE_LIBRARIAN));
        ROLE_WHITELIST.put(Action.CHECKIN, Set.of(Account.ROLE_STUDENT, Account.ROLE_LIBRARIAN));
        ROLE_WHITELIST.put(Action.RESERVE, Set.of(Account.ROLE_STUDENT));
        ROLE_WHITELIST.put(Action.VIEW_STUDENT_LOANS, Set.of(Account.ROLE_STUDENT, Account.ROLE_LIBRARIAN));
        ROLE_WHITELIST.put(Action.VIEW_RANKING, Set.of(Account.ROLE_LIBRARIAN));
        ROLE_WHITELIST.put(Action.VIEW_PURCHASE, Set.of(Account.ROLE_BUYER));
        ROLE_WHITELIST.put(Action.VIEW_RECOMMENDATION, Set.of(Account.ROLE_STUDENT));
        ROLE_WHITELIST.put(Action.MANAGE_ACCOUNT, Set.of(Account.ROLE_SYSADMIN));
        ROLE_WHITELIST.put(Action.MANAGE_CLOCK, Set.of(Account.ROLE_LIBRARIAN));
    }

    private final AccountMapper accountMapper;
    private final MajorMapper majorMapper;
    private final AuditLogMapper auditLogMapper;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    public AuthService(
            AccountMapper accountMapper,
            MajorMapper majorMapper,
            AuditLogMapper auditLogMapper,
            PasswordEncoder passwordEncoder,
            AppProperties appProperties
    ) {
        this.accountMapper = accountMapper;
        this.majorMapper = majorMapper;
        this.auditLogMapper = auditLogMapper;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
    }

    /**
     * 四角色登录：未知账号、错误密码或已停用账号统一提示，防止账号枚举。
     */
    public AuthContext login(String loginName, String rawPassword, HttpSession session) {
        Account account = accountMapper.selectOne(
                Wrappers.<Account>lambdaQuery().eq(Account::getLoginName, loginName.trim()));
        if (account == null
                || !Boolean.TRUE.equals(account.getIsActive())
                || !passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            recordAudit(account != null ? account.getUserId() : null, "login", "account:" + loginName, "denied");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误，或账号已停用");
        }
        session.setAttribute(SESSION_USER_ID, account.getUserId());
        recordAudit(account.getUserId(), "login", "account:" + account.getUserId(), "success");
        return toContext(account);
    }

    public void logout(HttpSession session) {
        if (session == null) {
            return;
        }
        Object uid = session.getAttribute(SESSION_USER_ID);
        if (uid instanceof Long actorId) {
            recordAudit(actorId, "logout", "account:" + actorId, "success");
        }
        session.invalidate();
    }

    /**
     * 获取当前登录用户（每次从数据库重读有效状态与角色）。
     */
    public AuthContext requireAuthenticated(HttpSession session, String requestId) {
        if (session == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录", requestId);
        }
        Object uid = session.getAttribute(SESSION_USER_ID);
        if (!(uid instanceof Long userId)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录", requestId);
        }
        Account account = accountMapper.selectById(userId);
        if (account == null || !Boolean.TRUE.equals(account.getIsActive())) {
            session.invalidate();
            recordAudit(userId, "authorize", "session:" + userId, "account_inactive");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_INACTIVE", "会话已失效或账号已停用，请重新登录", requestId);
        }
        return toContext(account);
    }

    /**
     * INT-01 authorize(session, action, targetStudentId?)：
     * 查会话 -> 重读账号与激活状态 -> 匹配 action 角色白名单 -> 校验对象归属。
     */
    public AuthContext authorize(HttpSession session, Action action, Long targetStudentId, String requestId) {
        AuthContext ctx = requireAuthenticated(session, requestId);

        Set<String> allowedRoles = ROLE_WHITELIST.getOrDefault(action, Set.of());
        if (!allowedRoles.contains(ctx.role())) {
            recordAudit(ctx.actorId(), "authorize:" + action.name(), "role:" + ctx.role(), "forbidden");
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权执行该操作", requestId);
        }

        if (action == Action.MANAGE_CLOCK && !appProperties.isExperimentalClockEnabled()) {
            recordAudit(ctx.actorId(), "authorize:MANAGE_CLOCK", "clock", "disabled");
            throw new ApiException(HttpStatus.FORBIDDEN, "EXPERIMENTAL_CLOCK_DISABLED", "当前环境未开启模拟日期功能", requestId);
        }

        // 学生操作的目标归属必须是本人
        if (Account.ROLE_STUDENT.equals(ctx.role())
                && targetStudentId != null
                && !ctx.actorId().equals(targetStudentId)) {
            recordAudit(ctx.actorId(), "authorize:" + action.name(), "student:" + targetStudentId, "ownership_denied");
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问或操作他人记录", requestId);
        }
        return ctx;
    }

    /**
     * 系统管理员维护账号（新增或更新）：校验角色、学生专业与登录名唯一性并写审计日志。
     */
    @Transactional
    public Map<String, Object> saveAccount(AuthContext actor, Long userId, AccountSaveRequest req) {
        if (!Account.ROLE_SYSADMIN.equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅系统管理员可维护账号");
        }
        String role = req.role().trim().toLowerCase();
        if (!VALID_ROLES.contains(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "不支持的角色类型: " + req.role());
        }

        Long majorId = req.majorId();
        if (Account.ROLE_STUDENT.equals(role)) {
            if (majorId == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MAJOR_REQUIRED", "学生账号必须指定有效专业");
            }
            Major major = majorMapper.selectById(majorId);
            if (major == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MAJOR_NOT_FOUND", "指定的专业不存在");
            }
        } else {
            majorId = null;
        }

        String loginName = req.loginName().trim();
        String displayCode = Account.ROLE_STUDENT.equals(role)
                && req.displayCode() != null && !req.displayCode().isBlank()
                ? req.displayCode().trim()
                : null;

        Account duplicateLogin = accountMapper.selectOne(
                Wrappers.<Account>lambdaQuery().eq(Account::getLoginName, loginName));
        if (duplicateLogin != null && !duplicateLogin.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "LOGIN_NAME_EXISTS", "登录名已存在");
        }
        if (displayCode != null) {
            Account duplicateCode = accountMapper.selectOne(
                    Wrappers.<Account>lambdaQuery().eq(Account::getDisplayCode, displayCode));
            if (duplicateCode != null && !duplicateCode.getUserId().equals(userId)) {
                throw new ApiException(HttpStatus.CONFLICT, "DISPLAY_CODE_EXISTS", "试验代号已存在");
            }
        }

        Account target;
        String auditAction;
        if (userId == null) {
            if (req.password() == null || req.password().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_REQUIRED", "新建账号必须提供密码");
            }
            target = new Account();
            target.setLoginName(loginName);
            target.setPasswordHash(passwordEncoder.encode(req.password()));
            target.setRole(role);
            target.setMajorId(majorId);
            target.setDisplayCode(displayCode);
            target.setIsActive(req.isActive() != null ? req.isActive() : Boolean.TRUE);
            accountMapper.insert(target);
            auditAction = "create_account";
        } else {
            target = accountMapper.selectById(userId);
            if (target == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "目标账号不存在");
            }
            String newPasswordHash = (req.password() != null && !req.password().isBlank())
                    ? passwordEncoder.encode(req.password())
                    : target.getPasswordHash();
            boolean newIsActive = req.isActive() != null ? req.isActive() : Boolean.TRUE.equals(target.getIsActive());

            // 显式更新含 null 字段（如学生转为非学生角色时清空 major_id 与 display_code），避免 MyBatis-Plus 忽略 null
            accountMapper.update(
                    null,
                    Wrappers.<Account>lambdaUpdate()
                            .eq(Account::getUserId, userId)
                            .set(Account::getLoginName, loginName)
                            .set(Account::getPasswordHash, newPasswordHash)
                            .set(Account::getRole, role)
                            .set(Account::getMajorId, majorId)
                            .set(Account::getDisplayCode, displayCode)
                            .set(Account::getIsActive, newIsActive)
            );
            target.setLoginName(loginName);
            target.setPasswordHash(newPasswordHash);
            target.setRole(role);
            target.setMajorId(majorId);
            target.setDisplayCode(displayCode);
            target.setIsActive(newIsActive);
            auditAction = "update_account";
        }

        recordAudit(actor.actorId(), auditAction, "account:" + target.getUserId(), "success");
        return toAccountView(target);
    }

    public List<Map<String, Object>> listAccounts(AuthContext actor) {
        if (!Account.ROLE_SYSADMIN.equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅系统管理员可查看账号列表");
        }
        return accountMapper.selectList(Wrappers.<Account>lambdaQuery().orderByAsc(Account::getUserId))
                .stream()
                .map(this::toAccountView)
                .toList();
    }

    /**
     * 系统管理员快捷停用/启用账号：停用后旧会话在下一次受保护请求时立即失效。
     */
    @Transactional
    public Map<String, Object> updateAccountStatus(AuthContext actor, Long userId, boolean active) {
        if (!Account.ROLE_SYSADMIN.equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅系统管理员可维护账号状态");
        }
        Account target = accountMapper.selectById(userId);
        if (target == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "目标账号不存在");
        }
        target.setIsActive(active);
        accountMapper.updateById(target);
        recordAudit(actor.actorId(), active ? "enable_account" : "disable_account", "account:" + userId, "success");
        return toAccountView(target);
    }

    public List<Map<String, Object>> listMajors(AuthContext actor) {
        if (!Account.ROLE_SYSADMIN.equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅系统管理员可维护专业");
        }
        return majorMapper.selectList(Wrappers.<Major>lambdaQuery().orderByAsc(Major::getMajorId))
                .stream()
                .map(this::toMajorView)
                .toList();
    }

    /**
     * 系统管理员维护专业（新增或更新）：校验 code 与 name 唯一性并写审计日志。
     */
    @Transactional
    public Map<String, Object> saveMajor(AuthContext actor, Long majorId, MajorSaveRequest req) {
        if (!Account.ROLE_SYSADMIN.equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅系统管理员可维护专业");
        }
        String code = req.code().trim().toUpperCase();
        String name = req.name().trim();

        Major dupCode = majorMapper.selectOne(Wrappers.<Major>lambdaQuery().eq(Major::getCode, code));
        if (dupCode != null && !dupCode.getMajorId().equals(majorId)) {
            throw new ApiException(HttpStatus.CONFLICT, "MAJOR_CODE_EXISTS", "专业简称已存在");
        }
        Major dupName = majorMapper.selectOne(Wrappers.<Major>lambdaQuery().eq(Major::getName, name));
        if (dupName != null && !dupName.getMajorId().equals(majorId)) {
            throw new ApiException(HttpStatus.CONFLICT, "MAJOR_NAME_EXISTS", "专业名称已存在");
        }

        Major target;
        String auditAction;
        if (majorId == null) {
            target = new Major();
            target.setCode(code);
            target.setName(name);
            majorMapper.insert(target);
            auditAction = "create_major";
        } else {
            target = majorMapper.selectById(majorId);
            if (target == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "MAJOR_NOT_FOUND", "目标专业不存在");
            }
            target.setCode(code);
            target.setName(name);
            majorMapper.updateById(target);
            auditAction = "update_major";
        }

        recordAudit(actor.actorId(), auditAction, "major:" + target.getMajorId(), "success");
        return toMajorView(target);
    }

    private Map<String, Object> toMajorView(Major m) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("major_id", m.getMajorId());
        view.put("code", m.getCode());
        view.put("name", m.getName());
        return view;
    }

    public void recordAudit(Long actorId, String action, String resource, String outcome) {
        AuditLog log = new AuditLog();
        log.setActorId(actorId);
        log.setAction(action);
        log.setResource(resource);
        log.setOutcome(outcome);
        log.setCreatedAt(OffsetDateTime.now(SHANGHAI_OFFSET));
        auditLogMapper.insert(log);
    }

    private AuthContext toContext(Account account) {
        return new AuthContext(
                account.getUserId(),
                account.getLoginName(),
                account.getRole(),
                account.getMajorId(),
                account.getDisplayCode()
        );
    }

    private Map<String, Object> toAccountView(Account a) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("user_id", a.getUserId());
        view.put("login_name", a.getLoginName());
        view.put("role", a.getRole());
        view.put("major_id", a.getMajorId());
        view.put("display_code", a.getDisplayCode());
        view.put("is_active", a.getIsActive());
        return view;
    }
}
