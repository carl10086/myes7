/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.admin.indices.mapping.put;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.RequestValidators;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ack.ClusterStateUpdateResponse;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetaDataMappingService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Put mapping action.
 */
public class TransportPutMappingAction extends TransportMasterNodeAction<PutMappingRequest, AcknowledgedResponse> {

    private final MetaDataMappingService metaDataMappingService;
    private final RequestValidators<PutMappingRequest> requestValidators;

    @Inject
    public TransportPutMappingAction(
            final TransportService transportService,
            final ClusterService clusterService,
            final ThreadPool threadPool,
            final MetaDataMappingService metaDataMappingService,
            final ActionFilters actionFilters,
            final IndexNameExpressionResolver indexNameExpressionResolver,
            final RequestValidators<PutMappingRequest> requestValidators) {
        super(PutMappingAction.NAME, transportService, clusterService, threadPool, actionFilters, indexNameExpressionResolver,
            PutMappingRequest::new);
        this.metaDataMappingService = metaDataMappingService;
        this.requestValidators = Objects.requireNonNull(requestValidators);
    }

    @Override
    protected String executor() {
        // we go async right away
        return ThreadPool.Names.SAME;
    }

    @Override
    protected AcknowledgedResponse read(StreamInput in) throws IOException {
        return new AcknowledgedResponse(in);
    }

    @Override
    protected AcknowledgedResponse newResponse() {
        throw new UnsupportedOperationException("usage of Streamable is to be replaced by Writeable");
    }

    @Override
    protected ClusterBlockException checkBlock(PutMappingRequest request, ClusterState state) {
        String[] indices;
        if (request.getConcreteIndex() == null) {
            indices = indexNameExpressionResolver.concreteIndexNames(state, request);
        } else {
            indices = new String[] {request.getConcreteIndex().getName()};
        }
        return state.blocks().indicesBlockedException(ClusterBlockLevel.METADATA_WRITE, indices);
    }

    @Override
    protected void masterOperation(final PutMappingRequest request, final ClusterState state,
                                   final ActionListener<AcknowledgedResponse> listener) {
        try {
            final Index[] concreteIndices = request.getConcreteIndex() == null ?
                indexNameExpressionResolver.concreteIndices(state, request)
                : new Index[] {request.getConcreteIndex()};
            final Optional<Exception> maybeValidationException = requestValidators.validateRequest(request, state, concreteIndices);
            if (maybeValidationException.isPresent()) {
                listener.onFailure(maybeValidationException.get());
                return;
            }
            PutMappingClusterStateUpdateRequest updateRequest = new PutMappingClusterStateUpdateRequest()
                    .ackTimeout(request.timeout()).masterNodeTimeout(request.masterNodeTimeout())
                    .indices(concreteIndices).type(request.type())
                    .source(request.source());

            metaDataMappingService.putMapping(updateRequest, new ActionListener<ClusterStateUpdateResponse>() { // ?????? Mapping

                @Override
                public void onResponse(ClusterStateUpdateResponse response) {
                    listener.onResponse(new AcknowledgedResponse(response.isAcknowledged()));
                }

                @Override
                public void onFailure(Exception t) {
                    logger.debug(() -> new ParameterizedMessage("failed to put mappings on indices [{}], type [{}]",
                        concreteIndices, request.type()), t);
                    listener.onFailure(t);
                }
            });
        } catch (IndexNotFoundException ex) {
            logger.debug(() -> new ParameterizedMessage("failed to put mappings on indices [{}], type [{}]",
                request.indices(), request.type()), ex);
            throw ex;
        }
    }

}
