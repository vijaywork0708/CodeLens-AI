package com.spring.devpilot.services.indexing;

import com.spring.devpilot.entity.IndexStatus;
import com.spring.devpilot.entity.Repository;
import com.spring.devpilot.exceptions.BadRequestException;
import com.spring.devpilot.exceptions.NotFoundException;
import com.spring.devpilot.services.ai.RagSettings;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import com.spring.devpilot.repository.RepoRepository;
import com.spring.devpilot.services.UserService;
import com.spring.devpilot.services.github.GithubApiClient;
import com.spring.devpilot.services.github.GithubRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {

    private static final int VECTOR_BATCH_SIZE = 32;

    private static final int PROGRESS_EVERY_N_FILES = 5;
    private final RepoRepository  repoRepository;
    private final UserService userService;
    private final GithubApiClient githubApiClient;
    private final CodeFileFilter codeFileFilter;
    private final CodeChunker  codeChunker;
    private final GithubRateLimiter   githubRateLimiter;
    private final VectorStore vectorStore;

    @Value("${app.indexing.max-file-bytes:102400}")
    private long maxFileBytes;

    public Repository startIndexing(UUID repoId, UUID userId){
        Repository repo = repoRepository.findByIdAndUserId(repoId,userId)
                .orElseThrow(()-> new NotFoundException("Repository not found"));

        if(repo.getIndexStatus() == IndexStatus.INDEXING){
            throw new BadRequestException("Repository is already being indexed");
        }

        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setFilesProcessed(0);
        repo.setFilesTotal(0);
        repo.setChunkCount(0);
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());
        return repoRepository.save(repo);
    }

    @Async("indexingExecutor")
    public void indexAsync(UUID repoId, UUID userId){
        log.info("========== ASYNC INDEXING STARTED ==========");
        log.info("Repo ID: {}", repoId);
        log.info("User ID: {}", userId);
        try {
            log.info("Calling doIndex()...");
            doIndex(repoId,userId);
            log.info("========== ASYNC INDEXING COMPLETED ==========");
        } catch (Exception ex){
            log.error("Indexing failed for repo {}", repoId, ex);
            markFailed(repoId, ex.getMessage());
        }
    }

    private void doIndex(UUID repoId, UUID userId){
        log.info("Fetching repository {}", repoId);
        Repository repo = repoRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        log.info("Repository found: {}", repo.getFullName());
        String token = userService.decryptAccessToken(userService.requiredById(userId));
        log.info("GitHub token retrieved");
        deleteExistingVectors(repoId.toString());

        log.info("Existing vectors deleted");

        log.info("Fetching repository tree...");

        Map<String, Object> tree = githubApiClient.getRepoTree(
                token, repo.getOwner(), repo.getName(), repo.getDefaultBranch());
        log.info("Repository tree received");
        List<String> filePaths = listIndexableFiles(tree);
        log.info("Indexable files found: {}", filePaths.size());
        updateProgress(repoId, filePaths.size(),0,0, IndexStatus.INDEXING, null);

        List<Document> batch = new ArrayList<>();
        int processed = 0;
        int totalChunks = 0;

        for(String path : filePaths){
            log.info("Processing file: {}", path);
            try {
                String content = githubApiClient.getFileContent(
                        token, repo.getOwner(), repo.getName(), path);
                List<Document> chunks = codeChunker.chunkFile(repoId.toString(), path, content);
                batch.addAll(chunks);
                totalChunks += chunks.size();
                if(batch.size() >= VECTOR_BATCH_SIZE){
                    log.info("Adding {} documents to VectorStore", batch.size());
                    vectorStore.add(batch);
                    batch.clear();
                }
            } catch (Exception ex) {
                log.warn("Skipping file {} in {}: {}", path,repo.getFullName(), ex.getMessage());
            }
            processed++;
            if(processed % PROGRESS_EVERY_N_FILES == 0 || processed == filePaths.size()){
                updateProgress(repoId, filePaths.size(), processed, totalChunks, IndexStatus.INDEXING,null);
            }
            githubRateLimiter.pause();
        }

        if(!batch.isEmpty()){
            log.info("Adding final {} documents to VectorStore", batch.size());
            vectorStore.add(batch);
        }
        log.info("Marking repository READY");

        markReady(repoId, filePaths.size(), processed, totalChunks, repo.getFullName());
    }

    @SuppressWarnings("unchecked")
    private List<String> listIndexableFiles(Map<String, Object> tree){
        if(tree == null || tree.get("tree") == null){
            return List.of();
        }

        List<Map<String,Object>> entries = (List<Map<String, Object>>) tree.get("tree");
        return entries.stream()
                .filter(entry -> "blob".equals(String.valueOf(entry.get("type"))))
                .filter(entry -> {
                    String path = String.valueOf(entry.get("path"));
                    long size = entry.get("size") instanceof Number n ? n.longValue() : 0L;
                    return codeFileFilter.isEligible(path, size, maxFileBytes);
                })
                .map(entry -> String.valueOf(entry.get("path")))
                .toList();
    }

    private void deleteExistingVectors(String repoId){
        try {
            var filter = new FilterExpressionBuilder().eq(RagSettings.METADATA_REPO_ID, repoId).build();
            vectorStore.delete(filter);
        } catch (Exception ex) {
            log.warn("Could not delete existing vectors for repo {}: {}", repoId, ex.getMessage());
        }
    }

    @Transactional
    protected void updateProgress(
            UUID repoId,
            int total,
            int processed,
            int chunks,
            IndexStatus status,
            String error) {
        repoRepository.findById(repoId).ifPresent(repo -> {
            repo.setFilesTotal(total);
            repo.setFilesProcessed(processed);
            repo.setChunkCount(chunks);
            repo.setIndexStatus(status);
            repo.setErrorMessage(error);
            repo.setUpdatedAt(Instant.now());
            repoRepository.save(repo);
        });
    }


    protected void markReady(UUID repoId, int totalFiles, int processedFiles, int totalChunks, String fullName){
        repoRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.READY);
            repo.setFilesTotal(totalFiles);
            repo.setFilesProcessed(processedFiles);
            repo.setChunkCount(totalChunks);
            repo.setIndexedAt(Instant.now());
            repo.setErrorMessage(null);
            repo.setUpdatedAt(Instant.now());
            repoRepository.save(repo);
        });
    }


    protected void markFailed(UUID repoId, String message){
        repoRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.FAILED);
            repo.setErrorMessage(message != null && message.length() > 2000
                    ? message.substring(0,2000)
                    : message);
            repo.setUpdatedAt(Instant.now());
            repoRepository.save(repo);
        });
    }
}
