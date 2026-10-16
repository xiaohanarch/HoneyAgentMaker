package dev.eab.assembler;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import dev.eab.spec.model.AgentSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钩子与确认点条件、策略规则、技能步骤里的表达式都用 CEL（ADR 0003），实现是 CEL 的官方 Java 版。
 *
 * <p>检查环境按事件准备：onResult 的 result 按标品 Agent 声明的 output.schema 注册成带字段的结构类型，
 * 引用不存在的字段会在组装阶段被发现，而不是运行时静默变成 false（故事 F）。求值环境里所有变量都是 dyn，
 * 值来自 JSON：整数是 long，小数是 double，数字比较允许混用（例如 input.amount > 100000）。
 */
public final class Conditions {

    private Conditions() {}

    static final CelOptions OPTIONS =
            CelOptions.current().enableHeterogeneousNumericComparisons(true).build();

    private static final CelType DYN_MAP = MapType.create(SimpleType.STRING, SimpleType.DYN);

    /** int 也按 double 处理，与 TS 版一致：运行时数字来自 JSON，比较运算照常可用 */
    private static final Map<String, CelType> OUTPUT_TYPES = Map.of(
            "string", SimpleType.STRING,
            "int", SimpleType.DOUBLE,
            "double", SimpleType.DOUBLE,
            "bool", SimpleType.BOOL,
            "list", dev.cel.common.types.ListType.create(SimpleType.DYN),
            "map", DYN_MAP);

    /** 一个检查环境：变量名到类型，可选的结构类型 */
    public record Environment(Map<String, CelType> variables, StructType struct) {}

    public static Environment hookEnvironment(String event, AgentSpec.Output output) {
        if (event.equals("onResult")) {
            if (output == null) return new Environment(Map.of("result", DYN_MAP), null);
            Map<String, CelType> fields = new LinkedHashMap<>();
            output.schema().forEach((name, field) -> fields.put(name, OUTPUT_TYPES.get(field.type())));
            StructType struct = StructType.create(
                    "eab.AgentResult",
                    ImmutableSet.copyOf(fields.keySet()),
                    name -> Optional.ofNullable(fields.get(name)));
            return new Environment(Map.of("result", struct), struct);
        }
        if (event.equals("onFailure")) return new Environment(Map.of("error", DYN_MAP), null);
        return new Environment(Map.of("tool", SimpleType.STRING, "input", DYN_MAP, "output", DYN_MAP), null);
    }

    /** 确认点条件可以引用工具入参 input 和调用者 user */
    public static Environment approvalEnvironment() {
        return new Environment(Map.of("input", DYN_MAP, "user", DYN_MAP), null);
    }

    /** 语义规则的检查条件只有一个变量 output，即工具的返回数据 */
    public static Environment ruleCheckEnvironment() {
        return new Environment(Map.of("output", DYN_MAP), null);
    }

    /** 策略规则只有一个变量 input */
    public static Environment policyEnvironment() {
        return new Environment(Map.of("input", DYN_MAP), null);
    }

    public sealed interface CheckOutcome {
        record Valid() implements CheckOutcome {}

        record Invalid(String reason) implements CheckOutcome {}

        default boolean valid() {
            return this instanceof Valid;
        }

        default String reason() {
            return this instanceof Invalid invalid ? invalid.reason() : null;
        }
    }

    private static CelCompiler compiler(Environment env) {
        CelCompilerBuilder builder = CelCompilerFactory.standardCelCompilerBuilder()
                .setOptions(OPTIONS)
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS);
        if (env.struct() != null) builder.setTypeProvider(new SingleStructProvider(env.struct()));
        env.variables().forEach(builder::addVar);
        return builder.build();
    }

    /** 语法与类型检查，结果类型必须是 bool（或 dyn） */
    public static CheckOutcome check(Environment env, String expression) {
        try {
            CelValidationResult result = compiler(env).compile(expression);
            if (result.hasError())
                return new CheckOutcome.Invalid(
                        firstLine(result.getErrors().getFirst().getMessage()));
            CelType type = result.getAst().getResultType();
            if (type.equals(SimpleType.BOOL) || type.equals(SimpleType.DYN)) return new CheckOutcome.Valid();
            return new CheckOutcome.Invalid("条件的结果类型是 " + type.name() + "，应为 bool");
        } catch (CelValidationException | RuntimeException e) {
            return new CheckOutcome.Invalid(firstLine(String.valueOf(e.getMessage())));
        }
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse(text).trim();
    }

    // ── 求值 ───────────────────────────────────────────────────────────────

    private static final CelRuntime RUNTIME =
            CelRuntimeFactory.standardCelRuntimeBuilder().setOptions(OPTIONS).build();

    /** 按“变量名 + 表达式”缓存的编译结果，变量全部按 dyn 声明 */
    private static final Map<String, CelAbstractSyntaxTree> CACHE = new ConcurrentHashMap<>();

    public static final class EvaluationException extends RuntimeException {
        public EvaluationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 求值表达式，变量值是 JSON 节点或普通 Java 值；返回值转换回普通 Java 值（long、double、String、List、Map）。
     * 编译结果按“变量名 + 表达式”缓存。
     */
    public static Object evaluate(String expression, Map<String, ?> variables) {
        List<String> names = new ArrayList<>(variables.keySet());
        names.sort(String::compareTo);
        String key = String.join(",", names) + "|" + expression;
        try {
            CelAbstractSyntaxTree ast = CACHE.computeIfAbsent(key, k -> compileDyn(names, expression));
            Map<String, Object> values = new LinkedHashMap<>();
            variables.forEach((name, value) -> values.put(name, CelValues.toCel(value)));
            return CelValues.fromCel(RUNTIME.createProgram(ast).eval(values));
        } catch (CelEvaluationException e) {
            throw new EvaluationException(firstLine(e.getMessage()), e);
        } catch (IllegalArgumentException e) {
            throw new EvaluationException(firstLine(String.valueOf(e.getMessage())), e);
        }
    }

    private static CelAbstractSyntaxTree compileDyn(List<String> names, String expression) {
        CelCompilerBuilder builder = CelCompilerFactory.standardCelCompilerBuilder()
                .setOptions(OPTIONS)
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS);
        for (String name : names) builder.addVar(name, SimpleType.DYN);
        try {
            return builder.build().compile(expression).getAst();
        } catch (CelValidationException e) {
            throw new IllegalArgumentException("表达式无效：" + firstLine(e.getMessage()), e);
        }
    }

    /** 求值布尔条件；结果不是布尔值时报错 */
    public static boolean test(String expression, Map<String, ?> variables) {
        Object value = evaluate(expression, variables);
        if (value instanceof Boolean b) return b;
        throw new EvaluationException("条件的结果不是布尔值：" + expression, null);
    }

    private record SingleStructProvider(StructType struct) implements CelTypeProvider {
        @Override
        public ImmutableCollection<CelType> types() {
            return ImmutableList.of(struct);
        }

        @Override
        public Optional<CelType> findType(String name) {
            return name.equals(struct.name()) ? Optional.of(struct) : Optional.empty();
        }
    }
}
