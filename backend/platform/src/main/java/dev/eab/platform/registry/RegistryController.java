package dev.eab.platform.registry;

import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.ApiResponse.ApiError;
import dev.eab.platform.http.Principals;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.registry.RegistryResult.ErrorCode;
import dev.eab.platform.registry.RegistryService.AssetSummary;
import dev.eab.platform.registry.RegistryService.AssetView;
import dev.eab.spec.AssetParser;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.AssetResolver.Status;
import dev.eab.spec.Layers;
import dev.eab.spec.Lint;
import dev.eab.spec.Versions;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 注册中心接口（主提示词 5.2 节）：按身份读写资产、有效定义预览 */
@RestController
@Tag(name = "registry")
public class RegistryController {

    private static final Map<ErrorCode, Integer> STATUS_OF = Map.of(
            ErrorCode.ASSET_INVALID, 422,
            ErrorCode.REGISTRY_WRITE_FORBIDDEN, 403,
            ErrorCode.VERSION_EXISTS, 409,
            ErrorCode.NOT_FOUND, 404,
            ErrorCode.INVALID_TRANSITION, 409,
            ErrorCode.INTEGRITY_VIOLATION, 409,
            ErrorCode.PUBLISH_BLOCKED, 422);

    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private final RegistryService registry;
    private final Principals principals;

    public RegistryController(RegistryService registry, Principals principals) {
        this.registry = registry;
        this.principals = principals;
    }

    private static <T> T unwrap(RegistryResult<T> result) {
        if (result.ok()) return result.value();
        var error = result.error();
        throw new ApiException(
                STATUS_OF.get(error.code()), new ApiError(error.code().name(), error.message(), null, error.issues()));
    }

