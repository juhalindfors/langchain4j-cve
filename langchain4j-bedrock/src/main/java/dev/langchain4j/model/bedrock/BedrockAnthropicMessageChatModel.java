package dev.langchain4j.model.bedrock;

import static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT;
import static dev.langchain4j.internal.RetryUtils.withRetryMappingExceptions;
import static dev.langchain4j.internal.Utils.isNotNullOrBlank;
import static dev.langchain4j.internal.Utils.isNullOrEmpty;
import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;
import static dev.langchain4j.model.bedrock.internal.sanitizer.BedrockAnthropicMessageSanitizer.sanitizeMessages;
import static dev.langchain4j.model.chat.request.ToolChoice.REQUIRED;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Objects.nonNull;
import static java.util.stream.Collectors.joining;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.internal.ChatRequestValidationUtils;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.bedrock.internal.AbstractBedrockChatModel;
import dev.langchain4j.model.bedrock.internal.Json;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.Response;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

/**
 * @deprecated please use {@link BedrockChatModel} instead
 */
@Deprecated(forRemoval = true, since = "1.0.0-beta2")
public class BedrockAnthropicMessageChatModel
        extends AbstractBedrockChatModel<BedrockAnthropicMessageChatModelResponse> {

    private static final Logger log = LoggerFactory.getLogger(BedrockAnthropicMessageChatModel.class);

    private static final String DEFAULT_ANTHROPIC_VERSION = "bedrock-2023-05-31";
    private static final int DEFAULT_TOP_K = 250;
    private static final String DEFAULT_MODEL = Types.AnthropicClaude3SonnetV1.getValue();
    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper()
            .enable(INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final int topK;

    private final String anthropicVersion;

    private final String model;

    private final ObjectMapper objectMapper;

    @Override
    protected String getModelId() {
        return model;
    }

    @Override
    protected Map<String, Object> getRequestParameters(String prompt) {
        final Map<String, Object> parameters = new HashMap<>(9);
        parameters.put("max_tokens", getMaxTokens());
        parameters.put("temperature", getTemperature());
        parameters.put("top_k", topK);
        parameters.put("top_p", getTopP());
        parameters.put("stop_sequences", getStopSequences());
        parameters.put("anthropic_version", anthropicVersion);
        return parameters;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        ChatRequestParameters parameters = chatRequest.parameters();
        ChatRequestValidationUtils.validateParameters(parameters);
        ChatRequestValidationUtils.validate(parameters.responseFormat());

        Response<AiMessage> response;
        List<ToolSpecification> toolSpecifications = parameters.toolSpecifications();
        if (isNullOrEmpty(toolSpecifications)) {
            response = generate(chatRequest.messages());
        } else {
            if (parameters.toolChoice() == REQUIRED) {
                if (toolSpecifications.size() != 1) {
                    throw new UnsupportedFeatureException(String.format(
                            "%s.%s is currently supported only when there is a single tool",
                            ToolChoice.class.getSimpleName(), REQUIRED.name()));
                }
                response = generate(chatRequest.messages(), toolSpecifications.get(0));
            } else {
                response = generate(chatRequest.messages(), toolSpecifications);
            }
        }

        return ChatResponse.builder()
                .aiMessage(response.content())
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(response.tokenUsage())
                        .finishReason(response.finishReason())
                        .build())
                .build();
    }

    @Override
    protected Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, emptyList());
    }

    private Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        return generate(messages, toolSpecification, singletonList(toolSpecification));
    }

    private Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        return generate(messages, null, toolSpecifications);
    }

    private Response<AiMessage> generate(
            List<ChatMessage> messages,
            ToolSpecification toolChoiceSpecification,
            List<ToolSpecification> toolSpecifications) {
        List<ChatMessage> sanitizedMessages = sanitizeMessages(messages);
        final String system = getAnthropicSystemPrompt(messages);

        List<BedrockAnthropicMessage> formattedMessages = getAnthropicMessages(sanitizedMessages);

        Map<String, Object> parameters = getRequestParameters(null);
        parameters.put("messages", formattedMessages);
        parameters.put("system", system);

        if (nonNull(toolChoiceSpecification)) {
            validateModelIdWithToolsSupport(this.model);
            parameters.put("tool_choice", toAnthropicToolChoice(toolChoiceSpecification));
        }

        if (!toolSpecifications.isEmpty()) {
            validateModelIdWithToolsSupport(this.model);
            parameters.put("tools", toAnthropicToolSpecifications(toolSpecifications));
        }

        final String body = Json.toJson(parameters);

        InvokeModelRequest invokeModelRequest = InvokeModelRequest.builder()
                .modelId(getModelId())
                .body(SdkBytes.fromString(body, Charset.defaultCharset()))
                .build();

        ChatRequest listenerRequest = createListenerRequest(invokeModelRequest, sanitizedMessages, toolSpecifications);
        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        ChatModelRequestContext requestContext = new ChatModelRequestContext(listenerRequest, provider(), attributes);
        listeners.forEach(listener -> {
            try {
                listener.onRequest(requestContext);
            } catch (Exception e) {
                log.warn("Exception while calling model listener", e);
            }
        });

        try {
            InvokeModelResponse invokeModelResponse =
                    withRetryMappingExceptions(() -> getClient().invokeModel(invokeModelRequest), getMaxRetries());
            String response = invokeModelResponse.body().asUtf8String();
            BedrockAnthropicMessageChatModelResponse result = Json.fromJson(response, getResponseClassType());

            Response<AiMessage> responseMessage =
                    Response.from(aiMessageFrom(result), result.getTokenUsage(), result.getFinishReason());

            ChatResponse listenerResponse = createListenerResponse(result.getId(), result.getModel(), responseMessage);
            ChatModelResponseContext responseContext =
                    new ChatModelResponseContext(listenerResponse, listenerRequest, provider(), attributes);

            listeners.forEach(listener -> {
                try {
                    listener.onResponse(responseContext);
                } catch (Exception e) {
                    log.warn("Exception while calling model listener", e);
                }
            });

            return responseMessage;
        } catch (RuntimeException e) {
            listenerErrorResponse(e, listenerRequest, provider(), attributes);
            throw e;
        }
    }

    private Object toAnthropicToolChoice(ToolSpecification toolChoiceSpecification) {
        ObjectNode toolChoiceNode = new ObjectMapper().createObjectNode();
        toolChoiceNode.put("type", "tool");
        toolChoiceNode.put("name", toolChoiceSpecification.name());
        return toolChoiceNode;
    }

    static void validateModelIdWithToolsSupport(String modelId) {
        if (Objects.isNull(modelId)) {
            throw new IllegalArgumentException("Model ID is required");
        }

        List<String> anthropicModelIdSplit = asList(modelId.split("-"));

        if (anthropicModelIdSplit.size() < 2) {
            throw new IllegalArgumentException("Tools are currently not supported by this model");
        }

        String anthropicModelMajorVersion = anthropicModelIdSplit.get(1);
        if (anthropicModelMajorVersion.contains(":")) {
            anthropicModelMajorVersion = anthropicModelMajorVersion.split(":")[0];
        }

        if (!anthropicModelMajorVersion.matches("[0-9]")) {
            anthropicModelMajorVersion = anthropicModelMajorVersion.replaceAll("[^0-9]", "");
        }

        if (anthropicModelMajorVersion.isEmpty() || Integer.parseInt(anthropicModelMajorVersion) < 3) {
            throw new IllegalArgumentException("Tools are currently not supported by this model");
        }
    }

    private AiMessage aiMessageFrom(BedrockAnthropicMessageChatModelResponse result) {
        List<BedrockAnthropicContent> toolUseRequests = result.getContent().stream()
                .filter(content -> content.getType().equals("tool_use"))
                .collect(Collectors.toList());

        if (toolUseRequests.isEmpty()) {
            return AiMessage.from(result.getOutputText());
        }

        List<ToolExecutionRequest> toolExecutionRequests = toolUseRequests.stream()
                .map(toolUseRequest -> ToolExecutionRequest.builder()
                        .id(toolUseRequest.getId())
                        .name(toolUseRequest.getName())
                        .arguments(toJson(toolUseRequest.getInput()))
                        .build())
                .collect(Collectors.toList());

        return AiMessage.from(toolExecutionRequests);
    }

    private String toJson(Object input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private Object toAnthropicToolSpecifications(List<ToolSpecification> toolSpecifications) {
        return toolSpecifications.stream()
                .map(toolSpecification -> BedrockAntropicToolSpecification.builder()
                        .name(toolSpecification.name())
                        .description(toolSpecification.description())
                        .input_schema(toAnthropicToolParameters(toolSpecification))
                        .build())
                .collect(Collectors.toList());
    }

    private Object toAnthropicToolParameters(ToolSpecification toolSpecification) {

        if (toolSpecification.parameters() != null) {
            return JsonSchemaElementUtils.toMap(toolSpecification.parameters());
        } else {
            ObjectNode inputSchemaNode = new ObjectMapper().createObjectNode();
            inputSchemaNode.put("type", "object");
            inputSchemaNode.set("properties", new ObjectMapper().createObjectNode());
            inputSchemaNode.set("required", new ObjectMapper().createArrayNode());

            return inputSchemaNode;
        }
    }

    private ObjectNode toAnthropicParameterProperties(Map<String, Map<String, Object>> properties) {
        ObjectNode propertiesNode = new ObjectMapper().createObjectNode();
        properties.forEach((propertyName, propertyMetadata) ->
                propertiesNode.set(propertyName, toAnthropicParameter(propertyMetadata)));
        return propertiesNode;
    }

    private JsonNode toAnthropicParameter(Map<String, Object> propertyMetadata) {
        ObjectNode propertyNode = new ObjectMapper().createObjectNode();

        String propertyType = propertyMetadata.get("type").toString();
        String propertyDescription = (String) propertyMetadata.get("description");
        if (propertyDescription != null) {
            propertyNode.put("description", propertyDescription);
        }
        propertyNode.put("type", propertyType);

        if ("object".equals(propertyType)) {
            ObjectNode childPropertiesNode = new ObjectMapper().createObjectNode();
            childPropertiesNode.setAll(toAnthropicParameterProperties(
                    (Map<String, Map<String, Object>>) propertyMetadata.get("properties")));
            propertyNode.set("properties", childPropertiesNode);
            if (Objects.nonNull(propertyMetadata.get("required"))) {
                ArrayNode requiredNode = new ObjectMapper().createArrayNode();
                ((List<String>) propertyMetadata.get("required")).forEach(requiredNode::add);
                propertyNode.set("required", requiredNode);
            }
        }

        if ("array".equals(propertyType)) {
            propertyNode.set("items", toAnthropicParameter((Map<String, Object>) propertyMetadata.get("items")));
        }

        if (propertyMetadata.get("enum") != null) {
            ArrayNode enumValues = new ObjectMapper().createArrayNode();
            ((List<String>) propertyMetadata.get("enum")).forEach(enumValues::add);
            propertyNode.set("enum", enumValues);
        }

        return propertyNode;
    }

    private List<BedrockAnthropicMessage> getAnthropicMessages(List<ChatMessage> messages) {
        List<ChatMessage> noSystemMessages = messages.stream()
                .filter(message -> message.type() != ChatMessageType.SYSTEM)
                .collect(Collectors.toList());

        List<BedrockAnthropicMessage> anthropicMessages = new ArrayList<>();
        List<BedrockAnthropicContent> toolContents = new ArrayList<>();

        for (ChatMessage message : noSystemMessages) {
            List<BedrockAnthropicContent> contents = getAnthropicContent(message);

            if (message instanceof ToolExecutionResultMessage) {
                toolContents.addAll(getAnthropicContent(message));
                continue;
            } else {
                if (!toolContents.isEmpty()) {
                    anthropicMessages.add(new BedrockAnthropicMessage("user", toolContents));
                    toolContents = new ArrayList<>();
                }

                if (message instanceof UserMessage) {
                    anthropicMessages.add(new BedrockAnthropicMessage("user", contents));
                }

                if (message instanceof AiMessage) {
                    anthropicMessages.add(new BedrockAnthropicMessage("assistant", contents));
                }
            }
        }

        if (!toolContents.isEmpty()) {
            anthropicMessages.add(new BedrockAnthropicMessage("user", toolContents));
        }

        return anthropicMessages;
    }

    private List<BedrockAnthropicContent> getAnthropicContent(ChatMessage message) {
        if (message instanceof AiMessage) {
            AiMessage aiMessage = (AiMessage) message;
            List<BedrockAnthropicContent> contents = new ArrayList<>();

            if (isNotNullOrBlank(aiMessage.text())) {
                contents.add(new BedrockAnthropicContent("text", aiMessage.text()));
            }

            if (aiMessage.hasToolExecutionRequests()) {
                List<BedrockAnthropicContent> toolUseRequests = aiMessage.toolExecutionRequests().stream()
                        .map(toolExecutionRequest -> BedrockAnthropicContent.builder()
                                .id(toolExecutionRequest.id())
                                .type("tool_use")
                                .name(toolExecutionRequest.name())
                                .input(fromJson(toolExecutionRequest.arguments(), Map.class))
                                .build())
                        .collect(Collectors.toList());

                contents.addAll(toolUseRequests);
            }

            return contents;
        } else if (message instanceof UserMessage) {
            return ((UserMessage) message)
                    .contents().stream()
                            .map(BedrockAnthropicMessageChatModel::mapContentToAnthropic)
                            .collect(Collectors.toList());
        } else if (message instanceof ToolExecutionResultMessage) {
            ToolExecutionResultMessage toolExecutionResultMessage = (ToolExecutionResultMessage) message;
            return Collections.singletonList(BedrockAnthropicContent.builder()
                    .type("tool_result")
                    .tool_use_id(toolExecutionResultMessage.id())
                    .content(toolExecutionResultMessage.text())
                    .build());
        } else {
            throw new IllegalArgumentException("Unknown message type: " + message.type());
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static BedrockAnthropicContent mapContentToAnthropic(Content content) {
        if (content instanceof TextContent) {
            return new BedrockAnthropicContent("text", ((TextContent) content).text());
        } else if (content instanceof ImageContent) {
            ImageContent imageContent = (ImageContent) content;
            if (imageContent.image().url() != null) {
                throw new UnsupportedFeatureException(
                        "Anthropic does not support images as URLs, only as Base64-encoded strings");
            }
            BedrockAnthropicImageSource imageSource = new BedrockAnthropicImageSource(
                    "base64",
                    ensureNotBlank(imageContent.image().mimeType(), "mimeType"),
                    ensureNotBlank(imageContent.image().base64Data(), "base64Data"));
            return new BedrockAnthropicContent("image", imageSource);
        } else {
            throw new IllegalArgumentException("Unknown content type: " + content);
        }
    }

    private String getAnthropicSystemPrompt(List<ChatMessage> messages) {
        return messages.stream()
                .filter(message -> message.type() == ChatMessageType.SYSTEM)
                .map(message -> ((SystemMessage) message).text())
                .collect(joining("\n"));
    }

    private String getAnthropicRole(ChatMessage message) {
        return message.type() == ChatMessageType.AI ? "assistant" : "user";
    }

    @Override
    public Class<BedrockAnthropicMessageChatModelResponse> getResponseClassType() {
        return BedrockAnthropicMessageChatModelResponse.class;
    }

    /**
     * Bedrock Anthropic model ids.
     * See <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/model-ids.html">this</a> for more details.
     */
    public enum Types {
        AnthropicClaudeInstantV1("anthropic.claude-instant-v1"),
        AnthropicClaudeV2("anthropic.claude-v2"),
        AnthropicClaudeV2_1("anthropic.claude-v2:1"),
        AnthropicClaude3SonnetV1("anthropic.claude-3-sonnet-20240229-v1:0"),
        AnthropicClaude3_5SonnetV1("anthropic.claude-3-5-sonnet-20240620-v1:0"),
        AnthropicClaude3HaikuV1("anthropic.claude-3-haiku-20240307-v1:0");

        private final String value;

        Types(String modelID) {
            this.value = modelID;
        }

        public String getValue() {
            return value;
        }
    }

    @Override
    public int getTopK() {
        return topK;
    }

    @Override
    public String getAnthropicVersion() {
        return anthropicVersion;
    }

    public String getModel() {
        return model;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    protected BedrockAnthropicMessageChatModel(BedrockAnthropicMessageChatModelBuilder<?, ?> builder) {
        super(builder);
        if (builder.isTopKSet) {
            this.topK = builder.topK;
        } else {
            this.topK = DEFAULT_TOP_K;
        }

        if (builder.isAnthropicVersionSet) {
            this.anthropicVersion = builder.anthropicVersion;
        } else {
            this.anthropicVersion = DEFAULT_ANTHROPIC_VERSION;
        }

        if (builder.isModelSet) {
            this.model = builder.model;
        } else {
            this.model = DEFAULT_MODEL;
        }

        if (builder.isObjectMapperSet) {
            this.objectMapper = builder.objectMapper;
        } else {
            this.objectMapper = DEFAULT_OBJECT_MAPPER;
        }
    }

    public static BedrockAnthropicMessageChatModelBuilder<?, ?> builder() {
        return new BedrockAnthropicMessageChatModelBuilderImpl();
    }

    public abstract static class BedrockAnthropicMessageChatModelBuilder<
                    C extends BedrockAnthropicMessageChatModel, B extends BedrockAnthropicMessageChatModelBuilder<C, B>>
            extends AbstractBedrockChatModel.AbstractBedrockChatModelBuilder<
                    BedrockAnthropicMessageChatModelResponse, C, B> {
        private boolean isTopKSet;
        private int topK;
        private boolean isAnthropicVersionSet;
        private String anthropicVersion;
        private boolean isModelSet;
        private String model;
        private boolean isObjectMapperSet;
        private ObjectMapper objectMapper;

        @Override
        public B topK(int topK) {
            this.topK = topK;
            this.isTopKSet = true;
            return self();
        }

        @Override
        public B anthropicVersion(String anthropicVersion) {
            this.anthropicVersion = anthropicVersion;
            this.isAnthropicVersionSet = true;
            return self();
        }

        public B model(String model) {
            this.model = model;
            this.isModelSet = true;
            return self();
        }

        public B objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            this.isObjectMapperSet = true;
            return self();
        }

        protected abstract B self();

        public abstract C build();

        @Override
        public String toString() {
            return "BedrockAnthropicMessageChatModel.BedrockAnthropicMessageChatModelBuilder(super=" + super.toString()
                    + ", topK$value=" + this.topK + ", anthropicVersion$value=" + this.anthropicVersion
                    + ", model$value=" + this.model + ", objectMapper$value=" + this.objectMapper + ")";
        }
    }

    private static final class BedrockAnthropicMessageChatModelBuilderImpl
            extends BedrockAnthropicMessageChatModelBuilder<
                    BedrockAnthropicMessageChatModel, BedrockAnthropicMessageChatModelBuilderImpl> {
        protected BedrockAnthropicMessageChatModelBuilderImpl self() {
            return this;
        }

        public BedrockAnthropicMessageChatModel build() {
            return new BedrockAnthropicMessageChatModel(this);
        }
    }
}
