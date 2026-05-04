package com.faqrobot.repository;

import com.faqrobot.model.KnowledgeItem;
import com.faqrobot.model.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.highlight.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class LuceneIndexManager {

    private static final String INDEX_PATH = "./data/lucene-index";

    private Directory directory;
    private SmartChineseAnalyzer analyzer;
    private IndexWriter indexWriter;

    private static final String FIELD_ID = "id";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_CATEGORY = "category";

    private final KnowledgeRepository knowledgeRepository;

    public LuceneIndexManager(KnowledgeRepository knowledgeRepository) {
        this.knowledgeRepository = knowledgeRepository;
    }

    @PostConstruct
    public void init() {
        try {
            // 文件持久化索引，重启不丢失
            directory = FSDirectory.open(Paths.get(INDEX_PATH));
            analyzer = new SmartChineseAnalyzer();
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            indexWriter = new IndexWriter(directory, config);

            // 启动时如果索引为空，从数据库自动重建
            long docCount = getDocumentCount();
            if (docCount == 0) {
                List<KnowledgeItem> allItems = knowledgeRepository.findAll();
                if (!allItems.isEmpty()) {
                    rebuildIndex(allItems);
                    log.info("auto-rebuilt index from DB: {} items", allItems.size());
                }
            } else {
                log.info("existing lucene index loaded: {} docs", docCount);
            }
        } catch (IOException e) {
            throw new RuntimeException("Lucene init failed", e);
        }
    }

    @PreDestroy
    public void destroy() {
        try {
            if (indexWriter != null) indexWriter.close();
            if (directory != null) directory.close();
        } catch (IOException e) {
            log.error("close failed", e);
        }
    }

    public void addDocument(KnowledgeItem item) {
        try {
            Document doc = new Document();
            doc.add(new StringField(FIELD_ID, String.valueOf(item.getId()), Field.Store.YES));
            doc.add(new TextField(FIELD_TITLE, item.getTitle(), Field.Store.YES));
            doc.add(new TextField(FIELD_CONTENT, item.getContent(), Field.Store.YES));
            doc.add(new StringField(FIELD_CATEGORY, item.getCategory() != null ? item.getCategory() : "", Field.Store.YES));
            indexWriter.addDocument(doc);
            indexWriter.commit();
        } catch (IOException e) {
            log.error("add failed: {}", item.getId(), e);
        }
    }

    public void updateDocument(KnowledgeItem item) {
        try {
            Document doc = new Document();
            doc.add(new StringField(FIELD_ID, String.valueOf(item.getId()), Field.Store.YES));
            doc.add(new TextField(FIELD_TITLE, item.getTitle(), Field.Store.YES));
            doc.add(new TextField(FIELD_CONTENT, item.getContent(), Field.Store.YES));
            doc.add(new StringField(FIELD_CATEGORY, item.getCategory() != null ? item.getCategory() : "", Field.Store.YES));
            indexWriter.updateDocument(new Term(FIELD_ID, String.valueOf(item.getId())), doc);
            indexWriter.commit();
        } catch (IOException e) {
            log.error("update failed: {}", item.getId(), e);
        }
    }

    public void deleteDocument(Long id) {
        try {
            indexWriter.deleteDocuments(new Term(FIELD_ID, String.valueOf(id)));
            indexWriter.commit();
        } catch (IOException e) {
            log.error("delete failed: {}", id, e);
        }
    }

    public List<SearchResult> search(String queryStr, int maxResults) {
        List<SearchResult> results = new ArrayList<>();
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            String[] fields = {FIELD_TITLE, FIELD_CONTENT};
            MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer);
            Query query = parser.parse(queryStr);
            TopDocs topDocs = searcher.search(query, maxResults);
            QueryScorer scorer = new QueryScorer(query);
            SimpleHTMLFormatter formatter = new SimpleHTMLFormatter("<mark>", "</mark>");
            Highlighter highlighter = new Highlighter(formatter, scorer);
            highlighter.setTextFragmenter(new SimpleFragmenter(200));
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                Long kid = Long.valueOf(doc.get(FIELD_ID));
                String title = doc.get(FIELD_TITLE);
                String content = doc.get(FIELD_CONTENT);
                String category = doc.get(FIELD_CATEGORY);
                String snippet;
                try {
                    String hl = highlighter.getBestFragment(analyzer, FIELD_CONTENT, content);
                    snippet = hl != null ? hl : (content.length() > 200 ? content.substring(0, 200) + "..." : content);
                } catch (Exception ex) {
                    snippet = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                }
                results.add(SearchResult.builder()
                        .knowledgeId(kid).title(title)
                        .snippet(snippet).score(scoreDoc.score)
                        .category(category).fullContent(content).build());
            }
        } catch (Exception e) {
            log.error("search failed: {}", queryStr, e);
        }
        return results;
    }

    public void rebuildIndex(List<KnowledgeItem> items) {
        try {
            indexWriter.deleteAll();
            indexWriter.commit();
            for (KnowledgeItem item : items) {
                Document doc = new Document();
                doc.add(new StringField(FIELD_ID, String.valueOf(item.getId()), Field.Store.YES));
                doc.add(new TextField(FIELD_TITLE, item.getTitle(), Field.Store.YES));
                doc.add(new TextField(FIELD_CONTENT, item.getContent(), Field.Store.YES));
                doc.add(new StringField(FIELD_CATEGORY, item.getCategory() != null ? item.getCategory() : "", Field.Store.YES));
                indexWriter.addDocument(doc);
            }
            indexWriter.commit();
            log.info("rebuild done: {} items", items.size());
        } catch (IOException e) {
            log.error("rebuild failed", e);
        }
    }

    public long getDocumentCount() {
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        } catch (IOException e) {
            return 0;
        }
    }
}
