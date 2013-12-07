/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.stratos.messaging.message.processor.tenant;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.messaging.listener.EventListener;
import org.apache.stratos.messaging.listener.tenant.TenantCreatedEventListener;
import org.apache.stratos.messaging.listener.tenant.TenantRemovedEventListener;
import org.apache.stratos.messaging.listener.tenant.TenantSubscribedEventListener;
import org.apache.stratos.messaging.listener.tenant.TenantUpdatedEventListener;
import org.apache.stratos.messaging.listener.topology.CompleteTopologyEventListener;
import org.apache.stratos.messaging.message.processor.MessageProcessorChain;

/**
 * Defines default tenant message processor chain.
 */
public class TenantMessageProcessorChain extends MessageProcessorChain {
    private static final Log log = LogFactory.getLog(TenantMessageProcessorChain.class);

    private CompleteTenantMessageProcessor completeTopologyMessageProcessor;
    private TenantCreatedMessageProcessor tenantCreatedMessageProcessor;
    private TenantUpdatedMessageProcessor tenantUpdatedMessageProcessor;
    private TenantRemovedMessageProcessor tenantRemovedMessageProcessor;
    private TenantSubscribedMessageProcessor tenantSubscribedMessageProcessor;

    public void initialize() {
        // Add topology event processors
        completeTopologyMessageProcessor = new CompleteTenantMessageProcessor();
        add(completeTopologyMessageProcessor);

        tenantCreatedMessageProcessor = new TenantCreatedMessageProcessor();
        add(tenantCreatedMessageProcessor);

        tenantUpdatedMessageProcessor = new TenantUpdatedMessageProcessor();
        add(tenantUpdatedMessageProcessor);

        tenantRemovedMessageProcessor = new TenantRemovedMessageProcessor();
        add(tenantRemovedMessageProcessor);

        tenantSubscribedMessageProcessor = new TenantSubscribedMessageProcessor();
        add(tenantSubscribedMessageProcessor);

        if (log.isDebugEnabled()) {
            log.debug("Tenant message processor chain initialized");
        }
    }

    public void addEventListener(EventListener eventListener) {
        if (eventListener instanceof CompleteTopologyEventListener) {
            completeTopologyMessageProcessor.addEventListener(eventListener);
        } else if (eventListener instanceof TenantCreatedEventListener) {
            tenantCreatedMessageProcessor.addEventListener(eventListener);
        } else if (eventListener instanceof TenantUpdatedEventListener) {
            tenantUpdatedMessageProcessor.addEventListener(eventListener);
        } else if (eventListener instanceof TenantRemovedEventListener) {
            tenantRemovedMessageProcessor.addEventListener(eventListener);
        } else if (eventListener instanceof TenantSubscribedEventListener) {
            tenantSubscribedMessageProcessor.addEventListener(eventListener);
        } else {
            throw new RuntimeException("Unknown event listener");
        }
    }
}
