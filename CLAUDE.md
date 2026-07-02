# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在本仓库中工作时提供指引。

## 项目概述

MindBridge 是一个校园心理健康 AI 助手，基于 Spring Boot 3.3.5（响应式 WebFlux 技术栈）和 Java 17 构建。采用多智能体架构，对学生意图进行分类（CHAT/CONSULT/RISK），通过混合 RAG 检索知识，并提供具有风险检测能力的心理支持回复。

## 构建与运行命令

```bash
# 构建（产出 target/mindbridge-agent-0.1.0.jar）
mvn -Dmaven.repo.local=.m2/repository package

# 运行应用
mvn spring-boot:run

# 运行测试
mvn test

# Docker 全栈部署（应用 + MySQL + Redis + Chroma + Mailpit）
docker-compose up --build
```

默认账号：`admin/admin123`（咨询师）、`student/student123`（学生聊天）。

## 架构

### 请求流程

`POST /api/chat/stream`（SSE）→ `ChatService` → `AgentRuntimeService.run()`（最多 8 步智能体循环）→ AI 模型（Ollama 或 OpenAI）→ 流式响应

### 智能体循环（service/agent/）

各智能体实现 `MindBridgeAgent` 接口，按顺序执行：

1. **MemoryAgent** – 加载 Redis 短期记忆 + Chroma 用户画像召回
2. **SupervisorAgent** – 通过 AI 分类意图（CHAT / CONSULT / RISK）
3. **KnowledgeAgent** – 用于 CONSULT/RISK：查询改写 + 混合 RAG 检索
4. **RiskGuardianAgent** – 用于 CONSULT/RISK：AI 心理评估 + 词库兜底检测
5. **CompanionAgent** – 用于 CHAT：闲聊回复规划
6. **CounselorAgent** – 用于 CONSULT/RISK：结合记忆 + RAG + 风险上下文的治疗性回复

`AgentRuntimeService` 编排循环；`AgentContext` 在各步骤间传递共享状态。

### 混合 RAG（service/knowledge/）

`KnowledgeService` 将 ChromaDB 的向量相似度（权重 65%）与 BM25 文本评分（权重 35%）融合。文档按 512 字符分块，64 字符重叠。知识源文件位于 `src/main/resources/knowledge/`。

### 记忆系统（service/memory/）

- **短期记忆**：Redis，每个会话 24 小时 TTL
- **长期记忆**：`UserProfileMemoryService` 提取用户特征；`UserMemoryChromaGateway` 通过 Chroma 嵌入进行存储/召回

### MCP 工具（service/mcp/）

同时承担 MCP 服务端和客户端角色。每个工具（Excel 报告写入、风险告警通知）有三种实现：本地、HTTP、MCP——通过 `MindBridgeProperties` 选择。`MindBridgeMcpTools` 向外部 MCP 客户端暴露工具。

### AI 抽象层（service/ai/）

`AiClient` 接口，`SpringAiChatClient` 实现支持 Ollama（本地）和 OpenAI。系统提示词在 `PromptTemplates` 中。`RiskLexicon` 提供基于关键词的高风险检测作为兜底。

### 回复后工具链

`ToolOrchestrationService` 在每次回复后异步执行：通过 `ExcelReportWriter` 写入 Excel 报告，对 HIGH 风险案例通过 `AlertNotifier` 发送风险告警。

## 关键配置

所有 `mindbridge.*` 属性通过 `MindBridgeProperties` 绑定。激活的 Spring Profile 控制数据库选择（默认 H2，`prod` Profile 使用 MySQL）和 AI 提供商选择。

## 数据层

JPA 实体在 `domain/` 目录，Spring Data 仓库在 `repository/` 目录。默认使用 H2 文件数据库（`./data/mindbridge`）；通过 `prod` Profile 切换到 MySQL。

## 测试

测试使用 JUnit 5 + Mockito（subclass mock maker）+ Spring Boot Test，配合 H2 内存数据库。运行单个测试：`mvn -Dtest=AgentApplicationTests#methodName test`。

## RAGAS 评估

Java 端 `RagEvaluationRunner` 生成 `target/rag-eval-report.json`，Python 端 `eval/run-ragas-eval.py` 消费该报告计算 RAGAS 指标。评估数据集：`src/main/resources/rag-eval/mindbridge-rag-eval.json`。

## 前端

原生 HTML/CSS/JS，位于 `src/main/resources/static/`——无构建工具，无 npm。
