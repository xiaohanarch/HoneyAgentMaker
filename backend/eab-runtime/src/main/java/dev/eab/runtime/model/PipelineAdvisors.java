package dev.eab.runtime.model;

import dev.eab.runtime.model.ContentFilters.ConfidentialText;
import dev.eab.tracing.SpanRecord.SpanKind;
import dev.eab.tracing.Tracer.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 模型处理管道里调用模型前后的三个环节（主提示词 8.2 节），实现为 Spring AI 的 {@link CallAdvisor}，按顺序包在模型调用外面：
 *
 * <pre>
 * 输出过滤 → 脱敏与还原 → 输入过滤 → 模型
 * </pre>
 *
 * 请求方向先脱敏再做输入过滤；响应方向先还原再做输出过滤。每个环节在追踪里是模型 span 下的一个 pipeline span。
 * 环节之间通过 ChatClientRequest 和 ChatClientResponse 的 context 传递状态，键名见下面的常量。
 */
public final class PipelineAdvisors {

    private PipelineAdvisors() {}

    /** 模型 span，各环节的 span 挂在它下面 */
    public static final String SPAN = "eab.span";

    public static final String MASKER = "eab.masker";
    /** 保密片段（标品指令、平台规则原文），List&lt;ConfidentialText&gt; */
    public static final String CONFIDENTIAL = "eab.confidential";
    /** 本轮用户话术（原文），输入过滤用它判断 */
    public static final String UTTERANCE = "eab.utterance";

    /** 响应 context：输入被拦截时为 true，此时没有调用模型 */
    public static final String BLOCKED = "eab.inputBlocked";
    /** 响应 context：回复里被遮蔽的保密片段编号 */
    public static final String LEAKED = "eab.leakedSegments";

    static Span stage(ChatClientRequest request, String name) {
        Span parent = (Span) request.context().get(SPAN);
        return parent.child(name, SpanKind.pipeline, name, Map.of());
    }

    static ChatResponse withMessage(ChatResponse response, AssistantMessage message) {
        Generation generation = response.getResult();
        Generation replaced =
                generation == null ? new Generation(message) : new Generation(message, generation.getMetadata());
        ChatResponseMetadata metadata = response.getMetadata();
        return new ChatResponse(List.of(replaced), metadata);
    }

    static AssistantMessage copyWith(AssistantMessage message, String text, List<AssistantMessage.ToolCall> calls) {
        AssistantMessage.Builder<?> builder =
                AssistantMessage.builder().content(text).properties(message.getMetadata());
        if (!calls.isEmpty()) builder.toolCalls(calls);
        return builder.build();
    }

    /** 敏感数据脱敏：请求里的消息替换成占位符，响应里的文本和工具参数还原 */
    public static final class Masking implements CallAdvisor {
        @Override
        public String getName() {
            return "pipeline.masking";
        }

        @Override
        public int getOrder() {
            return 200;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            Masker masker = (Masker) request.context().get(MASKER);
            Span span = stage(request, getName());
            int[] count = {0};
            List<Message> masked = new ArrayList<>();
            for (Message m : request.prompt().getInstructions()) masked.add(mask(m, masker, count));
            span.set("eab.masking.count", count[0]).end();
            ChatClientRequest maskedRequest = request.mutate()
                    .prompt(request.prompt().mutate().messages(masked).build())
                    .build();
            ChatClientResponse response = chain.nextCall(maskedRequest);
            ChatResponse chat = response.chatResponse();
            if (chat == null || chat.getResult() == null) return response;
            AssistantMessage output = chat.getResult().getOutput();
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            for (var call : output.getToolCalls()) {
                calls.add(new AssistantMessage.ToolCall(
                        call.id(), call.type(), call.name(), masker.unmask(call.arguments())));
            }
            AssistantMessage restored = copyWith(output, masker.unmask(output.getText()), calls);
            return response.mutate().chatResponse(withMessage(chat, restored)).build();
        }

        private static Message mask(Message message, Masker masker, int[] count) {
            return switch (message) {
                case SystemMessage s -> {
                    var r = masker.mask(s.getText());
                    count[0] += r.count();
                    yield new SystemMessage(r.text());
                }
                case UserMessage u -> {
                    var r = masker.mask(u.getText());
                    count[0] += r.count();
                    yield new UserMessage(r.text());
                }
                case AssistantMessage a -> {
                    var r = masker.mask(a.getText() == null ? "" : a.getText());
                    count[0] += r.count();
                    List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                    for (var call : a.getToolCalls()) {
                        var args = masker.mask(call.arguments());
                        count[0] += args.count();
                        calls.add(new AssistantMessage.ToolCall(call.id(), call.type(), call.name(), args.text()));
                    }
                    yield copyWith(a, r.text(), calls);
                }
                case ToolResponseMessage t -> {
                    List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                    for (var r : t.getResponses()) {
                        var data = masker.mask(r.responseData());
                        count[0] += data.count();
                        responses.add(new ToolResponseMessage.ToolResponse(r.id(), r.name(), data.text()));
                    }
                    yield ToolResponseMessage.builder().responses(responses).build();
                }
                default -> message;
            };
        }
    }

    /** 输入过滤：本轮话术命中提示注入特征时拦截，不调用模型 */
    public static final class InputFilter implements CallAdvisor {
        @Override
        public String getName() {
            return "pipeline.input-filter";
        }

        @Override
        public int getOrder() {
            return 300;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            Span span = stage(request, getName());
            var verdict = ContentFilters.checkInput((String) request.context().get(UTTERANCE));
            span.set("eab.filter.blocked", verdict.blocked())
                    .set("eab.filter.rule", verdict.ruleId())
                    .end();
            if (!verdict.blocked()) return chain.nextCall(request);
            AssistantMessage reply = AssistantMessage.builder()
                    .content(ContentFilters.INPUT_BLOCKED_REPLY)
                    .build();
            return ChatClientResponse.builder()
                    .chatResponse(new ChatResponse(List.of(new Generation(reply))))
                    .context(request.context())
                    .context(BLOCKED, true)
                    .build();
        }
    }

    /** 输出过滤：去掉片段标记，遮蔽复述的保密片段并报告片段编号 */
    public static final class OutputFilter implements CallAdvisor {
        @Override
        public String getName() {
            return "pipeline.output-filter";
        }

        @Override
        public int getOrder() {
            return 100;
        }

        @Override
        @SuppressWarnings("unchecked")
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            ChatClientResponse response = chain.nextCall(request);
            Span span = stage(request, getName());
            ChatResponse chat = response.chatResponse();
            if (chat == null || chat.getResult() == null) {
                span.end();
                return response;
            }
            List<ConfidentialText> confidential =
                    (List<ConfidentialText>) request.context().getOrDefault(CONFIDENTIAL, List.of());
            AssistantMessage output = chat.getResult().getOutput();
            var filtered = ContentFilters.filterOutput(output.getText(), confidential);
            span.set("eab.filter.leaked_segments", String.join(",", filtered.leakedSegments()))
                    .set("eab.filter.markers_removed", filtered.markersRemoved())
                    .end();
            return response.mutate()
                    .chatResponse(withMessage(chat, copyWith(output, filtered.text(), output.getToolCalls())))
                    .context(LEAKED, filtered.leakedSegments())
                    .build();
        }
    }
}