    /** 路径参数先按资产命名规则校验，不合法直接 400，后面的代码不会拿到非法命名空间 */
    private static void checkPath(String namespace, String name) {
        if (!Layers.isValidNamespace(namespace) || !NAME.matcher(name).matches()) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：命名空间或名称不符合资产命名规则");
        }
    }

    private static Status statusOf(String value) {
        if (value == null) return null;
        try {
            return Status.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：status 只能是 draft、testing、published、retired");
        }
    }

    @GetMapping("/registry/assets")
    @Operation(summary = "列出当前身份可见的资产版本")
    public ApiResponse<List<AssetSummary>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String status) {
        Viewer viewer = principals.require(request).viewer();
        if (layer != null && !List.of("platform", "vendor", "tenant").contains(layer)) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：layer 只能是 platform、vendor、tenant");
        }
        return ApiResponse.success(
                registry.list(viewer, new AssetFilter(kind, namespace, layer, null, statusOf(status))));
    }

    @GetMapping("/registry/assets/{namespace}/{name}")
    @Operation(summary = "读取资产（按身份裁剪，租户看不到标品指令原文）")
    public ApiResponse<AssetView> view(
            HttpServletRequest request,
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestParam(required = false) String version) {
        Viewer viewer = principals.require(request).viewer();
        checkPath(namespace, name);
        return ApiResponse.success(unwrap(registry.view(viewer, namespace, name, version)));
    }

    @PutMapping("/registry/assets")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "提交 YAML，新建资产的草稿版本（只能写本层命名空间）")
    public ApiResponse<AssetSummary> put(
            HttpServletRequest request,
            @RequestParam(required = false) String changeNote,
            @RequestBody(required = false) String yaml) {
        Viewer viewer = principals.require(request).viewer();
        if (yaml == null || yaml.isBlank()) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：缺少资产 YAML");
        return ApiResponse.success(unwrap(registry.put(viewer, yaml, changeNote)));
    }

    public record StatusChange(String status) {}

    @PatchMapping("/registry/assets/{namespace}/{name}/{version}/status")
    @Operation(summary = "变更资产状态，标品层发布时签名")
    public ApiResponse<AssetSummary> setStatus(
            HttpServletRequest request,
            @PathVariable String namespace,
            @PathVariable String name,
            @PathVariable String version,
            @RequestBody StatusChange body) {
        Viewer viewer = principals.require(request).viewer();
        checkPath(namespace, name);
        if (!Versions.isValid(version)) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：版本号不是语义化版本");
        Status status = statusOf(body == null ? null : body.status());
        if (status == null) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：缺少 status");
        return ApiResponse.success(unwrap(registry.setStatus(viewer, namespace, name, version, status)));
    }

    public record Validation(boolean valid, List<AssetParser.AssetIssue> issues, List<Lint.LintIssue> lint) {}

    @PostMapping("/registry/validate")
    @Operation(summary = "校验资产 YAML 并运行静态检查，不写入（引用只在当前身份可见的范围内解析）")
    public ApiResponse<Validation> validate(HttpServletRequest request, @RequestBody(required = false) String yaml) {
        Viewer viewer = principals.require(request).viewer();
        if (yaml == null || yaml.isBlank()) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：缺少资产 YAML");
        var parsed = AssetParser.parseYaml(yaml);
        if (!parsed.ok()) return ApiResponse.success(new Validation(false, parsed.issues(), List.of()));
        var lint = Lint.lint(parsed.asset(), registry.resolverFor(viewer));
        return ApiResponse.success(
                new Validation(lint.stream().noneMatch(i -> i.severity().equals("error")), List.of(), lint));
    }

    public record BatchBody(List<String> assets) {}

    /** 一次最多校验的资产数：一个代码工程最多几份资产，取个宽松上限 */
    static final int MAX_BATCH = 20;

    @PostMapping("/registry/validate-batch")
    @Operation(summary = "批量校验一组资产 YAML，不写入：同一工程的资产（扩展、评测集、技能）相互引用时在叠加层里一起解析")
    public ApiResponse<List<Validation>> validateBatch(HttpServletRequest request, @RequestBody BatchBody body) {
        Viewer viewer = principals.require(request).viewer();
        if (body == null || body.assets() == null || body.assets().isEmpty()) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：缺少 assets 数组");
        }
        if (body.assets().size() > MAX_BATCH) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：一次最多校验 " + MAX_BATCH + " 份资产");
        }
        List<AssetParser.ParseResult> parsed = new ArrayList<>();
        for (String yaml : body.assets()) {
            if (yaml == null || yaml.isBlank()) {
                throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：assets 里出现了空 YAML");
            }
            parsed.add(AssetParser.parseYaml(yaml));
        }
        // 解析成功的资产一起进叠加层：新工程的扩展引用还没发布的评测集与技能，也能在工程内部解析
        List<Asset> overlay = parsed.stream()
                .filter(AssetParser.ParseResult::ok)
                .map(AssetParser.ParseResult::asset)
                .toList();
        var resolver = new OverlayResolver(registry.resolverFor(viewer), overlay);
        List<Validation> results = new ArrayList<>();
        for (AssetParser.ParseResult one : parsed) {
            if (!one.ok()) {
                results.add(new Validation(false, one.issues(), List.of()));
                continue;
            }
            List<Lint.LintIssue> lint = Lint.lint(one.asset(), resolver);
            results.add(new Validation(lint.stream().noneMatch(i -> i.severity().equals("error")), List.of(), lint));
        }
        return ApiResponse.success(results);
    }

    public record EffectiveResponse(Object definition, Object issues) {}

    @GetMapping("/registry/agents/{namespace}/{name}/effective")
    @Operation(summary = "有效定义预览：平台规则 + 标品 Agent + 租户扩展的组装结果（按身份裁剪）")
    public ApiResponse<EffectiveResponse> effective(
            HttpServletRequest request,
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false, defaultValue = "false") String includeDrafts) {
        Viewer viewer = principals.require(request).viewer();
        checkPath(namespace, name);
        if (!List.of("true", "false").contains(includeDrafts)) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：includeDrafts 只能是 true 或 false");
        }
        // 租户只能以自己的身份组装；厂商看不到任何租户的内容，不能带 tenant；平台身份可以指定租户
        if (viewer instanceof Viewer.Tenant t && tenant != null && !tenant.equals(t.tenantId())) {
            throw new ApiException(403, "TENANT_MISMATCH", "身份 " + viewer.format() + " 不能查看其他租户的组装结果");
        }
        if (viewer instanceof Viewer.Vendor && tenant != null) {
            throw new ApiException(403, "TENANT_FORBIDDEN", "厂商身份不能查看租户的组装结果");
        }
        String tenantId = viewer instanceof Viewer.Tenant t ? t.tenantId() : tenant;
        var outcome = EffectiveAssembly.assemble(
                registry,
                viewer,
                new EffectiveAssembly.Request(
                        namespace + "/" + name,
                        tenantId,
                        includeDrafts.equals("true") ? AssetResolver.LIVE : null,
                        null));
        if (outcome instanceof EffectiveAssembly.Outcome.Ok success) {
            return ApiResponse.success(new EffectiveResponse(success.view(), success.issues()));
        }
        throw new ApiException(422, new ApiError("ASSEMBLY_FAILED", "组装失败", outcome.issues(), null));
    }
}
