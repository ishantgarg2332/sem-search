package com.dev.semsearch.ingest.event;

import com.dev.semsearch.ingest.job.IngestJob;
import com.dev.semsearch.ingest.job.JobRepository;
import org.alfresco.event.sdk.handling.filter.EventFilter;
import org.alfresco.event.sdk.handling.filter.IsFileFilter;
import org.alfresco.event.sdk.handling.handler.OnNodeCreatedEventHandler;
import org.alfresco.repo.event.v1.model.DataAttributes;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;
import org.alfresco.repo.event.v1.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles Alfresco node-created events for files.
 * Enqueues an UPSERT job so the background worker can download, chunk, embed,
 * and index the new document.
 */
@Component
public class NodeCreatedHandler implements OnNodeCreatedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(NodeCreatedHandler.class);

    private final JobRepository jobRepository;

    public NodeCreatedHandler(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Override
    public void handleEvent(RepoEvent<DataAttributes<Resource>> event) {
        NodeResource node = (NodeResource) event.getData().getResource();
        String nodeId = node.getId();
        log.info("Node CREATED event: nodeId={}", nodeId);
        jobRepository.enqueue(nodeId, IngestJob.ACTION_UPSERT);
    }

    @Override
    public EventFilter getEventFilter() {
        return IsFileFilter.get();
    }
}
