package com.dev.semsearch.ingest.event;

import com.dev.semsearch.ingest.job.IngestJob;
import com.dev.semsearch.ingest.job.JobRepository;
import org.alfresco.event.sdk.handling.filter.EventFilter;
import org.alfresco.event.sdk.handling.filter.IsFileFilter;
import org.alfresco.event.sdk.handling.handler.OnNodeUpdatedEventHandler;
import org.alfresco.repo.event.v1.model.DataAttributes;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;
import org.alfresco.repo.event.v1.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles Alfresco node-updated events for files.
 *
 * <p>This fires for content edits, renames, moves, and permission changes alike.
 * All are enqueued as UPSERT. The pipeline's content-hash check makes
 * metadata-only updates cheap (no re-embedding).
 */
@Component
public class NodeUpdatedHandler implements OnNodeUpdatedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(NodeUpdatedHandler.class);

    private final JobRepository jobRepository;

    public NodeUpdatedHandler(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Override
    public void handleEvent(RepoEvent<DataAttributes<Resource>> event) {
        NodeResource node = (NodeResource) event.getData().getResource();
        String nodeId = node.getId();
        log.info("Node UPDATED event: nodeId={}", nodeId);
        jobRepository.enqueue(nodeId, IngestJob.ACTION_UPSERT);
    }

    @Override
    public EventFilter getEventFilter() {
        return IsFileFilter.get();
    }
}
