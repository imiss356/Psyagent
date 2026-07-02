package com.mindbridge.agent.service.knowledge.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.knowledge.KnowledgeService;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class RagEvaluationService {

    private final KnowledgeService knowledgeService;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();

    public RagEvaluationService(
            KnowledgeService knowledgeService,
            AiClient aiClient,
            ObjectMapper objectMapper
    ) {
        this.knowledgeService = knowledgeService;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public RagEvalReport evaluate(String datasetLocation, int topK) {
        List<RagEvalCase> cases = loadDataset(datasetLocation);
        List<RagEndToEndCaseResult> results = cases.stream()
                .map(testCase -> buildRagasCase(testCase, topK))
                .toList();
        return new RagEvalReport(
                Instant.now(),
                datasetLocation,
                topK,
                results.size(),
                results);
    }

    public void writeReport(RagEvalReport report, String outputPath) {
        if (outputPath == null || outputPath.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(outputPath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            objectMapper.writeValue(path.toFile(), report);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to write RAGAS input report: " + outputPath, exception);
        }
    }

    public String formatSummary(RagEvalReport report) {
        long casesWithContexts = report.cases().stream()
                .filter(testCase -> !testCase.retrievedContexts().isEmpty())
                .count();
        return """
                RAGAS input report completed.
                dataset=%s
                cases=%d
                topK=%d
                casesWithRetrievedContexts=%d
                output contains no Java custom metrics; run eval/run-ragas-eval.py for RAGAS scores.
                """.formatted(
                report.dataset(),
                report.totalCases(),
                report.topK(),
                casesWithContexts);
    }

    private RagEndToEndCaseResult buildRagasCase(RagEvalCase testCase, int topK) {
        List<SearchResult> retrieved = knowledgeService.retrieve(testCase.question(), topK);
        List<String> retrievedContexts = retrieved.stream()
                .map(SearchResult::content)
                .toList();
        List<String> retrievedSources = retrieved.stream()
                .map(SearchResult::source)
                .distinct()
                .toList();
        String answer = generateAnswer(testCase.question(), retrievedContexts);
        return new RagEndToEndCaseResult(
                testCase.id(),
                testCase.question(),
                normalizeLabel(testCase.expectedIntent()),
                "",
                normalizeLabel(testCase.expectedRiskLevel()),
                "",
                retrievedSources,
                retrievedContexts,
                safeString(testCase.referenceAnswer()),
                answer);
    }

    private String generateAnswer(String question, List<String> retrievedContexts) {
        String context = retrievedContexts.isEmpty()
                ? "无可用检索上下文。"
                : String.join("\n\n---\n\n", retrievedContexts);
        return aiClient.complete(List.of(
                AiMessage.system("""
                        你是 MindBridge 的 RAG 回答生成器，用于 RAGAS 评测样本生成。
                        请依据检索上下文回答学生问题，语气温和、具体、克制。
                        如果上下文不足，只给出安全的一般支持建议，不要编造校园流程。
                        禁止诊断疾病、开药、透露后台风险等级、Excel、MCP 或报告流程。
                        用中文回答，不超过 180 字。
                        """),
                AiMessage.user("""
                        学生问题：
                        %s

                        检索上下文：
                        %s
                        """.formatted(question, context))
        )).trim();
    }

    private List<RagEvalCase> loadDataset(String datasetLocation) {
        try {
            Resource resource = resourceLoader.getResource(datasetLocation);
            try (InputStream inputStream = resource.getInputStream()) {
                return objectMapper.readValue(inputStream, new TypeReference<>() {
                });
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to load RAG evaluation dataset: " + datasetLocation, exception);
        }
    }

    private String normalizeLabel(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }
}
