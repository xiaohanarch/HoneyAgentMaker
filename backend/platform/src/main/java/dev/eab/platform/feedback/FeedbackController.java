package dev.eab.platform.feedback;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.feedback.FeedbackService.FeedbackException;
import dev.eab.platform.feedback.FeedbackStore.Feedback;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.Principals.Principal;
import dev.eab.platform.http.RateLimiter;
import dev.eab.spec.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 统一入口的“结论有误”：业务用户对自己的一次运行提交纠正，生成候选规则进入审核队列 */
@RestController
@Tag(name = "feedback")
public class FeedbackController {

    static final int MAX_REASON = 500;

    private final PlatformServices services;
    private final FeedbackService feedback;
    private final RateLimiter limiter;

    public FeedbackController(PlatformServices services, FeedbackService feedback) {
        this.services = services;
        this.feedback = feedback;
        this.limiter = new RateLimiter(20, 60_000, services.clock());
    }

    public record FeedbackBody(String runId, String reason) {}

    @PostMapping("/feedback")
    @Operation(summary = "结论有误：对自己的一次运行提交纠正，生成候选规则进入治理中心的审核队列")
    public ApiResponse<Feedback> submit(HttpServletRequest request, @RequestBody FeedbackBody body) {
        Principal principal = services.principals().require(request);
        if (!(principal.viewer() instanceof Viewer.Tenant)) throw new ApiException(403, "FORBIDDEN", "只有租户用户可以反馈结论");
        if (body == null || body.runId() == null || !body.runId().matches("^run-[A-Za-z0-9-]{1,80}$")) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：runId 格式不对");
        }
        if (body.reason() == null || body.reason().isBlank() || body.reason().length() > MAX_REASON) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：reason 不能为空，最长 " + MAX_REASON + " 字");
        }
        limiter.check("feedback:" + principal.user().id());
        try {
            return ApiResponse.success(feedback.submit(
                    principal.user(), body.runId(), body.reason().strip()));
        } catch (FeedbackException e) {
            throw new ApiException(e.status(), e.code(), e.getMessage());
        }
    }

    @GetMapping("/feedback/mine")
    @Operation(summary = "我提交过的反馈及审核状态")
    public ApiResponse<List<Feedback>> mine(HttpServletRequest request) {
        Principal principal = services.principals().require(request);
        if (!(principal.viewer() instanceof Viewer.Tenant)) throw new ApiException(403, "FORBIDDEN", "只有租户用户有反馈记录");
        return ApiResponse.success(feedback.store().list(principal.user().tenantId(), null, 100).stream()
                .filter(f -> f.userId().equals(principal.user().id()))
                .toList());
    }
}
