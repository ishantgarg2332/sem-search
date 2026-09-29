package com.dev.semsearch.ingest.event;

import com.dev.semsearch.ingest.job.IngestJob;
import com.dev.semsearch.ingest.job.JobRepository;
import org.alfresco.event.sdk.handling.filter.EventFilter;
import org.alfresco.event.sdk.handling.filter.IsFileFilter;
import org.alfresco.event.sdk.handling.handler.OnNodeDeletedEventHandler;
import org.alfresco.repo.event.v1.model.DataAttributes;
import org.alfresco.repo.event.v1.model.NodeResource;
import org.alfresco.repo.event.v1.model.RepoEvent;
import org.alfresco.repo.event.v1.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles Alfresco node-deleted events for files.
 * Enqueues a DELETE job so the worker removes all chunks for this node
 * from the Elasticsearch index and cleans up node_state.
 */
@Component
public class NodeDeletedHandler implements OnNodeDeletedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(NodeDeletedHandler.class);

    private final JobRepository jobRepository;

    public NodeDeletedHandler(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Override
    public void handleEvent(RepoEvent<DataAttributes<Resource>> event) {
        NodeResource node = (NodeResource) event.getData().getResource();
        String nodeId = node.getId();
        log.info("Node DELETED event: nodeId={}", nodeId);
        jobRepository.enqueue(nodeId, IngestJob.ACTION_DELETE);
    }

    @Override
    public EventFilter getEventFilter() {
        return IsFileFilter.get();
    }
}
