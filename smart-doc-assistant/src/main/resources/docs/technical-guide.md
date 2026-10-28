# 技术开发手册（内部知识库）

## Spring Boot 快速指南

Spring Boot 基于 Java 构建，提供自动配置能力，简化微服务开发。核心思想是"约定优于配置"：只要引入对应 starter 依赖，框架会自动装配所需的 Bean，开发者只需关注业务代码。

常用 starter 包括 spring-boot-starter-web（Web 应用）、spring-boot-starter-data-redis（Redis 缓存）、spring-boot-starter-test（测试）等。应用入口使用 @SpringBootApplication 注解标注主类，通过 main 方法启动内嵌 Tomcat。

## 向量检索与 pgvector

pgvector 是 PostgreSQL 的向量检索扩展，支持 HNSW 索引。使用方式：创建 vector 类型字段存储向量，通过 <=> 运算符计算余弦距离，配合 ivfflat 或 hnsw 索引实现近似最近邻搜索。

生产环境建议：向量维度 1024 以内使用 HNSW 索引效果最佳；数据量超过百万级需评估索引内存占用。混合检索场景可以先用关键词（全文检索）召回候选集，再对候选集做向量精排。

## RAG 检索增强生成原理

RAG（Retrieval-Augmented Generation）通过向量检索召回相关知识，交给大模型生成答案。核心流程分两段：索引阶段（文档切块、向量化、入库）和查询阶段（问题向量化、相似度检索、拼接上下文生成）。

RAG 质量的关键在索引阶段：文档解析是否完整、分块是否合理、元数据是否丰富。检索阶段可通过 Reranker 二次精排提升召回质量，生成阶段可通过 Prompt 模板约束输出格式。

## 大模型 API 成本优化

DeepSeek 等模型按 token 计费，成本优化三板斧：1）缓存命中，相同前缀请求命中缓存可大幅降价；2）错峰调用，非高峰时段价格更低；3）上下文裁剪，只传必要的检索片段而非全部文档。
