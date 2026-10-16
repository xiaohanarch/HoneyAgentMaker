package dev.eab.platform.skills;

import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.DemoMode;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.extend.ExtendController;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.RateLimiter;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.studio.StudioService;
import dev.eab.platform.studio.StudioService.DraftView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 技能编辑器的接口（docs/studio-api.md 的 P6 一节） */
@RestController
@Tag(name = "studio-skills")
public class SkillController {

    static final String NAME = "^[a-z][a-z0-9-]{2,40}$";

    /** P6 技能编辑器的装配 */
    @Configuration(proxyBeanMethods = false)
    public static class SkillsConfig {
        @Bean
        public SkillStudio skillStudio(PlatformServices services, RuntimeService runtime, StudioService studio) {
            return new SkillStudio(services, runtime, studio);
        }
    }

    private final PlatformServices services;
    private final SkillStudio skills;
    private final boolean demoMode;
    private final RateLimiter limiter;

    public SkillController(PlatformServices services, SkillStudio skills, DemoMode demoMode) {
        this.services = services;
        this.skills = skills;
        this.demoMode = demoMode.enabled();
        this.limiter = new RateLimiter(60, 60_000, services.clock());
    }

    private DemoUser developer(HttpServletRequest request) {
        return ExtendController.developer(services, request);
    }

    public record CreateBody(String name, String displayName, String from) {}

    @PostMapping("/studio/skills")
    @Operation(summary = "新建技能草稿，可以从已有技能复制")
    public ApiResponse<DraftView> create(HttpServletRequest request, @RequestBody CreateBody body) {
        DemoUser user = developer(request);
        if (body == null || body.name() == null || !body.name().matches(NAME)) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：name 用小写字母、数字和连字符，3 到 41 个字符");
        }
        if (body.from() != null && !body.from().isBlank() && !body.from().matches("^[a-z0-9.-]+/[a-z0-9-]+$")) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：from 写作技能的 namespace/name");
        }
        limiter.check("skills:" + user.id());
        return ApiResponse.success(
                ExtendController.call(() -> skills.create(user, body.name(), body.displayName(), body.from())));
    }

    public record StepsBody(List<Map<String, Object>> steps) {}

    @PutMapping("/studio/drafts/{id}/skill-steps")
    @Operation(summary = "替换技能草稿的步骤列表")
    public ApiResponse<DraftView> steps(
            HttpServletRequest request, @PathVariable String id, @RequestBody StepsBody body) {
        DemoUser user = developer(request);
        if (body == null || body.steps() == null || body.steps().size() > 50) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：steps 必须是数组，最多 50 步");
        }
        return ApiResponse.success(ExtendController.call(() -> skills.putSteps(user, id, body.steps())));
    }

    public record TestBody(Map<String, Object> input, String asUser) {}

    @PostMapping("/studio/drafts/{id}/skill-test")
    @Operation(summary = "单独测试技能：填入参，返回结果和每一步的输入输出；遇到确认点时停下，不写入")
    public ApiResponse<SkillStudio.SkillTestView> test(
            HttpServletRequest request, @PathVariable String id, @RequestBody TestBody body) {
        DemoUser user = developer(request);
        if (!demoMode) throw new ApiException(404, "NOT_FOUND", "演示模式未开启，私有环境测试不可用");
        limiter.check("skills:" + user.id());
        Map<String, Object> input = body == null || body.input() == null ? Map.of() : body.input();
        String asUser = body == null ? null : body.asUser();
        return ApiResponse.success(ExtendController.call(() -> skills.test(user, id, input, asUser)));
    }

    @PostMapping("/studio/drafts/{id}/skill-publish")
    @Operation(summary = "发布技能")
    public ApiResponse<SkillStudio.PublishResult> publish(HttpServletRequest request, @PathVariable String id) {
        DemoUser user = developer(request);
        return ApiResponse.success(ExtendController.call(() -> skills.publish(user, id)));
    }
}
