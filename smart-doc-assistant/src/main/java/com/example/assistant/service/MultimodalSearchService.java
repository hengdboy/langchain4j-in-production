package com.example.assistant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ：多模态（跨模态）语义检索服务。
 *
 * <p>核心思路：图片和文本被多模态 Embedding 模型映射到<b>同一个语义空间</b>，
 * 于是可以直接比较——用文本向量去撞图片向量（文本搜图），或用一张图的向量
 * 去撞库里的图片向量（图搜图），本质都是余弦相似度。
 *
 * <p>实现与 DocumentSearchService 对齐：
 * <ul>
 * <li>启动时把预置图片库全部嵌入，得到图片向量列表</li>
 * <li>运行时把查询（文本 or 图片）嵌入，与图片向量逐个算余弦相似度，取 Top-K</li>
 * <li>小规模用内存暴力扫描即可（与 InMemoryEmbeddingStore 同思路）</li>
 * </ul>
 */
@Slf4j
@Service
public class MultimodalSearchService implements ApplicationRunner {

    private final MultimodalEmbeddingClient embeddingClient;

    /** 预置图片库：公网可访问的示例图 + 文字描述（模拟"商品图库/素材库"） */
    private static final List<ImageDoc> IMAGE_LIBRARY = List.of(
            new ImageDoc(
                    "img-001",
                    "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241022/emyrja/dog_and_girl.jpeg",
                    "一个小女孩和一只白色小狗在户外草地上"),
            new ImageDoc(
                    "img-002",
                    "https://dashscope.oss-cn-beijing.aliyuncs.com/images/256_1.png",
                    "一只白猫坐在室内窗台上"),
            new ImageDoc(
                    "img-003",
                    "https://dashscope.oss-cn-beijing.aliyuncs.com/images/tiger.png",
                    "一只老虎的图片"),
            new ImageDoc(
                    "img-004",
                    "https://img.alicdn.com/imgextra/i3/O1CN01rdstgY1uiZWt8gqSL_!!6000000006071-0-tps-1970-356.jpg",
                    "一双白色运动鞋，轻便透气，适合跑步日常穿着")
    );

    private final List<float[]> imageVectors = new ArrayList<>();

    public MultimodalSearchService(MultimodalEmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    /** 启动时：把预置图片库全部嵌入（一次性建库）——建库失败不阻断启动（ 最佳实践） */
    @Override
    public void run(ApplicationArguments args) {
        try {
            for (ImageDoc doc : IMAGE_LIBRARY) {
                float[] vector = embeddingClient.embedImage(doc.imageUrl());
                imageVectors.add(vector);
            }
            log.info("已索引 {} 张图片到多模态向量库（embedding 维度={}）",
                    IMAGE_LIBRARY.size(), embeddingClient.dimension());
        } catch (Exception e) {
            log.error("多模态建库失败（检查 DASHSCOPE_API_KEY 与网络后重试），本次跳过，应用继续启动", e);
        }
    }

    /** 文本搜图：嵌入查询文本 → 与所有图片向量算相似度 → Top-K */
    public List<ImageHit> searchByText(String text, int topK) {
        float[] query = embeddingClient.embedText(text);
        return rank(query, topK);
    }

    /** 图搜图：嵌入查询图片 → 与所有图片向量算相似度 → Top-K */
    public List<ImageHit> searchByImage(String imageUrl, int topK) {
        float[] query = embeddingClient.embedImage(imageUrl);
        return rank(query, topK);
    }

    /** 余弦相似度暴力扫描（数据量小时够用，生产应换 pgvector / ES） */
    private List<ImageHit> rank(float[] query, int topK) {
        List<ImageHit> hits = new ArrayList<>();
        for (int i = 0; i < IMAGE_LIBRARY.size(); i++) {
            double score = cosineSimilarity(query, imageVectors.get(i));
            ImageDoc doc = IMAGE_LIBRARY.get(i);
            hits.add(new ImageHit(score, doc.id(), doc.imageUrl(), doc.description()));
        }
        hits.sort(Comparator.comparingDouble(ImageHit::score).reversed());
        return hits.stream().limit(topK).toList();
    }

    /** 余弦相似度：两个向量夹角的余弦值，值域 [-1, 1]，越大越相似 */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("向量维度不一致：" + a.length + " vs " + b.length);
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 图片条目 */
    public record ImageDoc(String id, String imageUrl, String description) {}

    /** 检索结果 */
    public record ImageHit(double score, String id, String imageUrl, String description) {}
}
